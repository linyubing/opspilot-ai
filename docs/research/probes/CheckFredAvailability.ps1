param([ValidateSet('DFII10','DTWEXBGS')][string]$Series = 'DFII10')

# 只诊断来源，不连接数据库、不写行情；密钥仅从环境读取，禁止传到命令行。
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$fredKey = [Environment]::GetEnvironmentVariable('FRED_API_KEY', 'Process')
if ([string]::IsNullOrWhiteSpace($fredKey)) {
    $fredKey = [Environment]::GetEnvironmentVariable('FRED_API_KEY', 'User')
}
if ([string]::IsNullOrWhiteSpace($fredKey)) { throw '未配置 FRED_API_KEY' }
$handler = [System.Net.Http.HttpClientHandler]::new()
# 不把含密钥的请求跟随到其他地址。
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(20)
$started = [DateTimeOffset]::UtcNow
try {
    $address = 'https://api.stlouisfed.org/fred/series/observations?series_id=' + $Series +
        '&file_type=json&limit=1&sort_order=desc&api_key=' + [Uri]::EscapeDataString($fredKey)
    try {
        $response = $client.GetAsync($address).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $data = $null
        try { $data = $body | ConvertFrom-Json } catch { }
        $date = [DateTime]::MinValue
        $number = [decimal]0
        $available = $response.IsSuccessStatusCode -and $null -ne $data.observations -and @($data.observations).Count -gt 0
        if ($available) {
            $available = [DateTime]::TryParseExact([string]$data.observations[0].date, 'yyyy-MM-dd',
                [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$date) -and
                [decimal]::TryParse([string]$data.observations[0].value, [Globalization.NumberStyles]::Float,
                    [Globalization.CultureInfo]::InvariantCulture, [ref]$number)
        }
        $result = [ordered]@{series=$Series; startedAt=$started.ToString('o'); checkedAt=[DateTimeOffset]::UtcNow.ToString('o');
            status=[int]$response.StatusCode; httpSuccess=$response.IsSuccessStatusCode;
            contentType=[string]$response.Content.Headers.ContentType; jsonParsed=$null -ne $data;
            dataAvailable=[bool]$available}
        $titleMatch = [regex]::Match($body, '(?is)<title[^>]*>(.*?)</title>')
        if ($titleMatch.Success) {
            # 仅留脱敏标题，不保存正文、原始URL或供应商错误原文。
            $title = [System.Net.WebUtility]::HtmlDecode($titleMatch.Groups[1].Value)
            $title = $title -replace [regex]::Escape($fredKey), '[redacted]'
            $title = $title -replace 'https?://\S+', '[url]'
            $result['htmlTitle'] = $title.Substring(0, [Math]::Min(120, $title.Length))
        }
        if ($available) {
            $result['latestDate'] = $data.observations[0].date
            $result['latestValue'] = $data.observations[0].value
        }
        [pscustomobject]$result | ConvertTo-Json
    } catch {
        # 异常Message和堆栈可能包含查询参数，因此只输出异常类型。
        $types = @()
        $item = $_.Exception
        while ($null -ne $item) { $types += $item.GetType().FullName; $item = $item.InnerException }
        [pscustomobject]@{series=$Series; startedAt=$started.ToString('o'); checkedAt=[DateTimeOffset]::UtcNow.ToString('o');
            dataAvailable=$false; exceptionTypes=$types} | ConvertTo-Json
    }
} finally {
    $client.Dispose()
    $fredKey = $null
    $address = $null
}
