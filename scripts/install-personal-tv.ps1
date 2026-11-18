#Requires -Version 7.0
param(
    [Parameter(Mandatory)]
    [string]$Device,
    [string]$Apk = "$PSScriptRoot/../app/native/app/build/outputs/apk/tv/debug/app-tv-debug.apk"
)
$ErrorActionPreference = 'Stop'
$packageName = 'com.studyontv.tv.debug'
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
adb connect $Device
if ($LASTEXITCODE -ne 0) { throw 'ADB connection failed' }
$installed = adb -s $Device shell pm path $packageName
if ($installed -match '^package:') {
    # Closing the app also makes the SQLite/journal backup consistent.
    adb -s $Device shell am force-stop $packageName
    $backupDir = Join-Path $PSScriptRoot "../.local-backups/$(Get-Date -Format yyyyMMdd-HHmmss-fff)"
    New-Item -ItemType Directory -Force $backupDir | Out-Null
    $info = [Diagnostics.ProcessStartInfo]::new('adb')
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($arg in @('-s', $Device, 'exec-out', 'run-as', $packageName, 'tar', 'cf', '-', 'databases', 'shared_prefs', 'files')) { $info.ArgumentList.Add($arg) }
    $process = [Diagnostics.Process]::Start($info)
    $errors = $process.StandardError.ReadToEndAsync()
    $file = [IO.File]::Create((Join-Path $backupDir 'app-data.tar'))
    try { $process.StandardOutput.BaseStream.CopyTo($file) } finally { $file.Dispose() }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Backup failed; installation cancelled: $($errors.Result)" }
    Write-Output "Private data backup: $backupDir"
}
# Never uninstall or clear data on a signature/version failure.
adb -s $Device install -r $apkPath
if ($LASTEXITCODE -ne 0) { throw 'Upgrade failed. Existing app/data kept. Check signing key and versionCode; do not uninstall.' }
adb -s $Device shell monkey -p $packageName -c android.intent.category.LAUNCHER 1
if ($LASTEXITCODE -ne 0) { throw 'Installed, but app launch failed' }
