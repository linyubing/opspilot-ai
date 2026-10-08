param(
    [Parameter(Mandatory=$true)][string]$Receipt,
    [Parameter(Mandatory=$true)][string]$FredDirectory
)
# 只在私有副本加审计标记，不改任何真实价格；不同回执不可冒充固定实验。
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
$folder=Join-Path $root ('backend\target\hourly-cohort-check-'+[Guid]::NewGuid())
$null=New-Item -ItemType Directory -Path $folder
$copy=Join-Path $folder 'source.json'
$original=Get-Content $Receipt -Raw -Encoding UTF8 | ConvertFrom-Json
$original | Add-Member -NotePropertyName cohortCheck -NotePropertyValue $true
[IO.File]::WriteAllText($copy,($original | ConvertTo-Json -Depth 100),[Text.UTF8Encoding]::new($false))
$output=Join-Path $folder 'result.json'
$rejected=$false
try { & (Join-Path $PSScriptRoot 'RunHourlyBench.ps1') -Receipt $copy -Output $output -FredDirectory $FredDirectory }
catch { $rejected=$true }
# 变体必须先通过真实小时核验，不能把损坏输入导致的失败误当作冻结保护有效。
$check=Get-Content ($copy+'.check.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if($check.consecutiveCompleteWindows -ne 186) { throw 'Receipt variant did not preserve the real complete history' }
if(!$rejected -or (Test-Path $output)) { throw 'Different source receipt was allowed to score the frozen real-rate cohort' }
[pscustomobject]@{checks=1; rejectedDifferentReceipt=$true; resultWritten=$false} | ConvertTo-Json
