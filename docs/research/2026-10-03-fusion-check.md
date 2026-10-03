# 完整黄金历史与真实宏观后融合：涨跌识别没有改善

## 本轮结论

固定等权融合命中349/720（48.47%），完整价格模型350/720（48.61%），冻结早期PIT参照352/720（48.89%）。**本轮没有提高准确率；正式模型、生产参数及晋级规则保持不变。** 三折720条覆盖2022-02-02—2024-11-07，是已反复研究的旧开发验证，不是2026最新行情、最终留出集或新盲测。

## 固定对照与真实输入

按[评分前固定协议](2026-10-03-fusion-protocol.md)，价格分支保留4382根真实黄金日线构建的4361条完整OHLC样本，复算冻结全历史L2逻辑回归原概率。宏观分支从生产`GoldDatasetBuilder`、`FredHistoryStore`构建的1500条完整样本中提取9字段，分别仅使用779/1019/1259条当时已结算训练记录。

两分支均不读取未来目标；宏观只用D-1已知有效版本，不以零填缺失、不以采集时间当发布时间。实际利率为DFII10，美元字段为DTWEXBGS宽基指数，**不是DXY**。两分支验证的日期、目标日及真实标签逐条一致。

候选概率为价格、宏观各占50%。另设“价格概率与宏观训练类别频率各占50%”控制，以区别宏观关系与多数类偏好。不搜索权重、正则、特征、阈值或日期，不打开最终留出集。

## 完整720条结果

| 模型或控制 | 命中 | 准确率 | 平衡准确率 | 上涨/震荡/下跌召回 | Brier | LogLoss |
| --- | ---: | ---: | ---: | --- | ---: | ---: |
| 等权融合 | 349 | 48.47% | 33.67% | 1.01% / 100% / 0% | 0.63067 | 1.04857 |
| 完整价格分支 | 350 | 48.61% | 34.98% | 5.53% / 95.97% / 3.45% | 0.62755 | 1.04295 |
| 宏观分支 | 347 | 48.19% | 33.33% | 0% / 100% / 0% | 0.64259 | 1.06715 |
| 价格与宏观训练频率混合 | 347 | 48.19% | 33.33% | 0% / 100% / 0% | 0.62852 | 1.04449 |
| 完整价格训练多数类 | 347 | 48.19% | 33.33% | 0% / 100% / 0% | 0.63325 | 1.05089 |
| 冻结早期PIT参照 | 352 | 48.89% | 35.79% | 4.52% / 94.81% / 8.05% | 0.63664 | 1.05755 |

Brier和LogLoss越低越好，均在相同完整720条概率上计算。多数类控制展示的概率来自训练类别频率，不伪造100%方向概率。

## 配对失败原因

融合相对价格模型新增14次命中、丢失15次命中，净少1次。按真实类别拆解：

| 真实类别 | 新增命中 | 丢失原有命中 |
| --- | ---: | ---: |
| 上涨 | 0 | 9 |
| 震荡 | 14 | 0 |
| 下跌 | 0 | 6 |

实际720条中上涨199条、震荡347条、下跌174条。宏观分支所有720条都判震荡；融合717条判震荡、3条判上涨、0条判下跌。这一固定融合把原价格分支的方向判断更多拉向震荡，恢复了14个震荡命中，却丢掉15个涨跌命中。**这解释了本轮输出如何变差，不是对黄金真实涨跌事件的因果归因。**

与训练频率混合控制比较，融合仅新增2个上涨命中，Brier和LogLoss反而更差，未形成稳健的宏观增量证据。不能把融合的349次命中全部归功于宏观信息。

三个宏观拟合均收敛，最终梯度约1.40e-7、4.25e-8、2.96e-8；独立Node从训练矩阵重算中心化L2目标与三类梯度。不是把未收敛模型作为弱参照。宏观训练的震荡样本数分别399/779、506/1019、637/1259；多数类偏好与实际训练分布相关，但仅凭分布和输出不能断言类别平衡调整一定能修好。

## 逐折与连续块

