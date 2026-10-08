param(
    [Parameter(Mandatory=$true)][string]$Receipt,
    [Parameter(Mandatory=$true)][string]$FredDirectory
)
# 真实回执集成回归：利率模式缺失、换人群、泄漏日期或特征错误均应失败。
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
$taskDir=Join-Path $root ('backend\target\hourly-rate-check-'+[Guid]::NewGuid())
$null=New-Item -ItemType Directory -Path $taskDir
$output=Join-Path $taskDir 'rate.json'
& (Join-Path $PSScriptRoot 'RunHourlyBench.ps1') -Receipt $Receipt -Output $output -FredDirectory $FredDirectory
$result=Get-Content $output -Raw -Encoding UTF8 | ConvertFrom-Json
if($result.developmentSamples -ne 135 -or $result.cases.Count -ne 74) { throw 'Comparison changed the frozen sample cohort' }
if($result.metrics.LOGISTIC_7.samples -ne 74) { throw 'Missing paired seven-feature predictions' }
if($result.rateInputs.Count -ne 135) { throw 'Missing real-rate input lineage' }
$raw=Get-Content (Join-Path $FredDirectory 'DFII10.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach($inputRow in $result.rateInputs) {
    $cutoff=([datetime]$inputRow.baseDate).AddDays(-1).ToString('yyyy-MM-dd')
    # 从原始版本区间独立选择；不调用被测 Java 的利率特征方法。
    $rows=@($raw.observations | Where-Object { $_.date -le $cutoff -and $_.realtime_start -le $cutoff -and $_.realtime_end -ge $cutoff -and $_.value -ne '.' } | Sort-Object date -Descending | Select-Object -First 6)
    if($rows.Count -ne 6 -or $inputRow.latestDate -ne $rows[0].date) { throw 'Historical date selection differs from original archive' }
    if([Math]::Abs($inputRow.level-[double]$rows[0].value) -gt 1e-12) { throw 'Incorrect real-rate level' }
    if([Math]::Abs($inputRow.change5-([double]$rows[0].value-[double]$rows[5].value)) -gt 1e-12) { throw 'Incorrect five-observation rate change' }
}
$old=Get-Content (Join-Path $root 'backend\target\2026-10-08-hourly-bench-provenance.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if(($result.metrics.LOGISTIC_5 | ConvertTo-Json -Depth 6 -Compress) -ne ($old.metrics.LOGISTIC_5 | ConvertTo-Json -Depth 6 -Compress)) { throw 'Price-only control changed' }
# JSON对象字段顺序不属于训练契约，逐折比较实际字段，避免Map序列化顺序造成误报。
if($result.folds.Count -ne $old.folds.Count) { throw 'Training fold count changed' }
for($i=0;$i -lt $result.folds.Count;$i++) {
    foreach($field in @('trainingSamples','purgedSamples','validationBase','latestTrainingTarget')) {
        if($result.folds[$i].$field -ne $old.folds[$i].$field) { throw 'Training fold values changed' }
    }
}
[pscustomobject]@{checks=7; realRateInputs=135; pairedSamples=74; failures=0} | ConvertTo-Json
