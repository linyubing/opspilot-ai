# VIX增量对照：命中下降4条，不晋级

## 结果

按[评分前固定协议](2026-10-03-vix-protocol.md)完成两种模型的三折训练、重复拟合和完整导出，并独立复算。VIX候选命中349/720，匹配训练日期价格模型353/720，减少4条、约0.56个百分点；三个折都下降。五项完整诊断门槛、三项信号门槛均未通过。

**本轮没有提高准确率，不更换正式模型，不打开最终留出集。** 这是2022-02-02—2024-11-07旧开发日期，已多轮研究，不是2026最新行情或新盲测。

| 分支 | 命中 / 720 | 准确率 | 平衡准确率 | 上涨/震荡/下跌召回 | Brier | LogLoss |
| --- | ---: | ---: | ---: | --- | ---: | ---: |
| 匹配价格20维 | 353 | 49.03% | 35.63% | 4.02% / 95.97% / 6.90% | 0.627398 | 1.042705 |
| 价格＋VIX24维 | 349 | 48.47% | 34.93% | 2.51% / 95.97% / 6.32% | 0.630584 | 1.048402 |
| 匹配训练多数类 | 347 | 48.19% | 33.33% | 0% / 100% / 0% | 0.634109 | 1.052053 |
| 冻结完整历史价格 | 350 | 48.61% | 34.98% | 5.53% / 95.97% / 3.45% | 0.627546 | 1.042947 |

完整历史价格与匹配价格训练日期不同，不能把350→353解释为新增VIX信息带来的提升；真正增量对照是353→349。指标采用未四舍五入的原值判断门槛。

## 逐折，不挑好折

| 验证开始 | 共同训练样本 | 匹配价格命中 / 240 | VIX命中 / 240 | VIX新增/丢失命中 |
| --- | ---: | ---: | ---: | --- |
| 2022-02-02 | 2915 | 106 | 105 | 2 / 3 |
| 2023-01-04 | 3155 | 139 | 137 | 1 / 3 |
| 2023-12-07 | 3395 | 108 | 107 | 3 / 4 |

第一、二折VIX概率误差略低，但第三折Brier由0.639558增至0.652026、LogLoss由1.060171增至1.080226，合并概率误差变差。不能只报告概率误差改善的两个折。

## 信号指标不是涨跌能力

固定0.55阈值覆盖189条、26.25%，其中109条正确，信号准确率57.67%。**189条全是震荡信号，同日期价格和多数类同样命中109条。** 未达到30%覆盖要求，没有同日期基线优势，涨跌召回均为0。禁止把57.67%当作未来涨跌胜率或与完整720日期基线直接比较。

## 失败原因与已有轮次的联系

1. **不是训练不收敛。** 六个模型独立重算最终最大梯度约2.44e-8—3.85e-8，均小于1e-6；每个分支重复拟合，概率差为0。两次完整实际导出除创建时间外一致。
2. **不是训练日期差异或VIX缺失替代值。** 两个分支共享2915/3155/3395训练日期，目标严格早于各验证开始；VIX为D-1已知真实历史版本，缺失725个早期日期仅影响训练。720验证日期全部完整保留。
3. **方向判断仍主要偏震荡。** 候选16次判上涨、672次震荡、32次下跌；价格19/670/31。涨跌命中由20条降至16条，上涨由8降至5、下跌由12降至11；震荡命中均333条。
4. **新增信息没有形成这套模型下的样本外增益。** 相对价格新增6次命中、丢失10次：上涨2/5、震荡3/3、下跌1/2。训练集交叉熵三折都更低，但验证合并更差，与有限训练适配而未稳定泛化一致；不将其作为唯一过拟合原因或VIX永远无用的证明。

这与前九项研究的共同问题一致：收敛修复、加权、扩大历史、非线性模型、宏观融合和两阶段结构均未补足稳定方向区分能力。VIX新增了真实信息，但这四个固定特征和固定线性关系没有解决问题。没有真实逐新闻/事件证据，不能声称具体某次错判由某条新闻引起。

## 路线决定

该候选失败保留供复现，不搜索VIX窗口、权重或正则参数补考，也不自动再加一个指标。次日黄金方向目标不变，但“在旧日级输入上继续换模型/堆指标”不能作为默认主线。

