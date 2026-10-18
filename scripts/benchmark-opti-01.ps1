param(
    [Parameter(Mandatory=$true)][string]$BeforeRef,
    [string]$AfterRef,
    [string]$Java = 'java',
    [string]$ResultsDir = 'target/opti-01-results',
    [ValidateSet('All','Prepare','Calibrate','Before','After','Application','Profiles')][string]$Phase = 'All',
    [int]$EnginePairs = 5,
    [int]$ApplicationPairs = 3,
    [int]$ApplicationSeconds = 20,
    [int]$ApplicationWarmupSeconds = 5,
    [switch]$Resume
)
$ErrorActionPreference = 'Stop'
if ($EnginePairs -lt 5 -or $ApplicationPairs -lt 3 -or $ApplicationSeconds -lt 20) { throw 'Minimum: 5 engine pairs, 3 application pairs, 20 second windows.' }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$results = if ([IO.Path]::IsPathRooted($ResultsDir)) { [IO.Path]::GetFullPath($ResultsDir) } else { [IO.Path]::GetFullPath((Join-Path $repo $ResultsDir)) }
$javaPath = (Get-Command $Java -ErrorAction Stop).Source
$jdkBin = Split-Path -Parent $javaPath
$env:JAVA_HOME = Split-Path -Parent $jdkBin
$flags = @('-Xms2g','-Xmx2g','-XX:+UseG1GC')
foreach ($dir in @('raw','logs','profiles','builds')) { New-Item -ItemType Directory -Force -Path (Join-Path $results $dir) | Out-Null }
$environment = [ordered]@{StartedUtc=[DateTime]::UtcNow.ToString('o'); Before=$BeforeRef; After=$AfterRef; Java=$javaPath; Flags=$flags; EnginePairs=$EnginePairs; ApplicationPairs=$ApplicationPairs; ApplicationSeconds=$ApplicationSeconds; ApplicationWarmupSeconds=$ApplicationWarmupSeconds; EngineWarmupSeconds=3; HeapSamplePeriodMs=10; PerformanceSeed=11; ReplaySeeds=@(11,22,33); PollEndpoints=@('/api/simulation','/api/simulation/scores','/api/performance','/api/board/preview'); PollPeriodMs=1000; PreviewWidth=500; PreviewHeight=500; Cpu=(Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors,L2CacheSize,L3CacheSize); Computer=(Get-CimInstance Win32_ComputerSystem | Select-Object TotalPhysicalMemory); Os=(Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,OSArchitecture)}
$environment | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $results 'environment.json')
$environment | ConvertTo-Json -Depth 8 -Compress | Add-Content -Encoding UTF8 -LiteralPath (Join-Path $results 'invocations.jsonl')
$savedErrorPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue' # java -version intentionally writes its version to stderr on Windows PowerShell.
& $javaPath -version 2> (Join-Path $results 'java-version.txt')
$ErrorActionPreference = $savedErrorPreference
$versions = @{}

