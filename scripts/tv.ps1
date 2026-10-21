# Shared setup, dot-source it:  . "$PSScriptRoot\tv.ps1"
$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = 'C:\Program Files (x86)\Iriun Webcam\adb.exe' }
if (-not (Test-Path $ADB)) { throw "adb.exe not found (Android SDK platform-tools or Iriun Webcam)" }
$ROOT = Split-Path $PSScriptRoot
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}  # adb prints UTF-8 (settings values may contain non-ASCII)

# The router hands out the TV's address by DHCP, so it can change after a restart. Remember the last one that worked,
# and if it doesn't answer, look for the TV (adb port 5555, model "UnionTV") on the local /24.
$ipFile = Join-Path $PSScriptRoot 'tv-ip.txt'
$DEV = if (Test-Path $ipFile) { (Get-Content $ipFile -TotalCount 1).Trim() } else { '192.168.0.50:5555' # example: set your TV's address, or put it in scripts\tv-ip.txt }

function Test-TvDevice($dev) {
    & $ADB connect $dev 2>$null | Out-Null
    (& $ADB -s $dev get-state 2>$null) -eq 'device' -and (& $ADB -s $dev shell getprop ro.product.model 2>$null) -eq 'UnionTV'
}

if (-not (Test-TvDevice $DEV)) {
    $net = ($DEV -split '\.')[0..2] -join '.'
    $probes = 1..254 | ForEach-Object { $c = New-Object Net.Sockets.TcpClient; [pscustomobject]@{ ip = "$net.$_"; c = $c; t = $c.ConnectAsync("$net.$_", 5555) } }
    Start-Sleep 3
    $open = $probes | Where-Object { $_.t.IsCompleted -and -not $_.t.IsFaulted -and $_.c.Connected } | ForEach-Object { $_.ip }
    $probes | ForEach-Object { $_.c.Close() }
    $found = $open | ForEach-Object { "${_}:5555" } | Where-Object { Test-TvDevice $_ } | Select-Object -First 1
    if (-not $found) {
        throw "The TV was not found on $net.x. Is it on, with network debugging enabled and the adb prompt on the TV accepted?"
    }
    $DEV = $found
    Set-Content -Path $ipFile -Value $DEV -Encoding ASCII
    Write-Host "TV found at $DEV (address changed)"
}

function sh($cmd) { & $ADB -s $DEV shell $cmd }
