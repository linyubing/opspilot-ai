# 两阶段黄金预测：开发集多命中1条，未形成稳定涨跌优势

## 结果与决定

按[评分前固定协议](2026-10-03-hierarchy-protocol.md)执行三折真实实验。两阶段候选命中351/720（48.75%），完整价格三分类350/720（48.61%），冻结早期PIT参照352/720（48.89%），训练多数类347/720（48.19%）。相对价格仅多1条、约0.14个百分点；**不是未来准确率提升证明，不晋级、不改生产模型。**

本轮采用两个二分类逻辑回归头：先波动/震荡，再在波动发生条件下区分涨/跌，组合回原三类概率。全部验证日期仍参与预测和评分；只在训练时筛选已结算涨跌样本训练方向头，预测时完全不读真实未来幅度。

## 同720条完整对照

| 分支 | 命中 | 准确率 | 平衡准确率 | 上涨/震荡/下跌召回 | Brier | LogLoss |
| --- | ---: | ---: | ---: | --- | ---: | ---: |
| 两阶段 | 351 | 48.75% | 35.15% | 6.03% / 95.97% / 3.45% | 0.627385 | 1.042666 |
| 价格三分类 | 350 | 48.61% | 34.98% | 5.53% / 95.97% / 3.45% | 0.627546 | 1.042947 |
| 早期PIT参照 | 352 | 48.89% | 35.79% | 4.52% / 94.81% / 8.05% | 0.636638 | 1.057549 |
| 训练多数类 | 347 | 48.19% | 33.33% | 0% / 100% / 0% | 0.633245 | 1.050887 |

Brier/LogLoss小幅下降，但准确率未同时超所有参照、平衡准确率仅提高约0.17pp而非2pp，三方向召回不足25%。完整五门槛中仅概率误差两项通过；不降低标准。

信号阈值仍0.55，151条信号、覆盖20.97%，命中89条（58.94%）。**151条都是震荡信号，同日期三个参照也均命中89条**；不能将58.94%当作涨跌优势或与完整720条的48.61%直接比较。信号三个门槛全未通过。

## 逐折：不能挑第二折

| 验证开始 | 样本 | 两阶段命中 | 价格命中 | 波动头AUC | 条件方向头AUC |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2022-02-02 | 240 | 108 | 106 | 0.4861 | 0.4860 |
| 2023-01-04 | 240 | 136 | 137 | 0.5715 | 0.6054 |
| 2023-12-07 | 240 | 107 | 107 | 0.5769 | 0.4983 |

全部合并波动AUC0.5634、方向AUC0.5267，但合并统计会受跨折概率分布差异影响，必须连同三个折展示。方向AUC只在真实涨跌373条上诊断，不能替代原三分类或事前挑出震荡；缺类块保留null。没有显著性/置信区间结论。

## 失败原因：结构没有补足方向区分能力

1. **不是未收敛。** 波动训练3640/3880/4120，方向训练1900/2033/2142；六个头均收敛，独立重算最大梯度≤1e-6。每次导出每个头重复拟合，两次完整实际导出除创建时间外一致。
2. **概率仍接近类别频率。** 真实上涨时平均波动概率0.5258、条件上涨概率0.5204；真实下跌时分别0.5323、0.5183。方向头只有中间一折排序较好，其余接近0.5。不能宣称“方向头拆开就学会涨跌”。
3. **组合后仍偏震荡。** 候选33条判上涨、671条判震荡、16条判下跌；价格模型31/673/16。相对价格新增2次命中、丢失1次：上涨+1，下跌新增1同时丢失1，震荡不变；总体只有三条最大类别判断发生变化。
4. **大幅变化仍难识别。** 真实>1%幅度165条，候选命中5条、价格4条；改进仅1条。不根据事后幅度筛选验证或调参数。

这只能说明当前20维日线输入、固定线性两头结构和这些开发时期未形成稳定方向优势，不证明黄金永远不可预测，也不证明所有非线性/宏观/盘中信息无用。没有真实逐事件证据，不能把某次错误归因于一条新闻或某个宏观事件。

完整三折、36个连续20条块、五个真实幅度组、四个分支及门槛见[公开汇总](2026-10-03-hierarchy-metrics.json)。不发布逐条价格/特征/预测或秘密信息。

## 下一步方向

保留失败模型供复现，不搜索该结构的阈值/正则/类别权重补考。下一轮优先核查可获得的真实新增信息，例如预测截止时已知的跨市场或盘中数据；先检查时间戳、历史覆盖和授权，再固定一项增量实验。不能拿DXY替代宽基美元指数的名字，也不能用最新修订数据冒充历史当时已知数据。

