# Applies a plan file (see debloat\batch1.txt). Every change is logged to changes.log with
# the previous state so restore.ps1 can undo it. -DryRun only reads and prints.
param([Parameter(Mandatory = $true)][string]$File, [switch]$DryRun)
. "$PSScriptRoot\tv.ps1"

$NEVER = @(
    'com.tcl.tv', 'com.tcl.tvinput', 'com.tcl.framework.custom', 'com.tcl.keycustomfunctionservice',
    'com.tcl.tcl_bt_rcu_service', 'com.tcl.autopair', 'com.tcl.settings', 'com.tcl.partnercustomizer',
    'com.tcl.partnercustomizer.resource', 'com.tcl.systemuioverlay', 'com.tcl.providers.config',
    'com.google.android.gms', 'com.google.android.gsf', 'com.android.vending', 'com.google.android.webview',
    'com.android.tv.settings', 'com.android.systemui', 'com.android.bluetooth', 'com.android.providers.settings',
    'com.android.providers.tv', 'com.tcl.inputmethod.international', 'com.google.android.inputmethod.latin', 'android'
)
$LOG = Join-Path $ROOT 'changes.log'
$homePkg = (@(sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')[-1] -split '/')[0]
# Disabling the current HOME is only safe when Isbjörn is installed as a HOME candidate to take over
$isbjornHome = [bool](@(sh 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -match '^\s*se\.isbjorn\.home/')

# Parse and validate the whole file before touching anything.
$actions = @()
foreach ($line in Get-Content -Encoding UTF8 $File) {
    $l = ($line -replace '#.*$', '').Trim()
    if (-not $l) { continue }
    $a = -split $l
    $argc = @{ disable = 2; appops = 4; idle = 2; setting = 4 }[$a[0]]
    if ($a.Count -ne $argc) { throw "Bad line: $line" }
    if ($a[0] -eq 'setting' -and $a[1] -notin 'global', 'secure', 'system') { throw "Bad settings namespace: $line" }
    if ($a[0] -ne 'setting' -and $NEVER -contains $a[1]) { throw "REFUSED: $($a[1]) is on the NEVER list. Nothing was changed." }
    if ($a[0] -eq 'disable' -and $a[1] -eq $homePkg -and -not $isbjornHome) { throw "REFUSED: $($a[1]) is the current HOME launcher and Isbjörn is not installed. Nothing was changed." }
    $actions += , $a
}

$installed = sh 'pm list packages' | ForEach-Object { $_ -replace '^package:', '' }
$disabled = sh 'pm list packages -d' | ForEach-Object { $_ -replace '^package:', '' }
$idle = sh 'dumpsys deviceidle whitelist'

function log($action, $argz, $prev, $out) {
    $line = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $action, $argz, $prev, ((@($out) -join ' | ') -replace "`t", ' ') -join "`t"
    Add-Content -Encoding UTF8 $LOG $line
}

foreach ($a in $actions) {
    $act = $a[0]; $p = $a[1]; $argz = $a[1..($a.Count - 1)] -join ' '
    if ($act -ne 'setting' -and $installed -cnotcontains $p) {
        "SKIP     $act $argz  (not installed)"
        if (-not $DryRun) { log "skipped:$act" $argz 'not installed' '' }
        continue
    }
    switch ($act) {
        'disable' {
            $prev = if ($disabled -ccontains $p) { 'disabled' } else { 'enabled' }
            $cmd = "pm disable-user --user 0 $p"
        }
        'appops' {
            $m = [regex]::Match(((sh "cmd appops get $p $($a[2])") -join "`n"), "(?m)^$($a[2]): (\w+)")
            $prev = if ($m.Success) { $m.Groups[1].Value } else { 'default' }
            $cmd = "cmd appops set $p $($a[2]) $($a[3])"
        }
        'idle' {
            # whitelist kinds this package is on, e.g. "system-excidle+system"; only "user" entries can be removed again
            $prev = ($idle | Where-Object { $_ -match ",$([regex]::Escape($p))," } | ForEach-Object { ($_ -split ',')[0] }) -join '+'
            if (-not $prev) { $prev = 'none' }
            $cmd = "dumpsys deviceidle whitelist +$p"
        }
        'setting' {
            $prev = "$(sh "settings get $p $($a[2])")"
            $cmd = "settings put $p $($a[2]) $($a[3])"
        }
    }
    if ($DryRun) { "DRYRUN   $cmd   (now: $prev)"; continue }
    $out = sh $cmd
    log $act $argz $prev $out
    "DONE     $cmd   (was: $prev) -> $out"
}
if (-not $DryRun) { "Logged to $LOG" }
