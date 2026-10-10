# Сверка матрицы владения путями.
#
# Матрица объявлена в трёх местах намеренно: хук работает офлайн, CODEOWNERS —
# на сервере, BRANCHING.md объясняет причину. Обратная сторона — их можно
# править по отдельности и получить расхождение: коммит начнёт проходить там,
# где CODEOWNERS указывает другого владельца, и заметить это некому.
#
# Скрипт сравнивает разбор путей из .githooks/pre-commit с CODEOWNERS и
# падает, если правила не покрывают один и тот же набор путей.
#
# Использование: pwsh -File .\tools\check-owners.ps1

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

$HookPath = Join-Path $Root '.githooks/pre-commit'
$OwnersPath = Join-Path $Root '.github/CODEOWNERS'

if (-not (Test-Path -LiteralPath $HookPath)) { throw "Не найден $HookPath" }
if (-not (Test-Path -LiteralPath $OwnersPath)) { throw "Не найден $OwnersPath" }

# Windows PowerShell 5.1 читает файл без BOM в текущей кодовой странице и
# оставляет \r в конце строки — и то и другое ломает разбор правил. Читаем
# явно как UTF-8 и режем сами, без Get-Content.
function Read-Lines([string]$path) {
    $text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    return ($text -replace "`r`n", "`n") -split "`n"
}

# ── Пути из хука ────────────────────────────────────────────────────────
$hook = Read-Lines $HookPath
$inFn = $false
$hookRules = @{}
foreach ($line in $hook) {
    if ($line -match '^path_owner\(\)') { $inFn = $true; continue }
    if ($inFn -and $line -match '^\s*\}') { $inFn = $false; continue }
    if (-not $inFn) { continue }
    # Правило выглядит как:  <шаблоны>) echo "<владелец>"
    # Владелец может содержать дефис (pc-client), поэтому класс символов
    # включает его — иначе правило молча выпадает из разбора, и сверка
    # потом утверждает, что хук «не знает» путь, который знает.
    if ($line -match '^(.+?)\)\s+echo\s+"([a-z-]+)"') {
        $patterns = $Matches[1] -split '\s*\|\s*'
        $owner = $Matches[2]
        foreach ($p in $patterns) {
            $hookRules[$p.Trim()] = $owner
        }
    }
}

if ($hookRules.Count -eq 0) { throw 'В хуке не найдено ни одного правила path_owner()' }

# ── Пути из CODEOWNERS ──────────────────────────────────────────────────
$ownerRules = @{}
foreach ($line in Read-Lines $OwnersPath) {
    $t = $line.Trim()
    if (-not $t -or $t.StartsWith('#')) { continue }
    if ($t -match '^(.+?)\s+@([\w\-\[\]]+)\s*$') {
        $ownerRules[$Matches[1].Trim()] = $Matches[2]
    }
}

if ($ownerRules.Count -eq 0) { throw 'В CODEOWNERS не найдено ни одного правила' }

# Владельцы в двух файлах называются по-разному: в хуке это машинные
# идентификаторы, в CODEOWNERS — GitHub-команды. Сопоставление по смыслу.
$alias = @{
    'firmware'   = 'firmware'
    'android'    = 'android'
    'pc-client'  = 'pc-client'
    'shared'     = 'integrator'
    'integrator' = 'integrator'
}

function Resolve-Owner([string]$o) {
    if ($alias.ContainsKey($o)) { return $alias[$o] }
    return $o
}

# Правило '*' — это «владелец по умолчанию» для всего остального. В сравнении
# оно бесполезно: покрывает любой путь и маскирует настоящее расхождение —
# например, удаление README из хука прошло бы незамеченным. Поэтому catch-all
# откладывается в сторону и сравниваются только конкретные правила.
$hookRulesAll = $hookRules
$ownerRulesAll = $ownerRules
$hookRules = @{}
$ownerRules = @{}
foreach ($k in $hookRulesAll.Keys) { if ($k.TrimStart('/') -ne '*') { $hookRules[$k] = $hookRulesAll[$k] } }
foreach ($k in $ownerRulesAll.Keys) { if ($k.TrimStart('/') -ne '*') { $ownerRules[$k] = $ownerRulesAll[$k] } }

if ($hookRules.Count -eq 0) { throw "В хуке нет ни одного конкретного правила, кроме '*'" }
if ($ownerRules.Count -eq 0) { throw "В CODEOWNERS нет ни одного конкретного правила, кроме '*'" }

# Проверка наличия catch-all '*' в обоих наборах: без него новые файлы
# остаются без владельца — хук их пропустит (→ * → unknown), а CODEOWNERS
# назначит @integrator по умолчанию, и никто не заметит расхождения.
if (-not ($hookRulesAll.Keys | Where-Object { $_.TrimStart('/') -eq '*' })) {
    $problems += "В хуке отсутствует catch-all '*' (правило *) — новые файлы не будут проверяться на принадлежность направлению"
}
if (-not ($ownerRulesAll.Keys | Where-Object { $_.TrimStart('/') -eq '*' })) {
    $problems += "В CODEOWNERS отсутствует catch-all '*' — новые файлы получат владельца по умолчанию без явного назначения"
}

