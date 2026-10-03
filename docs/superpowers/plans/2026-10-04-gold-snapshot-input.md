# 黄金快照输入留痕实施计划

> 使用 superpowers:executing-plans 原生实施，不派实现代理。用户已明确授权按推荐方案执行、不反复询问；这是既有闭市确认任务2的细化，不改变模型路线。

**Goal:** 新快照保存计算窗口，正式预测拒绝无依据旧快照。
**Architecture:** 快照附加不可变窗口与核验时刻；重新推导黄金指标核对；JDBC原子保存nullable JSON，旧记录不升级。
**Tech Stack:** Java21、Spring Boot、Jackson、JdbcTemplate、Flyway、PostgreSQL、JUnit。
**Spec:** ../specs/2026-10-04-gold-snapshot-input-design.md

## 全局约束

不修改模型、标签、阈值或holdout，不回填旧记录，不动受保护文件、不独立worktree。新增类中文注释，SQL小写。

## 审查重点

- 存储往返后OffsetDateTime时区表示变化不应误拒绝相同绝对时刻。
- 修改窗口导致参与计算的指标变化时不能通过核对；日期重复不能凑足21根。匹配聚合指标不是对任意字段修改的签名认证。
- 序列外更老行不得挤入实际使用的窗口。
- 旧SQL记录与旧回测JSON缺input都不能自动升级。
- 新版本不得绕过窗口验证，旧幂等结果不得被重标。

## 任务1：计算窗口与核对

文件：analysis/GoldSnapshotInput.java、GoldResearchSnapshot.java、GoldResearchSnapshotService.java；对应测试。
接口：GoldSnapshotInput(List<GoldDailyBar> bars, OffsetDateTime checkedAt)，boolean matches(LocalDate date, GoldReturnMetrics gold, OffsetDateTime asOf)。GoldResearchSnapshot新增input字段，保留旧11/7参数构造器。

- [x] 编译骨架后运行行为测试RED：21根正确指标应为true；缺一根、未来checkedAt、错指标、中间未确认应false；窗口只读。
- [x] 实现完整核对，快照只附加实际前21根窗口，无重复查询。
- [x] 定向测试GREEN、完整mvnw.cmd test通过745/0fail/0err/3skip；中文提交。

## 任务2：持久化与正式预测

文件：JdbcGoldResearchSnapshotRepository、V22迁移、GoldForecastGenerationService、快照记录服务与生成/数据库测试。

- [x] 真实数据库roundtrip与缺input拒绝生成断言RED。
- [x] nullable gold_input jsonb单列保存与映射；旧行不回填；保存新版本v3，同日旧v2保持不可变。
- [x] 模型调用前核对已保存输入，核验时间不晚于创建；旧预测继续只读，不重写历史。
- [x] 回归通过并审查，修复持久化精度失配；758/0fail/0err/3skip。未解决生成早于目标收盘的边界。

## 任务3：旧回测与真实核查

- [ ] HorizonDiagnosticService检查旧case快照input，缺依据或基准不匹配跳过，不重标旧结果。
- [ ] 真实供应商同步一次，只读核对新快照依据，不调用真实HOLDOUT诊断。
- [ ] 总结失败原因、跨轮路线判断；若没有真实样本外提升证据，如实写未证明。
