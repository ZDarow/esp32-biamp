# Общие настройки сборки прошивки (ESP32 би-амп)
# Подключается через . "$PSScriptRoot\common.ps1"

$ErrorActionPreference = 'Stop'

$Script:ProjectRoot = Split-Path -Parent $PSScriptRoot
# Arduino требует, чтобы имя главного файла совпадало с именем папки скетча,
# поэтому скетч лежит в ESP32_BiAmp\ESP32_BiAmp.ino, а tools\ — уровнем выше.
$Script:SketchName = 'ESP32_BiAmp'
$Script:SketchPath  = Join-Path $Script:ProjectRoot $Script:SketchName
$Script:BuildPath   = Join-Path $Script:ProjectRoot '.build'

# Поиск arduino-cli: сперва путь из переменной окружения, затем типичные места
# установки, затем система. Путь из $env:USERPROFILE в коде не используется —
# он привязан к конкретной машине и ломает сборку у всех остальных.
function Resolve-ArduinoCli {
    if ($env:ARDUINO_CLI -and (Test-Path -LiteralPath $env:ARDUINO_CLI)) { return $env:ARDUINO_CLI }
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Arduino15\arduino-cli.exe'),
        (Join-Path $env:USERPROFILE  'Documents\Arduino\.arduino-cli\arduino-cli.exe'),
        (Join-Path $env:USERPROFILE  'AppData\Local\Arduino15\arduino-cli.exe')
    )
    foreach ($c in $candidates) { if (Test-Path -LiteralPath $c) { return $c } }
    $onPath = Get-Command 'arduino-cli' -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    return $null
}
$Script:Cli = Resolve-ArduinoCli

# Плата: ESP32-WROOM-32 (classic). Заменить на esp32:esp32:esp32s3 при необходимости.
# PartitionScheme=huge_app — 3MB APP вместо 1.2MB (скетч занимает большую часть дефолтной партиции).
# ВНИМАНИЕ: смена партиции требует первой прошивки с "Erase all flash" (см. flash.ps1 -Erase).
$Script:Fqbn = 'esp32:esp32:esp32:PartitionScheme=huge_app,UploadSpeed=921600'

$Script:Baud   = 115200

# Порт задаётся переменной окружения BT_ESP_PORT или ключом -Port у скриптов.
# Значение по умолчанию — заведомо несуществующий порт: так опечатка обнаружится
# сразу, а не приведёт к прошивке не того устройства.
$Script:Port   = $env:BT_ESP_PORT
if (-not $Script:Port) { $Script:Port = 'COM_PORT_NOT_SET' }

function Get-Cli {
    if (-not $Script:Cli) {
        throw "arduino-cli не найден. Установите его и задайте путь в переменной окружения ARDUINO_CLI."
    }
    if (-not (Test-Path -LiteralPath $Script:Cli)) {
        throw "arduino-cli не найден по пути: $($Script:Cli)"
    }
    return $Script:Cli
}

function New-BuildDir {
    if (Test-Path -LiteralPath $Script:BuildPath) {
        # Очищаем каталог от стухших артефактов: иначе Get-Firmware может
        # выбрать .flashed.bin или .merged.bin вместо актуального .ino.bin.
        Get-ChildItem -LiteralPath $Script:BuildPath -Force |
            Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    } else {
        New-Item -ItemType Directory -Path $Script:BuildPath | Out-Null
    }
}

function Invoke-Compile {
    param([switch]$Quiet)
    $cli = Get-Cli
    New-BuildDir
    $compileArgs = @(
        'compile'
        '--fqbn', $Script:Fqbn
        '--build-path', $Script:BuildPath
        '--warnings', 'all'
    )
    if ($Quiet) { $compileArgs += '--quiet' }
    $compileArgs += $Script:SketchPath
    Write-Host "[build] fqbn=$($Script:Fqbn) sketch=$($Script:SketchPath)" -ForegroundColor Cyan
    & $cli @compileArgs
    if ($LASTEXITCODE -ne 0) { throw "Сборка провалилась (код $LASTEXITCODE)" }
    Write-Host "[build] OK" -ForegroundColor Green
}

function Get-EsptoolPath {
    $pkgs = Join-Path $env:LOCALAPPDATA 'Arduino15\packages\esp32\tools\esptool_py'
    if (-not (Test-Path -LiteralPath $pkgs)) { throw "esptool_py не найден: $pkgs" }
    # Сортируем по версии через [version], а не лексикографически:
    # 4.9.0 > 4.10.0 строково, но 4.10.0 > 4.9.0 численно.
    $vsort = {
        if ($_.FullName -match '\\(\d+\.\d+(?:\.\d+)?)\\') {
            try { [version]$Matches[1] } catch { [version]'0' }
        } else { [version]'0' }
    }
    $exe = Get-ChildItem -LiteralPath $pkgs -Recurse -Filter 'esptool.exe' -ErrorAction SilentlyContinue |
        Sort-Object $vsort -Descending | Select-Object -First 1
    if ($exe) { return @{ Path = $exe.FullName; Interpreter = $null } }
    # esptool.py — исполняемый как скрипт через python, а не напрямую.
    $py = Get-ChildItem -LiteralPath $pkgs -Recurse -Filter 'esptool.py' -ErrorAction SilentlyContinue |
        Sort-Object $vsort -Descending | Select-Object -First 1
    if ($py) {
        $python = Get-Command 'python' -ErrorAction SilentlyContinue
        if (-not $python) { $python = Get-Command 'py' -ErrorAction SilentlyContinue }
        if (-not $python) { $python = Get-Command 'python3' -ErrorAction SilentlyContinue }
        if (-not $python) { throw "esptool.py найден, но python не установлен" }
        return @{ Path = $py.FullName; Interpreter = $python.Source }
    }
    throw "esptool не найден в $pkgs"
}

function Get-Firmware {
    # Строгий выбор: ищем ровно $SketchName.ino.bin. Раньше использовался
    # Select-Object -First 1 по порядку ФС, и попадал .flashed.bin или
    # .merged.bin — префикс у них ESP32_BiAmp.ino.*, а не boot_app0.
    $expectedPath = Join-Path $Script:BuildPath "$($Script:SketchName).ino.bin"
    if (-not (Test-Path -LiteralPath $expectedPath)) {
        throw "Не найден артефакт прошивки: $expectedPath. Ожидался ровно один файл $("$($Script:SketchName).ino.bin")"
    }
    return $expectedPath
}

function Wait-ForPort {
    param([int]$TimeoutSec = 20)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    # Строгий матч: Select-String -SimpleMatch COM1 поймает и COM10, COM14.
    # Используем regex с \b — граница слова не даст совпасть COM1 с COM10.
    $escapedPort = [regex]::Escape($Script:Port)
    while ((Get-Date) -lt $deadline) {
        $found = & (Get-Cli) board list 2>$null | Select-String -Pattern "\b$escapedPort\b"
        if ($found) { return $true }
        Start-Sleep -Milliseconds 1500
    }
    return $false
}
