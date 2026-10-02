# Прошивка скетча ESP32_BiAmp на плату
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

# Предварительная проверка порта: опечатка в номере приводит к попытке
# стереть или прошить несуществующий порт. Выводим все доступные порты.
if ($Script:Port -eq 'COM_PORT_NOT_SET') {
    Write-Host "[flash] порт не задан (BT_ESP_PORT не установлен, -Port не передан)" -ForegroundColor Red
    Write-Host "[flash] доступные порты:" -ForegroundColor Yellow
    & $cli board list 2>&1 | Write-Host
    throw "Порт не задан. Задайте -Port COMxx или переменную BT_ESP_PORT."
}
$escapedPort = [regex]::Escape($Script:Port)
$portFound = & $cli board list 2>$null | Select-String -Pattern "\b$escapedPort\b"
if (-not $portFound) {
    Write-Host "[flash] порт $Script:Port не найден. Доступные порты:" -ForegroundColor Red
    & $cli board list 2>&1 | Write-Host
    throw "Порт $Script:Port не найден в выводе arduino-cli board list"
}

if ($Erase) {
    $esptoolInfo = Get-EsptoolPath
    Write-Host "[flash] стирание флеша (смена разметки разделов)" -ForegroundColor Yellow
    if ($esptoolInfo.Interpreter) {
        & $esptoolInfo.Interpreter $esptoolInfo.Path --chip esp32 --port $Script:Port erase_flash
    } else {
        & $esptoolInfo.Path --chip esp32 --port $Script:Port erase_flash
    }
    if ($LASTEXITCODE -ne 0) { throw "Стирание не удалось (код $LASTEXITCODE)" }
    Start-Sleep -Seconds 2
}

& $cli upload -p $Script:Port --fqbn $Script:Fqbn --input-dir $Script:BuildPath $Script:SketchPath
if ($LASTEXITCODE -ne 0) { throw "Прошивка провалилась (код $LASTEXITCODE)" }

Write-Host "[flash] OK" -ForegroundColor Green
if (Wait-ForPort -TimeoutSec 20) {
    Write-Host "[flash] порт $($Script:Port) снова доступен" -ForegroundColor DarkGray
}
