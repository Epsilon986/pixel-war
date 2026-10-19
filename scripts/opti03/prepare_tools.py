"""Create the O3 common driver from the retained O2 driver, without changing O2."""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
dest = root / 'scripts/opti03'
for name in ('StorageAgent.java', 'JfrSummary.java', 'requirements.txt'):
    text = (root / 'scripts/opti02' / name).read_text(encoding='utf-8-sig')
    (dest / name).write_text(text.replace('CounterExperiment', 'PositionExperiment'), encoding='utf-8')
s = (root / 'scripts/opti02/CounterExperiment.java').read_text(encoding='utf-8-sig').replace('CounterExperiment', 'PositionExperiment').replace('opti-02', 'opti-03')
s = s.replace('private static final Field ACTIONS', 'private static final Field ELIMINATED = field(Simulation.class, "eliminated");\n    private static final Field ACTIONS')
s = s.replace('private static Recording recording;', '''private static Recording recording;
    private static volatile Long victoryDetectedNanos;
    private static volatile Long lastContestedNanos;
    private static volatile Long victoryDetectedActions;
    private static long observationStart;
    private static int alive(Simulation simulation) throws Exception {
        int alive = 0;
        for (boolean eliminated : (boolean[]) ELIMINATED.get(simulation)) if (!eliminated) alive++;
        return alive;
    }
    private static void observe(Simulation simulation) throws Exception {
        synchronized (simulation) {
            long now = System.nanoTime() - observationStart;
            if (alive(simulation) >= 2) lastContestedNanos = now;
            else if (victoryDetectedNanos == null) {
                victoryDetectedNanos = now;
                victoryDetectedActions = ACTIONS.getLong(simulation);
            }
        }
    }
    private static Map<String, Object> gc() {
        long count = 0, millis = 0;
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += bean.getCollectionCount(); millis += bean.getCollectionTime();
        }
        return map("collections", count, "collectionMillis", millis);
    }''')
s = s.replace('var game = create(size, initial, seed, true);', '''if (warmupSeconds > 0) warmup(size, initial, seed, warmupSeconds);
            var game = create(size, initial, seed, true);''')
s = s.replace('serverBoard = game.board(); serverSimulation = game.simulation();', '''serverBoard = game.board(); serverSimulation = game.simulation();
            SAMPLER.scheduleAtFixedRate(() -> {
                try { observe(serverSimulation); } catch (Exception exception) { throw new RuntimeException(exception); }
            }, 0, 10, TimeUnit.MILLISECONDS);''')
s = s.replace('long start = System.nanoTime();\n            runActions', '''var gcBefore = gc();
            victoryDetectedNanos = lastContestedNanos = victoryDetectedActions = null;
            long start = observationStart = System.nanoTime();
            runActions''')
s = s.replace('"colorsLayout", layout, "runtime", runtime(), "errors", List.of()', '''"colorsLayout", layout, "runtime", runtime(), "errors", List.of(),
                    "gcBefore", gcBefore, "gcAfter", gc(),
                    "victoryDetectedNanos", victoryDetectedNanos, "lastContestedNanos", lastContestedNanos,
                    "victoryDetectedActions", victoryDetectedActions, "aliveAfter", alive(game.simulation()),
                    "representativeContest", victoryDetectedNanos == null && alive(game.simulation()) >= 2''')
s = s.replace('first = (first + 1) % 4;', 'observe(simulation);\n            first = (first + 1) % 4;')
s = s.replace('PEAK.set(MEMORY.getHeapMemoryUsage().getUsed());\n            if (serverProfile', '''PEAK.set(MEMORY.getHeapMemoryUsage().getUsed());
            synchronized (serverSimulation) {
                observationStart = System.nanoTime();
                victoryDetectedNanos = lastContestedNanos = victoryDetectedActions = null;
                observe(serverSimulation);
            }
            if (serverProfile''')
s = s.replace('"colorsLayout", layout(serverBoard), "runtime", runtime()', '''"colorsLayout", layout(serverBoard), "runtime", runtime(), "gc", gc(),
                    "alive", alive(serverSimulation), "victoryDetectedNanos", victoryDetectedNanos,
                    "lastContestedNanos", lastContestedNanos, "victoryDetectedActions", victoryDetectedActions,
                    "victoryObservationPeriodMillis", 10''')
(dest / 'PositionExperiment.java').write_text(s, encoding='utf-8')

