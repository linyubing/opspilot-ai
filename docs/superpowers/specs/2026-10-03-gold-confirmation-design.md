# 黄金闭市确认依据设计

目标是让真实价格可用性和预测结算可信，不以工程改动冒充方向准确率提升。
沿用已核实的 quote eod=true 合同，不改预测标签、阈值、模型或最终留出集。

## 数据与写入

新增 GoldBarConfirmation(source, closedDay, checkedAt, receiptHash)。source 固定为
twelve_data_quote_eod_v1；receiptHash 是 source、确认日、规范化 OHLC 以换行连接的 UTF-8 SHA-256。
这不是原始 HTTP 正文哈希，不包含 apikey，不在公开报告发布原始价格。
每根已通过获取入口校验的日线携带该依据；该依据只表示本次供应商闭市边界，不证明历史 OHLC 当时可得的版本。
GoldDailyBar 保留旧十参数构造器，产生 confirmation=null，不能自动信任。

V20 为 gold_daily_bar 增加 nullable closed_day、confirmed_at、confirmation_source、confirmation_hash。
旧记录默认四项都 null，禁止按日期回填。四字段全部为空或完整；确认日不早于日线日期，确认时刻不早于采集时刻。
saveAll 原子写入价格与确认字段，普通未确认覆盖将四字段清空；失败整批回滚。
不允许 PostgreSQL numeric(19,8) 静默舍入已确认价格，超出有效八位小数拒绝写入。

## 统一信任判断与读取

GoldDailyBar.isConfirmedAt(asOf) 检查来源、指纹格式、时间和日期边界。
checkedAt 不能晚于 asOf，不能早于 collectedAt；priceDate 不晚于 closedDay 或 asOf 的 UTC 日期。
closedDay 不能晚于 checkedAt 的 UTC 日期；错误的“提前确认未来日”不能因时光流逝而变成有效依据，V21 增量约束同一条件。
数据库读取不先删除未确认行，保持真实顺序和间隔。
结算先取基准日后第一行再判断；未确认就等待，不跳过它。
快照拒绝未确认窗口；历史样本逐个检查特征窗口和目标窗口，缺确认就跳过样本，不能缩短 horizon。
最新价 API 不把未确认记录显示成正式价。
历史 BacktestRunner、HorizonDiagnosticService、HistoricalHorizonDiagnosticService 也是直接读取方，必须检查窗口及目标确认，不从仓储预过滤日期。
确认元数据是今天核验的供应商闭市边界，不是历史当时已公布的 OHLC vintage；历史快照不能把 checkedAt 改成历史 asOf 来伪造可得性。
旧的已持久化研究快照/预测也没有自动补确认的资格，接入生成入口时需审计其使用路径。

## 实施分段与不变要求

第一段生产获取、domain predicate、迁移和 roundtrip；第二段接入读取方及旧行保护；第三段真实同步与核查。
任一段交付不表示全链路完成。每段先得到行为断言 RED，再实现 GREEN，全量 Maven 回归、只读审查、中文提交推送。
SQL 小写，新增类写简短中文注释；不动 .env.example 和用户项目设计文件；不独立 worktree。
只读或测试隔离数据不视为最新市场准确率。事前预测生成时间和历史回测 PIT 仍需单独核查。