下一轮先检查原始预测时点、当前数据是否完整及可获得的真实增量信息；盘中、跨市场或新闻输入必须有真实历史来源与发布时间证明，先固定可检验假设再建模。没有这些证据时保持“尚无稳定涨跌优势”，不制造数据、不把波动预测或更长周期替代原目标。将来晋级仍需要固定方案的未参与选择日期或真实前瞻验证，而非旧720条上的选优。

## 验收与复现

公开[统计摘要](2026-10-03-vix-model-metrics.json)含完整合并、逐折、训练指标、混淆矩阵、同日期信号基线和代码/输入摘要。36个连续20条块在本地完整验收输出中保留，不新增发布逐条价格或预测。

- Java训练筛选空实现先触发断言RED；实现后8项日期/拼接检查GREEN。夹具仅是数学。
- 独立Node缩放、概率、手算梯度与有限差分4项先RED后GREEN；模型认证空实现的8项损坏拒绝检查先RED后GREEN，另有真实完整对照正常验收。
- Node研究检查与既有输入检查合计30项通过，0失败；Java研究检查不由Maven自动运行。
- 完整来源认证链先检查真实双归档与黄金来源，再独立从共同训练输入重算缩放、正则损失、梯度、六个模型训练/验证概率、基线及完整指标。两个实际导出认证通过。
- 完整Maven回归650运行、0失败、0错误、3跳过，BUILD SUCCESS，2026-10-03 19:58:09，日志`backend/target/vix-fit-regression.log`。
- Git基线`14461d8f2b947f6395c473a4aa8ac1fd0448c7a1`用于记录运行前状态，计算源码另外以SHA绑定，不冒充包含新增研究代码的提交。

根目录检查：

```powershell
node --test docs/research/probes/VixFitMathChecks.cjs docs/research/probes/VixModelAuditChecks.cjs docs/research/probes/VixAuditChecks.cjs docs/research/probes/InfoMathChecks.cjs
node docs/research/probes/verify-vix-model.cjs backend/target/vix-fit-results.json backend/target/vix-fit-repeat.json
```

重新执行时先按照输入报告准备真实归档和认证的`vix-four-vectors.json`。在backend编译并使用新的输出名，不覆盖或删除已有证据：

```powershell
$vixFitCp = 'target/classes;target/probe-classes;' + (Get-Content -Raw target/probe-classpath.txt).Trim()
javac -encoding UTF-8 -cp $vixFitCp -d target/probe-classes ../docs/research/probes/VixCohort.java ../docs/research/probes/VixCohortChecks.java ../docs/research/probes/FusionScale.java ../docs/research/probes/RidgeFit.java ../docs/research/probes/SoftmaxFit.java ../docs/research/probes/VixRun.java
if ($LASTEXITCODE -ne 0) { throw 'VIX模型编译失败' }
$vixFitCommit = git rev-parse HEAD
java '-Dfile.encoding=UTF-8' -cp $vixFitCp com.opspilot.ai.forecast.learning.VixRun target/vix-four-vectors.json target/vix-fit-new.json $vixFitCommit
if ($LASTEXITCODE -ne 0) { throw 'VIX模型运行失败' }
java '-Dfile.encoding=UTF-8' -cp $vixFitCp com.opspilot.ai.forecast.learning.VixRun target/vix-four-vectors.json target/vix-fit-new-repeat.json $vixFitCommit
if ($LASTEXITCODE -ne 0) { throw 'VIX重复模型运行失败' }
```

回到根目录对新文件调用同一`verify-vix-model.cjs`。缺少真实来源、代码指纹不一致或数学认证失败须停止，不用假数据恢复绿色结果。

## 审查与裁定

一次只读审查没有发现Critical、Important或Minor问题；审查者实际运行13项模型数学/损坏拒绝检查及完整递归来源认证，复核349/353/347/350命中、同日期信号分母和全部门槛失败。不冒充审查者重跑Java训练或完整Maven。

审查未判断供应商原始事实真实性、未来有效性、生产上线、Git推送和每个中间Hessian/SVD步骤。裁定：本轮仅认证现有真实归档、最终收敛解和统计，不新增这些证明；训练与Maven证据来自主线程，推送另外核验。若要证明未来有效或用于生产，仍需独立验收，不能用本次审查代替。