| 验证开始 | 样本数 | 融合命中 | 价格命中 | 宏观命中 | 频率混合命中 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2022-02-02 | 240 | 108 | 106 | 106 | 106 |
| 2023-01-04 | 240 | 132 | 137 | 132 | 132 |
| 2023-12-07 | 240 | 109 | 107 | 109 | 109 |

完整保留36个连续20条块的混淆矩阵、概率指标、覆盖率与配对变化。相对价格命中数，4块提高、2块下降、30块相同；不以“改善块更多”替代完整样本净少1次的结论。所有块均展示，不能评分后筛好时期。

完整公开指标见[逐折与36块汇总](2026-10-03-fusion-metrics.json)：包含六个分支的全部混淆矩阵、召回、Brier/LogLoss，以及融合信号覆盖和配对变化，不含原始日线、宏观数值或逐条预测。

## 固定信号门槛也未通过

阈值0.55下，融合仅95条信号，覆盖13.19%，其中50次命中，准确率52.63%。**95条全部为震荡信号；同日期多数类也正好50次命中。** 信号不提供涨跌优势，覆盖未达30%，三方向召回未达25%。信号子集的Brier/LogLoss只描述该子集，不拿它与完整720条指标交叉比较。

完整样本五项门槛全部失败：准确率未同时超过价格/PIT/多数类，平衡准确率未提高2个百分点，三方向召回不足，Brier/LogLoss均劣于价格。信号三项门槛全部失败。不修改门槛、不晋级。

## 验收证据与交付边界

- Java手算概率、训练期标准化和日期隔离检查经历RED/GREEN；Node九维宏观公式及混合数学检查3项通过，数学夹具不冒充真实行情。
- 独立实际宏观版本重选、九维特征、训练缩放、拟合梯度、训练/验证概率及融合全部复核。8类真实归档内存损坏副本先观察拒绝检查失败（RED），实现后均被拒绝（GREEN）；不修改原始行情文件。
- 12个Node检查通过，0失败、0跳过；Java训练日期、未结算样本、重复/倒序日期检查通过。
- 独立审查发现来源对象的下载/采集元数据可以在内存中被替换。新增4类来源/版本损坏副本检查，其中下载批次、采集时间、生效日3项明确RED；将来源对象完整绑定到原始字节解析结果后全部GREEN。加入原有8项历史版本数学检查后，共24项通过、0跳过；不改变原始来源或本轮预测。
- 独立审查发现Windows重新检出可将5个生产文本转CRLF，导致字节指纹变化。真实`git cat-file --filters`检查先5项RED；只增加`backend/.gitattributes`五个LF约束后5项GREEN，不改变任何生产Java代码。
- 轨迹验收只验证各步损失单调、数值有限和最终梯度最优性，不声称独立复算每一个早期迭代梯度；这是审查指出的Minor边界，当前分数不受影响。
- 两次实际导出除生成时间外逐值一致；每次导出每折各拟合两次，权重和概率复现差值均为0。
- 当前产品完整`mvnw.cmd test`：650运行、0失败、0错误、3跳过，退出码0、BUILD SUCCESS，2026-10-03 12:51:56；日志`backend/target/fusion-regression.log`。跳过真实FRED、AlphaVantage黄金及GoldPriceSync接口测试，不证明外部接口当前可用。
- 修复审查问题后的交付前回归再次650运行、0失败、0错误、3跳过，退出码0，2026-10-03 13:00:32，`backend/target/fusion-delivery-regression.log`。新导出`target/fusion-after-build.json`上29项Node检查全部通过；独立CLI同时复核原导出与新导出，除生成时间外逐值一致。公开汇总的3折、36块、六分支与所有门槛逐字段匹配实际独立验收输出。
- 一次独立只读审查指出的三项Important（来源对象绑定、跨Windows检出指纹、公开逐块指标）已通过上述红绿及交付验证修正。Minor早期梯度边界明确记录，编译/导出命令已补齐。审查未确认最新2026行情、运行服务/外部接口或未来准确率；这些也不是本轮交付主张。
- 只读PostgreSQL日期早于2024-11-11的黄金日线；本轮不写数据库，不操作页面，不修改生产源代码。
- 产品基线Git为5c0f999d32309d9937fc84350bf2e0482201a9ba。未提交探针通过源码摘要另行绑定，不把基线哈希冒充包含新研究代码的构建版本。
- 原始追踪JSON只放本地忽略文件，公开代码、协议、结论，不新增公开原始行情或宏观档案。黄金日线历史修订可得性与日内宏观发布时间仍未完全认证。

