# 不确定性与震荡类别：单规则候选准备

## 当前结果

完成一个未晋级候选及真实冻结输入的离线导出，尚未调用模型或评分。正式提示词v2、生成服务、目标合同、数据放行和晋级规则均未改变。不能说准确率已经提高。

上一轮[来源审计](2026-10-04-forecast-input-audit.md)已排除用旧两条失败记录直接选优：价格口径不同且完整黄金窗口缺失。本轮不重跑旧错误日期，不读取最终留出集，不以测试数量充当预测效果。

## 固定假设与唯一变化

待检验假设：正式v2的“高波动优先NEUTRAL”可能诱导模型把证据不确定误当作真实涨跌幅位于±0.5%区间。9月18日旧推理支持检查这个问题，但不是已证明的根因，也不是候选能改善的证据。

基线 `gold-direction-forecast-prompt-v2` 第7条保持：

> 7. 波动率较高时，优先考虑 NEUTRAL 或反转风险。

候选 `gold-neutral-contract-candidate-v1` 仅将第7条替换为：

> 7. 波动率较高只表示价格变动可能更大，不能因此优先选择 NEUTRAL；证据不确定不等于涨跌幅位于 [-0.5%, 0.5%]，仍须按三方向合同选择最有依据的类别，并在 reasoning 中说明不确定性。

仍为同一个三分类问题，没有添加NO_SIGNAL，没有要求强制上涨/下跌。第8条“证据只支持轻微涨跌则选NEUTRAL”保留。候选是否改变模型行为及是否更准确，必须通过后续配对实测回答。

`GoldForecastPromptBuilder`共用同一模板，规则作为格式化参数注入，不对快照事实做全局替换。正式生成仍调用 `build()`；`buildCandidate()`只由离线研究探针使用。没有添加生产候选开关、接口或自动切换逻辑。

## 真实输入归档

文件：[真实快照及两版完整提示词](inputs/2026-10-04-neutral-prompt-pair.json)。

- 快照：`26d256f3-7614-4683-9bb5-c78162a1453b`
- 黄金基准日：2026-10-02，研究版本 `gold-multifactor-confirmed-v3`
- 原快照21根确认黄金日线完整保留；探针调用现有 `GoldSnapshotInput.matches()`复算核对窗口及指标。
- 导出标签为 `researchOnly=true`、`status=NOT_SCORED`，不是预测记录；exportedAt不是模型输出发布时刻。
- 宏观保留该快照当次保存的指标和解释，不重新查询修订后的数据库。没有补造21根宏观历史版本链；该输入不冒充历史精确时点认证。
- 基线提示词UTF8 SHA-256：`6c23e30aa82a088ecb984c0f5e782015d7276821125a941bf052de3073da933e`
- 候选提示词UTF8 SHA-256：`caa3f2f2d7e57e7179913c1961c0070da93c974c208cfa3b588daa22208aa6dd`

两个摘要由Java生成，落盘后通过PowerShell的SHA256独立复算；候选第7条还原成基线第7条后，完整文本逐字一致。摘要只证明本批内容可追溯，不是供应商身份或预测资格认证。

## 验证

| 检查 | 实际结果 |
| --- | --- |
| RED：候选暂委托正式版 | 5项，2失败、0错误；候选内容和摘要没有变化，不是找不到类的编译失败 |
| GREEN：提示词与正式生成定向回归 | 20项，0失败0错误 |
| 全量 `mvnw.cmd test` | 864项，0失败0错误、3跳过，MAVEN_EXIT=0 |
| 原v2固定测试输入完整摘要 | `365f6f42689df76c50ecc6e863dd84ed64a3981867d5e188aea0ec89e9645d08`保持不变 |
| 真实离线导出 | 现有v3快照读取、窗口匹配、两版提示词生成成功；无模型调用 |
| 不完整真实旧快照 | `e350e40d-8b5d-4390-af22-cdbc69169f3b`退出1，明确SNAPSHOT_INPUT_INCOMPLETE，无输入导出 |
| 数据库预测计数 | 仍2条、均resolved，没有新增预测 |

审查后再次裸全量回归仍为864项、0失败0错误、3跳过、MAVEN_EXIT=0；最终日志 `backend/target/neutral-prompt-final-suite.log`，完成2026-10-04 19:13:50+08。首次全量日志 `neutral-prompt-full-suite.log`保留。探针不启动Spring应用，不运行Flyway和调度；JDBC显式repeatable read只读事务，最后rollback。

一次独立只读审查结论Yes，仅允许作为研究候选保存，无Critical或Important问题。审查者独立复算归档的两个摘要，确认两版46行文本仅第35行规则7不同，正式生成路径仍只调用build。审查未重跑Maven、连接数据库或判断模型效果、供应商真实性、历史可得性；这些限制保留，不冒充已独立验收。本轮主代理真实执行的回归、只读导出与旧快照拒绝覆盖相应工程行为，仍不覆盖候选预测效果。

离线导出首次发现Windows终端按本地代码页解码Java UTF8输出，中文内容与声明摘要不能一致复核，未发布这份内容。改为ASCII Base64封装后再解码、复算并归档。另一次启动错误来自PowerShell未对 `-Dfile.encoding=UTF-8` 加引号，未执行数据库查询；下面命令已修正。这些工程错误不是模型实验失败。

## 复现探针

[探针源码](probes/NeutralPromptProbe.java)只读取指定快照，无任何模型凭据依赖；数据库密码从已有环境变量读取，不写入文件。先在backend完成编译及依赖classpath生成，再在项目根目录执行：

```powershell
# 若不存在classpath文件，可在backend执行：
# .\mvnw.cmd '-DskipTests' compile dependency:build-classpath '-Dmdep.outputFile=target/probe-classpath.txt'
$promptClassPath = 'D:\workFile\demo-ai\backend\target;D:\workFile\demo-ai\backend\target\classes;' + (Get-Content backend/target/probe-classpath.txt -Raw).Trim()
& javac -encoding UTF-8 -cp $promptClassPath -d backend/target docs/research/probes/NeutralPromptProbe.java
if ($LASTEXITCODE -ne 0) { throw '探针编译失败' }
$lines = & java '-Dfile.encoding=UTF-8' -cp $promptClassPath NeutralPromptProbe 26d256f3-7614-4683-9bb5-c78162a1453b
if ($LASTEXITCODE -ne 0) { throw '探针导出失败' }
$line = $lines | Where-Object { $_.StartsWith('PROMPT_PAIR_BASE64=') }
if (@($line).Count -ne 1) { throw '缺少唯一的导出结果' }
$pair = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($line.Substring('PROMPT_PAIR_BASE64='.Length))) | ConvertFrom-Json
$pair.status
```

复现时exportedAt会变化；原快照与两版提示词摘要应一致，否则不得当作同一对照输入。探针不是预测接口：生成了提示词不等于有明天的行情预测。

## 后续评测约束

上游模型余额不足尚未恢复，本轮不重复探测收费接口。候选尚无模型输出、配对样本、分数或晋级资格。

后续实测必须使用相同输入、同一模型及固定模型参数，两版在目标开始前各请求一次，保存原始输出和完成时刻；单边失败不能补考择优。冻结日期清单与停止规则后再看成绩，不使用旧两条错误选优。报告配对新增/丢失命中、全部方向召回和相同日期基线；无原始概率时不计算Brier/LogLoss。

当前Sydney时段仍只是候选合同；未获官方来源认证之前，即使成功前瞻生成也必须保留资格限制。没有明确的独立验证，不改正式模型。若该单一假设也失败，结合多轮路线复盘评估信息与目标是否支持方向优势，不进行无限提示词搜索。
