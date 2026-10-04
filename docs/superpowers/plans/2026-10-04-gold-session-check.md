# 黄金候选时段核验器实施计划

> **For agentic workers:** 使用superpowers:executing-plans原生逐任务实施；实现由主代理完成，只派只读审查员，不创建worktree。

**Goal:** 用真实日线和小时线核验候选时段，明确输出不完整或不一致原因，不自动升级正式信任。

**Architecture:** 纯时间/数学核验器与现有RestClient的只读获取服务分离；独立GET诊断接口，无数据库写入与模型调用。

**Tech Stack:** Java 21、Spring Boot、RestClient、JUnit、MockRestServiceServer。

**Spec:** ../specs/2026-10-04-gold-session-check-design.md

## Global Constraints

- 规则为Australia/Sydney、当地07:00至次日07:00，版本`sydney-0700-candidate-v1`；不是供应商官方最终性声明。
- 不改正式预测、日历、模型、阈值、确认来源或数据库；旧结果只读，最终留出集模型评分封存。
- 不补缺小时、不去重覆盖、不容忍价格误差、不输出原始OHLC/密钥/带key URL。
- 新生产类简短中文Javadoc、SQL小写（本任务无SQL）；不读或改`.env.example`及用户设计文件。
- 每任务串行RED/GREEN、裸`./mvnw.cmd test`、只读审查、显式文件提交和中文推送。

## Review Focus

- 悉尼切换日不是固定24小时：任务1验证23/25小时。
- 重复行不能凑够数量掩盖缺小时：任务1验证INVALID_INPUT而非MATCHED。
- 接口可能包含end额外行：任务2只排除精确end，其余越界拒绝。
- 响应时区元字段缺失不是UTC输出证明：任务2验证固定请求UTC与严格解析，一致性失败仍返回失败。
- 市场请求异常不能泄露apikey：任务2验证公开异常消息、日志及HTTP响应不含敏感标记。

## 任务1：纯候选时段与完整性核验

文件：`backend/src/main/java/com/opspilot/ai/marketdata/GoldSession.java`、`GoldHourBar.java`、`GoldSessionCheckResult.java`、`GoldSessionCheck.java`；测试位于相同test包，分别`GoldSessionTests.java`、`GoldSessionCheckTests.java`。

接口按Spec的record及`forDate/check/matched`签名实施；GoldSessionCheck标注@Component但无外部副作用。missingHours用List.copyOf。

- [ ] 创建可编译的类型及行为断言；新测试只使用标注为数学样例的数据，不冒充真实行情。
- [ ] 时间字面量断言：2026-01-15 start=2026-01-14T20:00Z/end=2026-01-15T20:00Z；2026-04-04 start=2026-04-03T20:00Z/end=2026-04-04T21:00Z且25小时；2026-10-03 start=2026-10-02T21:00Z/end=2026-10-03T20:00Z且23小时。
- [ ] 手工构造整点数学序列：完整集合MATCHED；移除2026-04-04T15:00Z为MISSING_HOURS且missingHours只含该Instant；用重复行替代则INVALID_INPUT；乱序仍MATCHED且输入原序列不变。
- [ ] 逐项断言：NOT_ENDED、end相等可继续、未来collectedAt INVALID_INPUT、越界/非整点INVALID_INPUT、四项价格各自不一致OHLC_MISMATCH、结果列表不可变。
- [ ] 运行`./mvnw.cmd -Dtest=GoldSessionTests,GoldSessionCheckTests test`，确认是行为断言RED，不以找不到类/编译失败替代。
- [ ] 实现悉尼区间计算、精确小时集合和十进制聚合；不提供市场日历或自动确认功能。
- [ ] 定向GREEN、裸全量回归及只读审查通过后，显式暂存上述文件，提交`feat: 增加黄金候选时段核验`并推送；记录实际数字。

## 任务2：真实只读获取及HTTP诊断

文件：`backend/src/main/java/com/opspilot/ai/marketdata/GoldSessionCheckService.java`、`api/GoldSessionCheckController.java`；测试`marketdata/GoldSessionCheckServiceTests.java`、`marketdata/api/GoldSessionCheckControllerTests.java`。

接口：服务构造器消费`@Qualifier("twelveDataRestClient") RestClient`、TwelveDataProperties、Clock及GoldSessionCheck；`check(LocalDate date)`返回GoldSessionCheckResult。Controller的GET `/api/market-data/gold/session-check`返回嵌套record响应，字段按Spec，不创建独立不必要DTO文件。

- [ ] 写MockRestServiceServer行为测试：日线唯一标签、小时起止UTC、outputsize=64、严格十进制与时间解析；提供数学HTTP样例，断言实际MATCHED/缺失/不一致状态，而非仅断言mock被调用。
- [ ] 断言精确end额外行被排除，其他越界/重复拒绝；空/错误标的/错误interval/HTTP异常转中文MarketDataUnavailableException，包含模拟apikey的原异常不得透传。
- [ ] Controller断言合法日期200、非法日期400、响应含固定警告及候选计数、不含OHLC或凭据；全程无仓储和模型依赖。
- [ ] 定向行为RED后实施两次请求及一次checkedAt捕获，返回规则版本；不写库，不启动调度，不更新确认SOURCE。
- [ ] 定向GREEN和裸全量回归，独立只读审查通过后提交`feat: 提供黄金时段只读核验接口`并推送。

## 任务3：真实接口验收与跨轮结论

- [ ] 查8080端口和进程；如需自启服务，明确关闭两种定时任务，不重复启动或停止用户进程。
- [ ] 调用诊断接口日期2026-10-02，检查start/end、24小时和MATCHED；若数据变动导致失败，报告真实失败，不修改样例或降低要求。
- [ ] 调用已知缺口标签2026-04-04，检查25小时及MISSING_HOURS/真实最新状态，禁止补数据凑MATCHED。
- [ ] 只输出状态、日期、计数、缺失时刻；记录本次Git hash和checkedAt，不输出价格、密钥或带key URL。
- [ ] 停止自己启动的核查服务；如果代码因真实验收需修复，重新RED/GREEN和完整回归后提交。
- [ ] 写本轮报告：候选是否支持、失败原因、供应商缺口、跨轮路线判断，以及正式生成截止/结算资格仍未接入；不能称方向准确率提高或全目标完成。

## 自查和交接

本计划只完成独立核验能力。Formal timing、可信统计分层和冻结开发实验必须后续继续，不能以该子任务替代兵哥提高真实准确率的完整目标。按既有自主授权继续实施任务1，不再要求重复选择执行方式。
