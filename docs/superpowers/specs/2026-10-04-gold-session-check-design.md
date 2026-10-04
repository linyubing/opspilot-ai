# 黄金候选时段核验器设计

## 意图与范围

兵哥要求真实数据、可追踪失败原因、可复现迭代，而不是虚高准确率。本子任务建立能独立核验供应商日线时间口径的生产诊断能力，供后续生成与结算时间合同使用。

这属于已有数据获取链上的新诊断组件。兵哥已授权推荐方案自动实施、不重复提问；使用原生逐任务实施和只读独立审查，不创建worktree、不派实现代理。当前只保存设计和计划，实施状态不得提前标记完成。

不调整正式预测、日历、模型、特征、阈值、确认来源或数据库；不重写旧记录、不打开最终留出集模型评测。诊断结果不自动升级为正式确认、历史可得性或模型晋级证明。

## 选定方案

不选纽约17点或固定UTC小时：真实复原已经反证。采用`Australia/Sydney`、当地07:00至次日07:00的候选规则，并对每次输入核验完整小时集合和精确OHLC一致性。候选可能失败；失败必须显式输出，不填数据、不移动日期、不忽略重复行。

规则版本固定为`sydney-0700-candidate-v1`，明确写作候选规则，不宣传为交易所官方时段。日期为供应商标签，不解释为北京时间交易日或伦敦交易所日期。

## 单一职责与接口

新增生产类均位于`backend/src/main/java/com/opspilot/ai/marketdata/`：

- `GoldSession`：不可变`record(LocalDate date, Instant start, Instant end)`，`static GoldSession forDate(LocalDate date)`按IANA时区计算；不调用Clock，不判断周末或节假日。
- `GoldHourBar`：不可变`record(Instant start, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close)`。构造器拒绝null、非正价格、无效高低范围；不自动舍入。
- `GoldSessionCheckResult`：包含session、checkedAt、status、expectedHours、receivedRows、不可变missingHours列表、中文reason。status使用内部枚举`MATCHED/NOT_ENDED/MISSING_HOURS/INVALID_INPUT/OHLC_MISMATCH`；`matched()`仅表示候选与所给完整数据一致。
- `GoldSessionCheck`：无数据库/HTTP依赖的Spring组件；`GoldSessionCheckResult check(GoldDailyBar day, List<GoldHourBar> hours, Instant checkedAt)`进行确定性核验。
- `GoldSessionCheckService`：依赖现有`@Qualifier("twelveDataRestClient") RestClient`、`TwelveDataProperties`、`Clock`及核验器；`check(LocalDate date)`获取真实日线和小时线并返回结果。
- `api/GoldSessionCheckController`：只读`get /api/market-data/gold/session-check?date=yyyy-MM-dd`，不写库、不生成预测。日期绑定错误400；上游获取/结构错误复用现有`MarketDataUnavailableException`处理。

## 核验算法

null day/list/checkedAt作为非法调用抛`IllegalArgumentException`；其他输入问题返回`INVALID_INPUT`并说明原因。日线必须为XAUUSD/twelve_data/usd/troy_ounce，日期和采集时刻非空、采集不晚于checkedAt，OHLC非空正值且高低合法。

从日线标签计算候选session。checkedAt早于end返回`NOT_ENDED`；相等可继续核验，表示候选区间已经走完，不保证供应商永不修订。

输入小时行不得为null；start必须是UTC整点、在[start,end)内且唯一。重复、非整点或越界返回`INVALID_INPUT`，不能去重覆盖或自动过滤。按Instant排序，不修改调用者列表。逐小时生成期望集合；缺任何时刻返回`MISSING_HOURS`及全部缺失时刻，不设24小时常量：可为23、24或25小时。

集合完整后，首行open、末行close、最大high、最小low逐项以BigDecimal.compareTo与原生日线比较；任何差异返回`OHLC_MISMATCH`，不设epsilon、不输出原始价格。全部一致返回`MATCHED`。

`expectedHours`取候选区间整小时数，`receivedRows`取原输入列表大小；missingHours按时间升序、不可变。规则不是已公布版本证明，不能将checkedAt换成过去的日期来制造PIT。

## 真实获取与公开响应

日线请求interval=1day，start_date=date，end_date=date+1，outputsize=2；必须返回唯一的对应标签记录。小时请求interval=1h、timezone=UTC，start_date/end_date为候选UTC起止时刻，outputsize=64。仅允许明确的结束端点额外行在传入核验器前排除；其他越界、重复或错误标的/interval拒绝。日期和整点严格解析，保持十进制价格。

供应商响应可能不含exchange_timezone；不能虚构该字段，也不能把exchange_timezone当作API输出时区字段。请求UTC与固定解析规则记在诊断合同中，复原不一致仍必须失败。HTTP、结构和凭据异常不得附带原异常、带key URL或响应原文进入公开日志。

两次获取成功后用Clock捕获一次checkedAt，作为本次接收观察时刻；不是历史发布时刻。原生日线collectedAt使用该本次接收时刻，不将其写库。

HTTP响应包含date、start、end、checkedAt、ruleVersion、status、expectedHours、receivedRows、missingHours、reason、matched，以及固定警告“候选时段核验，不是历史可得性或正式预测晋级证明”。不得返回原始OHLC、key、请求URL或模型准确率。

## 验收与后续边界

必须RED/GREEN和完整裸Maven回归。测试覆盖普通24小时、2026-04-04的25小时、2026-10-03的23小时、缺一个小时、重复替代缺失、乱序、越界、非整点、未来观察/采集、合法边界相等、四项OHLC各自不一致及不可变输入/结果。

服务测试运行真实HTTP边界替身（不访问市场），验证请求时间范围、UTC、唯一日线选择、结束额外行排除、错误响应与日志脱敏；Controller验证参数和不含敏感字段。真实验收仅调用一个当前已结束标签及一个已知缺小时标签，输出状态和计数，不运行模型或改变数据。

本子任务交付后再设计正式时间合同：固定发布时窗、目标日期与区间承诺、生成前后截止校验、结算资格和旧记录可信统计分层。不能把诊断`MATCHED`直接当作这些功能已经完成。
