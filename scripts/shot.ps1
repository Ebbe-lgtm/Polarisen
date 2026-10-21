# Screenshot of the TV UI (video planes / DRM content usually come out black).
param([Parameter(Mandatory = $true)][string]$Label)
. "$PSScriptRoot\tv.ps1"

$dir = Join-Path $ROOT 'screenshots'
New-Item -ItemType Directory -Force $dir | Out-Null
$out = Join-Path $dir "$(Get-Date -Format yyyyMMdd-HHmmss)-$Label.png"

sh 'screencap -p /data/local/tmp/isb_shot.png'
& $ADB -s $DEV pull /data/local/tmp/isb_shot.png $out | Out-Null  # pull writes the file directly, no binary through the pipeline
sh 'rm -f /data/local/tmp/isb_shot.png'
if (-not (Test-Path $out)) { throw "Screenshot failed" }
$out
