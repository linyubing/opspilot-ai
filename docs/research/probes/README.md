# 训练体检研究探针

这两个探针属于一次性诊断，不是生产模型、正式模型选型器或自动交易代码。保存源码只是为了复核报告；没有 Spring Bean、对外接口或数据库写入。真实数据不足时停止，不补造价格或宏观特征。

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
