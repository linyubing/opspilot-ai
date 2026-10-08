param([Parameter(Mandatory=$true)][string]$Receipt)

# 独立编译当前公式，再核对研究记录中实际加载的class摘要，捕获旧缓存冒充新源码。
$ErrorActionPreference='Stop'
$taskDir=Join-Path 'D:\workFile\demo-ai\backend\target' ('hourly-formula-check-'+[guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $taskDir
$cp='D:\workFile\demo-ai\backend\target\classes;'+(Get-Content backend\target\probe-classpath.txt -Raw).Trim()
& javac -encoding UTF-8 -cp $cp -d $taskDir backend\src\main\java\com\opspilot\ai\forecast\learning\GoldFeatureCalculator.java backend\src\main\java\com\opspilot\ai\forecast\learning\GoldOhlcFeatures.java
if ($LASTEXITCODE -ne 0) { throw 'Independent formula compilation failed' }
$result=Join-Path $taskDir 'audit.json'
$null=& (Join-Path $PSScriptRoot 'RunHourlyBench.ps1') -Receipt $Receipt -Output $result
$record=Get-Content -LiteralPath $result -Raw | ConvertFrom-Json
$expected=(Get-FileHash (Join-Path $taskDir 'com\opspilot\ai\forecast\learning\GoldFeatureCalculator.class')).Hash.ToLowerInvariant()
if ($record.featureClassSha256 -ne $expected) { throw 'Actual formula class fingerprint does not match current source compilation' }
[pscustomobject]@{formulaClassProvenanceChecks=1;failures=0} | ConvertTo-Json
