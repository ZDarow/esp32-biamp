# Компиляция скетча BT_esp
# Использование: pwsh -File .\tools\build.ps1

. "$PSScriptRoot\common.ps1"

Invoke-Compile
Get-Firmware | ForEach-Object { Write-Host "[build] firmware: $_" -ForegroundColor DarkGray }
