<#
.SYNOPSIS
    Starts the vendor mock (:8081) and the Quote App (:8080) in the background with the `local` profile.

.DESCRIPTION
    Builds the jars first if they are missing (or always, with -Build), starts both services, waits until both
    report healthy, and prints where they run. Logs and process ids go to .run/ (git-ignored).
    Environment variables are passed through, e.g. $env:MOCK_CHAOS_FAILURE_RATE='1'; $env:MOCK_CHAOS_SLOW_RATE='0'.
    Stop with scripts\stop.ps1.

.PARAMETER Build
    Rebuild both jars before starting (tests skipped; run `mvnw.cmd verify` for those).
#>
param([switch]$Build)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $root '.run'
$healthTimeoutSeconds = 60

# The mock starts first: the app doesn't need it to start, but a quote needs it to be up.
$services = @(
    @{ Name = 'vendor-mock'; Port = 8081 },
    @{ Name = 'quote-app';   Port = 8080 }
)

function Find-Jar([string]$name) {
    Get-ChildItem (Join-Path $root "$name\target") -Filter "$name-*.jar" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}

function Test-Running([string]$name) {
    $pidFile = Join-Path $runDir "$name.pid"
    if (-not (Test-Path $pidFile)) { return $false }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$([int](Get-Content $pidFile))"
    return [bool]($process -and $process.CommandLine -match [regex]::Escape("$name-"))
}

function Test-PortInUse([int]$port) {
    # Try to connect rather than list sockets: closing sockets from a just-stopped service can still be listed.
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        return $client.ConnectAsync('localhost', $port).Wait(500)
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

New-Item -ItemType Directory -Force $runDir | Out-Null

$missingJars = @($services | Where-Object { -not (Find-Jar $_.Name) })
if ($Build -or $missingJars.Count -gt 0) {
    Write-Host 'Building jars (tests skipped)...'
    Push-Location $root
    try {
        & (Join-Path $root 'mvnw.cmd') -B -q package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Build failed (exit code $LASTEXITCODE)." }
    } finally {
        Pop-Location
    }
}

foreach ($service in $services) {
    $name = $service.Name
    if (Test-Running $name) {
        Write-Host "$name is already running (pid $(Get-Content (Join-Path $runDir "$name.pid")))."
        continue
    }
    if (Test-PortInUse $service.Port) {
        throw "Port $($service.Port) is already in use by another process, so $name can't start."
    }
    $jar = Find-Jar $name
    $log = Join-Path $runDir "$name.log"
    $process = Start-Process java -PassThru -WindowStyle Hidden `
        -ArgumentList '-jar', "`"$jar`"", '--spring.profiles.active=local' `
        -RedirectStandardOutput $log -RedirectStandardError (Join-Path $runDir "$name.err.log")
    Set-Content (Join-Path $runDir "$name.pid") $process.Id
    Write-Host "Started $name (pid $($process.Id)), logging to .run\$name.log"
}

foreach ($service in $services) {
    $name = $service.Name
    $healthUrl = "http://localhost:$($service.Port)/actuator/health"
    $deadline = (Get-Date).AddSeconds($healthTimeoutSeconds)
    while ($true) {
        try {
            if ((Invoke-WebRequest $healthUrl -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) { break }
        } catch {
            # Not listening yet.
        }
        if (-not (Test-Running $name)) {
            Get-Content (Join-Path $runDir "$name.log") -Tail 30
            throw "$name stopped during startup; the end of its log is above."
        }
        if ((Get-Date) -gt $deadline) {
            throw "$name did not become healthy within $healthTimeoutSeconds s; see .run\$name.log"
        }
        Start-Sleep -Milliseconds 500
    }
}

Write-Host ''
Write-Host 'Both services are up:'
Write-Host '  Quote App    http://localhost:8080   <- open this in a browser'
Write-Host '  Vendor mock  http://localhost:8081   (POST /v1/commission-quotes)'
Write-Host 'Stop them with scripts\stop.ps1'
