# CPI增量的月度稳定性复核

## 本轮问题与边界

上一轮360条开发诊断中，加入真实CPI同比后从142条命中增至149条。此次不重训、不调参、不增加数据、不读取最终留出集，只检查净增7条是否跨月份稳定。月份按预测基准日 `date` 分组，比较同一天、同标签的原36维、加入CPI及训练频率基线。

输入固定为 `2026-10-13-cpi-compare-result.json`，SHA256：
`9d0df45be900f5ed368b05d79630f5f6e171d3b12d4c98585c24d17202bc0863`。

这是对已见开发结果的事后诊断，不是新盲测。部分月份仅包含诊断窗口的一部分日期；不将其称为完整月回测，也不将相邻日看作独立经济事件。

## 真实归档逐条复算

| 基准月份 | 样本 | 原模型命中 | CPI命中 | 频率基线命中 | 修正 | 退化 | 净增 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2021-02 | 14 | 2 | 2 | 7 | 0 | 0 | 0 |
| 2021-03 | 23 | 5 | 5 | 11 | 0 | 0 | 0 |
| 2021-04 | 22 | 5 | 9 | 11 | 5 | 1 | 4 |
| 2021-05 | 21 | 3 | 2 | 13 | 2 | 3 | -1 |
| 2021-06 | 22 | 7 | 11 | 12 | 9 | 5 | 4 |
| 2021-07 | 18 | 11 | 5 | 15 | 1 | 7 | -6 |
| 2022-01 | 15 | 11 | 11 | 11 | 1 | 1 | 0 |
| 2022-02 | 20 | 12 | 11 | 11 | 0 | 1 | -1 |
| 2022-03 | 23 | 6 | 7 | 5 | 3 | 2 | 1 |
| 2022-04 | 21 | 8 | 11 | 13 | 4 | 1 | 3 |
| 2022-05 | 22 | 9 | 10 | 10 | 1 | 0 | 1 |
| 2022-06 | 19 | 6 | 6 | 8 | 3 | 3 | 0 |
| 2023-01 | 14 | 8 | 9 | 9 | 1 | 0 | 1 |
| 2023-02 | 20 | 6 | 7 | 11 | 1 | 0 | 1 |
| 2023-03 | 23 | 12 | 12 | 6 | 0 | 0 | 0 |
| 2023-04 | 3 | 1 | 1 | 1 | 0 | 0 | 0 |
| 2023-05 | 18 | 9 | 11 | 9 | 2 | 0 | 2 |
| 2023-06 | 22 | 11 | 11 | 13 | 0 | 0 | 0 |
| 2023-08 | 20 | 10 | 8 | 15 | 0 | 2 | -2 |
| 合计 | 360 | 142 | 149 | 191 | 33 | 26 | 7 |

19个月中，CPI相对原模型8个月提高、4个月下降、7个月持平；相对频率基线仅3个月胜出、3个月持平、13个月落后。

2021年4月及6月各净增4条，共44个样本净增8条；其他316个样本净减1条。这只是集中度敏感性说明，不能据此删除月份、挑选窗口或部署按月份开关。2021年7月单月净减6条也必须保留。

## 失败归纳与下一步

1. 标签与价格核验没有发现错配，主要错误不在分类边界。
2. 充分求解已解决旧优化不足，却仍不能超过同人群频率基线。
3. CPI增量净增7条，但三折概率误差均退化；本轮进一步发现命中改善不具备跨月份的一致性。
4. 新信息不是自动有效信息：月度已发布通胀状态并不等于发布时的市场意外，本轮不能证明事件共识或发布后意外一定有效。

因此不晋级CPI候选，不再围绕这批已见日期筛选同比窗口、月份或参数。要继续提高真实准确率，应引入有可得时点的新信息假设，并事前冻结与基线的配对验收，而不是从此表挑胜出月份。

未来10月14日样本尚未结算只限制该样本的评分，并不限制历史诊断或代码回归。新假设的历史实验如果能获得真实事前输入与完整来源证据，也不必等10月15日；没有这些输入时不能伪造历史共识、用修订后数据冒充当时已知数据。

## 可复现复核（PowerShell）

以下命令只读归档，不写数据库，不生成假价格或假新闻。独立Node核验器已复算全部历史同比、折内缩放、模型概率及评分，结果为142/149/191、360条。

```powershell
node docs/research/probes/VerifyCpiCompare.mjs docs/research/2026-10-11-prior-compare-input.json backend/target/oct13-CPIAUCNS.json docs/research/2026-10-11-fit-reference-result.json docs/research/2026-10-13-cpi-compare-result.json

$path = 'docs/research/2026-10-13-cpi-compare-result.json'
if ((Get-FileHash $path).Hash.ToLower() -ne '9d0df45be900f5ed368b05d79630f5f6e171d3b12d4c98585c24d17202bc0863') {
    throw '输入归档发生变化，停止复核'
}
$data = Get-Content $path -Raw | ConvertFrom-Json
$labels = @{bullish='BULLISH'; neutral='NEUTRAL'; bearish='BEARISH'}
$rows = foreach ($fold in $data.folds) {
    for ($i=0; $i -lt $fold.base.diagnostic.rows.Count; $i++) {
        $base = $fold.base.diagnostic.rows[$i]
        $cpi = $fold.cpi.diagnostic.rows[$i]
        if ($base.date -ne $cpi.date -or $base.actual -ne $cpi.actual) { throw '配对不一致' }
        $b = $base.model.PSObject.Properties | Sort-Object Value -Descending | Select-Object -First 1
        $c = $cpi.model.PSObject.Properties | Sort-Object Value -Descending | Select-Object -First 1
        $p = $base.prior.PSObject.Properties | Sort-Object Value -Descending | Select-Object -First 1
        $bh = [int]($labels[$b.Name] -eq $base.actual)
        $ch = [int]($labels[$c.Name] -eq $base.actual)
        [pscustomobject]@{
            month=$base.date.Substring(0,7); base=$bh; cpi=$ch
            prior=[int]($labels[$p.Name] -eq $base.actual)
            fixed=[int]($bh -eq 0 -and $ch -eq 1)
            broken=[int]($bh -eq 1 -and $ch -eq 0)
        }
    }
}
$rows | Group-Object month | ForEach-Object {
    $sum = @{}
    foreach ($name in @('base','cpi','prior','fixed','broken')) {
        $sum[$name] = ($_.Group | Measure-Object $name -Sum).Sum
    }
    [pscustomobject]@{
        month=$_.Name; n=$_.Count; base=$sum.base; cpi=$sum.cpi; prior=$sum.prior
        fixed=$sum.fixed; broken=$sum.broken; gain=$sum.cpi-$sum.base
    }
} | Format-Table -AutoSize
```

## 交付验证

文档中的完整复核命令实际执行成功，月度表与独立Node总计一致。代码全量回归于北京时间2026-10-13 13:34结束：996 tests，0 failures，0 errors，3 skipped，`BUILD SUCCESS`；日志 `backend/target/oct13-month-audit-full.log`。本轮只新增此诊断文档，没有修改正式模型、原预测、评分或数据库。
