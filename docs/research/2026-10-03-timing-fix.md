# 黄金结算时点修复：未来行情保护

## 本轮目的

前一轮在 `86850bb` 复现：结算服务会接受未来目标日期、未来采集时刻。
这会损害实时评测可信度。本轮修复这些无歧义的时间错误，不能据此宣称方向预测准确率提高。
保留全部历史研究、正式预测模型、方向阈值和最终留出集。

## 生产改动

`GoldForecastResolutionService` 在计算收益率、写入结算之前增加必要条件：

- 目标日期和采集时间存在。
- 目标日期严格晚于基准日期，且不晚于当前 Clock 日期；生产注入 Clock 为 UTC。
- 采集时间按绝对瞬间比较，不晚于当前时刻；不使用字符串或本地钟面比较。
- 校验和 `resolvedAt` 使用同一时刻。

不满足条件则返回待结算；不调用仓储 `resolve`、不跳过第一根行情选择更晚目标。
时间到达后允许同一根行情正常结算。不删除或重写历史已结算记录。

同时修正原有正向测试时钟：旧用例在 2026-08-31 01:00 UTC 已结算采集时间更晚的行情，测试夹具本身不满足时间一致性。
正向时钟改为 2026-09-02 12:00 UTC，价格和方向断言不变。

## TDD 和独立诊断证据

新增七个异常时间参数场景、一个跨时区等瞬间边界用例、一个先等待后结算用例。

1. 修改测试、生产未改时：17 tests，8 failures，0 errors。失败为“预期待结算、实际已结算”，不是缺类或空指针。
2. 修复后定向：17 tests，0 failures，0 errors，0 skipped。
3. 首轮全量 `mvnw.cmd test`：659 tests，0 failures，0 errors，3 skipped，BUILD SUCCESS；完成时间 2026-10-03 20:17:36 +08:00。
4. 独立编译并执行 `TimingProbe`：六个生产调用观测符合预期。未来目标、未来采集时间分别隔离检查。

独立只读审查未发现阻断性问题，指出固定 Clock 不能捕获二次取时回归。新增 `freezesTime`：使用每次前进一分钟的 Clock，断言落库沿用首次校验时刻。
临时将生产 `resolvedAt` 改为再次读取 Clock 的变异测试确实失败（预期 12:00，实际 12:01，1 failure、0 errors）；随后恢复实现。
最终全量：660 tests，0 failures，0 errors，3 skipped，BUILD SUCCESS；完成时间 2026-10-03 20:20:59 +08:00。结算服务最终为 18 个测试，独立六项诊断再次通过。
三个跳过项是原有的 `FredHistoryArchiveLiveTests`、`AlphaVantageGoldPriceProviderLiveTests`、`GoldPriceSyncLiveTests`；因此没有用本轮回归证明真实供应商接口正常。
审查者只读了当时的代码和现存报告，未重跑 Maven 或诊断；最终新增 Clock 测试与变异验证由主线程执行。不使用残留 Surefire XML 的累计数量替代本次 Maven 日志。

旧 [时点审计](2026-10-03-timing-audit.md) 中四项输出和源码摘要对应修复前提交 `86850bb`，保留为历史证据。
当前 `TimingProbe` 已更新为六项保护验收；它不由 Maven 自动执行。

复核命令（PowerShell，项目根目录）：

```powershell
cd backend
.\mvnw.cmd '-Dtest=GoldForecastResolutionServiceTests' test
.\mvnw.cmd test
cd ..
$timingCp = 'backend/target/classes;backend/target/probe-classes;' + (Get-Content -Raw backend/target/probe-classpath.txt).Trim()
javac -encoding UTF-8 -cp $timingCp -d backend/target/probe-classes docs/research/probes/TimingProbe.java
java '-Dfile.encoding=UTF-8' -cp $timingCp TimingProbe
```

诊断和测试使用数学夹具，不写行情表、不调用外部 API、不产生市场准确率样本。
测试存在 JVM 动态加载 agent 警告（Mockito/ByteBuddy），没有测试错误；不声称输出完全无警告。

## 尚未解决，不包装成完成

- 当前日线仍可结算，`TimingProbe` 有意保留这一观测；本轮不是完整的“已收盘日线保护”。
- Twelve Data 的 XAU/USD 时区、日线日期含义、最终确认时刻仍须核实；不能直接假定纽约午夜或某个收盘钟点。
- 快照采用纽约日期、结算日期采用生产 UTC Clock，供应商时间合同尚未统一。
- 旧快照晚生成的预测是否冒充事前预测、已结算历史是否含异常记录，尚未通过真实数据库审计。
- 修改源码和测试不等于正在运行的服务已更新；此轮未启动应用或核验运行中数据库。
- 本轮没有新增真实样本外方向评测，也没有证据表明准确率提高。

下一轮继续核实供应商已完成日线合同，并据此修正同步、快照、结算的共享边界；不以本轮测试数量代替预测能力证据。
