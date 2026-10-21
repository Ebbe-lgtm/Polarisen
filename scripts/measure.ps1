# Measures memory, launcher smoothness, launch times and Cast/remote health.
# NOT read-only: presses keys, force-stops and starts YouTube, SVT Play and Play Store. Ends on HOME.
param([Parameter(Mandatory = $true)][string]$Label)
. "$PSScriptRoot\tv.ps1"

$dir = Join-Path $ROOT 'measurements'
New-Item -ItemType Directory -Force $dir | Out-Null
$out = Join-Path $dir "$(Get-Date -Format yyyyMMdd-HHmm)-$Label.txt"

$lines = New-Object System.Collections.ArrayList   # "key: value"
$raw = New-Object System.Collections.ArrayList     # raw sections
function kv($k, $v) { [void]$lines.Add("${k}: $v"); Write-Host "${k}: $v" }
function raw($title, $text) { [void]$raw.Add("===== $title ====="); foreach ($t in $text) { [void]$raw.Add("$t") }; [void]$raw.Add('') }
function num($text, $key) { $m = [regex]::Match(($text -join "`n"), "$key\s*(\d+)"); if ($m.Success) { [int]$m.Groups[1].Value } else { 'n/a' } }
function med($xs) {
    $s = @($xs | Where-Object { $_ -is [int] } | Sort-Object)
    if (-not $s.Count) { return 'n/a' }
    $h = [math]::Floor($s.Count / 2)
    if ($s.Count % 2) { $s[$h] } else { ($s[$h - 1] + $s[$h]) / 2 }
}

# --- Memory
$mi = sh 'cat /proc/meminfo'
function memkb($k) { [int](@($mi -match "^${k}:")[0] -replace '\D', '') }  # kB
kv 'MemTotal MB' ([math]::Round((memkb MemTotal) / 1024))
kv 'MemAvailable MB' ([math]::Round((memkb MemAvailable) / 1024))
kv 'SwapTotal MB' ([math]::Round((memkb SwapTotal) / 1024))
kv 'SwapFree MB' ([math]::Round((memkb SwapFree) / 1024))
kv 'Swap used MB' ([math]::Round(((memkb SwapTotal) - (memkb SwapFree)) / 1024))
kv 'Processes' ([int](sh 'ps -A | wc -l') - 1)
raw '/proc/meminfo' $mi
$dm = sh 'dumpsys meminfo'
$i = [array]::IndexOf($dm, 'Total PSS by process:')
if ($i -ge 0) { raw 'Top-15 Total PSS by process' $dm[($i + 1)..($i + 15)] }

# --- Home / launcher
$homeComp = @(sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')[-1]
$homePkg = ($homeComp -split '/')[0]
kv 'Home activity' $homeComp
sh 'input keyevent KEYCODE_HOME'
Start-Sleep 3
sh "dumpsys gfxinfo $homePkg reset" | Out-Null
$keys = 'RIGHT RIGHT RIGHT RIGHT RIGHT RIGHT DOWN DOWN LEFT LEFT LEFT LEFT LEFT LEFT UP UP' -split ' '
sh (($keys | ForEach-Object { "input keyevent KEYCODE_DPAD_$_" }) -join '; sleep 0.4; ')  # one adb call, no per-key adb overhead
$gfx = sh "dumpsys gfxinfo $homePkg"
foreach ($k in 'Total frames rendered', 'Janky frames', '50th percentile', '90th percentile', '95th percentile', '99th percentile') {
    kv "Home $k" ((@($gfx -match "^${k}:")[0] -split ':\s*', 2)[1])
}
raw "gfxinfo $homePkg" ($gfx -match '^(\*\* Graphics|Total frames|Janky|\d+th percentile|Number )')

$tt = @(); $wt = @()
foreach ($n in 1..3) {
    # Open Settings first so HOME really has to come back to the front (otherwise it reports 0)
    sh 'am start -W -n com.android.tv.settings/.MainSettings >/dev/null; sleep 2' | Out-Null
    $r = sh 'am start -W -a android.intent.action.MAIN -c android.intent.category.HOME'
    raw "am start HOME #$n" $r
    $tt += num $r 'TotalTime:'
    $wt += num $r 'WaitTime:'
    Start-Sleep 1
}
kv 'Home warm TotalTime ms' "$($tt -join ', ') (median $(med $tt))"
kv 'Home warm WaitTime ms' "$($wt -join ', ') (median $(med $wt))"
kv 'Launcher PSS kB' (num (sh "dumpsys meminfo $homePkg") 'TOTAL')

# --- Cold starts
foreach ($p in 'com.google.android.youtube.tv', 'se.svt.android.svtplay', 'com.android.vending') {
    $comp = @(sh "cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER $p")[-1]
    if ($comp -notmatch '/') { kv "Cold $p" "no leanback activity ($comp)"; continue }
    $t = @()
    foreach ($n in 1..2) {
        $r = sh "am force-stop $p; sleep 2; am start -W -n '$comp'"
        raw "cold start $p #$n" $r
        $t += num $r 'WaitTime:'  # TotalTime is bogus for trampoline activities (YouTube ShellActivity)
        sh 'input keyevent KEYCODE_HOME; sleep 3' | Out-Null
    }
    kv "Cold $p WaitTime ms" "$($t -join ', ') (median $(med $t))"
}

# --- Cast / remote health (oom_score_adj is permission-denied for shell on this TV -> dumpsys fallback)
foreach ($p in 'com.google.android.apps.mediashell', 'com.google.android.tv.remote.service') {
    $procId = sh "pidof $p"
    if (-not $procId) { kv $p 'NOT RUNNING'; continue }
    $adj = sh "cat /proc/$procId/oom_score_adj 2>/dev/null"
    if (-not $adj) { $adj = (sh "dumpsys activity processes $p" | Where-Object { $_ -match 'oom:|Proc #' } | ForEach-Object { $_.Trim() }) -join ' | ' }
    kv $p "running pid $procId, adj $adj"
}

sh 'input keyevent KEYCODE_HOME'

$summary = $lines -match '^(MemAvailable|Swap used|Processes|Home Janky|Home 90th|Home warm TotalTime|Launcher PSS|Cold |com\.google\.android\.(apps\.mediashell|tv\.remote))'
@("=== SUMMARY $Label  $(Get-Date -Format 'yyyy-MM-dd HH:mm') ===") + $summary + '' + '=== VALUES ===' + $lines + '' + $raw |
    Set-Content -Encoding UTF8 $out
''
$summary
"Saved $out"
