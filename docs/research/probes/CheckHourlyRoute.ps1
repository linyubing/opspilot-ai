param(
    [string]$Receipt = 'D:\workFile\demo-ai\backend\target\hourly-route-receipt.json',
    [switch]$Fetch
)

# 真实小时线可行性探针：固定UTC 21点切分，不冒充供应商日线或交易所收盘价。
$ErrorActionPreference = 'Stop'
$culture = [Globalization.CultureInfo]::InvariantCulture
if ($Fetch) {
    if (Test-Path -LiteralPath $Receipt) { throw 'Receipt already exists; refusing overwrite' }
    $key = [Environment]::GetEnvironmentVariable('TWELVE_DATA_API_KEY', 'Process')
    if ([string]::IsNullOrWhiteSpace($key)) { $key = [Environment]::GetEnvironmentVariable('TWELVE_DATA_API_KEY', 'User') }
    if ([string]::IsNullOrWhiteSpace($key)) { throw 'TWELVE_DATA_API_KEY missing' }
    $started = [DateTimeOffset]::UtcNow
    $uri = 'https://api.twelvedata.com/time_series?symbol=XAU%2FUSD&interval=1h&timezone=UTC&outputsize=1000&apikey=' + [Uri]::EscapeDataString($key)
    try { $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 60 }
    catch { throw 'Hourly request failed; sensitive request URL omitted' }
    $bundle = [ordered]@{ startedAt=$started.ToString('o'); completedAt=[DateTimeOffset]::UtcNow.ToString('o'); httpStatus=[int]$response.StatusCode; request=@{symbol='XAU/USD';interval='1h';timezone='UTC';outputsize=1000}; rawResponse=$response.Content }
    # 回执是运行生成的数据，存到被Git忽略的target，不公开原始价格或密钥。
    [IO.File]::WriteAllText($Receipt, ($bundle | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
}
$bundle = Get-Content -LiteralPath $Receipt -Encoding UTF8 -Raw | ConvertFrom-Json
$source = $bundle.rawResponse | ConvertFrom-Json
# 贵金属回执使用currency_quote，未回显timezone；UTC依据显式请求，不伪造响应元数据。
if ($bundle.httpStatus -ne 200 -or $bundle.request.symbol -ne 'XAU/USD' -or $bundle.request.interval -ne '1h' -or $source.status -ne 'ok' -or $source.meta.symbol -ne 'XAU/USD' -or $source.meta.interval -ne '1h' -or $source.meta.currency_quote -ne 'US Dollar' -or $source.meta.type -ne 'Precious Metal' -or $bundle.request.timezone -ne 'UTC') { throw 'Hourly response identity not verified' }
$checked = [DateTimeOffset]::Parse($bundle.completedAt, $culture)
if ([DateTimeOffset]::Parse($bundle.startedAt,$culture) -gt $checked -or $checked -gt [DateTimeOffset]::UtcNow) { throw 'Invalid collection time' }
$hours = @{}
foreach ($row in $source.values) {
    $time = [DateTimeOffset]::ParseExact($row.datetime + ' +00:00','yyyy-MM-dd HH:mm:ss zzz',$culture)
    if ($time -gt $checked) { throw 'Future hourly timestamp' }
    if ($time.Minute -ne 0 -or $time.Second -ne 0 -or $hours.ContainsKey($time.ToUnixTimeSeconds())) { throw 'Duplicate or unaligned hourly timestamp' }
    foreach ($field in @('open','high','low','close')) {
        if ($row.$field -isnot [string] -or [decimal]::Parse($row.$field,$culture) -le 0) { throw 'Invalid decimal price' }
    }
    [decimal]$high=[decimal]::Parse($row.high,$culture)
    [decimal]$low=[decimal]::Parse($row.low,$culture)
    if ($high -lt $low -or $high -lt [decimal]$row.open -or $high -lt [decimal]$row.close -or $low -gt [decimal]$row.open -or $low -gt [decimal]$row.close) { throw 'Invalid OHLC order' }
    $hours.Add($time.ToUnixTimeSeconds(),$row)
}
$end = [DateTimeOffset]::new($checked.Year,$checked.Month,$checked.Day,21,0,0,[TimeSpan]::Zero)
if ($end -gt $checked) { $end=$end.AddDays(-1) }
$windows=@()
$bars=@()
$consecutive=0
$counting=$true
for ($i=0; $i -lt 35; $i++) {
    $finish=$end.AddDays(-$i)
    $start=$finish.AddDays(-1)
    $rows=@()
    $missing=@()
    for ($t=$start; $t -lt $finish; $t=$t.AddHours(1)) {
        if ($hours.ContainsKey($t.ToUnixTimeSeconds())) { $rows += $hours[$t.ToUnixTimeSeconds()] }
        else { $missing += $t.ToString('o') }
    }
    $windows += [pscustomobject]@{start=$start.ToString('o');endExclusive=$finish.ToString('o');hours=$rows.Count;missing=$missing}
    if ($missing.Count -gt 0) { $counting=$false; continue }
    if ($counting) { $consecutive++ }
    [decimal]$high=[decimal]::Parse($rows[0].high,$culture)
    [decimal]$low=[decimal]::Parse($rows[0].low,$culture)
    foreach ($row in $rows) {
        [decimal]$h=[decimal]::Parse($row.high,$culture); [decimal]$l=[decimal]::Parse($row.low,$culture)
        if ($h -gt $high) { $high=$h }; if ($l -lt $low) { $low=$l }
    }
    $bars += [pscustomobject]@{start=$start.ToString('o');endExclusive=$finish.ToString('o');open=$rows[0].open;high=$high.ToString($culture);low=$low.ToString($culture);close=$rows[-1].close}
}
$result=[ordered]@{basis='TWELVE_HOURLY_UTC21_RESEARCH_V1';checkedAt=$checked.ToString('o');sourceRows=$hours.Count;latestClosedEnd=$end.ToString('o');consecutiveCompleteWindows=$consecutive;requiredWindows=21;ready=($consecutive -ge 21);officialSessionCertified=$false;productionEligible=$false;windows=$windows;bars=$bars;receiptSha256=(Get-FileHash -LiteralPath $Receipt -Algorithm SHA256).Hash.ToLowerInvariant()}
[IO.File]::WriteAllText($Receipt + '.check.json', ($result | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
# 标准输出只展示元数据，不暴露原始行情。
[pscustomobject]@{basis=$result.basis;sourceRows=$result.sourceRows;latestClosedEnd=$result.latestClosedEnd;consecutiveCompleteWindows=$consecutive;ready=$result.ready;missingWindows=@($windows | Where-Object {$_.missing.Count -gt 0}).Count;receiptSha256=$result.receiptSha256} | ConvertTo-Json