若没有可靠的新输入，应如实保留“当前尚无稳定涨跌优势”，而不是制造新闻、修改标签或挑好日期。最终留出集仍封存，正式模型未改。

## 验收与复现

所有输入来自已认证的本地真实`2026-10-03-tree-check.json`，4382根日线、4361条20维样本；各训练目标严格早于验证开始，所有目标早于2024-11-11。本轮不查询新行情、不写数据库、不访问付费API。日线历史修订可得性没有新的证明。旧720条已反复研究，不是新盲测或2026最新行情。

在仓库根目录先认证来源（新检出必须准备本地真实归档，不能用假行情替代）：

```powershell
New-Item -ItemType Directory -Force backend/target | Out-Null
node docs/research/probes/verify-tree.cjs docs/research/2026-10-03-tree-check.json > backend/target/hierarchy-source.log
```

在`backend`：

```powershell
.\mvnw.cmd test
.\mvnw.cmd dependency:build-classpath -Dmdep.outputFile=target/probe-classpath.txt
$hierarchyCp = 'target/classes;target/probe-classes;' + (Get-Content -Raw target/probe-classpath.txt).Trim()
javac -encoding UTF-8 -cp $hierarchyCp -d target/probe-classes ../docs/research/probes/HistorySlice.java ../docs/research/probes/FusionMix.java ../docs/research/probes/FusionScale.java ../docs/research/probes/FusionChecks.java ../docs/research/probes/TrainingProbe.java ../docs/research/probes/BinaryFit.java ../docs/research/probes/HierarchyModel.java ../docs/research/probes/HierarchyChecks.java ../docs/research/probes/HierarchyRun.java
$hierarchyCommit = git rev-parse HEAD
java -cp $hierarchyCp com.opspilot.ai.forecast.learning.HierarchyRun target/hierarchy-results.json $hierarchyCommit
java -cp $hierarchyCp com.opspilot.ai.forecast.learning.HierarchyRun target/hierarchy-repeat.json $hierarchyCommit
```

原始导出只接受新的`backend/target`文件，不覆盖已有结果；重跑使用新文件名，不能删除原研究证据。每条命令检查退出码，失败立即停止。根目录验收：

```powershell
node --test docs/research/probes/HierarchyMathChecks.cjs docs/research/probes/HierarchyAuditChecks.cjs
node docs/research/probes/verify-hierarchy.cjs backend/target/hierarchy-results.json backend/target/hierarchy-repeat.json > backend/target/hierarchy-verified.log
```

- Java数学组合、损失、有限差分梯度、手算Hessian及实际收敛先观察RED，再GREEN；夹具只是数学，不冒充行情。日期/方向子集也检查未结算、倒序与震荡排除。
- 独立Node数学检查2项、实际归档认证/损坏拒绝9项共11项先RED后GREEN。拒绝方向头混入震荡、缩放变化、非最优权重、中间概率变化、错误标签、截止日变化、伪造源码摘要、删验证日期。
- Node不调用Java模型评分，独立从真实训练输入重算标准化、平均交叉熵+中心化L2梯度和六头概率，再逐条认证验证概率/参照，重新计算所有统计。
- 完整`mvnw.cmd test`650运行、0失败、0错误、3跳过，退出0、BUILD SUCCESS，2026-10-03 13:42:28，`backend/target/hierarchy-regression.log`。跳过真实接口不证明外部服务当前可用。
- Git基线`1bd647b942a8a7f49f8b435438faee072d9719bb`，研究源码/协议另以SHA绑定，不把基线冒充包含新增研究代码的提交。原始结果只存本地target忽略文件。

### 交付复核

- 完整交付回归：650运行、0失败、0错误、3跳过，退出0，2026-10-03 13:46:17，日志为`backend/target/hierarchy-delivery-regression.log`。
- 一次独立只读审查未发现阻塞问题；审查覆盖日期隔离、两个训练子集、独立缩放、概率组合和完整公开指标一致性，不额外声称复跑Maven或证明未来有效。
- 已补齐审查提出的计算依赖指纹：`InfoMath.cjs`和`DirectionMath.cjs`也进入公开摘要。补齐后11项Node检查再次通过，真实两次导出认证再次通过，汇总日志为`backend/target/hierarchy-final-verified.log`。
- 本轮不晋级。下一轮优先审计真实增量信息及其历史可获得时间，不在这720条已反复查看的日期上继续搜索阈值或正则参数。
