# 黄金闭市确认实施计划

> For agentic workers: 使用 superpowers:executing-plans 原生逐任务实施；不派实现代理。

**Goal:** 持久化并统一检查真实黄金日线的闭市确认依据。
**Architecture:** 获取入口产生不可混淆的确认元数据，仓储原子存储，读取方保留原始顺序后统一判断。旧记录不自动确认。
**Tech Stack:** Java 21、Spring Boot、JdbcTemplate、Flyway、PostgreSQL、JUnit。
**Spec:** ../specs/2026-10-03-gold-confirmation-design.md

## Global Constraints

- 不改预测标签、阈值、模型或最终留出集。
- SQL 小写，新增类写简短中文注释；不动 .env.example 和用户项目设计文件；不独立 worktree。
- 旧记录默认四项都 null，禁止按日期回填。
- 任一段交付不表示全链路完成。

## Review Focus

- 未确认覆盖不能留下旧确认，任务1真实数据库 upsert 验证。
- 批次中后行违反约束不能留下前行，任务1事务回滚验证。
- 相同绝对时刻的不同时区 offset 不能误拒绝，任务1 domain predicate 验证。
- 第一目标未确认不能跳向以后，任务2结算测试。
- 未确认中间行不能消失而缩短交易日跨度，任务2数据集窗口测试。

## 任务1：生产依据及原子存储

文件：marketdata/GoldBarConfirmation.java、GoldDailyBar.java、TwelveDataGoldBarProvider.java、JdbcGoldDailyBarRepository.java（位于 backend/src/main/java/com/opspilot/ai/）；V20__add_gold_bar_confirmation.sql；对应 marketdata 测试。
审查修正：V21__validate_gold_confirmation_observation_day.sql 同时固定 closedDay 不晚于 checkedAt UTC日，不重写已执行的 V20。
接口：GoldBarConfirmation(String source, LocalDate closedDay, OffsetDateTime checkedAt, String receiptHash)；GoldDailyBar 增加 confirmation 字段、保留十参数构造器；boolean isConfirmedAt(OffsetDateTime asOf)。saveAll 和所有读取接口签名不变。

- [x] 新增类型骨架使测试编译，旧十参数对象的 isConfirmedAt 返回 false；写供应商元数据、持久化 roundtrip、未确认覆盖、原子回滚、精度拒绝行为测试。
- [x] 执行定向测试确认断言 RED，不用编译失败充当 RED。
- [x] 获取入口对已通过校验列表附上确认依据；统一谓词实现完整日期/时间/来源校验；V20 不回填旧行；仓储批量 upsert 事务保存四字段并拒绝价格舍入。
- [x] 定向及完整 ./mvnw.cmd test 通过；只读审查完成、修正再验证718/0fail/0err/3skip。
- [ ] 显式暂存任务文件，中文提交推送（回执见本轮报告和 git）。

## 任务2：读取方共享依据

文件：forecast/GoldForecastResolutionService.java、analysis/GoldResearchSnapshotService.java、forecast/learning/GoldDatasetBuilder.java、marketdata/api/GoldDailyBarController.java（均位于 backend/src/main/java/com/opspilot/ai/）；对应测试。
已核实快照实际包为 analysis；还需纳入 forecast/backtest/BacktestRunner.java、HorizonDiagnosticService.java、HistoricalHorizonDiagnosticService.java，审计已保存快照的正式预测使用入口。
接口：使用任务1 isConfirmedAt；仓储仍返回完整日期序列，不在 SQL 预过滤。

- [ ] 写第一目标未确认但更晚行确认仍 pending、未来 checkedAt pending、同一目标确认后可结算的断言 RED。
- [ ] 快照未确认窗口拒绝，dataset 未确认特征/目标窗口跳过且不缩短 horizon，最新价未确认不显示正式数据的断言 RED。
- [ ] 历史回测/多日诊断的窗口与目标未确认时拒绝评分；旧快照不能自动升级为可信；不将今天确认元数据伪装成历史 OHLC PIT 证据。
- [ ] 实现各读取方判断；更新旧测试明确标记数学样例确认，不让旧构造器默认信任；确认生成时间审计单列，不冒充已解决。
- [ ] 定向及全量测试通过，中文提交推送。

## 任务3：真实同步与核查

工具：现有真实获取入口和数据库只读查询；先查进程/端口，不重复启动。

- [ ] 真实同步之前核实环境、迁移、可用服务；不打印 key 或带 key URL。
- [ ] 执行一次同步并核对真实确认日、时间、来源、指纹、保存行数；旧未确认行仍不可信。
- [ ] 核对快照与 pending 结算使用同一依据；不得改写已完成历史结算或重新评分旧 holdout。
- [ ] 输出实际结果、失败原因、跨轮总结和路线判断；没有新市场增益证据就明确报告未提高。
