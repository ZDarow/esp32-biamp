# Сверка матрицы владения путями.
#
# Матрица объявлена в трёх местах намеренно: хук работает офлайн,
# CODEOWNERS — на сервере, BRANCHING.md объясняет причину. Обратная
# сторона — их можно править по отдельности и получить расхождение:
# коммит начнёт проходить там, где CODEOWNERS указывает другого
# владельца, и заметить это некому.
#
# Скрипт моделирует семантику обоих механизмов: в хуке (sh case)
# выигрывает ПЕРВОЕ совпавшее правило, в CODEOWNERS — ПОСЛЕДНЕЕ.
# Результат сравнивается с самым узким правилом, независимо от
# порядка, — так ловится и расхождение владельцев, и перестановка
# строк, и неправильная позиция catch-all.
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

# ── Правила хука ─────────────────────────────────────────────────
# Порядок сохраняется: в sh case первый подошедший шаблон выигрывает.
$hook = Read-Lines $HookPath
$inFn = $false
$hookOrdered = @()
foreach ($line in $hook) {
    if ($line -match '^path_owner\(\)') { $inFn = $true; continue }
    if ($inFn -and $line -match '^\s*\}') { $inFn = $false; continue }
    if (-not $inFn) { continue }
    # Правило выглядит как:  <шаблоны>) echo "<владелец>"
    # Владелец может содержать дефис (pc-client), поэтому класс символов
    # включает его — иначе правило молча выпадает из разбора, и сверка
    # потом утверждает, что хук «не знает» путь, который знает.
    if ($line -match '^(.+?)\)\s+echo\s+"([a-z-]+)"') {
        $patterns = @($Matches[1] -split '\s*\|\s*' | ForEach-Object { $_.Trim() })
        $hookOrdered += [pscustomobject]@{ Patterns = $patterns; Owner = $Matches[2] }
    }
}

if ($hookOrdered.Count -eq 0) { throw 'В хуке не найдено ни одного правила path_owner()' }

# ── Правила CODEOWNERS ───────────────────────────────────────────
# Порядок сохраняется: GitHub применяет последнее совпавшее правило.
$coOrdered = @()
foreach ($line in Read-Lines $OwnersPath) {
    $t = $line.Trim()
    if (-not $t -or $t.StartsWith('#')) { continue }
    if ($t -match '^(.+?)\s+@([\w\-\[\]]+)\s*$') {
        $coOrdered += [pscustomobject]@{ Pattern = $Matches[1].Trim(); Owner = $Matches[2] }
    }
}

if ($coOrdered.Count -eq 0) { throw 'В CODEOWNERS не найдено ни одного правила' }

# Владельцы в двух файлах называются по-разному: в хуке это машинные
# идентификаторы, в CODEOWNERS — GitHub-команды. Сопоставление по смыслу.
$alias = @{
    'firmware'   = 'firmware'
    'android'    = 'android'
    'web'        = 'web'
    'verify'     = 'verify'
    'shared'     = 'integrator'
    'integrator' = 'integrator'
}

function Resolve-Owner([string]$o) {
    if ($alias.ContainsKey($o)) { return $alias[$o] }
    return $o
}

# Хук — семантика шаблонов sh case: '*' совпадает с чем угодно,
# 'dir/*' — с содержимым каталога на любой глубине (в шаблонах case
# '*' проходит через '/'), иначе — точное совпадение.
function Test-HookPattern([string]$pattern, [string]$path) {
    if ($pattern -eq '*') { return $true }
    if ($pattern.EndsWith('/*')) {
        return $path.StartsWith($pattern.Substring(0, $pattern.Length - 1))
    }
    return $path -eq $pattern
}

# CODEOWNERS: ведущий / привязывает к корню, завершающий / покрывает
# каталог и его содержимое, '*' — всё.
function Test-COPattern([string]$pattern, [string]$path) {
    $p = $pattern.TrimStart('/')
    if ($p -eq '*') { return $true }
    $p = $p.TrimEnd('/')
    return ($path -eq $p) -or $path.StartsWith($p + '/')
}

# Владелец, которого назначит механизм: первое совпадение в хуке,
# последнее — в CODEOWNERS.
function Get-HookFirstMatch([string]$path, $rules) {
    foreach ($r in $rules) {
        foreach ($p in $r.Patterns) {
            if (Test-HookPattern $p $path) { return $r.Owner }
        }
    }
    return $null
}

function Get-COLastMatch([string]$path, $rules) {
    $owner = $null
    foreach ($r in $rules) {
        if (Test-COPattern $r.Pattern $path) { $owner = $r.Owner }
    }
    return $owner
}

