# 训练体检研究探针

这些探针属于一次性诊断，不是生产模型、正式模型选型器或自动交易代码。保存源码只是为了复核报告；没有 Spring Bean、对外接口或数据库写入。真实数据不足时停止，不补造价格或宏观特征。

## 固定问题与方法

问题：现有 OHLC 逻辑回归的五轮训练是否已足够？增加训练轮数能否同时改善训练误差和训练内部的后续时间段，而非只改善拟合？

- SQL 只取 `twelve_data / XAUUSD`、`price_date < 2024-11-11`，共 4382 根真实日线。2024-11-08 只用于结算前一天标签。旧外层开发集和最终留出集都不读取。
- 21 根预热后得到 4361 条样本。使用既有 `GoldFeatureCalculator` 的 20 项 OHLC 特征和 `GoldForecastRule` 的 ±0.5% 三分类定义，不使用、也不补齐宏观特征。
- 从这段初始训练期末尾取连续三个 240 条块作为内部诊断区间，每块训练只用该块之前已经结算的样本；按 `targetDate < blockStart` 清除边界标签。
- 每折拟合自己的均值和总体标准差。固定 AdaGrad(1.0, 0.1)、种子 12345、batch=1、无类别权重、无正则化。只比较预定 5/20/80 轮，不增加搜索范围。
- 每个检查点从同一随机种子重新训练。五轮显式训练器必须与 `LogisticRegressionTrainer()` 完全一致，并验证重复运行一致。
- 记录训练/验证完整样本准确率、三方向召回、Brier、LogLoss、梯度范数；另外报告 0.55 信号覆盖率。梯度范数不是参数误差的保证，80 轮不等于已收敛。
- 基线方向取训练期多数类；概率使用训练期三类频率，不能与旧报告的 one-hot 基线概率误差混比。
- 不把内部诊断最优点作为正式候选，不把 720 条内层结果与旧 240 条外层分数直接相减。此次诊断固定模型覆盖整块，不是每 20 日重训。

## 在 PowerShell 中复核

