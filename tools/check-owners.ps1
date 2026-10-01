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
    if ($inFn -and $line -match '^\}') { $inFn = $false; continue }
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

# Покрыт ли путь набором правил: правило совпадает само с собой, покрывает
# путь по префиксу, либо путь покрывает правило по своему префиксу.
# CODEOWNERS пишет с ведущим слэшем ('/firmware/'), хук — без ('firmware/*'),
# поэтому слэш нормализуется, а 'docs/' покрывает 'docs/*'.
function Test-Covered([string]$path, [hashtable]$rules) {
    $needle = $path.TrimStart('/')
    foreach ($rule in $rules.Keys) {
        $r = $rule.TrimStart('/')
        $rBase = $r -replace '\*$', ''
        $nBase = $needle -replace '\*$', ''
        if ($needle -eq $r) { return $true }
        if ($rBase -and $nBase.StartsWith($rBase)) { return $true }
        if ($nBase -and $rBase.StartsWith($nBase)) { return $true }
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

if ($problems.Count -gt 0) {
    Write-Host "::error::Матрица владения расходится ($($problems.Count)):" -ForegroundColor Red
    foreach ($p in $problems) { Write-Host "  $p" -ForegroundColor Red }
    Write-Host ''
    Write-Host 'Правьте .githooks/pre-commit, .github/CODEOWNERS и docs/BRANCHING.md вместе.' -ForegroundColor Yellow
    exit 1
}

Write-Host "Матрица владения согласована: $($hookRules.Count) конкретных правил в хуке, $($ownerRules.Count) в CODEOWNERS (плюс catch-all '*' в обоих)." -ForegroundColor Green
exit 0