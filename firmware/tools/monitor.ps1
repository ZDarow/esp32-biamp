# Монитор последовательного порта + отправка команд
# Использование:
#   pwsh -File .\tools\monitor.ps1                        # слушать 10 c
#   pwsh -File .\tools\monitor.ps1 -Seconds 60
#   pwsh -File .\tools\monitor.ps1 -Commands "status,stats" -Seconds 5

param(
    [string]$Port,
    [int]$Baud = 115200,
    [string]$Commands,
    [int]$Seconds = 10,
    [switch]$Dtr
)

# Порт запрашиваем ДО dot-source: common.ps1 присваивает $Script:Port, а в скрипте
# $Port и $Script:Port — одна и та же переменная, и переданный порт затирался бы.
$RequestedPort = $Port

. "$PSScriptRoot\common.ps1"

if ($RequestedPort) { $Script:Port = $RequestedPort }

$sp = [System.IO.Ports.SerialPort]::new($Script:Port, $Baud, [System.IO.Ports.Parity]::None, 8, [System.IO.Ports.StopBits]::One)
$sp.ReadTimeout = 200
$sp.DtrEnable = $Dtr.IsPresent
$sp.Open()
Write-Host "[monitor] $Port @ $Baud, $Seconds s" -ForegroundColor Cyan

$deadline = (Get-Date).AddSeconds($Seconds)
$sent = $false
$buffer = New-Object System.Text.StringBuilder

try {
    while ((Get-Date) -lt $deadline) {
        if (-not $sent -and $Commands) {
            $cmds = $Commands -split '[,;]' | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() }
            foreach ($c in $cmds) {
                Write-Host "> $c" -ForegroundColor Yellow
                $sp.Write("$c`n")
                Start-Sleep -Milliseconds 400
            }
            $sent = $true
        }
        $data = $sp.ReadExisting()
        if ($data) { Write-Host $data.TrimEnd() }
        Start-Sleep -Milliseconds 100
    }
} finally {
    if ($sp.IsOpen) { $sp.Close() }
    $sp.Dispose()
}
Write-Host "[monitor] closed" -ForegroundColor DarkGray
