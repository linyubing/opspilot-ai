# 一次性只读审计：比较同一观测日期在不同历史版本日是否已可见，不输出 API Key。
$ErrorActionPreference = 'Stop'
$key = [Environment]::GetEnvironmentVariable('FRED_API_KEY')
if ([string]::IsNullOrWhiteSpace($key)) { throw 'FRED_API_KEY 环境变量缺失' }
$path = Join-Path $PSScriptRoot '../2026-09-29-fred-vintage.json'
if (Test-Path -LiteralPath $path) { throw '不覆盖已有审计结果' }
$results = @()
foreach ($series in @('DFII10', 'DTWEXBGS')) {
    foreach ($vintage in @('2024-11-01', '2024-11-08')) {
        $uri = 'https://api.stlouisfed.org/fred/series/observations?series_id=' + $series + '&api_key=' + [Uri]::EscapeDataString($key) + '&file_type=json&observation_start=2024-10-28&observation_end=2024-11-01&realtime_start=' + $vintage + '&realtime_end=' + $vintage
        try {
            $response = Invoke-RestMethod -Uri $uri -TimeoutSec 45
            $results += [pscustomobject]@{
                series = $series; vintage = $vintage; count = $response.count
                observations = @($response.observations | Select-Object date, value, realtime_start, realtime_end)
            }
        } catch {
            # 不直接输出请求异常，异常中可能包含带凭据的 URL。
            throw "历史版本查询失败：$series / $vintage；停止审计，不生成替代数据"
        }
    }
}
[IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $results -Depth 8), [Text.UTF8Encoding]::new($false))
$results | Select-Object series, vintage, count | Format-Table
