param([Parameter(Mandatory=$true)][string]$Receipt)

# 固定真实回执中8月27日21点到10月7日21点，共41个完整24小时窗口。
# 防止研究工具悄悄截断历史，导致回测样本数量错误。
$ErrorActionPreference='Stop'
if ((Get-FileHash -LiteralPath $Receipt).Hash.ToLowerInvariant() -ne '17113871672c311e4889c586188237232d2b53745fb9ac297798192f056a4d25') { throw 'This test requires the retained real receipt' }
$null=& (Join-Path $PSScriptRoot 'CheckHourlyRoute.ps1') -Receipt $Receipt
$r=Get-Content -LiteralPath ($Receipt+'.check.json') -Encoding UTF8 -Raw | ConvertFrom-Json
if ($r.windows.Count -ne 41 -or $r.consecutiveCompleteWindows -ne 41 -or $r.bars.Count -ne 41) { throw 'Complete real history was truncated: expected 41 windows' }
if ($r.bars[-1].start -ne '2026-08-27T21:00:00.0000000+00:00' -or $r.bars[0].endExclusive -ne '2026-10-07T21:00:00.0000000+00:00') { throw 'Real history boundaries changed' }
[pscustomobject]@{tests=2;failures=0;realWindows=41} | ConvertTo-Json
