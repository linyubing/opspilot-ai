# 当前正式预测基准的单次小时复核

## 先固定的问题与停止条件

只回答：现有预测 `978b5294-fd31-4ff1-ad40-a7e422ee0ff6` 的冻结基准日线，是否与原来承诺的候选窗口一致？不训练模型、不重跑预测、不评价涨跌准确率。

调用前协议为同目录 `2026-10-21-current-base-session-protocol.json`，当次文件字节SHA256为 `7ae012f6b446991290c1b966f67b436410a12de12be969b440e2c3dcf5646e74`。固定10/20基准价4120.79556000，Sydney07规则，对应UTC 10/19 20:00至10/20 20:00；只调用一次1h接口。任何不匹配即拒绝，不下载新日线替价、不搜索偏移窗口。

## 实际结果

| 项目 | 结果 |
|---|---|
| 核验时刻 | 2026-10-21 11:24:16.493680500 UTC |
| 冻结基准与仓库同日收盘 | 一致 |
| 候选小时窗口 | 完整24/24，无缺时、重复或越界 |
| 原仓库OHLC与小时聚合 | 四价精确一致，MATCHED |
| 时区依据 | REQUEST_ONLY_UTC，仅原请求指定UTC，未获得响应时区确认 |
| 外部行情调用 | 实际成功执行一次1h请求；没有重新取1day |
| 数据库、预测及成绩写入 | 0 |

原仓库日线采集于05:18:08.588088 UTC，闭市凭证观察于05:18:17.550676 UTC。查询原预测及仓库使用单连接只读事务；数据库连接在HTTP前关闭，没有持有跨网络事务。

新证据改变下一步判断：10/13、10/14冻结研究曾不匹配，**不能将该失败泛化为所有日期不可匹配**。当前这条基准可以通过原候选窗口复核，后续应按同一规则验证它的真实目标，而不是改窗口或重新挑样本。

但本次复核晚于预测生成。它不能证明生成时已取得这些小时证据，也不是官方会话、历史PIT或未来准确率认证。旧冻结配对仍不评分，当前正式记录仍待真实目标；没有把这份事后研究结果写成正式结算证据或命中。

## 原归档与离线复验

本机原输入归档：`backend/target/oct21-current-base-session.json`（Git忽略，不分发完整行情）。原归档SHA256固定为：

```text
b846bca9122fd983b2e77f5cf5f339945b1d59847f311b1c4081f652b04c79bf
```

核验器GoldSessionCheck字节码SHA256：`45481d1e3d818e9f27a56b62059a76bc298577c01c1e124e6d81c250d1acc86f`，生产代码提交为 `a81b3e6`。保存的是精确解析输入而非原始HTTP认证；只对本次固定归档、依赖与代码版本作复验。

从仓库根目录运行（使用现有依赖类路径文件，不需要新下载）：

```powershell
$sessionCp = (Get-Content backend/target/oct21-runtime-cp.txt -Raw).Trim() + ';D:/workFile/demo-ai/backend/target/classes'
New-Item -ItemType Directory -Path backend/target/oct21-session-probe -Force | Out-Null
javac -encoding UTF-8 -classpath $sessionCp -d backend/target/oct21-session-probe docs/research/probes/CheckGoldStoredSession.java
node docs/research/probes/CheckGoldStoredSessionChecks.mjs
```

离线检查将数据库密码和行情Key置空，验证原归档复算MATCHED、错误摘要拒绝、同路径再次live调用在数据库和HTTP前拒绝；3项通过。摘要必须取上面的预先公开值，不能对替换文件临时现算后冒充原证据。不要重复live下载来追求另一结果。

执行细节：首次使用Java源文件直接启动时，独立类加载器不能访问同包package-private方法，失败发生在HTTP之前。改为javac输出到独立target目录并使用同一应用类加载器后完成唯一外部请求；未放宽生产方法可见性。Node离线断言曾因Windows stderr编码失败，局部指定stderr=UTF-8后通过，未改全局终端编码。

独立只读审查未发现Critical/Important。暂缓Minor：replay再显式断言固定收盘及窗口（当前依靠固定受信任归档摘要绑定）；脚本只防同输出路径重取，不是跨任意新路径的全局一次锁。不能据脚本推断本次运行证据之外的历史调用次数。

本轮完整回归1090项、0失败、0错误、3跳过；页面Node21项通过。复查真实接口仍为4条原记录，新弃权待验证、targetDate/hit=null，三条旧未命中结果未变。运行日志为`backend/target/oct21-current-base-full.log`；没有用单个基准匹配结果生成任何准确率。
