# VIX四特征输入验收

## 当前结果

按[评分前协议](2026-10-03-vix-protocol.md)实现真实VIX水平及1、5、20观测期变化。生产`FredHistory`选择历史窗口，Java计算特征，独立Node重选版本并复算全部窗口与公式。

4361个冻结黄金输入日期中，3636个完整21观测窗口，725个早期日期保持null，不补零。原720个开发验证日期全部完整保留。三折可用匹配训练数2915、3155、3395，已结算目标严格早于对应验证开始。

**本交付没有训练VIX模型、没有新准确率结果、没有晋级正式模型。** 已建立下一步同训练日期价格/VIX对照所需的真实输入，不能将窗口验收通过说成预测能力改善。

## 边界

- 四特征定义固定，变化期是非缺失观测期，不是自然日。
- 所有21条值必须是有限正数，窗口不完整保留缺失；比值溢出明确拒绝。
- 历史版本采用D-1有效区间，未使用预测日新版本或未来观测；不声称具备盘中发布时间精度。
- 只读取已认证真实归档和已冻结黄金研究输入，目标早于2024-11-11，不查询新行情、不写数据库、不读取最终留出集。
- 原始特征导出至新的本地target文件，不覆盖已有结果；真实父路径限制在target内，使用CREATE_NEW写入。
- 两次完整导出逐字段相同，摘要见[公开元数据](2026-10-03-vix-vectors-metrics.json)，不新增发布逐条行情/特征。

## 验证证据

- Java空实现先触发手算公式断言RED；实现后10项手算及非法窗口检查GREEN。夹具仅是数学，不是行情。
- Node认证空实现的9项测试全部RED；实现后9项GREEN，拒绝特征变化、完整窗口伪造缺失、缺失补零、日期/目标替换、删日期和源码/协议摘要变化。
- 与既有日期选择器8项合计17项Node检查通过，0失败。
- 两次Java实际导出各4361条，退出0；完整独立CLI先认证归档摘要、重复归档及黄金来源链，再验收窗口、特征和训练日期，退出0。
- 完整`mvnw.cmd test`：650运行、0失败、0错误、3跳过，BUILD SUCCESS，2026-10-03 19:47:59，日志`backend/target/vix-vectors-regression.log`。Maven没有自动运行研究目录中的Java/Node检查，二者另行运行。
- 一次Java运行命令未给`-Dfile.encoding=UTF-8`参数加引号，PowerShell拆分导致找不到启动类；加引号后通过。该命令错误不是VIX缺失或模型问题。

单独`VixAuditChecks`测试计算与损坏拒绝，不能代替完整CLI的来源认证。公开摘要绑定直接计算依赖，完整来源链仍须执行原验收器。

## 复现

先准备现有真实归档、重复归档和完整黄金来源链；缺少真实文件时明确失败，不能生成假行情。仓库根目录编译：

```powershell
$vixCp = 'backend/target/classes;' + (Get-Content -Raw backend/target/probe-classpath.txt).Trim()
javac -encoding UTF-8 -cp $vixCp -d backend/target/probe-classes docs/research/probes/VixFeatures.java docs/research/probes/VixFeatureChecks.java docs/research/probes/RiskVectors.java
if ($LASTEXITCODE -ne 0) { throw 'VIX特征编译失败' }
java '-Dfile.encoding=UTF-8' -cp backend/target/probe-classes com.opspilot.ai.macrodata.VixFeatureChecks
if ($LASTEXITCODE -ne 0) { throw 'VIX公式检查失败' }
```

在backend执行，输出名称必须尚不存在，不能删除旧证据后重跑：

```powershell
$vixCp = 'target/classes;target/probe-classes;' + (Get-Content -Raw target/probe-classpath.txt).Trim()
java '-Dfile.encoding=UTF-8' -cp $vixCp com.opspilot.ai.macrodata.RiskVectors target/vix-history-first/VIXCLS.json target/vix-four-new.json
if ($LASTEXITCODE -ne 0) { throw 'VIX真实特征导出失败' }
java '-Dfile.encoding=UTF-8' -cp $vixCp com.opspilot.ai.macrodata.RiskVectors target/vix-history-first/VIXCLS.json target/vix-four-new-repeat.json
if ($LASTEXITCODE -ne 0) { throw 'VIX重复导出失败' }
```

根目录运行完整认证：

```powershell
node docs/research/probes/verify-vix-vectors.cjs backend/target/vix-history-first backend/target/vix-history-repeat backend/target/vix-four-new.json backend/target/vix-four-new-repeat.json
if ($LASTEXITCODE -ne 0) { throw 'VIX独立认证失败' }
```

本地已有`vix-four-vectors.json`时可运行17项检查：

```powershell
node --test docs/research/probes/VixAuditChecks.cjs docs/research/probes/InfoMathChecks.cjs
```

下一步依协议执行同日期匹配训练、独立拟合核验及逐折错误复盘。不得因看到分数而新增特征组合或调整阈值。

## 独立审查与裁定

一次只读审查未发现Critical、Important或Minor问题；审查者实际运行9项Node检查和完整双归档/重复导出认证。Java数学检查、编译和Maven结果由主线程实际运行，不冒充审查者复跑。

审查未判断的范围及裁定：本交付不含模型拟合、效果或晋级，留待下一步；没有新增远端下载，认证只证明现有本地双归档与来源链一致，不新增证明远端数据真实；不读保护文件及无关旧模型。路径防护针对现存链接与意外覆盖，本地研究流程不宣称防恶意并发替换父目录的TOCTOU攻击，若以后作为多用户服务运行必须重新评估。审查不证明RED历史或编译时序；前述证据来自本轮主线程操作。
