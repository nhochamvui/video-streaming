# Phase 1 harness - RTMP handshake/chunk-size/publish throttle experiment.
# See docs/scaling/phase1-rtmp-throttle.md for the design and the PASS/FAIL gate.
#
# Pins one node near capacity with -Preload publishers, then bursts -Bots more
# and records how many are served vs rejected (NetStream.Publish.BadName) or
# timed out, plus the total wall time.
#
#   PASS = all -Bots served, none rejected/timed out, within -Window seconds.
#   FAIL = any rejected/timeout -> proceed with Phase 2
#          (docs/scaling/phase2-session-retry.md).
#
# Requires: ffmpeg on PATH, PowerShell 7+ (for -SkipHttpErrorCheck).
# Start the backend with the experiment throttles enabled, e.g.:
#   $env:RTMP_THROTTLE_MIN_STREAMS=14
#   $env:RTMP_THROTTLE_HANDSHAKE_MS=5000
#   $env:RTMP_THROTTLE_CHUNK_SIZE_MS=3000
#   $env:RTMP_THROTTLE_PUBLISH_MS=2000
#   ./gradlew.bat run
[CmdletBinding()]
param(
    [string]$RtmpHost   = '127.0.0.1',
    [int]$RtmpPort      = 1935,
    [int]$ApiPort       = 8888,
    [string]$Username   = 'admin',
    [string]$Password   = 'changeme',
    [int]$Preload       = 14,
    [int]$Bots          = 20,
    [string]$Resolution = '640x360',
    [string]$Bitrate    = '800k',
    [int]$Duration      = 60,
    [int]$Window        = 20
)

$ErrorActionPreference = 'Stop'
$apiBase = "http://${RtmpHost}:${ApiPort}"
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("scale-throttle-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Invoke-Login {
    $body = @{ username = $Username; password = $Password } | ConvertTo-Json
    $resp = Invoke-WebRequest -Uri "$apiBase/api/v1/auth/login" -Method Post `
        -Body $body -ContentType 'application/json' -WebSession $session -SkipHttpErrorCheck
    if ($resp.StatusCode -ne 200) {
        throw "Login failed (HTTP $($resp.StatusCode)): $($resp.Content)"
    }
}

function New-StreamKey {
    $resp = Invoke-WebRequest -Uri "$apiBase/api/v1/stream-sessions" -Method Post `
        -WebSession $session -SkipHttpErrorCheck
    if ($resp.StatusCode -eq 201) {
        return ($resp.Content | ConvertFrom-Json).streamKey
    }
    return $null
}

function Start-Publisher {
    param([string]$Id)
    $err = Join-Path $work "$Id.err.log"
    $out = Join-Path $work "$Id.out.log"
    $key = New-StreamKey
    if (-not $key) {
        Set-Content -Path $err -Value 'no-session'
        return $null
    }
    $ffArgs = @(
        '-hide_banner', '-loglevel', 'error', '-re',
        '-f', 'lavfi', '-i', "testsrc=duration=${Duration}:size=${Resolution}:rate=30",
        '-f', 'lavfi', '-i', "sine=frequency=440:duration=${Duration}",
        '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency', '-b:v', $Bitrate,
        '-c:a', 'aac', '-b:a', '64k', '-f', 'flv',
        "rtmp://${RtmpHost}:${RtmpPort}/live/$key")
    return Start-Process -FilePath 'ffmpeg' -ArgumentList $ffArgs -NoNewWindow -PassThru `
        -RedirectStandardError $err -RedirectStandardOutput $out
}

Invoke-Login
Write-Host "Preloading $Preload publishers on ${RtmpHost}:${RtmpPort} ..."
$pre = @()
for ($i = 1; $i -le $Preload; $i++) {
    $p = Start-Publisher "pre-$i"
    if ($p) { $pre += $p }
}
Start-Sleep -Seconds 3
$alive = ($pre | Where-Object { -not $_.HasExited }).Count
Write-Host "Preload active: $alive/$Preload"

Write-Host "Bursting $Bots publishers (window ${Window}s) ..."
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$burst = @()
for ($i = 1; $i -le $Bots; $i++) {
    $p = Start-Publisher "burst-$i"
    if ($p) { $burst += $p }
}
$burst | ForEach-Object { $_.WaitForExit() }
$sw.Stop()

$served = 0; $badname = 0; $timeout = 0
for ($i = 1; $i -le $Bots; $i++) {
    $err = Join-Path $work "burst-$i.err.log"
    $text = if (Test-Path $err) { Get-Content -Raw $err } else { '' }
    if ($text -match 'BadName') { $badname++ }
    elseif ([string]::IsNullOrWhiteSpace($text)) { $served++ }
    else { $timeout++ }
}

$wall = [math]::Round($sw.Elapsed.TotalSeconds, 1)
Write-Host '--------------------------------------------------'
Write-Host "PRELOAD=$Preload BOTS=$Bots RES=$Resolution BR=$Bitrate DURATION=$Duration"
Write-Host "served=$served badname=$badname timeout=$timeout"
Write-Host "total wall time=${wall}s (window=${Window}s)"

$pre | Where-Object { -not $_.HasExited } | ForEach-Object { $_.Kill() }

if ($served -eq $Bots -and $badname -eq 0 -and $timeout -eq 0) {
    Write-Host 'RESULT: PASS'
    exit 0
}
Write-Host 'RESULT: FAIL -> proceed with Phase 2 (docs/scaling/phase2-session-retry.md)'
exit 1
