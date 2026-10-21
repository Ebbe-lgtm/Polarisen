<#
  Installs Polarisen on an Android TV over the network (Windows).
  Usage:  powershell -ExecutionPolicy Bypass -File install.ps1 -Ip 192.168.0.50 [-Apk path\to\polarisen.apk] [-SetHome]
  Needs adb (https://developer.android.com/tools/releases/platform-tools) in PATH, and network debugging switched on
  in the TV's developer options. Nothing outside /data is changed; uninstall.ps1 removes it again.
#>
param([Parameter(Mandatory)][string]$Ip, [string]$Apk = (Join-Path $PSScriptRoot 'polarisen.apk'), [switch]$SetHome)
$ErrorActionPreference = 'Stop'
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { throw 'adb not found. Install Android platform-tools and put adb in PATH.' }
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk (download polarisen.apk from the Releases page)" }
$dev = "${Ip}:5555"
& $adb connect $dev
if ((& $adb -s $dev get-state) -ne 'device') { throw "Could not reach $dev. Accept the 'Allow USB debugging' prompt on the TV and try again." }
$sdk = [int](& $adb -s $dev shell getprop ro.build.version.sdk)
if ($sdk -lt 28) { Write-Warning "This TV runs Android API $sdk; Polarisen is built for Android 9 (API 28) and newer." }
& $adb -s $dev install -r $Apk
& $adb -s $dev shell pm grant se.isbjorn.home android.permission.DUMP                  # "Fortsätt titta" (continue watching)
& $adb -s $dev shell pm grant se.isbjorn.home android.permission.WRITE_SECURE_SETTINGS # sleep/off timers, animation speed
& $adb -s $dev shell appops set se.isbjorn.home WRITE_SETTINGS allow
if ($SetHome) { & $adb -s $dev shell cmd package set-home-activity se.isbjorn.home/.HomeActivity }
Write-Host "`nPolarisen is installed. Open it from the app list, or press Home and choose it as your home screen."
Write-Host "To make Home always open Polarisen you must also disable the stock launcher, see the README."