[CmdletBinding()]
param(
    [int[]]$RenderDistances = @(16, 24, 32),
    [ValidateRange(1, 20)]
    [int]$Repeats = 3,
    [ValidateRange(0, 300)]
    [int]$WarmupSeconds = 5,
    [ValidateRange(1, 600)]
    [int]$LoadingTimeoutSeconds = 60,
    [ValidateRange(1, 600)]
    [int]$TraversalSeconds = 30,
    [ValidateRange(0, 300)]
    [int]$SettleSeconds = 10,
    [string]$OutputDirectory = "target/profiling/matrix",
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repositoryRoot
try {
    if (-not $SkipBuild) {
        & .\mvnw.cmd package
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed with exit code $LASTEXITCODE"
        }
    }

    $jarPath = Join-Path $repositoryRoot "target/voxy-0.0.1.jar"
    if (-not (Test-Path -LiteralPath $jarPath)) {
        throw "Benchmark jar not found: $jarPath"
    }

    $resolvedOutput = Join-Path $repositoryRoot $OutputDirectory
    New-Item -ItemType Directory -Force -Path $resolvedOutput | Out-Null
    $runs = [System.Collections.Generic.List[object]]::new()

    foreach ($distance in $RenderDistances) {
        foreach ($sparseEnabled in @($true, $false)) {
            $streamingName = if ($sparseEnabled) { "sparse" } else { "legacy" }
            for ($repeat = 1; $repeat -le $Repeats; $repeat++) {
                $runName = "rd{0}-{1}-run{2}" -f $distance, $streamingName, $repeat
                $runDirectory = Join-Path $resolvedOutput $runName
                New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null

                $arguments = @(
                    "-jar", $jarPath,
                    "--benchmark",
                    "--benchmark-render-distance=$distance",
                    "--benchmark-warmup=$WarmupSeconds",
                    "--benchmark-loading-timeout=$LoadingTimeoutSeconds",
                    "--benchmark-duration=$TraversalSeconds",
                    "--benchmark-settle=$SettleSeconds",
                    "--jfr-output=$(Join-Path $runDirectory 'profile.jfr')",
                    "--runtime-output=$(Join-Path $runDirectory 'runtime-profile.csv')",
                    "--runtime-summary-output=$(Join-Path $runDirectory 'runtime-summary.json')"
                )
                if (-not $sparseEnabled) {
                    $arguments += "--disable-sparse-streaming"
                }

                Write-Host "Running $runName"
                $startedAt = Get-Date
                & java @arguments
                $exitCode = $LASTEXITCODE
                $runs.Add([pscustomobject]@{
                    run = $runName
                    render_distance = $distance
                    sparse_streaming = $sparseEnabled
                    repeat = $repeat
                    started_at = $startedAt.ToString("o")
                    elapsed_seconds = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 3)
                    exit_code = $exitCode
                    summary = Join-Path $runDirectory "runtime-summary.json"
                })
                $runs | Export-Csv -NoTypeInformation -Encoding utf8 -Path (Join-Path $resolvedOutput "matrix-runs.csv")
                if ($exitCode -ne 0) {
                    throw "Benchmark $runName failed with exit code $exitCode"
                }
            }
        }
    }
} finally {
    Pop-Location
}
