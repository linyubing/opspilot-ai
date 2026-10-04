param([Parameter(Mandatory=$true)][string]$Receipt)

# 离线核验本次切换日相邻窗口；保留重复源行，不写行情或预测记录。
$bundle = Get-Content -LiteralPath $Receipt -Encoding UTF8 -Raw | ConvertFrom-Json
if ($bundle.Count -ne 2 -or $bundle[0].response.status -ne 'ok' -or $bundle[1].response.status -ne 'ok' -or
    $bundle[0].response.meta.symbol -ne 'XAU/USD' -or $bundle[1].response.meta.symbol -ne 'XAU/USD' -or
    $bundle[0].response.meta.interval -ne '1day' -or $bundle[1].response.meta.interval -ne '1h' -or
    $bundle[1].request.timezone -ne 'UTC') { throw '回执标识或合同不符' }
$checked = [DateTimeOffset]::Parse($bundle[1].completedAt)
$hours = @{}
foreach ($row in $bundle[1].response.values) {
    $time = [DateTimeOffset]::ParseExact($row.datetime + ' +00:00', 'yyyy-MM-dd HH:mm:ss zzz', [Globalization.CultureInfo]::InvariantCulture)
    if ($hours.ContainsKey($time.ToUnixTimeSeconds())) { throw '小时线时间戳重复' }
    foreach ($field in @('open','high','low','close')) {
        if ($row.$field -isnot [string]) { throw '价格不是原始十进制字符串' }
    }
    $hours.Add($time.ToUnixTimeSeconds(), $row)
}

function Test-Window($Day, [DateTimeOffset]$Start, [DateTimeOffset]$End) {
    $result = [ordered]@{start=$Start.ToString('o');endExclusive=$End.ToString('o');officialSessionCertified=$false}
    if ($checked -lt $End) { $result.status='NOT_ENDED'; return [pscustomobject]$result }
    $rows = @()
    for ($time=$Start; $time -lt $End; $time=$time.AddHours(1)) {
        if (!$hours.ContainsKey($time.ToUnixTimeSeconds())) { $result.status='MISSING_HOURS'; return [pscustomobject]$result }
        $rows += $hours[$time.ToUnixTimeSeconds()]
    }
    # 使用decimal逐项比较，避免Measure-Object将极值转换成double。
    [decimal]$high=$rows[0].high
    [decimal]$low=$rows[0].low
    foreach ($row in $rows) {
        if ([decimal]$row.high -gt $high) { $high=[decimal]$row.high }
        if ([decimal]$row.low -lt $low) { $low=[decimal]$row.low }
    }
    $result.hours=$rows.Count
    $result.openMatch=([decimal]$Day.open -eq [decimal]$rows[0].open)
    $result.highMatch=([decimal]$Day.high -eq $high)
    $result.lowMatch=([decimal]$Day.low -eq $low)
    $result.closeMatch=([decimal]$Day.close -eq [decimal]$rows[-1].close)
    $result.status=if ($result.openMatch -and $result.highMatch -and $result.lowMatch -and $result.closeMatch) { 'MATCHED' } else { 'OHLC_MISMATCH' }
    return [pscustomobject]$result
}

# 只复现本次三个标签的候选，不把它推广成通用DST规则。
$starts=@{'2026-10-02'='2026-10-01T21:00:00Z';'2026-10-03'='2026-10-02T21:00:00Z';'2026-10-04'='2026-10-03T20:00:00Z'}
$ends=@{'2026-10-02'='2026-10-02T21:00:00Z';'2026-10-03'='2026-10-03T20:00:00Z';'2026-10-04'='2026-10-04T20:00:00Z'}
$results=@()
$index=0
foreach ($day in $bundle[0].response.values) {
    if (!$starts.ContainsKey($day.datetime)) { throw '本次实验之外的日期' }
    foreach ($field in @('open','high','low','close')) { if ($day.$field -isnot [string]) { throw '日线价格类型错误' } }
    $start=[DateTimeOffset]::Parse($starts[$day.datetime])
    $end=[DateTimeOffset]::Parse($ends[$day.datetime])
    $results += [pscustomobject]@{sourceRow=$index;date=$day.datetime;original=(Test-Window $day $start $end);fixed24FromSameStart=(Test-Window $day $start $start.AddHours(24))}
    $index++
}
$nextStart=[DateTimeOffset]::Parse($starts['2026-10-04'])
$previousEnd=([DateTimeOffset]::Parse($starts['2026-10-03'])).AddHours(24)
[pscustomobject]@{rows=$results;nextOriginalStart=$nextStart.ToString('o');extendedPreviousEnd=$previousEnd.ToString('o');overlapHours=($previousEnd-$nextStart).TotalHours;hypothesisOnly=$true} | ConvertTo-Json -Depth 8
