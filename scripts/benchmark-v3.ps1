param(
    [string]$Java = 'java',
    [int]$BoardSize = 100
)
$ErrorActionPreference = 'Stop'
if ($BoardSize -lt 3) { throw 'BoardSize must be >= 3.' }
$workspace = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location -LiteralPath $workspace
try {
    & ./mvnw.cmd -B -ntp test-compile dependency:build-classpath '-Dmdep.outputFile=target/v3-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Maven preparation failed.' }
    $classpath = 'target/classes;' + (Get-Content -LiteralPath 'target/v3-classpath.txt' -Raw).Trim()
    $javaPath = (Get-Command $Java -ErrorAction Stop).Source
    $compiler = Join-Path (Split-Path -Parent $javaPath) 'javac.exe'
    New-Item -ItemType Directory -Force -Path 'target/v3-benchmark-classes' | Out-Null
    & $compiler --release 21 --class-path $classpath -d target/v3-benchmark-classes scripts/FrontierBenchmark.java
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark compilation failed.' }
    & $Java -Xmx1g --class-path ('target/v3-benchmark-classes;' + $classpath) org.pixelwar.pixelwar.domain.FrontierBenchmark $BoardSize
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark failed.' }
} finally { Pop-Location }
