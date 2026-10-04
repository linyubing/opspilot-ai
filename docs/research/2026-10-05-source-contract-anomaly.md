# 新供应商回执异常与路线复盘

## 本轮实际请求及结果

本轮只读请求三次：quote(eod=true)、time_series(1day,outputsize=3)、quote(eod=true)复查。
不调用模型，不同步数据库，不改变正式预测，不读取最终留出集。
首次开始2026-10-04T16:54:23.9290675Z，完成16:54:25.1690440Z；
复查开始16:55:00.4887116Z，完成16:55:01.0802572Z。
凭据仅从已有环境读取，未记录含密钥URL。

真实响应显示：

- time_series状态ok，三行日期依次2026-10-04、2026-10-04、2026-10-03。
- 两条10月4日日线OHLC不完全相同，不能用“重复的相同数据”解释或随意取其中一行。
- 两次eod=true quote均标记10月4日、is_market_open=true。
- last_quote_at由16:54:00Z推进至16:55:00Z；两次报价OHLC相同。
- quote日期落在GoldSession候选未结束的10月4日区间中，但该边界尚未获得官方认证。

能确认重复标签和元数据推进，不能声称两次价格变化，也不能仅凭is_market_open
断定EOD合同必然无效。重复可能与夏令时标签有关，但尚未证明根因。
不能将当前EOD响应、日期已过去或接口名称单独升级为可信闭市证明。

## 官方合同与观察的差异

[官方Java SDK参数文档](https://github.com/twelvedata/twelvedata-java/blob/main/docs/MarketDataApi.md)
将quote的eod参数描述为返回closed day。
[官方EOD说明](https://support.twelvedata.com/en/articles/12682324-end-of-day-eod-pricing-market-data)
区分初步价格与完成日结后确认价格，但其示例不能认证此次XAU/USD响应的时段。
文档合同、时段候选、实际响应需要分别留存；不因文档存在就忽略实际异常。

## 生产链路离线重放

扩展ClosedDayProbe的单参数模式，将此次实际返回的decoded日线和quote送入loopback HTTP服务，
再调用未修改的TwelveDataGoldBarProvider.fetchDailyBars。
实际输出：`PASS real-receipt rejection: duplicate-date; no market write`，退出0。
没有删除、去重、排序挑选或修改任何返回行来获得通过。
旧双文件模式再运行仍输出`PASS real-receipt replay: retained=2, newest=2026-10-02; no market write`。

数据库只读核对：twelve_data日线4874条，最大日期2026-10-02。
生产Java没有修改，不以先前881项测试代替这次真实网络及重放证据。
核验工具输出成功表示“拒绝行为符合预期”，不是行情获取成功或准确率提高。

## 原始证据位置与公开范围

原始供应商行情不新增发布到公开仓库，保存在Git忽略的本机文件：

`backend/target/2026-10-05-source-receipts.json`

文件SHA-256：`7aef76e85c72aa74559e264d2944ccbb26d082dfc8b0c28901cfa398fed730e5`。
首次Invoke-RestMethod回执是decoded payload，不是逐字HTTP原始正文；两者不混称。
复查另外保留了逐字响应字符串，UTF-8 SHA-256：
`91610ccd68a7cab8c9d6b03f3a620dadcbb51831da575fe0dd2e793f5d9bfe28`。
文件已用git check-ignore核实；target可能被clean清除，重现时须保留该本机文件。
此前生成的未提交公开目录副本已移至此忽略目录，真实回执没有丢弃。

复现命令（项目根目录）：

```powershell
$probePath = 'D:\workFile\demo-ai\backend\target;D:\workFile\demo-ai\backend\target\classes;' + (Get-Content backend/target/probe-classpath.txt -Raw).Trim()
javac -encoding UTF-8 -cp $probePath -d backend/target docs/research/probes/ClosedDayProbe.java
java '-Dfile.encoding=UTF-8' -cp $probePath ClosedDayProbe backend/target/2026-10-05-source-receipts.json
```

## 多轮总结与下一步

统计模型多轮未找到稳健优势，本地模型又出现事实符号错误；研究发布协议存在时间限制。
现在实际网络回执还出现重复标签。不能把这些不同层的失败全部称为“AI不够聪明”。
现有拒绝护栏应保留，不能为获得新日期把重复日线去重后当正常数据。
单次异常不证明供应商全部历史价格错误，也不证明所有旧确认记录失效。
但若重复日期保留在历史全量响应中，生产获取整包仍会失败，不能承诺等到下个工作日自动恢复。

下一检查应有限复查同一供应商标签及真实小时覆盖，先定位异常是否持续，
并评估明确标注的独立小时聚合来源或具备清晰时间合同的替代来源。
任何聚合必须保留真实覆盖与缺口，不能替旧原生日线静默改价；任何换源也不保证准确率提升。
在没有新合格输入前，不运行新日期配对、不新增假成绩，不继续在旧日期挑满意的理由。
