<#
.SYNOPSIS
    Stops the services started by scripts\start.ps1.

.DESCRIPTION
    Only stops a process if its id is in .run/<service>.pid and its command line is still that service's jar, so a
    stale pid file can never stop an unrelated process.
#>
$ErrorActionPreference = 'Stop'
$runDir = Join-Path (Split-Path -Parent $PSScriptRoot) '.run'

# The app first, so it isn't left calling a vendor that has gone away.
foreach ($name in 'quote-app', 'vendor-mock') {
    $pidFile = Join-Path $runDir "$name.pid"
    if (-not (Test-Path $pidFile)) {
        Write-Host "$name is not running."
        continue
    }
    $id = [int](Get-Content $pidFile)
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$id"
    if ($process -and $process.CommandLine -match [regex]::Escape("$name-")) {
        Stop-Process -Id $id -Force
        Write-Host "Stopped $name (pid $id)."
    } else {
        Write-Host "$name is not running (removing stale pid file)."
    }
    Remove-Item $pidFile
}
