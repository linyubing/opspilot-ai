param(
    [Parameter(Mandatory=$true)][string]$Receipt,
    [Parameter(Mandatory=$true)][string]$Output,
    [switch]$InSession
)

# 独立小时聚合研究预测：不修改正式日线、正式模型或旧回测成绩。
$ErrorActionPreference='Stop'
$culture=[Globalization.CultureInfo]::InvariantCulture
if (Test-Path -LiteralPath $Output) { throw 'Forecast receipt exists; no retry or overwrite permitted' }
$null=& (Join-Path $PSScriptRoot 'CheckHourlyRoute.ps1') -Receipt $Receipt
$checked=Get-Content -LiteralPath ($Receipt+'.check.json') -Encoding UTF8 -Raw | ConvertFrom-Json
if (!$checked.ready) { throw 'Fewer than 21 consecutive complete windows' }
$start=[DateTimeOffset]::Parse($checked.latestClosedEnd)
$end=$start.AddDays(1)
$now=[DateTimeOffset]::UtcNow
if ($now -ge $end) { throw 'Target window already ended' }
if ($now -ge $start -and !$InSession) { throw 'Target window started; explicit InSession required' }
$tag='twelve-hourly-qwen-direction-research-v2'
$model='qwen3.5:9b'
$digest='6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7'
$tags=Invoke-RestMethod -Uri 'http://127.0.0.1:11435/api/tags' -TimeoutSec 10
if (@($tags.models | Where-Object {$_.name -eq $model -and $_.digest -eq $digest}).Count -ne 1) { throw 'Local model identity mismatch' }
$bars=@($checked.bars | Sort-Object endExclusive -Descending | Select-Object -First 21)
function Price($Index) { return [decimal]::Parse($bars[$Index].close,$culture) }
$close=Price 0
$facts=[ordered]@{}
foreach ($n in @(1,5,20)) { $base=Price $n; $facts['gold'+$n]=[decimal]::Round(($close-$base)/$base*100,8) }
$sum=0d
for ($i=0;$i -lt 20;$i++) { $sum+=Price $i }
$facts['ma20Distance']=[decimal]::Round(($close/($sum/20)-1)*100,8)
$facts['range1']=[decimal]::Round(([decimal]$bars[0].high-[decimal]$bars[0].low)/(Price 1)*100,8)
$factsInput=@()
foreach ($key in $facts.Keys) {
    $trend=if ($facts[$key] -gt 0) {'UP'} elseif ($facts[$key] -lt 0) {'DOWN'} else {'FLAT'}
    $factsInput += [pscustomobject]@{key=$key;value=$facts[$key];trend=$trend}
}
$factsJson=$factsInput | ConvertTo-Json -Compress
# 完整21根原始聚合窗口一并保留；模型只接收明确的五项事实，不伪造宏观因子。
$prompt=@"
你是黄金方向研究助手。仅根据以下真实小时聚合价格事实，预测指定窗口末收盘相对基准收盘的方向。
数据口径：Twelve Data XAU/USD小时线，固定UTC21点到次日21点，不是伦敦金官方日线或可执行交易报价。
基准窗口结束：$($start.ToString('o'))；目标窗口：[$($start.ToString('o')), $($end.ToString('o')))；请求时间：$($now.ToString('o'))。
目标窗口已经开始，本次是盘中研究，只使用基准窗口及此前的数据，没有目标窗口的最新价格。
所有事实的单位均为百分比：gold1/gold5/gold20是1/5/20个连续聚合窗口的收益率；ma20Distance是基准收盘相对20窗口均线距离；range1是基准窗口高低价差相对前收盘。
事实JSON：$factsJson
没有提供实际利率、DXY、新闻或订单流，不得编造；不能仅因波动高而判断横盘。
分类定义：目标收盘相对基准收盘涨幅>0.5%为BULLISH，跌幅<-0.5%为BEARISH，其余为NEUTRAL。
必须同时考虑涨幅>0.5%、跌幅<-0.5%、区间内三种情况；缺乏上涨证据不等于横盘，因为仍可能下跌。历史负收益不保证未来下跌。
range1是波幅水平，不是价格方向，不能把正波幅当成上涨信号。trend只是数值符号。
仅返回JSON对象：{"direction":"BULLISH|NEUTRAL|BEARISH","reasoning":"简短中文依据和局限，不重抄数字","invalidationConditions":["条件"],"supportingKeys":["gold1"]}。
supportingKeys至少一项，仅从上述五个事实编号选择，不能重复；事实数值和符号由程序保留，不由你重写。
"@
$sha=[Security.Cryptography.SHA256]::Create()
try { $promptSha=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($prompt)))).Replace('-','').ToLowerInvariant() }
finally { $sha.Dispose() }
$record=[ordered]@{version=$tag;status='STARTED';researchOnly=$true;basis=$checked.basis;timingLayer='IN_SESSION';trustedAccuracyEligible=$false;officialSessionCertified=$false;productionEligible=$false;model=$model;modelDigest=$digest;createdAt=$now.ToString('o');baseEnd=$start.ToString('o');targetEnd=$end.ToString('o');sourceReceiptSha256=$checked.receiptSha256;facts=$facts;bars=$bars;prompt=$prompt;promptSha256=$promptSha;rawResponse=$null;validation=$null;completedAt=$null}
function Save-Record { [IO.File]::WriteAllText($Output,($record | ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false)) }
Save-Record
try {
    $body=@{model=$model;stream=$false;think=$false;format='json';messages=@(@{role='user';content=$prompt});options=@{temperature=0;seed=42;num_ctx=8192;num_predict=2048}} | ConvertTo-Json -Depth 8
    $response=Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://127.0.0.1:11435/api/chat' -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 180
    $record.rawResponse=$response.Content
    $record.completedAt=[DateTimeOffset]::UtcNow.ToString('o')
    $record.status='RECEIVED'
    Save-Record
    $reply=$response.Content | ConvertFrom-Json
    if ($reply.done -ne $true -or $reply.model -ne $model) { throw 'Incomplete or wrong-model response' }
    $forecast=$reply.message.content | ConvertFrom-Json
    if ($forecast.direction -notin @('BULLISH','NEUTRAL','BEARISH') -or [string]::IsNullOrWhiteSpace($forecast.reasoning) -or @($forecast.invalidationConditions).Count -eq 0 -or @($forecast.supportingKeys).Count -eq 0) { throw 'Forecast output contract invalid' }
    $seen=@{}
    foreach ($key in $forecast.supportingKeys) {
        if ($key -isnot [string] -or !$facts.Contains($key) -or $seen.ContainsKey($key)) { throw 'Forecast reference unknown or repeated' }
        $seen.Add($key,$true)
    }
    if ([DateTimeOffset]::UtcNow -ge $end) { throw 'Response completed after target end' }
    $record.validation='KNOWN_REFERENCES_PASSED; facts owned by program; prose and direction not certified'
    $record.status='RESEARCH_ONLY'
    $record['forecast']=$forecast
    $record['evidence']=$factsInput
    Save-Record
    [pscustomobject]@{status=$record.status;direction=$forecast.direction;baseEnd=$record.baseEnd;targetEnd=$record.targetEnd;timingLayer=$record.timingLayer;trustedAccuracyEligible=$false;reasoning=$forecast.reasoning} | ConvertTo-Json -Depth 5
} catch {
    $record.status='FAILED'
    $record.validation=$_.Exception.Message
    Save-Record
    throw
}
