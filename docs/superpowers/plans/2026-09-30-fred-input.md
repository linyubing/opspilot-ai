# 黄金实验历史版本输入实施计划

> 执行技能：superpowers:executing-plans；本会话直接实现，测试先行。

**目标：** 接通可信宏观历史版本，阻止实验使用后公布值。
**架构：** 文件归档读取器 → 不可变批次 → 显式快照输入 → 数据集与实验元数据。
**技术：** Java 21、Spring Boot、Jackson、PowerShell；不新增依赖或 SQL。
**规格：** `docs/superpowers/specs/2026-09-30-fred-input-design.md`

## 全局约束

只用真实行情；中文类注释、简短命名；正式模型不切换；不评分最终留出集；不修改 `.env.example` 和用户设计文档。

## 审查重点

- 同日公布版本必须等到次日保守截止后可用。
- 最新版本为缺失值时不得用旧有效值填充。
- 文件过期或一半下载成功不能运行成“最新实验”。
- 同一 build 不能在中途换归档或指纹。
- 旧实验不能被自动标记为已经修正。

## 任务 1：历史版本读取

文件：`macrodata/FredHistory.java`、`FredHistoryStore.java`、`FredHistoryTests.java`。
接口：`FredHistoryStore.load()` 返回不可变 `Batch`；`Batch.recent(series, asOf, limit)` 返回当时观测；`Batch.metadata()` 返回来源说明。

- [x] 测试实际修订值与发布时间、缺失、区间边界、重叠、数量不符、旧文件、不可变来源。
- [x] 先运行到预期断言失败，再实现严格解析和版本选择。
- [x] 定向测试通过，读取器共 15 个场景。

## 任务 2：接通数据集与可复现参数

文件：`GoldResearchSnapshotService.java`、`GoldDatasetBuilder.java`、`GoldDataset.java`、`GoldDatasetFingerprint.java`、`ModelExperimentService.java` 和对应测试。
接口：快照重载 `createSnapshot(asOf, realRates, dollarIndexes)`；数据集新增 `macroInput`，旧双参数构造明确标记 legacy。

- [x] 写真实计算链测试，断言公开前的修订不进入 36 项特征。
- [x] 运行失败，再改默认 builder 调用；每次 build 只加载一次归档。
- [x] 在指纹和持久化参数中携带 `macroInput`，测试来源改变会改变指纹。
- [x] 旧实验详情提供明确口径说明，不删除记录；列表和详情同步显示中文提示。

## 任务 3：运行与验收

文件：`scripts/Sync-FredHistory.ps1`、操作说明、真实验证记录。

- [x] 采集工具显式日期、写新目录、脱敏报错，完成两个文件后才发布目录。
- [x] 本机恢复已验证归档至 D 盘持久目录，另采集截至 2026-09-29 的新批次，配置实验输入，不执行模型晋级。
- [x] 真数据重建、与上一轮全部样本/特征配对验证，运行全量 Maven 测试。
- [x] 独立代码审查，修正页面缺少口径和测试污染环境变量两项问题，完成回归。

## 执行记录

- 基线：`f1b27fd`；上轮只核对并解释方案，本轮开始产品接入。
- 选择文件归档而非新表：当前实验需要冻结和迁移整批来源；不混入实时观测的本地采集版本。后续若数据规模增大再引入同语义存储，不改变版本规则。
- 红绿证据保存在本机 `backend/target/fred-*-red.log` 及对应 green 日志；页面测试先出现 4 个失败，修正后 4 个通过。
- 最终全量回归：650 项，0 失败，0 错误，3 跳过；真实归档只读测试分别对两个批次显式运行通过。
- 独立审查已完成；桌面与移动端使用明确的 UI 测试记录验证标签，不执行行情预测。
- 集成采用用户已有授权：当前 master 原地中文提交并推送，不新建工作树，不提交保护文件。
- 完整验收与边界：[历史版本产品接入验收](../../research/2026-09-30-fred-product-check.md)。