需要现有 JDK 21、Maven、PostgreSQL 和 `OPSPILOT_DB_PASSWORD` 环境变量。运行目录是 `D:\workFile\demo-ai\backend`。

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd -q -DskipTests compile dependency:build-classpath '-Dmdep.outputFile=target/probe-classpath.txt'
$probeCp = 'target/classes;' + (Get-Content target/probe-classpath.txt -Raw).Trim()
javac -encoding UTF-8 -cp $probeCp -d target/probe-classes ../docs/research/probes/TrainingProbe.java
$probeCp = 'target/probe-classes;' + $probeCp
$probeCommit = (git rev-parse HEAD).Trim()
java -cp $probeCp com.opspilot.ai.forecast.learning.TrainingProbe target/training-health-repeat.json $probeCommit
```

源码和真实日线均记录 SHA-256。复跑输出到 `target`，不覆盖已归档报告。原始训练仅记录当时的产品 Git 哈希；探针本身以 `sourceSha256` 标识，可与本目录源码核对。数据库若发生历史价格修订，指纹可能变化，应当中止与旧结果直接比较。

独立复算归档结果：

```powershell
cd D:\workFile\demo-ai
.\docs\research\probes\verify-probe.ps1
```

`fred-vintage.ps1` 使用环境变量 `FRED_API_KEY` 对官方历史版本执行四次只读请求，不打印密钥、不写数据库。归档文件已存在时拒绝覆盖。请求失败时也不输出可能含 API Key 的请求 URL。

## 不能据此声称什么

- 不能证明未来命中率提高，不能用于买卖或重仓。
- 不证明 80 轮已经收敛；没有比较独立收敛求解器，也没有评价正则化。
- 日线是当前数据库保存的真实历史价格，不是带历史修订版本和日内公布时刻的逐时快照。
- `promotionReady` 是旧指标类型的类别完整性字段，不代表本诊断批准晋级。

## 2026-09-30 历史版本和收敛参照

`fred-history.ps1` 下载不晚于 2024-11-08 的 FRED 历史版本区间，只保存到 `backend/target/fred-history`。缺少早期版本时不回填，缓存存在时验证范围后复用。`verify-vintage.ps1` 使用官方单日接口做十个时点核验。

已有原始缓存的归档在 `docs/research/2026-09-30-fred-history.zip`。首次复核时可用 `Expand-Archive` 解压到空的 `backend/target/fred-history`，随后核对报告中的 SHA-256；不要覆盖已有不同指纹的数据。

`VintageProbe.java` 用只读内存适配器复用产品的 36 特征计算链，配对旧最新值和历史版本值，两侧训练日期完全一致。`ConvergenceProbe.java` 只比较求解充分性，未收敛时不计算参照模型验证分数。两者均属于研究探针，尚未接入产品。

```powershell
cd D:\workFile\demo-ai\backend
$probeCp = 'target/probe-classes;target/classes;' + (Get-Content target/probe-classpath.txt -Raw).Trim()
javac -encoding UTF-8 -cp $probeCp -d target/probe-classes ../docs/research/probes/TrainingProbe.java ../docs/research/probes/VintageProbe.java ../docs/research/probes/ConvergenceProbe.java
$probeCommit = (git rev-parse HEAD).Trim()
java -cp $probeCp com.opspilot.ai.forecast.learning.VintageProbe target/vintage-repeat.json $probeCommit
java -cp $probeCp com.opspilot.ai.forecast.learning.ConvergenceProbe target/convergence-repeat.json $probeCommit
cd ..
.\docs\research\probes\verify-vintage-results.ps1
```

三个脚本拒绝覆盖已有归档核验结果。复跑应使用新的 `target` 输出文件；源码以 LF 保存以维持字节指纹。参照优化过程中的 `NOT_CONVERGED` 是本次真实失败结果，脚本完成不代表收敛成功。

上述旧探针绑定当时提交 `f1b27fd`；历史版本输入产品接入后，旧 `VintageProbe` 构造器不再兼容当前 API。复核旧结果时使用其原提交，不要为适配新代码而改写已经记录摘要的探针。

## 2026-10-02 充分求解参照

`NewtonProbe` 和 `NewtonRun` 是离线诊断，不是新增产品模型。使用与训练体检相同的真实数据、目标函数和三个内层区间，不使用验证指标停止训练。数学测试在 `NewtonProbeChecks`，含解析梯度/Hessian 的差分校验。详细限制和结果见 [诊断报告](../2026-10-02-newton-check.md)。

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd -q -DskipTests compile dependency:build-classpath '-Dmdep.outputFile=target/probe-classpath.txt'
$probeCp = 'target/classes;' + (Get-Content target/probe-classpath.txt -Raw).Trim()
New-Item -ItemType Directory -Force target/newton-classes | Out-Null
javac -encoding UTF-8 -cp $probeCp -d target/newton-classes ../docs/research/probes/TrainingProbe.java ../docs/research/probes/NewtonProbe.java ../docs/research/probes/NewtonProbeChecks.java ../docs/research/probes/NewtonRun.java
javac -encoding UTF-8 -cp ('target/newton-classes;' + $probeCp) -d target/newton-classes ../docs/research/probes/NewtonDenseChecks.java
$probeCp = 'target/newton-classes;' + $probeCp
java '-Dfile.encoding=UTF-8' -cp $probeCp com.opspilot.ai.forecast.learning.NewtonProbeChecks
java '-Dfile.encoding=UTF-8' -cp $probeCp com.opspilot.ai.forecast.learning.NewtonDenseChecks
java '-Dfile.encoding=UTF-8' -cp $probeCp com.opspilot.ai.forecast.learning.NewtonRun target/newton-local.json (git rev-parse HEAD)
node ../docs/research/probes/verify-newton.cjs target/newton-local.json
```

输出路径必须尚不存在。真实日线若不再匹配报告指纹会停止。可再用另一个输出名重复运行，并把两个 JSON 路径作为 Node 脚本的第二、第三个命令行参数，检查全部折内结果逐值相同。
