# Компиляция скетча ESP32_BiAmp
# Использование: pwsh -File .\tools\build.ps1

. "$PSScriptRoot\common.ps1"

Invoke-Compile
Get-Firmware | ForEach-Object { Write-Host "[build] firmware: $_" -ForegroundColor DarkGray }