function Prepare-Version([string]$Label, [string]$Reference) {
    if (!$Reference) { return }
    Push-Location -LiteralPath $repo
    try {
        $commit = (git rev-parse $Reference).Trim()
        if ($LASTEXITCODE -ne 0) { throw "Invalid reference: $Reference" }
        $folder = Join-Path $results "builds/$Label"
        $source = Join-Path $folder 'source'
        New-Item -ItemType Directory -Force -Path $source | Out-Null
        $metadataPath = Join-Path $folder 'build.json'
        if (!($Resume -and (Test-Path -LiteralPath $metadataPath) -and ((Get-Content $metadataPath -Raw | ConvertFrom-Json).commit -eq $commit))) {
            git archive --format=tar -o (Join-Path $folder 'source.tar') $commit
            if ($LASTEXITCODE -ne 0) { throw 'git archive failed.' }
            & tar -xf (Join-Path $folder 'source.tar') -C $source
            if ($LASTEXITCODE -ne 0) { throw 'Source extraction failed.' }
            Push-Location -LiteralPath $source
            try {
                & ./mvnw.cmd -B -ntp verify dependency:build-classpath '-Dmdep.outputFile=target/experiment-classpath.txt' > (Join-Path $results "logs/build-$Label.log") 2>&1
                if ($LASTEXITCODE -ne 0) { throw "Build failed: $Label. See logs/build-$Label.log" }
                $cp = 'target/classes;' + (Get-Content target/experiment-classpath.txt -Raw).Trim()
                New-Item -ItemType Directory -Force -Path target/experiment-classes | Out-Null
                $javaFiles = @(Get-ChildItem -LiteralPath scripts/opti01 -Filter '*.java' | ForEach-Object FullName)
                & (Join-Path $jdkBin 'javac.exe') --release 21 --class-path $cp -d target/experiment-classes $javaFiles > (Join-Path $results "logs/harness-$Label.log") 2>&1
                if ($LASTEXITCODE -ne 0) { throw "Harness compilation failed: $Label" }
                @('Premain-Class: org.pixelwar.pixelwar.domain.StorageAgent','') | Set-Content -LiteralPath target/experiment-manifest.mf -Encoding Ascii
                & (Join-Path $jdkBin 'jar.exe') --create --file target/storage-agent.jar --manifest target/experiment-manifest.mf -C target/experiment-classes .
                if ($LASTEXITCODE -ne 0) { throw 'Agent packaging failed.' }
                [ordered]@{commit=$commit; jarSha256=(Get-FileHash target/pixel-war-0.0.1-SNAPSHOT.jar -Algorithm SHA256).Hash; harnessSha256=(Get-FileHash target/storage-agent.jar -Algorithm SHA256).Hash; sourceStatus='clean git archive'} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath $metadataPath
            } finally { Pop-Location }
        }
        $dependencyCp = (Get-Content (Join-Path $source 'target/experiment-classpath.txt') -Raw).Trim()
        $versions[$Label] = @{source=$source; commit=$commit; meta=(Get-Content $metadataPath -Raw | ConvertFrom-Json); cp=((Join-Path $source 'target/experiment-classes') + ';' + (Join-Path $source 'target/classes') + ';' + $dependencyCp); agent=(Join-Path $source 'target/storage-agent.jar')}
    } finally { Pop-Location }
}
function Stamp-Result([string]$Path, [string]$Label, [string]$RunId, [int]$Order) {
    $data = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    $data | Add-Member -NotePropertyName version -NotePropertyValue $Label -Force
    $data | Add-Member -NotePropertyName commit -NotePropertyValue $versions[$Label].commit -Force
    $data | Add-Member -NotePropertyName runId -NotePropertyValue $RunId -Force
    $data | Add-Member -NotePropertyName pairOrder -NotePropertyValue $Order -Force
    $data | Add-Member -NotePropertyName binarySha256 -NotePropertyValue $versions[$Label].meta.jarSha256 -Force
    $data | Add-Member -NotePropertyName harnessSha256 -NotePropertyValue $versions[$Label].meta.harnessSha256 -Force
    $data | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $Path -Encoding UTF8
}
function Run-Engine([string]$Label,[int]$Size,[string]$Initial,[long]$Seed,[long]$Budget,[string]$Mode,[int]$Pair,[int]$Order,[double]$Warmup=3) {
    $id = "$Mode-$Size-$Initial-s$Seed-p$Pair-$Label"
    $output = Join-Path $results "raw/$id.json"
    if ($Resume -and (Test-Path -LiteralPath $output)) {
        if ((Get-Content $output -Raw | ConvertFrom-Json).commit -ne $versions[$Label].commit) { throw "Stale result: $output" }
        return
    }
    Write-Host "[$([DateTime]::Now.ToString('HH:mm:ss'))] $id : $Budget actions"
    $profile = if ($Mode -eq 'profile') { Join-Path $results "profiles/$id.jfr" } else { 'none' }
    $v = $versions[$Label]
    $arguments = $flags + @(('-javaagent:' + $v.agent),'--class-path',$v.cp,'org.pixelwar.pixelwar.domain.BoardExperiment',$Mode,"$Size",$Initial,"$Seed","$Budget",$Warmup.ToString([Globalization.CultureInfo]::InvariantCulture),$output,$profile)
    $savedErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $javaPath @arguments > (Join-Path $results "logs/$id.log") 2>&1
    $nativeExit = $LASTEXITCODE
    $ErrorActionPreference = $savedErrorPreference
    if ($nativeExit -ne 0) { throw "Engine trial failed: $id (failure preserved in logs)." }
    Stamp-Result $output $Label $id $Order
}

