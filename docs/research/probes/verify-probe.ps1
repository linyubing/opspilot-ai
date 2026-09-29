# 独立复算探针结果：不用页面或报告里的准确率，直接从逐日概率和真实标签计算。
$ErrorActionPreference = 'Stop'
$r = Get-Content (Join-Path $PSScriptRoot '../2026-09-29-training-health.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$sourceHash = (Get-FileHash (Join-Path $PSScriptRoot 'TrainingProbe.java') -Algorithm SHA256).Hash.ToLowerInvariant()
if ($r.sourceSha256 -ne $sourceHash) { throw '探针源代码指纹不一致' }
if ($r.folds.Count -ne 3 -or $r.sampleCount -ne 4361 -or $r.featureNames.Count -ne 20) { throw '固定诊断范围改变' }
$labels = @('BULLISH', 'NEUTRAL', 'BEARISH')
$summary = @()
foreach ($epoch in @(5, 20, 80)) {
    $hits = 0; $downHits = 0; $downCount = 0; $signalCount = 0; $signalHits = 0
    $loss = 0.0; $brier = 0.0; $baseHits = 0; $dates = [Collections.Generic.HashSet[string]]::new()
    foreach ($f in $r.folds) {
        if ($f.trainTargetEnd -ge $f.start -or $f.purged -ne 1) { throw '折间标签时点隔离失败' }
        if ($f.defaultMaxDifference -ne 0 -or $f.repeatMaxDifference -ne 0) { throw '训练复现失败' }
        $c = @($f.checks | Where-Object epochs -eq $epoch)
        if ($c.Count -ne 1 -or $c[0].predictions.Count -ne 240) { throw '训练检查点缺失' }
        $c = $c[0]
        $foldHits = 0; $foldLoss = 0.0; $foldBrier = 0.0; $foldSignals = 0; $foldSignalHits = 0
        foreach ($p in $c.predictions) {
            if ($p.date -lt $f.start -or $p.date -gt $f.end -or $p.target -le $p.date -or $p.target -ge $r.cutoffExclusive) { throw '预测日期越界' }
            if (-not $dates.Add($p.date)) { throw '诊断验证区间重叠' }
            $sum = 0.0
            foreach ($v in $p.p) {
                if ([double]::IsNaN($v) -or [double]::IsInfinity($v) -or $v -lt 0 -or $v -gt 1) { throw '概率非法' }
                $sum += $v
            }
            if ([Math]::Abs($sum - 1.0) -gt 1e-10) { throw '概率之和不为一' }
            # 保持产品的并列规则：上涨、中性、下跌；不使用真实标签选方向。
            $best = if ($p.p[0] -ge $p.p[1] -and $p.p[0] -ge $p.p[2]) { 0 } elseif ($p.p[1] -ge $p.p[2]) { 1 } else { 2 }
            $hit = [int]($labels[$best] -eq $p.actual)
            $foldHits += $hit
            if ($p.actual -eq 'BEARISH') { $downCount++; $downHits += $hit }
            if ($p.p[$best] -ge 0.55) { $foldSignals++; $foldSignalHits += $hit }
            for ($i = 0; $i -lt 3; $i++) {
                $target = [int]($labels[$i] -eq $p.actual)
                $foldBrier += [Math]::Pow($p.p[$i] - $target, 2)
                if ($target -eq 1) { $foldLoss -= [Math]::Log([Math]::Max([double]$p.p[$i], 1e-15)) }
            }
        }
        if ([Math]::Abs($foldHits / 240.0 - $c.validation.all.accuracy) -gt 0.000051 -or
            [Math]::Abs($foldLoss / 240.0 - $c.validation.all.logLoss) -gt 0.000051 -or
            [Math]::Abs($foldBrier / 240.0 - $c.validation.all.brierScore) -gt 0.000051 -or
            $foldSignals -ne $c.validation.signals.coveredCount) { throw '独立复算与 Java 指标不一致' }
        $hits += $foldHits; $loss += $foldLoss; $brier += $foldBrier
        $signalCount += $foldSignals; $signalHits += $foldSignalHits
        $majority = $f.trainLabels.PSObject.Properties | Sort-Object Value -Descending | Select-Object -First 1
        $baseHits += @($c.predictions | Where-Object actual -eq $majority.Name).Count
    }
    $summary += [pscustomobject]@{epochs=$epoch; hits=$hits; samples=720; accuracy=$hits/720.0;
        majorityHits=$baseHits; logLoss=$loss/720.0; brier=$brier/720.0;
        downHits=$downHits; downCount=$downCount; signalCount=$signalCount; signalHits=$signalHits; coverage=$signalCount/720.0}
}
$summary | ConvertTo-Json
Write-Output '独立复算通过：9 个检查点、每点 240 条真实预测；时间边界、概率、源码指纹一致。'
