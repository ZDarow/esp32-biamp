# Прошивка скетча BT_esp на плату
# Использование:
#   pwsh -File .\tools\flash.ps1                # сборка + прошивка
#   pwsh -File .\tools\flash.ps1 -Port COM14    # указать порт
#   pwsh -File .\tools\flash.ps1 -NoBuild       # только прошить последний .build
#   pwsh -File .\tools\flash.ps1 -Erase         # стереть флеш (обязательно при смене PartitionScheme)

param(
    [string]$Port,
    [switch]$NoBuild,
    [switch]$Erase
)

# Порт запрашиваем ДО dot-source: $Port и $Script:Port в скрипте — одна переменная.
$RequestedPort = $Port

. "$PSScriptRoot\common.ps1"

if ($RequestedPort) { $Script:Port = $RequestedPort }

Write-Host "[flash] port=$($Script:Port) fqbn=$($Script:Fqbn)" -ForegroundColor Cyan

if (-not $NoBuild) { Invoke-Compile -Quiet }
Get-Firmware | Out-Null
$cli = Get-Cli

if ($Erase) {
    $esptool = Get-EsptoolPath
    Write-Host "[flash] стирание флеша (смена разметки разделов)" -ForegroundColor Yellow
    & $esptool --chip esp32 --port $Script:Port erase_flash
    if ($LASTEXITCODE -ne 0) { throw "Стирание не удалось (код $LASTEXITCODE)" }
    Start-Sleep -Seconds 2
}

& $cli upload -p $Script:Port --fqbn $Script:Fqbn --input-dir $Script:BuildPath $Script:SketchPath
if ($LASTEXITCODE -ne 0) { throw "Прошивка провалилась (код $LASTEXITCODE)" }

Write-Host "[flash] OK" -ForegroundColor Green
if (Wait-ForPort -TimeoutSec 20) {
    Write-Host "[flash] порт $($Script:Port) снова доступен" -ForegroundColor DarkGray
}
