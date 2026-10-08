# 最新行情同步与异常历史隔离

## 修复范围

全历史同步每次请求5000根日线，任一旧日期冲突会阻止整个批次保存。新入口
`post /api/market-data/gold/daily-bars/sync-latest` 先读取供应商的明确确认日，再使用
`/time_series?interval=1day&date=确认日` 请求该日。

这不是删除冲突数据：原全历史入口、重复检查、十进制OHLC检查及确认日报价逐项核对均保留。
新入口拒绝多日响应、重复日期、未来日期、品种不符、OHLC不一致、周末标签，以及同日价格修订或确认日倒退。
它仅保存一根最新日线，不表示历史完整，不修改预测结果、结算标签或正式模型。

单日参数依据：[Twelve Data官方API文档](https://twelvedata.com/docs)。
本轮真实请求发现相同`start_date/end_date`返回HTTP400“无数据”，改用`date=2026-10-07`后HTTP200，恰好一根日线。

## 真实数据取证

近期30根回执仍有`2026-10-04`两个不同OHLC，不能靠缩小到30根解决。
`2026-10-07`的日报价与该日日线四项OHLC相同；单日请求回执SHA256：
`56f668cf42aeac8467359be350c84eddde4b77712439770e29e780c0dc776eb7`。
私有回执保留于`backend/target/2026-10-08-daily-date-oct07.json`，不公开原始报价。

## 原始Tick路线也未通过

已通过HistData官方下载表单取得202609 XAUUSD ASCII Tick ZIP，未注册、支付或解压执行。
[官方规范](https://www.histdata.com/f-a-q/data-files-detailed-specification/)声明时间标记带毫秒、价格含Bid/Ask，并采用固定EST。
实际文件所有毫秒后缀均为000；不能据此恢复被省略的精确报价顺序。

- ZIP SHA256：`dbebbaefb5d8b04cf56e35d60682e21a75b9605f9bffaa2e8b2483aa7ff5f3bf`。
- CSV SHA256：`ee45749f41570799772ae7b6b4cfd292d74600a443597b5c9d1ee30f096cfb75`。
- 6,290,184条，买卖价合法性及时间格式检查未发现错误。
- 相邻时间倒序13,293处，其中跨分钟187处；同时间不同买卖报价4,823,094处。
- 按每分钟最早、最晚时间分别收集所有不同Bid后，27,035分钟有首尾价格歧义。
- 成品M1冲突的344分钟全部在Tick中存在，也全部有首尾歧义。
- 非冲突成品分钟28,603个与按文件顺序计算的OHLC相符，不足以证明344个冲突分钟可修复。

因此没有排序取首条、去重、填价或将Tick加入训练。此次排除了一个修复假设，而不是获得了新模型成绩。
ZIP、CSV和状态回执仅保存在Git忽略的target。

## 回归证据

新增10项HTTP边界及真实同步服务测试，先观察404失败、修订保护缺失失败和日期参数错误失败，再修复。
最终裸`mvnw.cmd test`：891运行、0失败、0错误、3跳过。应用仍使用`local-ai`配置和本地Qwen。
没有用旧回测分数宣称本轮准确率改善。历史缺口、时段口径和预测效果仍需单独验证。

## 部署与真实入库验收

- 实现提交：`a3cae640127a97227898fa332e6dcb984561637a`；运行JAR的`git.properties`一致。
- 重建后以`local-ai`启动在8080，`/actuator/health`为`UP`。
- 调用新入口实际收到`receivedCount=1, savedCount=1, weekendSkippedCount=0`。
- 随后`get /api/market-data/gold/daily-bars/latest`返回`2026-10-07`；刷新前为`2026-10-02`。
- 私有入库验收回执：`backend/target/2026-10-08-latest-sync-live.json`。
- 回执SHA256：`637d9f735c78b4bbe0bec70b8b2b0a3a04c54ebd018683eff41f3cb3cef2aba2`。
- 只刷新供应商确认的最新一天，不会因当前日期为10月8日就给未结束日盖确认章。

最新同步不再受旧日期冲突阻塞；全历史修复及预测准确率提升仍未完成。旧预测页面记录不自动重写。
