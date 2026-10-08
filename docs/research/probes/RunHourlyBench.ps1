param(
    [Parameter(Mandatory=$true)][string]$Receipt,
    [Parameter(Mandatory=$true)][string]$Output,
    [string]$Forecast,
    [string]$FredDirectory
)

# 每次都从真实原始回执重新核验，再运行研究回测或结算，不信任可手改的派生文件。
$ErrorActionPreference='Stop'
$projectRoot=Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
$previousLocation=Get-Location
try {
    Set-Location $projectRoot
    if($Forecast -and $FredDirectory) { throw 'Settlement cannot enable retrospective macro experiments' }
    $null=& (Join-Path $PSScriptRoot 'CheckHourlyRoute.ps1') -Receipt $Receipt
    $privateRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot 'backend\target')) + '\'
    $outputPath=[IO.Path]::GetFullPath($Output)
    if (!$outputPath.StartsWith($privateRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'Research output must remain inside backend/target' }
    if (Test-Path -LiteralPath $outputPath) { throw 'Output exists; no retry or overwrite' }
    # 独立编译当前公式，避免旧生产缓存与源码版本不一致；不改生产 classes。
    $probeClasses=Join-Path $privateRoot 'hourly-probe-classes'
    $null=New-Item -ItemType Directory -Path $probeClasses -Force
    $dependencies=(Join-Path $projectRoot 'backend\target\classes')+';'+(Get-Content (Join-Path $privateRoot 'probe-classpath.txt') -Raw).Trim()
    $featureRoot=Join-Path $projectRoot 'backend\src\main\java\com\opspilot\ai\forecast\learning'
    $macroRoot=Join-Path $projectRoot 'backend\src\main\java\com\opspilot\ai\macrodata'
    & javac -encoding UTF-8 -cp $dependencies -d $probeClasses (Join-Path $PSScriptRoot 'HourlyBench.java') (Join-Path $PSScriptRoot 'HourlyBenchChecks.java') (Join-Path $PSScriptRoot 'HourlyRateChecks.java') (Join-Path $featureRoot 'GoldFeatureCalculator.java') (Join-Path $featureRoot 'GoldOhlcFeatures.java') (Join-Path $macroRoot 'FredHistory.java') (Join-Path $macroRoot 'FredHistoryStore.java')
    $cp=$probeClasses+';'+$dependencies
    if ($LASTEXITCODE -ne 0) { throw 'Research probe compilation failed' }
    if ([string]::IsNullOrWhiteSpace($Forecast)) {
        if([string]::IsNullOrWhiteSpace($FredDirectory)) {
            & java '-Dfile.encoding=UTF-8' -cp $cp com.opspilot.ai.forecast.learning.HourlyBench $Receipt $outputPath
        } else {
            & java '-Dfile.encoding=UTF-8' -cp $cp com.opspilot.ai.forecast.learning.HourlyBench $Receipt $outputPath $FredDirectory
        }
    } else {
        & java '-Dfile.encoding=UTF-8' -cp $cp com.opspilot.ai.forecast.learning.HourlyBench --settle $Forecast $Receipt $outputPath
    }
    if ($LASTEXITCODE -ne 0) { throw 'Research probe failed; output is not a verified result' }
} finally { Set-Location $previousLocation }
