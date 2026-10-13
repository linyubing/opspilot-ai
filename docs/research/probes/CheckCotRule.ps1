# 固定规则的回溯探索；不训练、不访问数据库，不用Updated冒充首次发布。
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../../..").Path
$source = Join-Path $root 'docs/research/2026-10-11-prior-compare-input.json'
if ((Get-FileHash $source -Algorithm SHA256).Hash.ToLower() -ne '0e72f54e73b78efb0c12c3a5c474976afadb4c163aba732f9c39e9b6b124d936') { throw 'Frozen sample hash mismatch' }
$samples = @((Get-Content $source -Raw -Encoding UTF8 | ConvertFrom-Json).samples | Sort-Object asOfDate)
if ($samples.Count -ne 1268 -or @($samples | Where-Object { $_.targetDate -ge '2024-11-11' -or $_.targetDate -le $_.asOfDate -or $_.horizon -ne 'NEXT_DAY' }).Count -gt 0) { throw 'Sample scope mismatch' }
$manifest = Get-Content (Join-Path $root 'docs/research/2026-10-13-cot-archive-record.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($meta in $manifest.rows) {
    if ((Get-FileHash (Join-Path $root $meta.localReceipt) -Algorithm SHA256).Hash.ToLower() -ne $meta.receiptSha256) { throw 'Receipt hash mismatch' }
}
$archive = Join-Path $root 'backend/target/oct13-cftc-2023.zip'
if ((Get-FileHash $archive -Algorithm SHA256).Hash.ToLower() -ne $manifest.annualArchiveSha256) { throw 'Annual archive hash mismatch' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($archive)
try {
    $reader = [IO.StreamReader]::new($zip.Entries[0].Open())
    try { $positions = @(($reader.ReadToEnd() | ConvertFrom-Csv) | Where-Object CFTC_Contract_Market_Code -eq '088691' | Sort-Object Report_Date_as_YYYY-MM-DD) }
    finally { $reader.Dispose() }
} finally { $zip.Dispose() }
if ($positions.Count -ne 52) { throw 'Expected 52 positions' }
$labels = @('BULLISH', 'NEUTRAL', 'BEARISH')
$cases = @()
for ($i = 0; $i -lt $positions.Count; $i++) {
    $row = $positions[$i]
    $meta = @($manifest.rows | Where-Object positionDate -eq $row.'Report_Date_as_YYYY-MM-DD')
    if ($meta.Count -ne 1) { throw 'Report date not unique' }
    $meta = $meta[0]
    $case = [ordered]@{positionDate=$meta.positionDate; updatedDate=$meta.archiveUpdatedDate; evidence=$meta.evidence}
    if ($i -eq 0) { $case.status='NO_PREVIOUS_REPORT'; $cases += $case; continue }
    $earliest = ([datetime]$meta.archiveUpdatedDate).AddDays(2)
    $case.earliestInputDate = $earliest.ToString('yyyy-MM-dd')
    $sample = $samples | Where-Object { $_.asOfDate -ge $earliest.ToString('yyyy-MM-dd') } | Select-Object -First 1
    if ($null -eq $sample -or ([datetime]$sample.asOfDate - $earliest).Days -gt 4) { $case.status='NO_ELIGIBLE_SAMPLE'; $cases += $case; continue }
    $case.baseDate = $sample.asOfDate
    $case.targetDate = $sample.targetDate
    $case.positionAgeDays = ([datetime]$sample.asOfDate - [datetime]$meta.positionDate).Days
    if ($case.positionAgeDays -gt 14) { $case.status='STALE_POSITION'; $cases += $case; continue }
    $previous = $positions[$i-1]
    $previousMeta = $manifest.rows | Where-Object positionDate -eq $previous.'Report_Date_as_YYYY-MM-DD'
    if ([datetime]$previousMeta.archiveUpdatedDate -gt $earliest -or [long]$row.Open_Interest_All -le 0 -or [long]$previous.Open_Interest_All -le 0) { throw 'Invalid causal input order' }
    # 用交叉乘法判定占比变化的正负，避免浮点误差制造信号。
    $delta = ([decimal]$row.M_Money_Positions_Long_All - [decimal]$row.M_Money_Positions_Short_All) * [decimal]$previous.Open_Interest_All - ([decimal]$previous.M_Money_Positions_Long_All - [decimal]$previous.M_Money_Positions_Short_All) * [decimal]$row.Open_Interest_All
    $direction = if ($delta -gt 0) { 'BULLISH' } elseif ($delta -lt 0) { 'BEARISH' } else { 'NO_SIGNAL' }
    $counts = [ordered]@{BULLISH=0;NEUTRAL=0;BEARISH=0}
    foreach ($old in $samples) { if ($old.targetDate -lt $sample.asOfDate) { if ($old.label -notin $labels) { throw 'Unknown historical label' }; $counts[$old.label]++ } }
    $prior = $labels[0]
    foreach ($label in $labels) { if ($counts[$label] -gt $counts[$prior]) { $prior=$label } }
    if ($sample.label -notin $labels) { throw 'Unknown target label' }
    $case.status = if ($direction -eq 'NO_SIGNAL') { 'NO_SIGNAL' } else { 'SCORED' }
    $case.previousPositionDate=$previous.'Report_Date_as_YYYY-MM-DD'
    $case.long=[long]$row.M_Money_Positions_Long_All
    $case.short=[long]$row.M_Money_Positions_Short_All
    $case.openInterest=[long]$row.Open_Interest_All
    $case.deltaSign=[math]::Sign($delta)
    $case.direction=$direction
    $case.actual=$sample.label
    $case.hit=if ($direction -eq 'NO_SIGNAL') { $null } else { $direction -eq $sample.label }
    $case.prior=$prior
    $case.priorHit=$prior -eq $sample.label
    $case.priorCounts=$counts
    $cases += $case
}
$scored = @($cases | Where-Object status -eq 'SCORED')
$hits = @($scored | Where-Object hit -eq $true).Count
$priorHits = @($scored | Where-Object priorHit -eq $true).Count
$confusion = [ordered]@{}
foreach ($actual in $labels) {
    $confusion[$actual]=[ordered]@{}
    foreach ($pred in $labels) { $confusion[$actual][$pred]=@($scored | Where-Object { $_.actual -eq $actual -and $_.direction -eq $pred }).Count }
}
$months = @($scored | Group-Object { $_.baseDate.Substring(0,7) } | ForEach-Object {
    [ordered]@{month=$_.Name;signals=$_.Count;hits=@($_.Group | Where-Object hit -eq $true).Count;priorHits=@($_.Group | Where-Object priorHit -eq $true).Count}
})
[ordered]@{
    protocol='2026-10-13-cot-rule-protocol.md'
    kind='RETROSPECTIVE_EXPLORATION_NOT_STRICT_PIT'
    strictPitVerified=$false;trainingPerformed=$false;holdoutRead=$false;promotionAllowed=$false
    brierScore=$null;logLoss=$null
    summary=[ordered]@{
        opportunities=$cases.Count
        noPrevious=@($cases | Where-Object status -eq 'NO_PREVIOUS_REPORT').Count
        stale=@($cases | Where-Object status -eq 'STALE_POSITION').Count
        missingSample=@($cases | Where-Object status -eq 'NO_ELIGIBLE_SAMPLE').Count
        noSignal=@($cases | Where-Object status -eq 'NO_SIGNAL').Count
        signals=$scored.Count;hits=$hits;signalAccuracy=if($scored.Count){$hits/$scored.Count}else{$null}
        coverage=$scored.Count/$cases.Count
        priorHits=$priorHits;priorAccuracy=if($scored.Count){$priorHits/$scored.Count}else{$null}
        fixed=@($scored | Where-Object { $_.hit -and !$_.priorHit }).Count
        regressed=@($scored | Where-Object { !$_.hit -and $_.priorHit }).Count
    }
    confusionActualByPrediction=$confusion
    months=$months
    rows=$cases
} | ConvertTo-Json -Depth 8
