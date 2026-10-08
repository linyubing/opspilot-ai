param([Parameter(Mandatory=$true)][string]$Receipt)

# 使用真实回执做离线故障注入；不生成行情样本，不写生产数据库。
$ErrorActionPreference='Stop'
$probe=Join-Path $PSScriptRoot 'CheckHourlyRoute.ps1'
$root=Split-Path -Parent $Receipt
$passed=0
function Save-Variant($Name,$Change) {
    $copy=Get-Content -LiteralPath $Receipt -Encoding UTF8 -Raw | ConvertFrom-Json
    $source=$copy.rawResponse | ConvertFrom-Json
    & $Change $copy $source
    $copy.rawResponse=$source | ConvertTo-Json -Depth 12
    $path=Join-Path $root ('hourly-route-fault-'+$Name+'.json')
    [IO.File]::WriteAllText($path,($copy | ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
    return $path
}
function Expect-Rejection($Path,$Message) {
    $rejected=$false
    try { $null=& $probe -Receipt $Path }
    catch { if ($_.Exception.Message -ne $Message) { throw }; $rejected=$true }
    if (!$rejected) { throw 'Expected validation rejection' }
}
$null=& $probe -Receipt $Receipt
$valid=Get-Content -LiteralPath ($Receipt+'.check.json') -Encoding UTF8 -Raw | ConvertFrom-Json
if (!$valid.ready -or $valid.consecutiveCompleteWindows -lt 21 -or $valid.productionEligible -or $valid.officialSessionCertified) { throw 'Valid receipt check failed' }
$passed++
foreach ($window in $valid.windows) {
    if ([DateTimeOffset]::Parse($window.endExclusive) -gt [DateTimeOffset]::Parse($valid.checkedAt)) { throw 'Unfinished window admitted' }
    if (([DateTimeOffset]::Parse($window.endExclusive)-[DateTimeOffset]::Parse($window.start)).TotalHours -ne 24) { throw 'Invalid fixed duration' }
}
for ($i=1;$i -lt $valid.windows.Count;$i++) {
    if ($valid.windows[$i].endExclusive -ne $valid.windows[$i-1].start) { throw 'Overlapping or discontinuous windows' }
}
$passed++
$path=Save-Variant 'duplicate' {param($b,$s) $s.values=@($s.values)+@($s.values[0])}
Expect-Rejection $path 'Duplicate or unaligned hourly timestamp'
$passed++
$path=Save-Variant 'negative' {param($b,$s) $s.values[0].close='-1'}
Expect-Rejection $path 'Invalid decimal price'
$passed++
$path=Save-Variant 'identity' {param($b,$s) $s.meta.symbol='EUR/USD'}
Expect-Rejection $path 'Hourly response identity not verified'
$passed++
$path=Save-Variant 'collection-time' {param($b,$s) $b.completedAt=[DateTimeOffset]::UtcNow.AddDays(1).ToString('o')}
Expect-Rejection $path 'Invalid collection time'
$passed++
$path=Save-Variant 'future-hour' {param($b,$s) $s.values[0].datetime=[DateTimeOffset]::UtcNow.AddDays(1).ToString('yyyy-MM-dd HH:00:00')}
Expect-Rejection $path 'Future hourly timestamp'
$passed++
$missingTime=([DateTimeOffset]::Parse($valid.latestClosedEnd)).AddHours(-1).ToString('yyyy-MM-dd HH:mm:ss')
$path=Save-Variant 'missing' {param($b,$s) $s.values=@($s.values | Where-Object {$_.datetime -ne $missingTime})}
$null=& $probe -Receipt $path
$bad=Get-Content -LiteralPath ($path+'.check.json') -Encoding UTF8 -Raw | ConvertFrom-Json
if ($bad.ready -or $bad.consecutiveCompleteWindows -ne 0 -or $bad.windows[0].missing.Count -ne 1) { throw 'Missing latest hour not rejected' }
$passed++
[pscustomobject]@{tests=$passed;failures=0;usesRealReceipt=$true;productionWrites=0} | ConvertTo-Json
