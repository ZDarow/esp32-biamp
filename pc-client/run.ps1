param(
    [string]$Port = "COM14",
    [string]$Command = "status"
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
}

Set-Location $Root

switch ($Command) {
    "ports" { & $Python -m biamp ports }
    "web"   { & $Python -m biamp --port $Port web }
    "shell" { & $Python -m biamp --port $Port }
    default {
        $rest = $args
        & $Python -m biamp --port $Port $Command @rest
    }
}
