# 将缓存重建值逐条与 FRED 单日版本接口比较，不依赖 Java 选择器自身的断言。
$ErrorActionPreference = 'Stop'
$key = [Environment]::GetEnvironmentVariable('FRED_API_KEY')
if ([string]::IsNullOrWhiteSpace($key)) { throw 'FRED_API_KEY 缺失' }
$checks = @()
foreach ($series in @('DFII10', 'DTWEXBGS')) {
    $file = Join-Path $PSScriptRoot ("../../../backend/target/fred-history/$series.json")
    $cache = Get-Content $file -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($day in @('2019-02-05','2022-11-01','2022-11-02','2024-11-02','2024-11-05')) {
        $cutoff = [DateTime]::ParseExact($day, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture).AddDays(-1).ToString('yyyy-MM-dd')
        $local = @($cache.observations | Where-Object { $_.date -le $cutoff -and $_.realtime_start -le $cutoff -and $_.realtime_end -ge $cutoff -and $_.value -ne '.' } | Sort-Object date -Descending | Select-Object -First 21)
        $uri = 'https://api.stlouisfed.org/fred/series/observations?series_id=' + $series + '&api_key=' + [Uri]::EscapeDataString($key) + '&file_type=json&observation_start=2007-11-01&observation_end=' + $cutoff + '&realtime_start=' + $cutoff + '&realtime_end=' + $cutoff + '&sort_order=desc&limit=120'
        try { $response = Invoke-RestMethod -Uri $uri -TimeoutSec 45 } catch { throw "单日版本核验请求失败：$series/$day" }
        $remote = @($response.observations | Where-Object value -ne '.' | Select-Object -First 21)
        if ($local.Count -ne 21 -or $remote.Count -ne 21) { throw '时点核验缺少21个有效观测' }
        for ($i=0; $i -lt 21; $i++) {
            if ($local[$i].date -ne $remote[$i].date -or [decimal]$local[$i].value -ne [decimal]$remote[$i].value) { throw "历史版本逐项比较失败：$series/$day" }
        }
        $checks += [pscustomobject]@{series=$series; asOf=$day; cutoff=$cutoff; matched=21;
            latestDate=$local[0].date; latestValue=$local[0].value; sourceSha256=(Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
}
$path = Join-Path $PSScriptRoot '../2026-09-30-vintage-verification.json'
if (Test-Path $path) { throw '不覆盖已有核验记录' }
[IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $checks -Depth 6), [Text.UTF8Encoding]::new($false))
$checks | Select-Object series,asOf,cutoff,matched,latestDate,latestValue | Format-Table