# Нормализует паттерн: убирает ведущий /, trailing /* и завершающий /.
#
# Завершающий слэш убирается тоже: иначе 'firmware/capture/' (CODEOWNERS) и
# 'firmware/capture/*' (хук) нормализуются в разные строки, и сверка
# объявила бы расхождением то, что на самом деле означает одно и то же.
# Покрытие подкаталога проверяется отдельно, в Get-CoveringOwner.
function Normalize-Pattern([string]$p) {
    $p = $p.TrimStart('/')
    $p = $p -replace '\s*\*\s*$', ''
    $p = $p.TrimEnd('/')
    return $p
}

# Самое узкое правило, покрывающее путь, — порядок не важен, важна
# длина префикса. Это эталон того, кого матрица должна назначить.
function Get-CoveringOwner([string]$path, $rules) {
    $needle = Normalize-Pattern $path
    $best = $null
    $bestLen = -1
    foreach ($r in $rules) {
        $r2 = Normalize-Pattern $r.Pattern
        if (-not $r2) { continue }
        # Каталог покрывает и сам себя, и всё под ним. Проверка границы
        # обязательна: без неё правило 'firmware/capture' накрыло бы и
        # 'firmware/captures-backup/', которого оно не касается.
        if (($needle -eq $r2 -or $needle.StartsWith($r2 + '/')) -and $r2.Length -gt $bestLen) {
            $best = $r.Owner
            $bestLen = $r2.Length
        }
    }
    return $best
}

# Покрыт ли путь набором правил: правило совпадает само с собой, покрывает
# путь по префиксу, либо путь покрывает правило по своему префиксу.
# Нормализация общая с Get-CoveringOwner, граница префикса проверяется
# явно — иначе 'firmware/capture' считалось бы покрывающим
# 'firmware/captures-backup/', и пропущенный путь прошёл бы сверку.
function Test-Covered([string]$path, $rules) {
    $needle = (Normalize-Pattern $path) -replace '\*$', ''
    foreach ($r in $rules) {
        $rr = (Normalize-Pattern $r.Pattern) -replace '\*$', ''
        if (-not $rr -or -not $needle) { continue }
        if ($needle -eq $rr) { return $true }
        if ($needle.StartsWith($rr + '/') -or $rr.StartsWith($needle + '/')) { return $true }
    }
    return $false
}

# Плоские списки правил по одному шаблону — для эталонного поиска
# самого узкого правила и для проверок покрытия.
$hookFlat = @()
foreach ($r in $hookOrdered) {
    foreach ($p in $r.Patterns) {
        $hookFlat += [pscustomobject]@{ Pattern = $p; Owner = $r.Owner }
    }
}
$coFlat = @($coOrdered)

# Конкретные правила, без catch-all: сравнение владельцев идёт по ним,
# иначе '*' покрывает любой путь и маскирует настоящее расхождение —
# например, удаление README из хука прошло бы незамеченным.
$hookConcrete = @($hookFlat | Where-Object { $_.Pattern.TrimStart('/') -ne '*' })
$coConcrete = @($coFlat | Where-Object { $_.Pattern.TrimStart('/') -ne '*' })

if ($hookConcrete.Count -eq 0) { throw 'В хуке нет ни одного конкретного правила, кроме "*"' }
if ($coConcrete.Count -eq 0) { throw 'В CODEOWNERS нет ни одного конкретного правила, кроме "*"' }

$problems = @()

# Catch-all обязан быть в обоих наборах: без него новые файлы остаются
# без владельца — хук их пропустит (→ * → unknown), а CODEOWNERS
# назначит @integrator по умолчанию, и никто не заметит расхождения.
$hookHasAll = $hookFlat | Where-Object { $_.Pattern.TrimStart('/') -eq '*' }
if (-not $hookHasAll) {
    $problems += 'В хуке отсутствует catch-all "*" — новые файлы не будут проверяться на принадлежность направлению'
}
$coHasAll = $coFlat | Where-Object { $_.Pattern.TrimStart('/') -eq '*' }
if (-not $coHasAll) {
    $problems += 'В CODEOWNERS отсутствует catch-all "*" — новые файлы получат владельца по умолчанию без явного назначения'
}

# Позиция catch-all — часть матрицы. В CODEOWNERS он стоит ПЕРВЫМ:
# GitHub применяет последнее совпадение, и "* @integrator" в конце
# файла перекрывает все правила выше — владельцы направлений теряются.
if ($coOrdered[0].Pattern.TrimStart('/') -ne '*') {
    $problems += 'В CODEOWNERS catch-all "*" должен быть первым правилом: GitHub применяет последнее совпадение, и "* @integrator" в конце перекрывает все правила выше'
}

# В хуке catch-all стоит ПОСЛЕДНИМ: в case первый подошедший шаблон
# выигрывает, и "*" выше конкретных правил оставил бы их недостижимыми.
$hookLast = $hookOrdered[$hookOrdered.Count - 1]
if ($hookLast.Patterns.Count -ne 1 -or $hookLast.Patterns[0] -ne '*') {
    $problems += 'В хуке catch-all "*" должен быть последним правилом path_owner(): в case первый подошедший шаблон выигрывает'
}