s = (root / 'scripts/benchmark-opti-02.ps1').read_text(encoding='utf-8-sig')
s = s.replace('[Parameter(Mandatory=$true)][string]$Reference,', '[Parameter(Mandatory=$true)][string]$BeforeRef,\n    [Parameter(Mandatory=$true)][string]$AfterRef,')
s = s.replace("'All','Prepare','Calibrate','Application','Profiles'", "'All','Prepare','Calibrate','Engine','Replay','Application','Profiles','Analyze'")
s = s.replace('[int]$ApplicationWarmupSeconds = 5,', '[double]$ApplicationWarmupSeconds = 0.2,\n    [double]$SmallApplicationSeconds = 1,')
s = s.replace('opti-02', 'opti-03').replace('opti02', 'opti03').replace('Opti02', 'Opti03').replace('CounterExperiment', 'PositionExperiment')
s = s.replace("Reference=$Reference; Study='baseline only: no redundant production atomics'", "BeforeRef=$BeforeRef; AfterRef=$AfterRef; SmallApplicationSeconds=$SmallApplicationSeconds; Study='paired primitive frontier candidates'")
# Compile the same harness from this checkout against both archived production versions.
s = s.replace('scripts/opti03 -Filter', '(Join-Path $repo scripts/opti03) -Filter')
s = s.replace('$id = if ($Profile)', '$windowSeconds = if ($Size -eq 500) { $SmallApplicationSeconds } else { $ApplicationSeconds }\n    $id = if ($Profile)')
s = s.replace('$ApplicationSeconds seconds', '$windowSeconds seconds').replace("'11',\"$port\",'0'", "'11',\"$port\",'3'")
s = s.replace('Start-Sleep -Seconds $ApplicationWarmupSeconds', 'Start-Sleep -Milliseconds ([int](1000*$ApplicationWarmupSeconds))')
s = s.replace('$watch.Elapsed.TotalSeconds -lt $ApplicationSeconds', '$watch.Elapsed.TotalSeconds -lt $windowSeconds').replace('requestedSeconds=$ApplicationSeconds', 'requestedSeconds=$windowSeconds')
s = s.replace('colorsLayout=$after.colorsLayout }', '''colorsLayout=$after.colorsLayout; representativeContest=($before.alive -ge 2 -and $after.alive -ge 2 -and $null -eq $after.victoryDetectedNanos); victoryObservationPeriodMillis=10 }''')
start = s.index("Prepare-Version 'baseline' $Reference")
s = s[:start] + '''Prepare-Version 'before' $BeforeRef
Prepare-Version 'after' $AfterRef
if ($versions['before'].meta.harnessSha256 -ne $versions['after'].meta.harnessSha256) {
    # JAR timestamps may differ: class bytes, rather than ZIP timestamps, must match.
    foreach ($file in (Get-ChildItem (Join-Path $versions['before'].source 'target/experiment-classes') -Recurse -Filter '*.class')) {
        $relative = $file.FullName.Substring((Join-Path $versions['before'].source 'target/experiment-classes').Length + 1)
        $other = Join-Path (Join-Path $versions['after'].source 'target/experiment-classes') $relative
        if ((Get-FileHash $file.FullName).Hash -ne (Get-FileHash $other).Hash) { throw "Harness differs: $relative" }
    }
}
if ($Phase -eq 'Prepare') { return }
$budgetPath = Join-Path $results 'budgets.json'
if ($Phase -in @('All','Calibrate') -and !(Test-Path -LiteralPath $budgetPath)) {
    $budgets = [ordered]@{}
    foreach ($size in @(500,4000)) { foreach ($initial in @('empty','full')) {
        Run-Engine 'before' $size $initial 11 5000 'calibration' 0 0 3
        $data = Get-Content (Join-Path $results "raw/calibration-$size-$initial-s11-p0-before.json") -Raw | ConvertFrom-Json
        # Cap at the historical O2 budget, keeping the useful contest phase bounded.
        $cap = if ($size -eq 500) { 50000 } else { 100000 }
        $budgets["$size-$initial"] = [long]([Math]::Max(10000,[Math]::Min($cap,[Math]::Ceiling(5000*2e9/$data.elapsedNanos/1000)*1000)))
    } }
    $budgets | ConvertTo-Json | Set-Content -LiteralPath $budgetPath -Encoding UTF8
}
if ($Phase -eq 'Calibrate') { return }
if (!(Test-Path -LiteralPath $budgetPath)) { throw 'Run -Phase Calibrate first.' }
$budgets = Get-Content $budgetPath -Raw | ConvertFrom-Json
if ($Phase -in @('All','Engine')) {
    foreach ($size in @(500,4000)) { foreach ($initial in @('empty','full')) {
        for ($repeat=1;$repeat -le $EngineRepetitions;$repeat++) {
            $labels = if ($repeat % 2) { @('before','after') } else { @('after','before') }
            for ($order=0;$order -lt 2;$order++) { Run-Engine $labels[$order] $size $initial 11 $budgets."$size-$initial" 'engine' $repeat $order 3 }
        }
    } }
}
if ($Phase -in @('All','Replay')) {
    foreach ($size in @(500,4000)) { foreach ($initial in @('empty','full')) { foreach ($seed in @(11,22,33)) {
        foreach ($label in @('before','after')) { Run-Engine $label $size $initial $seed 10000 'replay' 1 0 0 }
    } } }
}
if ($Phase -in @('All','Application')) {
    foreach ($size in @(500,4000)) { foreach ($polling in @($false,$true)) {
        for ($repeat=1;$repeat -le $ApplicationRepetitions;$repeat++) {
            $labels = if ($repeat % 2) { @('before','after') } else { @('after','before') }
            for ($order=0;$order -lt 2;$order++) { Run-Application $labels[$order] $size $repeat $order $false $polling }
        }
    } }
}
if ($Phase -in @('All','Profiles')) {
    foreach ($label in @('before','after')) {
        Run-Engine $label 4000 'full' 11 $budgets.'4000-full' 'profile' 0 0 3
        Run-Application $label 4000 0 0 $true $false
        Run-Application $label 4000 0 1 $true $true
    }
}
Write-Host "Measurements saved in $results"
if ($Phase -in @('All','Profiles','Analyze')) {
    $v = $versions['before']
    foreach ($profile in (Get-ChildItem -LiteralPath (Join-Path $results 'profiles') -Filter '*.jfr')) {
        Invoke-LoggedNative $javaPath @('--class-path',$v.cp,'org.pixelwar.pixelwar.domain.JfrSummary',$profile.FullName,$profile.FullName.Replace('.jfr','.summary.json')) (Join-Path $results ('logs/export-' + $profile.BaseName + '.log'))
    }
    & $Python (Join-Path $PSScriptRoot 'opti03/analyze.py') $results
    if ($LASTEXITCODE -ne 0) { throw 'Analysis failed; see scripts/opti03/requirements.txt.' }
}
'''
(root / 'scripts/benchmark-opti-03.ps1').write_text(s, encoding='utf-8-sig')
