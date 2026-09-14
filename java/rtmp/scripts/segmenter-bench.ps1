<#
.SYNOPSIS
  Baseline benchmark for hls-segmenter: measures per-stream process overhead
  (RSS, threads, CPU) in the current one-process-per-stream model.

.DESCRIPTION
  Feeds N concurrent streams into N `hls-segmenter` processes and samples their
  resource usage. Run it BEFORE and AFTER the multiplexing change to compare
  (after: one daemon process serving all N streams).

  Only the `hls-segmenter` processes are measured; the ffmpeg feeder (which just
  re-muxes with -c copy) is excluded from the per-process numbers.

.EXAMPLE
  ./segmenter-bench.ps1 -Streams 8 -Seconds 30
#>
param(
  [int]$Streams = 8,
  [int]$Seconds = 30,
  [string]$Resolution = '1280x720',
  [string]$Bitrate = '2500k',
  [string]$OutRoot
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $OutRoot) { $OutRoot = Join-Path $root 'build\bench' }
New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null
$bin = Join-Path $OutRoot 'hls-segmenter.exe'
$sample = Join-Path $OutRoot ("sample-$Resolution.flv")
$sampleSeconds = $Seconds + 10

Write-Host "[bench] building segmenter..."
Push-Location (Join-Path $root 'go-hls')
& go build -o $bin .
Pop-Location

if (-not (Test-Path $sample)) {
  Write-Host "[bench] generating $Resolution sample (${sampleSeconds}s)..."
  & ffmpeg -y -hide_banner -loglevel error `
    -f lavfi -i "testsrc=size=${Resolution}:rate=30" `
    -f lavfi -i "sine=frequency=440" -t $sampleSeconds `
    -c:v libx264 -preset ultrafast -tune zerolatency -b:v $Bitrate `
    -c:a aac -b:a 64k -f flv $sample
}

Write-Host "[bench] launching $Streams stream(s), ${Seconds}s window ..."
$wrappers = @()
for ($i = 1; $i -le $Streams; $i++) {
  $dir = Join-Path $OutRoot "stream-$i"
  if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $line = "ffmpeg -hide_banner -loglevel error -re -i `"$sample`" -c copy -f flv - | `"$bin`" --out-dir `"$dir`" --hls-time 2 --hls-list-size 10 --hls-delete-threshold 1"
  $wrappers += Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', $line -WindowStyle Hidden -PassThru
}

Start-Sleep -Seconds 3

$samples = @()
$firstCpu = $null
$sw = [System.Diagnostics.Stopwatch]::StartNew()
for ($t = 0; $t -lt $Seconds; $t++) {
  $p = Get-Process -Name 'hls-segmenter' -ErrorAction SilentlyContinue
  if ($p) {
    $rss = ($p | Measure-Object -Property WorkingSet64 -Sum).Sum
    $thr = ($p | ForEach-Object { $_.Threads.Count } | Measure-Object -Sum).Sum
    $cpu = ($p | Measure-Object -Property CPU -Sum).Sum
    if ($null -eq $firstCpu) { $firstCpu = $cpu; $firstT = $sw.Elapsed.TotalSeconds }
    $samples += [pscustomobject]@{
      Second    = $t
      Procs     = $p.Count
      TotalRssMB= [math]::Round($rss / 1MB, 1)
      Threads   = $thr
      CpuSec    = $cpu
    }
  }
  Start-Sleep -Seconds 1
}
$sw.Stop()

# cleanup
Get-Process -Name 'hls-segmenter', 'ffmpeg' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
$wrappers | ForEach-Object { try { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue } catch {} }

if (-not $samples) { Write-Host '[bench] no hls-segmenter processes observed'; exit 1 }

$procs     = ($samples | Measure-Object -Property Procs -Maximum).Maximum
$avgRssTot = [math]::Round(($samples | Measure-Object -Property TotalRssMB -Average).Average, 1)
$maxRssTot = ($samples | Measure-Object -Property TotalRssMB -Maximum).Maximum
$avgThreads= [math]::Round(($samples | Measure-Object -Property Threads -Average).Average, 1)
$lastCpu   = $samples[-1].CpuSec
$cores     = if ($lastCpu -and ($sw.Elapsed.TotalSeconds - $firstT) -gt 0) {
  [math]::Round(($lastCpu - $firstCpu) / ($sw.Elapsed.TotalSeconds - $firstT), 2)
} else { 0 }

$result = [pscustomobject]@{
  mode              = 'process-per-stream'
  streams           = $Streams
  resolution        = $Resolution
  bitrate           = $Bitrate
  segmenterProcs    = $procs
  avgRssTotalMB     = $avgRssTot
  maxRssTotalMB     = $maxRssTot
  avgRssPerProcMB   = [math]::Round($avgRssTot / [math]::Max($procs, 1), 1)
  avgThreadsTotal   = $avgThreads
  avgThreadsPerProc = [math]::Round($avgThreads / [math]::Max($procs, 1), 1)
  avgCoresUsed      = $cores
  host              = "$env:COMPUTERNAME ($([Environment]::ProcessorCount) logical CPUs)"
  timestamp         = (Get-Date).ToString('s')
}
$jsonPath = Join-Path $OutRoot "bench-results-$Streams-$Resolution.json"
$result | ConvertTo-Json | Set-Content -Path $jsonPath -Encoding utf8

$result | Format-List
Write-Host "[bench] wrote $jsonPath"
