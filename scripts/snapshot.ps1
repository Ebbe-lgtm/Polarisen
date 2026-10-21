# Read-only snapshot of packages, settings, props, memory, HOME and doze whitelist.
param([string]$Label = 'snap')
. "$PSScriptRoot\tv.ps1"

$dir = Join-Path $ROOT "snapshots\$(Get-Date -Format yyyyMMdd-HHmm)-$Label"
New-Item -ItemType Directory -Force $dir | Out-Null

$cmds = [ordered]@{
    'packages_all.txt'      = 'pm list packages -f'
    'packages_disabled.txt' = 'pm list packages -d'
    'packages_3rd.txt'      = 'pm list packages -3'
    'settings_global.txt'   = 'settings list global'
    'settings_secure.txt'   = 'settings list secure'
    'settings_system.txt'   = 'settings list system'
    'getprop.txt'           = 'getprop'
    'meminfo.txt'           = 'dumpsys meminfo'
    'home.txt'              = 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
    'deviceidle.txt'        = 'dumpsys deviceidle whitelist'
}
foreach ($f in $cmds.Keys) {
    $out = @(sh $cmds[$f])
    Set-Content -Encoding UTF8 -Path (Join-Path $dir $f) -Value $out  # -Value (not pipeline) so empty output still creates the file
    '{0,-22} {1} lines' -f $f, $out.Count
}
"Saved $dir"
