$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = "utf-8"
$OutputEncoding = [System.Text.Encoding]::UTF8
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = Join-Path $Root ".venv\Scripts\python.exe"

Write-Host "[setup] окружение: $Root" -ForegroundColor Cyan

if (-not (Test-Path $Python)) {
    Write-Host "[setup] создаю venv" -ForegroundColor Cyan
    python -m venv (Join-Path $Root ".venv")
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
