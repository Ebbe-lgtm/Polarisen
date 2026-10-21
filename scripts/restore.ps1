# Reverts everything debloat.ps1 applied (from changes.log), newest first.
# Ultimate fallback: a factory reset always restores stock, because nothing outside /data is changed.
param([switch]$DryRun, [switch]$Uninstall)
. "$PSScriptRoot\tv.ps1"
$LOG = Join-Path $ROOT 'changes.log'

$entries = @()
if (Test-Path $LOG) { $entries = @(Get-Content -Encoding UTF8 $LOG) }
# Only actions applied after the last restore run are still active.
$start = 0
for ($i = 0; $i -lt $entries.Count; $i++) { if (($entries[$i] -split "`t")[1] -like 'restored:*') { $start = $i + 1 } }
$todo = @()
for ($i = $start; $i -lt $entries.Count; $i++) {
    $f = $entries[$i] -split "`t"
    if ($f[1] -in 'disable', 'appops', 'idle', 'setting') { $todo += , $f }
}
[array]::Reverse($todo)

function log($action, $argz, $prev, $out) {
    $line = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $action, $argz, $prev, ((@($out) -join ' | ') -replace "`t", ' ') -join "`t"
    Add-Content -Encoding UTF8 $LOG $line
}

if ($todo.Count -eq 0) { 'Nothing to restore.' }
foreach ($f in $todo) {
    $act = $f[1]; $a = -split $f[2]; $prev = $f[3]
    $cmd = switch ($act) {
        'disable' { if ($prev -eq 'enabled') { "pm enable $($a[0])" } }
        'appops'  { "cmd appops set $($a[0]) $($a[1]) $prev" }
        'idle'    { if (($prev -split '\+') -notcontains 'user') { "dumpsys deviceidle whitelist -$($a[0])" } }
        'setting' { if ($prev -eq 'null') { "settings delete $($a[0]) $($a[1])" } else { "settings put $($a[0]) $($a[1]) '$prev'" } }
    }
    if (-not $cmd) { $cmd = "no-op (was $prev before)" }
    if ($DryRun) { "DRYRUN   $act $($f[2]) -> $cmd"; continue }
    $out = if ($cmd -like 'no-op*') { '' } else { sh $cmd }
    log "restored:$act" $f[2] $prev "$cmd $out"
    "RESTORED $act $($f[2]) -> $cmd $out"
}

if ($Uninstall) {
    if ($DryRun) { 'DRYRUN   adb uninstall se.isbjorn.home'; 'DRYRUN   cmd package set-home-activity com.google.android.tvlauncher/.MainActivity' }
    else {
        $out = & $ADB -s $DEV uninstall se.isbjorn.home   # failure is fine (not installed)
        log 'restored:uninstall' 'se.isbjorn.home' '' $out; "uninstall se.isbjorn.home -> $out"
        $out = sh 'cmd package set-home-activity com.google.android.tvlauncher/.MainActivity'
        log 'restored:home' 'com.google.android.tvlauncher/.MainActivity' '' $out; "set-home-activity -> $out"
    }
}
