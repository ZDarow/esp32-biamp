$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = "utf-8"
$OutputEncoding = [System.Text.Encoding]::UTF8
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = Join-Path $Root ".venv\Scripts\python.exe"
$MinPython = [version]"3.10"

Write-Host "[setup] окружение: $Root" -ForegroundColor Cyan

$Launcher = Get-Command python -ErrorAction SilentlyContinue
if ($null -eq $Launcher) {
    Write-Host "[setup] python не найден в PATH. Установите Python $MinPython или новее" -ForegroundColor Red
    Write-Host "        https://www.python.org/downloads/ (обязательно галочка «Add python.exe to PATH»)" -ForegroundColor Red
    exit 1
}

$Have = & python -c "import sys; print('%d.%d' % sys.version_info[:2])"
if ($LASTEXITCODE -ne 0) {
    Write-Host "[setup] не удалось определить версию python: $Have" -ForegroundColor Red
    exit 1
}
if ([version]$Have -lt $MinPython) {
    Write-Host "[setup] нужен Python $MinPython или новее, найден $Have" -ForegroundColor Red
    exit 1
}
Write-Host "[setup] системный Python: $Have" -ForegroundColor Cyan

if (-not (Test-Path $Python)) {
    Write-Host "[setup] создаю venv" -ForegroundColor Cyan
    & python -m venv (Join-Path $Root ".venv")
    if (-not (Test-Path $Python)) {
        Write-Host "[setup] venv не создан" -ForegroundColor Red
        exit 1
    }
}

& $Python -m pip install --quiet --upgrade pip
& $Python -m pip install --quiet -r (Join-Path $Root "requirements.txt")

Write-Host "[setup] проверка импортов" -ForegroundColor Cyan
& $Python -c "import biamp.protocol, biamp.transport, biamp.client, biamp.cli, biamp.webapp; print('модули в порядке')"

Write-Host "[setup] тесты" -ForegroundColor Cyan
Push-Location $Root
& $Python -m unittest discover -s tests -t .
$code = $LASTEXITCODE
Pop-Location

if ($code -ne 0) {
    Write-Host "[setup] тесты не прошли" -ForegroundColor Red
    exit $code
}

Write-Host "[setup] готово. Запуск: .\run.ps1 -Command web -Port COM14" -ForegroundColor Green
