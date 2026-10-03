# VIX历史版本采集与窗口核验

## 结论

真实采集成功，具备进入下一轮固定增量实验的数据条件；**本轮没有训练新模型或提高准确率**。原720条旧开发验证日期均有21条当时已知VIX观测，但早期725条价格输入没有可认证版本，公平对照必须使用相同训练日期。

本轮延续[来源审计](2026-10-03-risk-source-audit.md)，不修改生产加载器、数据库、标签、正式模型或晋级规则，不打开最终留出集。

## 实际数据

- 序列VIXCLS，观测请求2007-11-01至2024-11-08，版本范围从首个可认证日2010-11-22至2024-11-08；15个连续按年批次，每次采集16个真实HTTP请求。
- 两次采集各39595条版本记录，除采集时间外所有字段逐值相同。条数包含缺失及跨批次截断版本，不是独立交易日数。
- 本地第一批`backend/target/vix-history-first`，重复批`backend/target/vix-history-repeat`；两者从不存在的新目录发布，不覆盖旧证据。原始数据不加入Git。
- 第一批文件SHA256：`ea703773449991e367efefe5e6b2087fea194f31bb5c3bdf04c0218eafb0b139`。
- 重复批文件SHA256：`b46228c267ae00c22e2a416ff7848894892cfc0dfb3cb0854b22d18ef65100f8`。采集时间不同导致字节摘要不同，不把两摘要不同误报为历史数值不同。

## 日期隔离与覆盖

现有生产`FredHistory`直接加载真实VIX归档，按D-1已知版本选择21条观测；独立Node `InfoMath.recent()`逐个日期重选，核对窗口数量、最新观测日期及包含观测日期/UUID/原值的完整窗口SHA。两个实现对全部4361条输入一致。

| 范围 | 无当时已知观测 | 最新观测日龄1—3自然日 | 日龄4—7自然日 |
| --- | ---: | ---: | ---: |
| 全部4361条冻结输入 | 725 | 3164 | 472 |
| 原720条验证 | 0 | 709 | 11 |

首个完整窗口的黄金基准日为2010-11-23。版本当时可见并不等于所有观测当天才发生；不会将该快照提供的2007年数据放入2007年预测输入。没有不足21条的部分窗口，也没有日龄超过7天的已完整窗口。日龄不冒充发布时间或交易日数。

| 验证折起点 | 与VIX完整窗口相交且目标已结算的训练样本 |
| --- | ---: |
| 2022-02-02 | 2915 |
| 2023-01-04 | 3155 |
| 2023-12-07 | 3395 |

这里只统计训练可用日期，没有拟合模型、调整阈值或根据验证分数删样本。黄金原始参照先通过`verify-tree.cjs`来源/模型认证；目标严格早于2024-11-11。黄金历史修订及盘中发布时间的旧边界仍然存在。

## 已实现的采集保障

- 按年分块与分页，校验响应输出类型、有效日期、分页偏移、稳定总数、实际返回数量、批次区间、有限正值和版本不重叠；`.`原样保留，不填补。
- 全部批次完成且校验后才发布新目录；先写唯一pending目录，最后重命名。不覆盖现有目录，真实路径限制在本地target内。
- 凭据仅来自环境变量，不打印请求URL、服务器错误正文或原始网络异常。下载器失败时不生成有效归档。
- 测试夹具仅是接口边界数学样例，不是行情，不参与评分。外部HTTP在单元测试中替换；真实生产解析、文件发布、独立重选与实际采集均另行运行。

## 验收

- 采集/校验初始空实现观察5项断言RED，再实现GREEN；目录发布初始空实现观察返回值断言RED，再GREEN。另补跨分页与总数变动检查。
- `RiskArchiveChecks.cjs`8项 + 已有`InfoMathChecks.cjs`8项：16运行，0失败、0跳过。
- 独立只读审查指出目录链接可能使输出越界。主代理分别用真实Windows junction复现Node外部mkdir和Java外部写入，两个断言均先RED；修改为Node先认证最近存在祖先、Java先认证真实父目录后，共18项检查全GREEN。审查者只读检查，不把主代理实测冒充审查者实测。
- 真实下载两次退出0；Java新编译核验器退出0，4361窗口导出；独立CLI退出0，所有窗口与重复归档一致。
- 独立CLI增加原黄金链认证时首次漏传归档参数而失败，补齐真实文件参数后重新通过；该工具调用错误不是市场数据缺失，不隐瞒失败过程。
- 完整`mvnw.cmd test`：650运行、0失败、0错误、3跳过，退出0、BUILD SUCCESS，2026-10-03 14:00:20，日志`backend/target/risk-archive-regression.log`。跳过接口测试不证明其他外部服务当前可用。
- 路径修复后完整交付回归再次650/0失败/0错误/3跳过，退出0，2026-10-03 14:06:21，`backend/target/risk-delivery-regression.log`。重新导出与原窗口逐值一致，独立真实验收再次通过；公开汇总含来源及直接代码依赖SHA，不含原始值或逐日记录。

## 复核命令

已配置FRED_API_KEY的项目根目录，用新的目录名下载，不能删除或覆盖已有研究证据：

```powershell
node --test docs/research/probes/RiskArchiveChecks.cjs docs/research/probes/InfoMathChecks.cjs
node docs/research/probes/download-risk.cjs backend/target/vix-new-first
node docs/research/probes/download-risk.cjs backend/target/vix-new-repeat
```

在backend，先构建生产类与依赖classpath：

```powershell
.\mvnw.cmd test
.\mvnw.cmd dependency:build-classpath "-Dmdep.outputFile=target/probe-classpath.txt"
$riskCp = 'target/classes;' + (Get-Content -Raw target/probe-classpath.txt).Trim()
javac -encoding UTF-8 -cp $riskCp -d target/probe-classes ../docs/research/probes/RiskCheck.java
if ($LASTEXITCODE -ne 0) { throw 'VIX核验编译失败' }
java -cp ('target/probe-classes;' + $riskCp) com.opspilot.ai.macrodata.RiskCheck target/vix-new-first/VIXCLS.json target/vix-new-windows.json
if ($LASTEXITCODE -ne 0) { throw 'VIX生产窗口核验失败' }
```

项目根独立复核（需要已存在的合法冻结黄金参照）：

```powershell
node docs/research/probes/verify-risk.cjs backend/target/vix-new-first backend/target/vix-new-repeat backend/target/vix-new-windows.json
node --test docs/research/probes/RiskPathChecks.cjs
```

缺少真实归档、黄金链依赖或环境条件就明确失败，不能用数学夹具替代真实核验。

## 下一轮

先冻结有限VIX特征和同日期训练对照，随后评分。保留全历史价格参照，以区别新增信息与训练期变化；即使旧开发区间改善也不自动晋级。当前只完成真实输入条件，不承诺VIX能识别黄金涨跌。