# Правила CODEOWNERS (кроме "*") обязаны начинаться с /: без якоря
# шаблон совпадает с путём в любом каталоге, и владелец назначается
# не тому файлу.
foreach ($r in $coOrdered) {
    if ($r.Pattern -ne '*' -and -not $r.Pattern.StartsWith('/')) {
        $problems += "Правило CODEOWNERS '$($r.Pattern)' не начинается с / — шаблон совпадёт с файлом в любом каталоге"
    }
}

# Хук обязан знать каждый путь, который CODEOWNERS кому-то приписывает.
foreach ($r in $coConcrete) {
    if (-not (Test-Covered $r.Pattern $hookConcrete)) {
        $problems += "CODEOWNERS описывает '$($r.Pattern)', но хук о нём не знает: правка из чужой ветки пройдёт молча"
    }
}

# Обратная проверка: путь из хука, которого нет в CODEOWNERS, на сервере
# останется без владельца.
foreach ($r in $hookConcrete) {
    if (-not (Test-Covered $r.Pattern $coConcrete)) {
        $problems += "хук описывает '$($r.Pattern)' (владелец $(Resolve-Owner $r.Owner)), но в CODEOWNERS его нет"
    }
}

# Пути-пробы: каждое правило порождает себя и файл внутри себя, плюс
# граничные случаи — пути, которые легко перекрыть широким правилом
# при перестановке строк.
$probes = @{}
foreach ($r in $hookConcrete) {
    $p = $r.Pattern.TrimStart('/')
    if ($p.EndsWith('/*')) {
        $dir = $p.Substring(0, $p.Length - 2)
        # Только файл внутри каталога: git никогда не ставит в индекс
        # «голый» каталог, поэтому проба bare-dir даёт ложное срабатывание
        # (хук 'docs/*' не покрывает bare 'docs', а CODEOWNERS '/docs/' — покрывает).
        $probes[$dir + '/probe.c'] = $true
    } else {
        $probes[$p] = $true
    }
}
foreach ($r in $coConcrete) {
    $p = $r.Pattern.TrimStart('/')
    if ($p.EndsWith('/')) {
        $dir = $p.TrimEnd('/')
        $probes[$dir + '/probe.c'] = $true
    } else {
        $probes[$p] = $true
    }
}
$probes['firmware/captures-backup/probe.c'] = $true
$probes['firmware/tools/other.py'] = $true
$probes['firmware/protocol/status-contract.md'] = $true
$probes['android-app/docs/DOCUMENTATION.md'] = $true
$probes['pc-client/biamp/protocol.py'] = $true
$probes['README.md'] = $true
$probes['docs/BRANCHING.md'] = $true
$probes['tools/check-owners.ps1'] = $true
$probes['.github/CODEOWNERS'] = $true
$probes['SECURITY.md'] = $true

# Моделирование: для каждой пробы сравниваем, кого назначит хук
# (первое совпадение), кого назначит CODEOWNERS (последнее) и кого
# должна назначить матрица (самое узкое правило). Расхождение —
# баг порядка или владельца.
foreach ($probe in ($probes.Keys | Sort-Object)) {
    $hookOwner = Get-HookFirstMatch $probe $hookOrdered
    $coOwner = Get-COLastMatch $probe $coOrdered
    $intendedHook = Get-CoveringOwner $probe $hookConcrete
    $intendedCo = Get-CoveringOwner $probe $coConcrete
    if ($hookOwner -and $intendedHook -and (Resolve-Owner $hookOwner) -ne (Resolve-Owner $intendedHook)) {
        $problems += "Порядок хука ломает '$probe': первое совпадение даёт '$hookOwner', а самое узкое правило — '$intendedHook'"
    }
    if ($coOwner -and $intendedCo -and (Resolve-Owner $coOwner) -ne (Resolve-Owner $intendedCo)) {
        $problems += "Порядок CODEOWNERS ломает '$probe': последнее совпадение даёт '@$coOwner', а самое узкое правило — '@$intendedCo'"
    }
    if ($hookOwner -and $coOwner -and (Resolve-Owner $hookOwner) -ne (Resolve-Owner $coOwner)) {
        $problems += "Владелец расхождён: '$probe' — хук приписывает '$hookOwner', CODEOWNERS приписывает '@$coOwner'"
    }
}

if ($problems.Count -gt 0) {
    Write-Host "::error::Матрица владения расходится ($($problems.Count)):" -ForegroundColor Red
    foreach ($p in $problems) { Write-Host "  $p" -ForegroundColor Red }
    Write-Host ''
    Write-Host 'Правьте .githooks/pre-commit, .github/CODEOWNERS и docs/BRANCHING.md вместе.' -ForegroundColor Yellow
    exit 1
}

Write-Host "Матрица владения согласована: $($hookConcrete.Count) конкретных правил в хуке, $($coConcrete.Count) в CODEOWNERS (плюс catch-all '*' первым в CODEOWNERS и последним в хуке)." -ForegroundColor Green
exit 0
