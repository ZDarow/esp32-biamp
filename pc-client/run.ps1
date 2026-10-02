param(
    [string]$Port = "COM14",
    [string]$Command = "status",
    # $Host — автоматическая переменная PowerShell, переопределять её нельзя.
    [string]$Bind = "127.0.0.1",
    [int]$WebPort = 8765
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = "utf-8"
$OutputEncoding = [System.Text.Encoding]::UTF8
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = Join-Path $Root ".venv\Scripts\python.exe"

if (-not (Test-Path $Python)) {
    Write-Host "Окружение не найдено, выполняю setup.ps1" -ForegroundColor Yellow
    & (Join-Path $Root "setup.ps1")
    if (-not (Test-Path $Python)) {
        Write-Host "setup.ps1 не создал окружение" -ForegroundColor Red
        exit 1
    }
}

Push-Location $Root
try {
    switch ($Command) {
        "ports" { & $Python -m biamp ports }
        "web"   { & $Python -m biamp --port $Port web --host $Bind --web-port $WebPort }
        default {
            $rest = $args
            & $Python -m biamp --port $Port $Command @rest
        }
    }
    $code = $LASTEXITCODE
}
finally {
    Pop-Location
}

if ($null -eq $code) { $code = 0 }
exit $code
