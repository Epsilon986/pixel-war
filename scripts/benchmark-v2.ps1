param(
    [string]$Java = 'java',
    [int]$BoardSize = 500,
    [int]$WarmupSeconds = 3,
    [int]$MeasureSeconds = 5,
    [int]$Port = 18082
)

$ErrorActionPreference = 'Stop'
$workspace = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifact = Join-Path $workspace 'target/pixel-war-0.0.1-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $artifact)) { throw 'Run mvnw package before benchmarking.' }
if ($BoardSize -lt 3 -or $WarmupSeconds -lt 1 -or $MeasureSeconds -lt 1) { throw 'Board size must be >=3 and durations >=1.' }
$target = Join-Path $workspace 'target'
$resultPath = Join-Path $target 'v2-benchmark.json'
$baseUrl = "http://localhost:$Port/api"
$results = @()
$scenarios = @(
    @{ Name='single'; Max=1; Enabled='false'; Initial=1000000; Refill=1000000; Period=1000 },
    @{ Name='multiple'; Max=10; Enabled='false'; Initial=1000000; Refill=1000000; Period=1000 },
    @{ Name='stock-limited'; Max=10; Enabled='false'; Initial=0; Refill=1; Period=1000000 },
    @{ Name='conversion-single'; Max=1; Enabled='true'; Initial=1000000; Refill=1000000; Period=1000 },
    @{ Name='conversion-multiple'; Max=10; Enabled='true'; Initial=1000000; Refill=1000000; Period=1000 }
)

function Get-Metrics { Invoke-RestMethod "$baseUrl/metrics" -TimeoutSec 60 }
function Get-PreviewCycle {
    Invoke-RestMethod "$baseUrl/simulation" -TimeoutSec 60 | Out-Null
    Invoke-RestMethod "$baseUrl/simulation/scores" -TimeoutSec 60 | Out-Null
    Get-Metrics | Out-Null
    Invoke-RestMethod "$baseUrl/board/preview" -TimeoutSec 60 | Out-Null
}

foreach ($scenario in $scenarios) {
    foreach ($polling in @($false, $true)) {
        # Refuse to send simulation commands to an unrelated server on the selected port.
        $portProbe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
        try { $portProbe.Start() } finally { $portProbe.Stop() }
        $arguments = @('-Xmx1g', '-jar', ('"' + $artifact + '"'), "--server.port=$Port",
            "--pixelwar.board.width=$BoardSize", "--pixelwar.board.height=$BoardSize",
            '--pixelwar.players.interval-ns=1000', "--pixelwar.players.max-pixels-per-action=$($scenario.Max)",
            "--pixelwar.stock.initial=$($scenario.Initial)", '--pixelwar.stock.capacity=1000000',
            "--pixelwar.stock.refill-amount=$($scenario.Refill)", "--pixelwar.stock.refill-interval-ns=$($scenario.Period)",
            "--pixelwar.conversion.enabled=$($scenario.Enabled)")
        # A separate local JVM per scenario, hidden, with output confined to target/.
        $process = Start-Process -FilePath $Java -ArgumentList $arguments -WindowStyle Hidden -PassThru -WorkingDirectory $workspace `
            -RedirectStandardOutput (Join-Path $target 'v2-benchmark-server.log') -RedirectStandardError (Join-Path $target 'v2-benchmark-server-error.log')
        try {
            $ready = $false
            for ($retry = 0; $retry -lt 60; $retry++) {
                if ($process.HasExited) { throw 'Benchmark server exited; inspect target/v2-benchmark-server-error.log.' }
                try { Invoke-RestMethod "$baseUrl/simulation" -TimeoutSec 1 | Out-Null; $ready = $true; break } catch { Start-Sleep -Milliseconds 250 }
            }
            if (!$ready) { throw 'Benchmark server did not start.' }
            Invoke-RestMethod -Method Post "$baseUrl/simulation/start" | Out-Null
            Start-Sleep -Seconds $WarmupSeconds
            # Pause permits coherent boundaries for deltas; counters and board are preserved.
            Invoke-RestMethod -Method Post "$baseUrl/simulation/pause" | Out-Null
            $before = Get-Metrics
            Invoke-RestMethod -Method Post "$baseUrl/simulation/resume" | Out-Null
            $timer = [System.Diagnostics.Stopwatch]::StartNew()
            while ($timer.Elapsed.TotalSeconds -lt $MeasureSeconds) {
                if ($polling) { Get-PreviewCycle }
                Start-Sleep -Seconds 1
            }
            Invoke-RestMethod -Method Post "$baseUrl/simulation/pause" | Out-Null
            $after = Get-Metrics
            $seconds = ($after.simulation.elapsedMs - $before.simulation.elapsedMs) / 1000.0
            $actions = $after.actions - $before.actions
            $passes = $after.conversionPasses - $before.conversionPasses
            $result = [pscustomobject]@{
                Scenario=$scenario.Name; Polling=$polling; BoardSize=$BoardSize; IntervalNs=1000; MaxPixels=$scenario.Max
                ActiveSeconds=$seconds; ActionsPerSecond=$actions/$seconds
                AttemptsPerSecond=($after.simulation.attempts-$before.simulation.attempts)/$seconds
                ModificationsPerSecond=($after.simulation.modifications-$before.simulation.modifications)/$seconds
                ConversionsPerSecond=($after.conversions-$before.conversions)/$seconds
                BoardChangesPerSecond=($after.boardChanges-$before.boardChanges)/$seconds
                ActionsWithoutStock=$after.actionsWithoutStock-$before.actionsWithoutStock
                ConversionMeanMs=$(if ($passes -gt 0) { ($after.conversionDurationNanos.totalNanos-$before.conversionDurationNanos.totalNanos)/$passes/1000000.0 } else { 0 })
                ActionMeanMs=$(if ($actions -gt 0) { ($after.latency.averageNanos*$after.actions-$before.latency.averageNanos*$before.actions)/$actions/1000000.0 } else { 0 })
                CpuLoad=$after.jvm.processCpuLoad; HeapUsedMiB=$after.jvm.heapUsedBytes/1MB
            }
            $results += $result
            $results | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 -LiteralPath $resultPath
            Write-Output ($result | ConvertTo-Json -Compress)
        } finally {
            if (!$process.HasExited) { Stop-Process -Id $process.Id }
            $process.Dispose()
        }
    }
}
Write-Output "Results: $resultPath"
