# 下载可复现的历史版本批次；不覆盖旧批次，不修改实时数据库。
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$EndDate,
    [string]$StartDate = '2007-11-01',
    [Parameter(Mandatory = $true)][string]$OutputDir
)
$ErrorActionPreference = 'Stop'
$format = [Globalization.CultureInfo]::InvariantCulture
$start = [DateTime]::ParseExact($StartDate, 'yyyy-MM-dd', $format)
$end = [DateTime]::ParseExact($EndDate, 'yyyy-MM-dd', $format)
if ($end -lt $start -or $end -gt [DateTime]::UtcNow.Date) { throw '日期范围无效，不能请求未来版本' }
$target = [IO.Path]::GetFullPath($OutputDir)
if (Test-Path -LiteralPath $target) { throw '输出目录已存在；每次采集必须使用新的批次目录' }
$key = [Environment]::GetEnvironmentVariable('FRED_API_KEY')
if ([string]::IsNullOrWhiteSpace($key)) { throw '请设置 FRED_API_KEY 环境变量' }
$parent = [IO.Path]::GetDirectoryName($target)
[IO.Directory]::CreateDirectory($parent) | Out-Null
$pending = Join-Path $parent ('.pending-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($pending) | Out-Null

# 不输出带密钥的 URL 或异常正文；失败批次保留在 pending 目录便于诊断。
function Read-Fred([string]$Path, [hashtable]$Query) {
    $Query['api_key'] = $key
    $Query['file_type'] = 'json'
    $pairs = $Query.GetEnumerator() | ForEach-Object {
        [Uri]::EscapeDataString($_.Key) + '=' + [Uri]::EscapeDataString([string]$_.Value)
    }
    try { return Invoke-RestMethod -Uri ('https://api.stlouisfed.org/fred/' + $Path + '?' + ($pairs -join '&')) -TimeoutSec 45 }
    catch { throw 'FRED 历史版本请求失败；本批未发布，可检查网络和 API 额度后重试新目录' }
}

foreach ($series in @('DFII10', 'DTWEXBGS')) {
    $first = Read-Fred 'series/vintagedates' @{
        series_id=$series; sort_order='asc'; limit=1; realtime_end=$EndDate
    }
    if (@($first.vintage_dates).Count -ne 1) { throw '该日期之前没有可验证的历史版本' }
    $firstDate = [DateTime]::ParseExact($first.vintage_dates[0], 'yyyy-MM-dd', $format)
    $from = if ($firstDate -gt $start) { $firstDate } else { $start }
    $rows = [Collections.Generic.List[object]]::new()
    $chunks = @()
    # 五个自然年的日数小于 JSON 接口 2000 个版本日上限。
    while ($from -le $end) {
        $to = $from.AddYears(5).AddDays(-1)
        if ($to -gt $end) { $to = $end }
        $offset = 0
        $total = $null
        do {
            $reply = Read-Fred 'series/observations' @{
                series_id=$series; observation_start=$StartDate; observation_end=$EndDate
                realtime_start=$from.ToString('yyyy-MM-dd'); realtime_end=$to.ToString('yyyy-MM-dd')
                output_type=1; limit=100000; offset=$offset
            }
            if ($null -eq $reply.count -or $null -eq $reply.observations -or [int]$reply.offset -ne $offset) {
                throw '历史版本分页字段无效'
            }
            if ($null -ne $total -and $total -ne [int]$reply.count) { throw '采集途中分页总数改变，拒绝混合版本' }
            $total = [int]$reply.count
            $page = @($reply.observations)
            if ($page.Count -eq 0 -and $offset -lt $total) { throw '历史版本分页缺失' }
            foreach ($row in $page) { $rows.Add($row) }
            $offset += $page.Count
        } while ($offset -lt $total)
        if ($offset -ne $total) { throw '历史版本分页总数不符' }
        $chunks += [pscustomobject]@{start=$from.ToString('yyyy-MM-dd'); end=$to.ToString('yyyy-MM-dd'); count=$offset}
        $from = $to.AddDays(1)
    }
    $archive = [ordered]@{
        series=$series; fetchedAt=[DateTimeOffset]::UtcNow.ToString('o'); outputType=1
        observationStart=$StartDate; observationEnd=$EndDate; realtimeStart=$StartDate; realtimeEnd=$EndDate
        firstAvailableVintage=$firstDate.ToString('yyyy-MM-dd'); count=$rows.Count
        chunks=$chunks; observations=$rows.ToArray()
    }
    # 此处是下载数据的序列化，不是改写源代码；完整两文件成功后才发布目录。
    [IO.File]::WriteAllText((Join-Path $pending ($series + '.json')),
        (ConvertTo-Json -InputObject $archive -Depth 8 -Compress), [Text.UTF8Encoding]::new($false))
    Write-Output "$series 已采集 $($rows.Count) 条版本区间"
}
if (Test-Path -LiteralPath $target) { throw '输出目录被其他任务创建，拒绝覆盖' }
# 两路径均为同一父目录下显式解析的绝对路径；只移动本次唯一 pending 目录。
[IO.Directory]::Move($pending, $target)
Write-Output '批次采集完成。将 FRED_HISTORY_DIR 指向该目录后启动应用；不要修改已用于实验的批次。'
Write-Output $target
