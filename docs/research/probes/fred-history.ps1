# 研究用只读采集：保留 FRED 的历史版本有效区间，不覆盖产品数据库。
$ErrorActionPreference = 'Stop'
$key = [Environment]::GetEnvironmentVariable('FRED_API_KEY')
if ([string]::IsNullOrWhiteSpace($key)) { throw 'FRED_API_KEY 环境变量缺失' }
$out = Join-Path $PSScriptRoot '../../../backend/target/fred-history'
[IO.Directory]::CreateDirectory($out) | Out-Null
$summary = @()
foreach ($series in @('DFII10', 'DTWEXBGS')) {
    $file = Join-Path $out ($series + '.json')
    if (Test-Path -LiteralPath $file) {
        $cached = Get-Content -LiteralPath $file -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($cached.series -ne $series -or $cached.observationEnd -ne '2024-11-08' -or $cached.count -ne $cached.observations.Count) { throw '缓存范围或条数不一致' }
        $summary += [pscustomobject]@{series=$series; count=$cached.count; cached=$true; sha256=(Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant()}
        continue
    }
    $dateUri = 'https://api.stlouisfed.org/fred/series/vintagedates?series_id=' + $series + '&api_key=' + [Uri]::EscapeDataString($key) + '&file_type=json&sort_order=asc&limit=1&realtime_end=2024-11-08'
    try { $dates = Invoke-RestMethod -Uri $dateUri -TimeoutSec 45 } catch { throw "版本日期查询失败：$series" }
    if ($dates.vintage_dates.Count -ne 1) { throw "没有可验证的版本日期：$series" }
    $firstVintage = $dates.vintage_dates[0]
    $rows = [Collections.Generic.List[object]]::new()
    $chunks = @()
    # JSON 每次最多 2000 个版本日；按不重叠的五年区间分批，保留 API 返回的裁剪区间。
    foreach ($range in @(@('2007-11-01','2012-10-31'), @('2012-11-01','2017-10-31'), @('2017-11-01','2022-10-31'), @('2022-11-01','2024-11-08'))) {
      if ($range[1] -lt $firstVintage) { continue }
      if ($range[0] -lt $firstVintage) { $range[0] = $firstVintage }
      $offset = 0
      do {
        $uri = 'https://api.stlouisfed.org/fred/series/observations?series_id=' + $series + '&api_key=' + [Uri]::EscapeDataString($key) + '&file_type=json&observation_start=2007-11-01&observation_end=2024-11-08&realtime_start=' + $range[0] + '&realtime_end=' + $range[1] + '&output_type=1&limit=100000&offset=' + $offset
        try { $response = Invoke-RestMethod -Uri $uri -TimeoutSec 45 } catch {
            $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 'network' }
            $detail = if ($_.ErrorDetails.Message) { $_.ErrorDetails.Message.Replace($key, '[redacted]') } else { '无响应正文' }
            throw "FRED 历史版本下载失败：$series；HTTP=$status；$detail"
        }
        if ($null -eq $response.count -or $null -eq $response.observations) { throw '响应缺少版本数据' }
        foreach ($row in $response.observations) { $rows.Add($row) }
        if ($response.observations.Count -eq 0 -and $offset -lt $response.count) { throw '分页返回空结果' }
        $offset += $response.observations.Count
      } while ($offset -lt $response.count)
      if ($offset -ne $response.count) { throw '历史版本分页总数不一致' }
      $chunks += [pscustomobject]@{start=$range[0]; end=$range[1]; count=$offset}
    }
    $archive = [ordered]@{series=$series; fetchedAt=[DateTimeOffset]::UtcNow.ToString('o');
        observationStart='2007-11-01'; observationEnd='2024-11-08';
        realtimeStart='2007-11-01'; realtimeEnd='2024-11-08'; outputType=1;
        firstAvailableVintage=$firstVintage; count=$rows.Count; chunks=$chunks; observations=$rows.ToArray()}
    [IO.File]::WriteAllText($file, (ConvertTo-Json -InputObject $archive -Depth 8 -Compress), [Text.UTF8Encoding]::new($false))
    $first = $rows | Sort-Object realtime_start | Select-Object -First 1
    $summary += [pscustomobject]@{series=$series; count=$rows.Count; firstVintage=$first.realtime_start;
        firstDate=$first.date; sha256=(Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant(); bytes=(Get-Item $file).Length}
}
$summary | ConvertTo-Json