Add-Type -AssemblyName System.Net.Http
if (!('Opti01Poller' -as [type])) {
    Add-Type -ReferencedAssemblies ([System.Net.Http.HttpClient].Assembly.Location) -TypeDefinition @'
using System;
using System.Diagnostics;
using System.Net.Http;
using System.Threading.Tasks;
public class Opti01PollResult { public string Path; public int Status; public double ElapsedNanos; public string Error; }
public static class Opti01Poller {
 public static async Task<Opti01PollResult> Read(HttpClient client, string root, string path) {
  var watch = Stopwatch.StartNew(); var result = new Opti01PollResult { Path = path };
  try { using (var response = await client.GetAsync(root + path)) { await response.Content.ReadAsStringAsync(); result.Status = (int)response.StatusCode; } }
  catch (Exception exception) { result.Error = exception.GetType().Name + ": " + exception.Message; }
  result.ElapsedNanos = watch.ElapsedTicks * 1e9 / Stopwatch.Frequency; return result;
 }
}
'@
}
function Run-Application([string]$Label,[int]$Size,[int]$Pair,[int]$Order,[bool]$Profile=$false) {
    $id = if ($Profile) { "application-profile-$Size-empty-s11-p$Pair-$Label" } else { "application-$Size-empty-s11-p$Pair-$Label" }
    $output = Join-Path $results "raw/$id.json"
    if ($Resume -and (Test-Path -LiteralPath $output)) {
        if ((Get-Content $output -Raw | ConvertFrom-Json).commit -ne $versions[$Label].commit) { throw "Stale result: $output" }
        return
    }
    $port = 18086
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$port)
    try { $probe.Start() } finally { $probe.Stop() }
    Write-Host "[$([DateTime]::Now.ToString('HH:mm:ss'))] $id : $ApplicationSeconds seconds with polling"
    $v = $versions[$Label]; $root = "http://127.0.0.1:$port"
    $profilePath = if ($Profile) { Join-Path $results "profiles/$id.jfr" } else { 'none' }
    $argValues = $flags + @(('-javaagent:' + $v.agent),'--class-path',$v.cp,'org.pixelwar.pixelwar.domain.BoardExperiment','server',"$Size",'empty','11',"$port",'0',$output,$profilePath)
    $quoted = @($argValues | ForEach-Object { '"' + $_ + '"' })
    $process = Start-Process -FilePath $javaPath -ArgumentList $quoted -WindowStyle Hidden -PassThru -WorkingDirectory $v.source -RedirectStandardOutput (Join-Path $results "logs/$id.log") -RedirectStandardError (Join-Path $results "logs/$id-error.log")
    [Net.ServicePointManager]::DefaultConnectionLimit = 32
    $client = [Net.Http.HttpClient]::new(); $client.Timeout = [TimeSpan]::FromSeconds(30)
    try {
        $ready=$false
        for ($i=0;$i -lt 120;$i++) {
            if ($process.HasExited) { throw "Server exited: $id" }
            try { Invoke-RestMethod "$root/health" -TimeoutSec 1 | Out-Null; $ready=$true; break } catch { Start-Sleep -Milliseconds 250 }
        }
        if (!$ready) { throw "Server startup timed out: $id" }
        Invoke-RestMethod "$root/api/simulation/start" -Method Post | Out-Null
        Start-Sleep -Seconds $ApplicationWarmupSeconds
        Invoke-RestMethod "$root/api/simulation/pause" -Method Post | Out-Null
        $before = Invoke-RestMethod "$root/experiment/begin" -Method Post
        $requests = [Collections.Generic.List[object]]::new()
        $watch = [Diagnostics.Stopwatch]::StartNew(); $nextPoll=0.0
        Invoke-RestMethod "$root/api/simulation/resume" -Method Post | Out-Null
        while ($watch.Elapsed.TotalSeconds -lt $ApplicationSeconds) {
            $tasks = @($environment.PollEndpoints | ForEach-Object { [Opti01Poller]::Read($client,$root,$_) })
            foreach ($task in $tasks) { $requests.Add($task.GetAwaiter().GetResult()) }
            $nextPoll += 1000
            $delay = $nextPoll - $watch.Elapsed.TotalMilliseconds
            if ($delay -gt 0) { Start-Sleep -Milliseconds ([int]$delay) }
        }
        Invoke-RestMethod "$root/api/simulation/pause" -Method Post | Out-Null
        $elapsedNanos = $watch.Elapsed.TotalSeconds * 1e9
        $after = Invoke-RestMethod "$root/experiment/finish" -Method Post
        $actions = $after.business.actions - $before.business.actions
        $latencies = @($requests | Where-Object Status -eq 200 | ForEach-Object ElapsedNanos | Sort-Object)
        $p95 = if ($latencies.Count) { $latencies[[int][Math]::Ceiling(.95*$latencies.Count)-1] } else { $null }
        $p99 = if ($latencies.Count) { $latencies[[int][Math]::Ceiling(.99*$latencies.Count)-1] } else { $null }
        $data = [ordered]@{mode='application'; size=$Size; initial='empty'; seed=11; requestedSeconds=$ApplicationSeconds; warmupSeconds=$ApplicationWarmupSeconds; elapsedNanos=$elapsedNanos; executedActions=$actions; before=$before; after=$after; cpuNanos=($after.cpuNanos-$before.cpuNanos); processCpuNanos=($after.processCpuNanos-$before.processCpuNanos); allocatedBytesPerAction=($after.engineAllocatedBytes-$before.engineAllocatedBytes)/$actions; peakObservedHeapBytes=$after.peakObservedHeapBytes; heapSamplePeriodMillis=10; attemptsPerSecond=($after.business.attempts-$before.business.attempts)*1e9/$elapsedNanos; modificationsPerSecond=($after.business.modifications-$before.business.modifications)*1e9/$elapsedNanos; httpP95Nanos=$p95; httpP99Nanos=$p99; httpSamples=$latencies.Count; errors=@($requests | Where-Object { $_.Status -ne 200 -or $_.Error }); requests=$requests; instrumentation= $(if ($Profile) {'JFR-profile; monitor threshold 1 ms'} else {'no JFR'}); colorsLayout=$after.colorsLayout }
        $data | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $output -Encoding UTF8
        Stamp-Result $output $Label $id $Order
    } finally {
        $client.Dispose()
        if (!$process.HasExited) { Stop-Process -Id $process.Id -Force }
    }
}

