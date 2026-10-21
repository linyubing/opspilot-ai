# 黄金来源合同调查：不能把不一致简单归为时区

## 事先限定的问题

本轮只检查已有真实回执及当前仓库，不重新取行情、不重跑模型、不读最终留出。先比较相同日期的来源版本，再只检验一个限定的悉尼本地零点诊断假设；不遍历偏移，不挑匹配窗口，也不替换冻结07:00合同。

## 新发现一：相同日期已有版本差异

固定10月13日保存的完整真实归档，SHA256为`090e5de5fc1ceb49fa1e5bddd21dfd62a6094a0cc8dfbc4c3a3e2424a45bf974`，与当时结果文件的sourceSha256一致。其10月12日目标日原始正文摘要仍为`3ca28f682c454a6168e48ebd2ad85c939559510e26f4862db93aacd221e367da`。

用同一日期、品种、供应商及美元/金衡盎司单位，与本次真实补缺入库记录比较：

| 项目 | 旧来源回执 | 当前仓库 |
| --- | --- | --- |
| 采集时刻UTC | 2026-10-13 01:25:37 | 2026-10-21 05:17:14 |
| 目标日 | 2026-10-12 | 2026-10-12 |
| OHLC逐项精确相等 | 与当前比较，四项均不等 | 与旧版比较，四项均不等 |
| 相对原冻结基准收益 | -0.6153476239% | -0.5611606317% |
| 按固定±0.5%规则分类 | BEARISH | BEARISH |

当前仓库收盘相对旧来源回执变化约+0.0545224951%。这里只确认“旧原文和当前入库值不同”，没有供应商解释或修订公告，不能声称已经查明厂商修订原因，更不能推断10月13/14日差异必然由同一原因造成。

旧研究验收与本次正式结算因此会出现不同收益数字；这条样本的下跌类别及原中性预测未命中结论没有变化。两次来源版本必须分别保留，不事后覆盖旧回执或评分。

## 新发现二：本地零点假设同样未通过

只用已锁定的10月21日48小时归档，检验10月14日Sydney零点至次日零点，即UTC `2026-10-13T13:00:00Z` 到 `2026-10-14T13:00:00Z`。

24小时完整，但open/high/low/close四项均与原生日线不一致。因此不能用“把07:00改成00:00就好了”解释问题，也不能继续遍历小时偏移寻找巧合。本次诊断没有改变原协议、标签、分数或正式数据库。

## 官方资料能证明什么

- 官方时区帮助页将Forex默认时区列为Australia/Sydney；这不等于证明本项目07:00是黄金日线边界。[Timezones](https://support.twelvedata.com/en/articles/5745849-timezones)
- 官方Java SDK文档说明，timezone参数只对日内周期适用，1day及以上周期使用Exchange当地时间。不能通过给日线请求加UTC参数来证明日线已按UTC零点重切。[MarketDataApi time series](https://github.com/twelvedata/twelvedata-java/blob/main/docs/MarketDataApi.md#gettimeseries)
- 官方说明商品/外汇使用聚合报价，默认中间价，并非特定经纪商可成交价格。这解释价格口径限制，不足以证明本次历史值变化或小时/日线不一致的具体原因。[报价口径说明](https://support.twelvedata.com/en/articles/11850499-understanding-price-deviations-in-commodities-and-forex-data)

目前仍缺：XAU/USD原生日线标签对应的精确起止端点、历史版本变化政策，以及同一版本日线与小时线聚合关系的可核查说明。不能用目录时区或24/7标识代替这些证据。

## 可复验与状态

`CheckGoldSourceAudit.java`固定两份归档摘要，从raw正文解析；数据库只用单连接只读事务查询并rollback，输出不含密钥或完整原始行情。核验器检查同日期、单位与后采集时间，报告官方认证false、0外部行情API调用、0写库、0评分变更。

从backend目录、在已配置数据库密码且保留真实回执的本机运行：

```powershell
.\mvnw.cmd dependency:build-classpath '-Dmdep.outputFile=target/source-audit-cp.txt'
$env:OPSPILOT_DB_PASSWORD = [Environment]::GetEnvironmentVariable('OPSPILOT_DB_PASSWORD', 'User')
$auditCp = (Get-Content target/source-audit-cp.txt -Raw).Trim() + ';D:/workFile/demo-ai/backend/target/classes'
java -Dstdout.encoding=UTF-8 --class-path $auditCp ../docs/research/probes/CheckGoldSourceAudit.java target/oct13-forward-receipts.json target/oct21-event-pair-score.json.source.json
```

源归档不随Git分发；本机归档缺失时不得把重新下载数据冒称原证据。数据库若将来经过显式维护，当前仓库比较结果可能改变，旧归档摘要仍固定。

本轮决策：冻结配对继续不评分，候选时段不升级可信合同，不新开模型调参网格。正式新弃权记录仍需真实到期行情；来源合同未清楚前不能把工程可运行包装为预测可靠。调查取得了版本差异和零点假设失败的新证据，但没有查明所有来源差异的根因，也没有提高准确率。

本轮验证：核验器编译并实际读取真实归档及数据库成功；只读独立审查无阻塞问题，已将0网络请求表述修正为0外部行情API请求（本机数据库连接仍存在）。原冻结验收检查及真实回执复验通过，Node页面17项通过，Java全量1049项、0失败、0错误、3跳过。运行日志保留于`backend/target/oct21-source-audit-full.log`；没有调用新的预测模型。