# Нормализует паттерн: убирает ведущий /, trailing /* и завершающий /.
#
# Завершающий слэш убирается тоже: иначе 'firmware/capture/' (CODEOWNERS) и
# 'firmware/capture/*' (хук) нормализуются в разные строки, и сверка объявит
# расхождением то, что на самом деле означает одно и то же. Покрытие
# подкаталога проверяется отдельно, в Get-CoveringOwner.
function Normalize-Pattern([string]$p) {
    $p = $p.TrimStart('/')
    $p = $p -replace '\s*\*\s*$', ''
    $p = $p.TrimEnd('/')
    return $p
}

# Возвращает владельца для пути: точное совпадение, затем покрытие по префиксу.
# CODEOWNERS пишет с ведущим слэшем ('/firmware/'), хук — без ('firmware/*'),
# поэтому оба нормализуются к общему виду для сравнения.
function Get-CoveringOwner([string]$path, [hashtable]$rules) {
    $needle = Normalize-Pattern $path
    foreach ($rule in $rules.Keys) {
        if ((Normalize-Pattern $rule) -eq $needle) { return $rules[$rule] }
    }
    $best = $null
    $bestLen = -1
    foreach ($rule in $rules.Keys) {
        $r = Normalize-Pattern $rule
        if (-not $r) { continue }
        # Каталог покрывает и сам себя, и всё под ним. Проверка границы
        # обязательна: без неё правило 'firmware/capture' накрыло бы и
        # 'firmware/captures-backup/', которого оно не касается.
        if (($needle -eq $r -or $needle.StartsWith($r + '/')) -and $r.Length -gt $bestLen) {
            $best = $rules[$rule]
            $bestLen = $r.Length
        }
    }
    return $best
}

# Покрыт ли путь набором правил: правило совпадает само с собой, покрывает
# путь по префиксу, либо путь покрывает правило по своему префиксу.
# Нормализация общая с Get-CoveringOwner, граница префикса проверяется
# явно — иначе 'firmware/capture' считалось бы покрывающим
# 'firmware/captures-backup/', и пропущенный путь прошёл бы сверку.
function Test-Covered([string]$path, [hashtable]$rules) {
    $needle = (Normalize-Pattern $path) -replace '\*$', ''
    foreach ($rule in $rules.Keys) {
        $r = (Normalize-Pattern $rule) -replace '\*$', ''
        if (-not $r -or -not $needle) { continue }
        if ($needle -eq $r) { return $true }
        if ($needle.StartsWith($r + '/') -or $r.StartsWith($needle + '/')) { return $true }
    }
    return $false
}

$problems = @()

# Хук обязан знать каждый путь, который CODEOWNERS кому-то приписывает.
foreach ($path in $ownerRules.Keys) {
    if (-not (Test-Covered $path $hookRules)) {
        $problems += "CODEOWNERS описывает '$path', но хук о нём не знает: правка из чужой ветки пройдёт молча"
    }
}

# Обратная проверка: путь из хука, которого нет в CODEOWNERS, на сервере
# останется без владельца.
foreach ($path in $hookRules.Keys) {
    if (-not (Test-Covered $path $ownerRules)) {
        $problems += "хук описывает '$path' (владелец $(Resolve-Owner $hookRules[$path])), но в CODEOWNERS его нет"
    }
}

# Сравнение значений владельцев: для каждого пути проверяем, что хук и
# CODEOWNERS приписывают его одному и тому же владельцу. Покрытие путей
# (Test-Covered выше) гарантирует, что путь известен обеим сторонам, но не
# гарантирует, что владелец совпадает — вот это сравнение и ловит расхождения
# вроде «хук: firmware, CODEOWNERS: @integrator».
$allPaths = @{}
foreach ($p in $hookRulesAll.Keys) {
    if ($p.TrimStart('/') -ne '*') { $allPaths[(Normalize-Pattern $p)] = $null }
}
foreach ($p in $ownerRulesAll.Keys) {
    if ($p.TrimStart('/') -ne '*') { $allPaths[(Normalize-Pattern $p)] = $null }
}
foreach ($path in $allPaths.Keys) {
    $hookOwner = Get-CoveringOwner $path $hookRules
    $coOwner = Get-CoveringOwner $path $ownerRules
    if ($hookOwner -and $coOwner) {
        $rh = Resolve-Owner $hookOwner
        $rc = Resolve-Owner $coOwner
        if ($rh -ne $rc) {
            $problems += "Владелец расхождён: '$path' — хук приписывает '$hookOwner' (→ $rh), CODEOWNERS приписывает '@$coOwner' (→ $rc)"
        }
    }
}

if ($problems.Count -gt 0) {
    Write-Host "::error::Матрица владения расходится ($($problems.Count)):" -ForegroundColor Red
    foreach ($p in $problems) { Write-Host "  $p" -ForegroundColor Red }
    Write-Host ''
    Write-Host 'Правьте .githooks/pre-commit, .github/CODEOWNERS и docs/BRANCHING.md вместе.' -ForegroundColor Yellow
    exit 1
}

Write-Host "Матрица владения согласована: $($hookRules.Count) конкретных правил в хуке, $($ownerRules.Count) в CODEOWNERS (плюс catch-all '*' в обоих)." -ForegroundColor Green
exit 0