Prepare-Version 'before' $BeforeRef
Prepare-Version 'after' $AfterRef
if ($Phase -eq 'Prepare') { return }
$budgetPath = Join-Path $results 'budgets.json'
if ($Phase -in @('All','Calibrate') -and !(Test-Path -LiteralPath $budgetPath)) {
    $budgets = [ordered]@{}
    foreach ($size in @(500,4000)) { foreach ($initial in @('empty','full')) {
        Run-Engine 'before' $size $initial 11 5000 'calibration' 0 0 3
        $data = Get-Content (Join-Path $results "raw/calibration-$size-$initial-s11-p0-before.json") -Raw | ConvertFrom-Json
        $budgets["$size-$initial"] = [long]([Math]::Max(10000,[Math]::Min(1000000,[Math]::Ceiling(5000*2e9/$data.elapsedNanos/1000)*1000)))
    } }
    $budgets | ConvertTo-Json | Set-Content -LiteralPath $budgetPath -Encoding UTF8
}
if ($Phase -eq 'Calibrate') { return }
if (!(Test-Path -LiteralPath $budgetPath)) { throw 'Run -Phase Calibrate on baseline before modifying storage.' }
$budgets = Get-Content $budgetPath -Raw | ConvertFrom-Json
if ($Phase -in @('All','Before','After')) {
    foreach ($size in @(500,4000)) { foreach ($initial in @('empty','full')) {
        for ($pair=1;$pair -le $EnginePairs;$pair++) {
            $order = if ($pair % 2) {@('before','after')} else {@('after','before')}
            for ($index=0;$index -lt $order.Count;$index++) {
                $label=$order[$index]
                if (($Phase -eq 'Before' -and $label -ne 'before') -or ($Phase -eq 'After' -and $label -ne 'after') -or !$versions.ContainsKey($label)) { continue }
                Run-Engine $label $size $initial 11 $budgets."$size-$initial" 'engine' $pair $index 3
            }
        }
    } }
    foreach ($size in @(500,4000)) { foreach ($initial in @('empty','full')) { foreach ($seed in @(11,22,33)) {
        foreach ($label in @('before','after')) {
            if (($Phase -eq 'Before' -and $label -ne 'before') -or ($Phase -eq 'After' -and $label -ne 'after') -or !$versions.ContainsKey($label)) { continue }
            Run-Engine $label $size $initial $seed 10000 'replay' 0 0 0
        }
    } } }
}
if ($Phase -in @('All','Application')) {
    foreach ($size in @(500,4000)) { for ($pair=1;$pair -le $ApplicationPairs;$pair++) {
        $order = if ($pair % 2) {@('before','after')} else {@('after','before')}
        for ($index=0;$index -lt 2;$index++) { Run-Application $order[$index] $size $pair $index }
    } }
}
if ($Phase -in @('All','Profiles','Before','After')) {
    foreach ($label in @('before','after')) {
        if (($Phase -eq 'Before' -and $label -ne 'before') -or ($Phase -eq 'After' -and $label -ne 'after') -or !$versions.ContainsKey($label)) { continue }
        Run-Engine $label 4000 'full' 11 (4*$budgets.'4000-full') 'profile' 0 0 3
        if ($Phase -in @('All','Profiles')) { Run-Application $label 4000 0 0 $true }
    }
}
Write-Host "Measurements saved in $results"
