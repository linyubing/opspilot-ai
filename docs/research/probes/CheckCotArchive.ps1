# 核验官方档案与年度持仓数据；只下载原始回执，不创建训练样本。
param([switch]$Fetch)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = (Resolve-Path "$PSScriptRoot/../../..").Path
$archive = Join-Path $root 'backend/target/oct13-cftc-2023.zip'
if ((Get-FileHash $archive -Algorithm SHA256).Hash.ToLower() -ne '9fe75dba0256819d476bb0d01f9e8e5ac6ec2c4390f609a7e3259e85796b79ce') { throw 'Annual archive hash mismatch' }
$zip = [IO.Compression.ZipFile]::OpenRead($archive)
try {
    $reader = [IO.StreamReader]::new($zip.Entries[0].Open())
    try { $rows = @(($reader.ReadToEnd() | ConvertFrom-Csv) | Where-Object CFTC_Contract_Market_Code -eq '088691' | Sort-Object Report_Date_as_YYYY-MM-DD) }
    finally { $reader.Dispose() }
} finally { $zip.Dispose() }
$known = Get-Content (Join-Path $root 'docs/research/2026-10-13-cot-delay-record.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$culture = [Globalization.CultureInfo]::GetCultureInfo('en-US')
$result = @()
foreach ($row in $rows) {
    $date = [datetime]::Parse($row.'Report_Date_as_YYYY-MM-DD', $culture)
    $stamp = $date.ToString('MMddyy')
    $receipt = "backend/target/oct13-cot-$stamp.html"
    $file = Join-Path $root $receipt
    $url = "https://www.cftc.gov/files/dea/cotarchives/2023/futures/other_lf$stamp.htm"
    if (!(Test-Path -LiteralPath $file)) {
        if (!$Fetch) { throw "Receipt missing: $stamp" }
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $file -TimeoutSec 25
    }
    $html = Get-Content -LiteralPath $file -Raw -Encoding UTF8
    $plain = [Net.WebUtility]::HtmlDecode(($html -replace '<[^>]+>', ''))
    $start = $plain.IndexOf('GOLD - COMMODITY EXCHANGE INC.')
    if ($start -lt 0) { throw "GOLD missing: $stamp" }
    $block = $plain.Substring($start, [math]::Min(3200, $plain.Length - $start))
    $header = [regex]::Match($block, 'Disaggregated Commitments of Traders - Futures Only,\s+([A-Za-z]+\s+\d{1,2},\s+\d{4})')
    if (!$header.Success -or [datetime]::Parse($header.Groups[1].Value, $culture).Date -ne $date.Date -or !$block.Contains('Code-088691')) { throw "Header mismatch: $stamp" }
    $update = [regex]::Match($plain, 'Updated\s+([A-Za-z]+\s+\d{1,2},\s+\d{4})')
    if (!$update.Success) { throw "Updated missing: $stamp" }
    $updated = [datetime]::Parse($update.Groups[1].Value, $culture)
    if ($updated -lt $date) { throw "Date order: $stamp" }
    $line = [regex]::Match($block, '(?m)^\s*All\s*:[^\r\n]+').Value
    $numbers = @([regex]::Matches(($line -replace ',', ''), '\d+') | ForEach-Object { [long]$_.Value })
    if ($numbers.Count -lt 9) { throw "Positions missing: $stamp" }
    $fields = @('Open_Interest_All', 'M_Money_Positions_Long_All', 'M_Money_Positions_Short_All', 'M_Money_Positions_Spread_All')
    $indices = @(0, 6, 7, 8)
    for ($j = 0; $j -lt 4; $j++) {
        if ($numbers[$indices[$j]] -ne [long]$row.($fields[$j])) { throw "Position mismatch: $stamp $($fields[$j])" }
    }
    $notice = @($known.rows | Where-Object positionDate -eq $date.ToString('yyyy-MM-dd'))
    if ($notice.Count -gt 0 -and $notice[0].actualPublicationDate -ne $updated.ToString('yyyy-MM-dd')) { throw "Notice mismatch: $stamp" }
    # Updated 仅证明档案自述的更新日，不把它升级成首次发布日期。
    $grade = if ($notice.Count -gt 0) { 'NOTICE_AND_ARCHIVE' } else { 'ARCHIVE_UPDATED_ONLY' }
    $result += [ordered]@{
        positionDate = $date.ToString('yyyy-MM-dd')
        archiveUpdatedDate = $updated.ToString('yyyy-MM-dd')
        actualPublicationDate = if ($notice.Count -gt 0) { $notice[0].actualPublicationDate } else { $null }
        evidence = $grade
        reportUrl = $url
        localReceipt = $receipt
        receiptSha256 = (Get-FileHash $file -Algorithm SHA256).Hash.ToLower()
        matchedFields = 4
    }
    [Console]::Error.WriteLine("Verified {0}/{1}: {2}", $result.Count, $rows.Count, $date.ToString('yyyy-MM-dd'))
}
if ($result.Count -ne 52) { throw 'Expected 52 weekly gold reports' }
[ordered]@{
    retrievedOn = '2026-10-13'
    annualArchiveSha256 = '9fe75dba0256819d476bb0d01f9e8e5ac6ec2c4390f609a7e3259e85796b79ce'
    verifiedReports = $result.Count
    noticeBackedPublicationDates = @($result | Where-Object evidence -eq 'NOTICE_AND_ARCHIVE').Count
    archiveUpdatedOnly = @($result | Where-Object evidence -eq 'ARCHIVE_UPDATED_ONLY').Count
    wholeYearHistoricalTrainingReady = $false
    firstPublishedBytesAuthenticated = $false
    rows = $result
} | ConvertTo-Json -Depth 6
