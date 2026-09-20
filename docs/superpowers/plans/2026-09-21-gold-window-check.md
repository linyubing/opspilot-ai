# 黄金近期训练窗口对照计划

> 执行方式：使用 executing-plans 在当前目录实施，独立只读复核；遵照兵哥已授权的推荐方向继续，不创建工作树。

**目标：** 在已验证的逐折标准化基础上，检验缩短训练历史能否提高下一交易日的完整样本准确率。

**设计：** 使用同一个真实数据集、同一个时间分区；比较全部已结算历史、最近 252 条、最近 504 条训练样本。每 20 条验证样本重训，裁剪发生在结算日期过滤之后，标准化统计在裁剪之后拟合。结果只用于开发验证，不替换正式预测。

**技术：** Java 21、Spring Boot、Tribuo、JUnit；不新增依赖或数据库表。

**依据：** 2026-09-21 标准化真实对照中下一交易日完整样本准确率有提升，但模型仍偏向中性；需要检验跨年代训练数据是否影响适应性，而不是继续提高置信度门槛来筛掉错误。

## 约束和复核重点

- 仅运行 NEXT_DAY；BASE_16 / OHLC_20 / ALL_36 都展示，不事后删掉失败组合。
- 固定 252、504 两个窗口，不根据本次结果追加窗口网格。
- 标签仍为 ±0.5%，信号门槛仍为 0.55，不打开最终留出集。
- 最近 N 条是交易样本数量，不宣称严格等于一、二个自然年。
- BASE_16 和 ALL_36 仍有历史宏观发布时点风险，OHLC_20 结果单独列出。
- 对无序样本、数量不足、重复日期明确拒绝，不悄悄补样本。
- 每个窗口同时展示完整 240 条评分与信号子集评分；同日期基线比较必须日期对齐。

## 任务 1：可验证的训练窗口

文件：`backend/src/main/java/com/opspilot/ai/forecast/learning/WindowGoldTrainer.java`，同包测试 `WindowGoldTrainerTests.java`。

接口：`new WindowGoldTrainer(GoldTrainer delegate, int size)`；实现原有 `GoldTrainer`。

- [x] 先建立可编译的委托骨架，测试以 5 条有序样本输入、窗口 3 断言委托收到后 3 条；红灯应是行为断言失败，不是缺类。
- [x] `train` 验证输入、严格递增日期及数量，再使用 `List.copyOf(samples.subList(samples.size() - size, samples.size()))` 交给原训练器。
- [x] 断言委托收到相同特征集合、返回模型保持不变；不足、重复、乱序、非正窗口均拒绝。
- [x] `name` 保存窗口参数：`delegate.name() + "-window-" + size`。
- [x] 定向运行 `WindowGoldTrainerTests`。

## 任务 2：共享分区的窗口对照

文件：`ScalingCheckService.java`、`ScalingReport.java`、`ScalingCheckController.java` 及对应测试。

- [x] 新增 `WindowReport` 领域类型保存代码版本、指纹、验证日期、留出日期和对照列表。
- [x] 新增 `windows()`，只构建和分区一次 NEXT_DAY；同组合完整历史的标准化预测和多数类基线只生成一次。
- [x] 对 252/504 调用 `walkForward.predict(split, profile, new WindowGoldTrainer(new TribuoGoldTrainer(true), size))`，复用已验证的配对评分代码。
- [x] 保留原 `compare(profile, before, after, baseline)` 的行为；增加包含版本参数的重载，避免把标准化全历史错误标成旧原始模型。
- [x] `POST /api/research/gold/model-experiments/scaling-check/windows` 返回完整结果；不改正式模型的配置。
- [x] 测试固定 6 组结果（3 特征 × 2 窗口）、共享分区、模型版本、原有接口兼容。
- [ ] 全量 Maven 回归通过后提交实现，再用真实接口运行一次并校验与上一批的数据指纹一致。
- [ ] 原始 JSON 和如实结果表存档，报告弱点与未通过门槛，不宣称稳定获利或预测保证；提交并推送。

## 执行记录

- 起点提交：`fe8449c`，标准化第一轮全量回归 624 项通过（2 跳过）。
- 辅助脚本 `task-start` 无法识别中文“任务 1”标题，因此保留中文计划并在本文件记录执行状态，不为脚本修改用户命名偏好。
- 任务 1 红灯：`WindowGoldTrainerTests` 两个行为断言失败，编译通过；原骨架错误地传入全部训练样本，且不拒绝不足/无序输入。日志 `backend/target/window-red.log`。
- 任务 1 绿灯、任务 2 红灯：9 项定向测试中窗口测试通过，服务仅因空结果断言失败。日志 `backend/target/window-service-red.log`。
- 两项代码完成：全量 `mvnw.cmd test` 627 项，0 失败、0 错误、2 跳过，日志 `backend/target/window-full-test.log`。只读复核未发现阻断问题。
