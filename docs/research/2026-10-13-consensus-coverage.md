# 连续四个月来源覆盖核验

此前只有2024年10月一份银行周报。这次进一步核验7月、8月、9月事件，同一出版方覆盖2024年7月至10月连续四次美国CPI发布。没有挑选涨跌结果，也没有训练或评分模型。检查范围仅四个月，不称为覆盖全部历史。

| 发布日期 | 报告日期 | 总体环比共识 | 核心环比共识 | 总体同比共识 | 核心同比共识 | 来源 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 2024-07-11 | 2024-07-05 | 0.1% | 0.2% | 3.1% | 3.4% | [周报，第4页](https://www.desjardins.com/content/dam/pdf/en/personal/savings-investment/economic-studies/united-states-election-debate-july-5-2024.pdf) |
| 2024-08-14 | 2024-08-09 | 0.2% | 0.2% | 3.0% | 3.2% | [指标日历，第1页](https://www.desjardins.com/content/dam/pdf/en/personal/savings-investment/economic-studies/economic-indicators-9-august-2024.pdf) |
| 2024-09-11 | 2024-09-06 | 0.2% | 0.2% | 2.6% | 3.2% | [周报，第5页](https://www.desjardins.com/content/dam/pdf/en/personal/savings-investment/economic-studies/canada-recession-boc-6-september-2024.pdf) |
| 2024-10-10 | 2024-10-04 | 0.1% | 0.2% | 2.3% | 3.2% | 上轮已归档 |

三个新增PDF均直接下载成功。Tika提取后，通过PDFBox渲染对应整页核对表头、国家、时期、共识列与银行预测列；元数据创建日期与报告日期相符。摘要见 `2026-10-13-consensus-coverage.json`。原PDF及渲染页仅本地保留，不整体公开复制报告。

## 两个会影响真实性的发现

1. 完整周报暑期暂停不代表日历缺失。[出版页面](https://www.desjardins.com/en/savings-investment/economic-studies/economic-indicators-9-august-2024.html)明确仍发送经济指标栏目；若只按完整周报标题搜索，会误删8月事件。
2. [7月BLS归档稿](https://www.bls.gov/news.release/archives/cpi_07112024.htm)注明当天重发，移除不符合发布条件的住院医疗服务细项。这次记录其总体/核心值，但不认证08:30原始快照，也不静默将重发版本当首次发布。8月、9月值分别核对[BLS 8月归档](https://www.bls.gov/news.release/archives/cpi_08142024.htm)、[BLS 9月归档](https://www.bls.gov/news.release/archives/cpi_09112024.htm)。

同一事件的四个口径不是四个独立样本。周报共识距发布5至6天，不是最后时刻的共识；PDF创建元数据及现在的出版页面也不是不可篡改的历史上线证明。因此本轮仍为有日期出版物的历史探索来源，不是严格PIT验收。

## 本轮路线判断

连续档案有可行线索，尚未因暑期停刊形成已确认缺口；值得继续扩展固定年度覆盖，不需只等未来样本。但四次事件不足以支持新模型、更不足以证明提高准确率。继续扩展前应固定研究用的保守可得日、报告年龄、重发处理和缺失事件处理，不因收益率或拟合成绩改变这些规则。

实际减共识只能在发布后使用；现有D-1规则下不得提前输入发布当日预测。正式预测、原标签、最终留出集均保持不变。本轮没有进行新的代码全量回归，因为只新增来源记录与此核验文档，不据此宣称预测通过。
