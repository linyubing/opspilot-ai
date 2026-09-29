# 从逐日预测独立复算两个时点方案；同时核对归档原始版本数据和未收敛参照的失败状态。
$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot '..'
$r = Get-Content (Join-Path $root '2026-09-30-vintage-check.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$ref = Get-Content (Join-Path $root '2026-09-30-convergence-check.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($entry in @(@('VintageProbe.java',$r), @('ConvergenceProbe.java',$ref))) {
    if ((Get-FileHash (Join-Path $PSScriptRoot $entry[0]) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry[1].sourceSha256) { throw '源码指纹不一致' }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $root '2026-09-30-fred-history.zip'))
try {
    foreach ($series in @('DFII10','DTWEXBGS')) {
        $stream=$zip.GetEntry($series+'.json').Open()
        $sha=[Security.Cryptography.SHA256]::Create()
        try { $hash=([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant() } finally { $stream.Dispose(); $sha.Dispose() }
        if ($hash -ne $r.sources.$series.sha256) { throw '归档版本缓存指纹不一致' }
    }
} finally { $zip.Dispose() }
$labels=@('BULLISH','NEUTRAL','BEARISH')
foreach ($fold in $r.folds) {
    if ($fold.lastTrainingTarget -ge $fold.start -or $fold.trainingCount -lt 500 -or $fold.validationCount -ne 240) { throw '训练隔离或样本数错误' }
    foreach ($pair in $fold.comparisons) {
        $before=@($pair.legacyPredictions); $after=@($pair.historicalPredictions)
        if ($before.Count -ne 240 -or $after.Count -ne 240) { throw '配对验证数量错误' }
        for ($i=0; $i -lt 240; $i++) {
            if ($before[$i].asOfDate -ne $after[$i].asOfDate -or $before[$i].actual -ne $after[$i].actual) { throw '配对日期或标签不一致' }
            if ($pair.profile -eq 'OHLC_20' -and (ConvertTo-Json $before[$i].probabilities -Compress) -cne (ConvertTo-Json $after[$i].probabilities -Compress)) { throw 'OHLC 阴性对照不一致' }
        }
        foreach ($side in @('legacy','historical')) {
            $hits=0; $loss=0.0; $brier=0.0; $signals=0; $predictions=$pair.($side+'Predictions')
            foreach ($p in $predictions) {
                $ps=@([double]$p.probabilities.bullish,[double]$p.probabilities.neutral,[double]$p.probabilities.bearish)
                if ($p.asOfDate -lt $fold.start -or $p.asOfDate -gt $fold.end) { throw '验证日期越界' }
                foreach ($v in $ps) { if ([double]::IsNaN($v) -or [double]::IsInfinity($v) -or $v -lt 0 -or $v -gt 1) { throw '概率非法' } }
                if ([Math]::Abs(($ps[0]+$ps[1]+$ps[2])-1.0) -gt 1e-10) { throw '概率和不等于一' }
                $best=if ($ps[0] -ge $ps[1] -and $ps[0] -ge $ps[2]) {0} elseif ($ps[1] -ge $ps[2]) {1} else {2}
                $hits += [int]($labels[$best] -eq $p.actual)
                if ($ps[$best] -ge .55) {$signals++}
                for ($j=0; $j -lt 3; $j++) {
                    $actual=[int]($labels[$j] -eq $p.actual)
                    $brier += [Math]::Pow($ps[$j]-$actual,2)
                    if ($actual -eq 1) {$loss -= [Math]::Log([Math]::Max($ps[$j],1e-15))}
                }
            }
            $m=$pair.$side.all
            if ([Math]::Abs($m.accuracy-$hits/240.0) -gt .000051 -or [Math]::Abs($m.brierScore-$brier/240.0) -gt .000051 -or
                [Math]::Abs($m.logLoss-$loss/240.0) -gt .000051 -or $pair.$side.signals.coveredCount -ne $signals) { throw '独立复算与报告指标不同' }
        }
    }
}
foreach ($fold in $ref.folds) {
    if ($fold.fit.converged -or $fold.predictions.Count -ne 0 -or $null -ne $fold.referenceValidation -or
        $fold.fit.gradientError -gt 1e-6 -or $fold.fit.trace[-1].gradientMax -le 1e-6) { throw '未收敛参照状态被错误解释' }
}
Write-Output '通过：18 组指标、4320 条逐日预测、OHLC 阴性对照、两个版本缓存指纹、三个未收敛状态。'