## 下一轮应检验什么

本轮否定的是“固定50%宏观概率融合”这一具体方案，不能概括为宏观信息永远无用。已有证据显示日级宏观线性分支无法在这个三分类任务中有效区别涨跌，继续加大它的权重没有依据。

下一轮优先审计真实错判的收益幅度和任务口径：±0.5%之外的涨跌是否集中于现有日线/宏观无法描述的事件；比较方向判断与概率区分能力。该审计不改标签、阈值或正式模型，不编造新闻原因。若要检验新数据或新结构，先固定假设和真实可得输入；任何开发结果改善仍需冻结后在未参与选择的新时期验证。

## 复核命令

在项目根，已有合法本地真实归档与`FRED_HISTORY_DIR`时执行：

```powershell
node --test docs/research/probes/FusionMathChecks.cjs docs/research/probes/FusionAuditChecks.cjs
node docs/research/probes/verify-fusion.cjs docs/research/2026-10-03-fusion-check.json backend/target/fusion-repeat.json > backend/target/fusion-local-verified.log
```

从新检出编译、导出时，需已配置只读黄金数据库连接所用环境变量`OPSPILOT_DB_PASSWORD`及获授权的真实FRED目录。在`backend`执行（不显示凭据，已有结果不覆盖）：

```powershell
.\mvnw.cmd test
.\mvnw.cmd dependency:build-classpath "-Dmdep.outputFile=target/probe-classpath.txt"
$fusionCp = 'target/classes;target/probe-classes;' + (Get-Content -Raw target/probe-classpath.txt).Trim()
$fusionNames = @('NewtonProbe.java','TrainingProbe.java','SoftmaxFit.java','SoftmaxChecks.java','RidgeFit.java','HistorySlice.java','HistoryChecks.java','HistoryRun.java','MacroModelProbe.java','FusionMix.java','FusionScale.java','FusionChecks.java','FusionCohort.java','FusionCohortChecks.java','FusionRun.java')
$fusionSources = $fusionNames | ForEach-Object { '../docs/research/probes/' + $_ }
javac -encoding UTF-8 -cp $fusionCp -d target/probe-classes $fusionSources
if ($LASTEXITCODE -ne 0) { throw '研究探针编译失败' }
$fusionCommit = (git rev-parse HEAD).Trim()
java -cp $fusionCp com.opspilot.ai.forecast.learning.FusionRun target/fusion-local.json $fusionCommit
if ($LASTEXITCODE -ne 0) { throw '真实实验导出失败' }
```

项目根复核新导出，先保存并在最后恢复会话变量：

```powershell
$oldFusionFile = $env:FUSION_AUDIT_FILE
try {
    $env:FUSION_AUDIT_FILE = 'backend/target/fusion-local.json'
    node --test docs/research/probes/FusionMathChecks.cjs docs/research/probes/FusionAuditChecks.cjs docs/research/probes/FusionCheckoutChecks.cjs docs/research/probes/InfoMathChecks.cjs
    node docs/research/probes/verify-fusion.cjs backend/target/fusion-local.json > backend/target/fusion-local-verified.log
} finally {
    $env:FUSION_AUDIT_FILE = $oldFusionFile
}
```

真实原始档案未在本轮公开；仅有Git检出而没有本地冻结黄金参照和FRED归档时，真实复核会明确失败，不伪造数据。编译所依赖的研究辅助源已明确列出，不依赖之前残留的编译类。

测试可用`FUSION_AUDIT_FILE`指定新导出；指定路径缺失时明确失败，不静默退回旧归档。验收输出只保存到忽略的target。缺少原始归档时不生成假行情，也不能把未运行的真实验收说成通过。
