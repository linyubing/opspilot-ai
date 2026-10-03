# 黄金错判诊断：不是只有边界小波动，方向概率也不稳定

## 本轮结论

本轮没有训练新模型，也没有提高准确率。固定等权融合仍命中349/720（48.47%），完整价格分支350/720（48.61%），完整训练多数类347/720（48.19%）。融合717条判震荡、3条判上涨、0条判下跌；正确率主要来自真实震荡347条全部命中，而不是有效涨跌识别。

按[评分前固定协议](2026-10-03-discrimination-protocol.md)，重新核对真实次日收益及概率排序。五个幅度组覆盖全部720条，不遗漏难预测日期。独立Node与Java复算分组、混淆矩阵、概率误差和AUC一致。**错误不只集中在±0.5%的标签边界；也没有稳定的概率方向排序证据支持“只调阈值就能修好”。** 这不是对所有模型、所有宏观信息或黄金可预测性的否定。

这些是2022-02-02—2024-11-07反复研究的旧开发样本，不是2026最新行情、新盲测或最终留出集。正式模型、标签、阈值、融合权重及晋级标准完全不变。

## 实际收益幅度与六分支命中

基准为当日真实收盘，目标为下一根实际黄金日线收盘。Node使用十进制BigInt交叉乘法，Java使用BigDecimal相乘比较，避免0.5/0.6/1/2%边界浮点舍入。严格超出±0.5%才为涨跌，边界仍为震荡。

表中数值为命中条数，不是各组正确率。

| 真实绝对次日收益 | 样本 | 融合 | 价格 | 宏观 | 频率混合 | 多数类 | 冻结PIT参照 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ≤0.5% | 347 | 347 | 333 | 347 | 347 | 347 | 329 |
| (0.5%,0.6%] | 61 | 1 | 2 | 0 | 0 | 0 | 3 |
| (0.6%,1%] | 147 | 1 | 11 | 0 | 0 | 0 | 10 |
| (1%,2%] | 145 | 0 | 3 | 0 | 0 | 0 | 8 |
| >2% | 20 | 0 | 1 | 0 | 0 | 0 | 2 |
| 全部 | 720 | 349 | 350 | 347 | 347 | 347 | 352 |

真实涨跌373条中，边界幅度61条约占16.35%；幅度>1%的165条，融合方向0命中，价格分支4命中。不能把全部失败归咎于“差一点点跨过标签边界”。这些组只使用事后结算结果进行描述，**不能作为事前筛选样本或训练输入**。

融合相对价格新增14次震荡命中，同时丢失15次涨跌命中。丢失分布为边界1次、中等10次、(1%,2%]3次、>2%1次。没有发现新增涨跌命中；完整结果仍净少1次。

## 完整概率排序诊断

AUC定义为一个真实正例的对应概率高于一个负例的比例，相等计半分；缺少正例或负例返回null，不能伪造为0或0.5。0.5表示这一排序统计没有优于相等概率，不等同于50%三分类正确率。

最后一列只在真实涨跌373条上检查条件上涨概率p(up)/(p(up)+p(down))排序。它是机制诊断，**不能替代原720条三分类成绩、信号覆盖率或生产任务**。该子集是事后选定，不能据此声称事前能够排除震荡。

| 分支 | 上涨one-vs-rest AUC | 震荡AUC | 下跌AUC | 真实涨跌子集方向AUC |
| --- | ---: | ---: | ---: | ---: |
| 融合 | 0.5114 | 0.5550 | 0.5098 | 0.4615 |
| 价格 | 0.5246 | 0.5633 | 0.5617 | 0.5260 |
| 宏观 | 0.4931 | 0.4820 | 0.4366 | 0.4425 |
| 频率混合 | 0.5197 | 0.5610 | 0.5508 | 0.5134 |
| 多数类 | 0.4745 | 0.4574 | 0.4596 | 0.4846 |
| 冻结PIT参照 | 0.4822 | 0.5548 | 0.5368 | 0.4706 |

跨折合并AUC可能受各时期概率分布差异影响，不作为单独选择标准。多数类每折概率恒定，**每折AUC都是0.5**，合并后偏离0.5是跨折类别比例和概率值变化的结果，不是发现多数类具备逐日方向信息。

## 三折方向AUC，全部展示

| 验证范围 | 融合 | 价格 | 宏观 | 频率混合 | 多数类 | 冻结PIT参照 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 2022-02-02—2023-01-03 | 0.5111 | 0.4837 | 0.5258 | 0.4893 | 0.5000 | 0.4804 |
| 2023-01-04—2023-12-06 | 0.4873 | 0.6133 | 0.4440 | 0.6150 | 0.5000 | 0.4852 |
| 2023-12-07—2024-11-07 | 0.4113 | 0.4978 | 0.3947 | 0.4976 | 0.5000 | 0.4824 |

价格第二折有较好的方向排序，但另外两折接近0.5；不能挑第二折宣布改善。宏观第一折0.5258，后两折0.4440/0.3947，也不支持稳定增量。没有计算显著性或置信区间，因此不声称某个数值差异已证明统计显著。

