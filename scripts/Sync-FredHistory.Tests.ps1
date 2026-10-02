# 只验证采集流程的边界；HTTP 替身数据不会进入真实行情目录。
$script:command = Join-Path $PSScriptRoot 'Sync-FredHistory.ps1'
$script:root = Join-Path $PSScriptRoot ('../backend/target/fred-script-tests/' + [Guid]::NewGuid().ToString('N'))
$script:priorKey = $env:FRED_API_KEY
try {
$env:FRED_API_KEY = 'offline-test-only'
Describe '历史版本采集批次' {
    Mock Invoke-RestMethod {
        if ($Uri -match 'vintagedates') { return [pscustomobject]@{vintage_dates=@('2007-11-01')} }
        $start = [regex]::Match($Uri, '[?&]realtime_start=([^&]+)').Groups[1].Value
        $end = [regex]::Match($Uri, '[?&]realtime_end=([^&]+)').Groups[1].Value
        return [pscustomobject]@{count=1; offset=0; observations=@(
            [pscustomobject]@{date='2007-11-01'; realtime_start=$start; realtime_end=$end; value='1.0'}
        )}
    }
    It '成功发布两个完整文件并拒绝覆盖原批次' {
        $output = Join-Path $script:root 'success'
        & $script:command -EndDate '2007-11-02' -OutputDir $output | Out-Null
        Test-Path -LiteralPath (Join-Path $output 'DFII10.json') | Should Be $true
        Test-Path -LiteralPath (Join-Path $output 'DTWEXBGS.json') | Should Be $true
        $file = Join-Path $output 'DFII10.json'
        $hash = (Get-FileHash $file).Hash
        $archive = Get-Content $file -Raw -Encoding UTF8 | ConvertFrom-Json
        $archive.count | Should Be 1
        $archive.chunks[0].end | Should Be '2007-11-02'
        { & $script:command -EndDate '2007-11-02' -OutputDir $output } | Should Throw
        (Get-FileHash $file).Hash | Should Be $hash
    }
    It '第二个序列失败时不发布半个批次' {
        Mock Invoke-RestMethod { throw 'intentional-network-failure' } -ParameterFilter { $Uri -match 'series_id=DTWEXBGS' }
        $output = Join-Path $script:root 'failed'
        { & $script:command -EndDate '2007-11-02' -OutputDir $output } | Should Throw
        Test-Path -LiteralPath $output | Should Be $false
    }
    It '拒绝未来日期且不创建输出目录' {
        $output = Join-Path $script:root 'future'
        { & $script:command -EndDate '2099-01-01' -OutputDir $output } | Should Throw
        Test-Path -LiteralPath $output | Should Be $false
    }
}
} finally {
    $env:FRED_API_KEY = $script:priorKey
}
