# Removes Polarisen and restores the stock home screen/screensaver. Usage: powershell -ExecutionPolicy Bypass -File uninstall.ps1 -Ip 192.168.0.50
param([Parameter(Mandatory)][string]$Ip)
$dev = "${Ip}:5555"; adb connect $dev | Out-Null
# re-enable anything disabled in the debloat lists, and put Google's launcher back as screensaver owner
Get-Content "$PSScriptRoot\debloat\*.txt" | ForEach-Object { if ($_ -match '^\s*disable\s+(\S+)') { adb -s $dev shell pm enable $Matches[1] | Out-Null } }
adb -s $dev shell settings put secure screensaver_components com.google.android.backdrop/com.google.android.backdrop.Backdrop
adb -s $dev uninstall se.isbjorn.home
Write-Host "Done. A factory reset also always restores the original system."