按真实类别的宏观平均概率进一步可见：真实上涨时平均上涨概率0.2577、震荡0.5234、下跌0.2189；真实下跌时平均上涨0.2609、震荡0.5245、下跌0.2146。它不仅最大类别偏震荡，真实涨跌之间的概率区分也弱。该描述不证明类别平衡、分层训练或新增真实信息一定有效。

全部五组及三折的六分支混淆矩阵、召回、Brier/LogLoss、三类AUC和实际类别平均概率，见[公开完整汇总](2026-10-03-discrimination-metrics.json)。五个幅度组还提供配对新增命中/错判；三折不提供该配对字段。组内缺类指标保留null；公开文件不含原始价格、宏观值、逐条预测或密钥。

## 下一轮假设与不允许的推论

一个值得受控检验、尚未实施的假设是：分别学习“是否发生超过0.5%的波动”和“发生波动时上涨还是下跌”，再组合为原三分类概率，保持全部验证日期、标签和原验收门槛。它可能减少单个三分类器的震荡偏好，**但本轮没有证明它会提高正确率**。必须在训练期内完成拟合，预测时不能读取真实未来幅度；不能删掉震荡样本评分。

若这种固定结构仍失败，应保留失败结论，核查真实事前跨市场/盘中/事件信息的可得性，而不是继续搜索阈值或换日期。新增来源必须明确标的、时间戳、授权和历史可获取时间。当前没有真实逐事件证据，不能把大幅错判自动归因于某条新闻、利率决议或战争。

## 复算与验收边界

输入仍是本地忽略的真实`2026-10-03-tree-check.json`及`2026-10-03-fusion-check.json`，需具备原研究数据及真实FRED历史归档。仅克隆公开代码不能凭空恢复这些本地输入；不提供假行情替代。先运行原融合验收器完整认证来源，再执行本轮诊断。

仓库根目录PowerShell生成Node结果：

```powershell
New-Item -ItemType Directory -Force backend/target | Out-Null
node docs/research/probes/run-direction.cjs > backend/target/direction-results.log
node docs/research/probes/run-direction.cjs > backend/target/direction-repeat.log
```

在`backend`生成依赖类路径并编译独立Java及其全部辅助源码，不依赖旧探针class：

```powershell
.\mvnw.cmd dependency:build-classpath -Dmdep.outputFile=target/probe-classpath.txt
$directionCp = 'target/classes;target/probe-classes;' + (Get-Content -Raw target/probe-classpath.txt).Trim()
javac -encoding UTF-8 -cp $directionCp -d target/probe-classes ../docs/research/probes/FusionMix.java ../docs/research/probes/FusionScale.java ../docs/research/probes/FusionChecks.java ../docs/research/probes/DirectionProbe.java ../docs/research/probes/DirectionProbeChecks.java
java -cp $directionCp com.opspilot.ai.forecast.learning.DirectionProbe > target/direction-java.log
java -cp $directionCp com.opspilot.ai.forecast.learning.DirectionProbe > target/direction-java-repeat.log
```

回到仓库根目录：

```powershell
node --test docs/research/probes/DirectionMathChecks.cjs docs/research/probes/DirectionAuditChecks.cjs docs/research/probes/DirectionCompareChecks.cjs
node docs/research/probes/verify-direction.cjs backend/target/direction-results.log backend/target/direction-java.log backend/target/direction-repeat.log
```

每条命令必须检查退出码，失败即停止，不能把后续命令成功当作前一条成功。Windows PowerShell输出可能是UTF-16LE，读取器支持它与UTF-8；数字来自解析结果，不以乱码展示反向修改数据。

- 数学边界与AUC夹具、真实归档损坏拒绝及Node/Java差异检测此前经历RED/GREEN。夹具仅为手算数学，不冒充真实市场数据。
- 当前重新编译Java成功；两个Node输出逐值一致，两个Java结果逐值一致，独立Node/Java对照通过。13个专项检查全部通过、0失败、0跳过。
- 完整产品回归日志`backend/target/direction-regression.log`：650运行、0失败、0错误、3跳过，BUILD SUCCESS，2026-10-03 13:13:51。跳过真实接口测试不证明外部接口当下可用。
- 交付前再次完整回归`backend/target/direction-delivery-regression.log`：650运行、0失败、0错误、3跳过，退出码0，BUILD SUCCESS，2026-10-03 13:28:49。加入原融合/宏观检查后42项Node检查通过。五个幅度组的六分支混淆矩阵逐格求和等于完整720条矩阵；公开汇总与实际Node输出逐字段一致。
- 本轮不查询最新行情、不写数据库、不改变生产Java/配置、不更新正式模型；只发布诊断源码、固定协议和汇总。
- 基线Git为`c844500edfa4e33bc76387fd9e26c16d58d0c53c`。当前研究源码和协议以摘要单独绑定，不能把基线冒充含新增诊断的提交。
- 一次独立只读审查实际执行13项专项及独立复算，未发现Critical/Important。两项Minor文档建议已修正：先创建输出目录，并区分五组配对指标与三折概率诊断；不影响真实分数。审查未验收最新行情、外部接口、未来准确率或未实施的分层训练假设。
