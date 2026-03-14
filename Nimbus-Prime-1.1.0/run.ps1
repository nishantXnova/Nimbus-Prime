# Nimbus Prime — launcher script
# Double-click this file (right-click → Run with PowerShell) or run from terminal.

$java = "$env:APPDATA\.minecraft\runtime\java-runtime-delta\windows-x64\java-runtime-delta\bin\java.exe"
$out = "$PSScriptRoot\out"

if (-not (Test-Path $java)) {
    Write-Host "ERROR: Could not find Minecraft's bundled java.exe." -ForegroundColor Red
    Write-Host "Make sure Minecraft Launcher has been run at least once." -ForegroundColor Yellow
    pause
    exit 1
}

& $java "-Dnimbus.home=$PSScriptRoot" -Xms64M -Xmx128M -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -cp $out NimbusPrime
