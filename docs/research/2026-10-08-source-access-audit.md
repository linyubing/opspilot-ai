# 黄金备用数据入口实测

## 目的与本轮结论

解决原生日线重复日期导致的正式同步阻塞，不修改价格、删除重复记录或追调预测标签。
本轮只读排查了备用入口；未取得可以接入正式预测的备用行情，不能报告同步已修复。
本地应用与模型服务仍可用，数据入口故障不能解释成全部开发或本地推理停止。

## 已验证事实

- `8080/actuator/health` 本轮返回 `UP`；`11435/api/tags` 包含 `qwen3.5:9b`。
- 本地模型摘要仍为 `6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7`。
- 固定5000根小时线回执 SHA256 为 `5f2921bd1541c5aa114cd525e93349836cb50b7d298ec3523b67005966272439`。
- 其中1434根时间标记落在UTC周末，覆盖60个UTC日期；没有满足 `high == low && open == close` 的完全平坦小时。
- 这排除了该回执周末行“全是平坦延续值”的简单解释，不证明全部报价可交易，也不能称它是假数据。
- [Twelve Data官方说明](https://support.twelvedata.com/en/articles/12520817-forex-api-v2)将包含XAU的来源定义为24/7加权组合报价；研究窗口不等于伦敦黄金交易日。
- [官方2026年4月更新](https://twelvedata.com/news/april-2026-updates)明确日线及更长周期忽略 `timezone` 参数。因此不能用追加UTC参数来解决日线重复日期。

## 备用入口结果

| 入口 | 本轮实际结果 | 决策 |
| --- | --- | --- |
| Dukascopy官方历史导出组件 | PowerShell、curl TLS失败；Node请求失败；浏览器页面读取超时 | 未取得CSV，不接入 |
| Dukascopy公开历史文件候选路径 | 两个主机均未取得HTTP行情回执，复查超时 | 路径和数据格式尚未认证，不猜测价格精度 |
| Dukascopy旧图表服务 | `core.js` HTTP200；按公开页面配置请求图表入口HTTP403 | 脚本可下载不等于行情可下载 |
| Stooq黄金CSV入口 | 两个官方域名HTTP200，但实际是浏览器验证HTML | 非CSV，不能当作成功行情响应 |
| FXCM公开小时文件候选路径 | XAUUSD的2026年第40周、2020年第1周均HTTP404 | 不接入；公开文档的蜡烛样本品种列表并未包含XAUUSD |
| OANDA现有授权 | Process/User/Machine均未配置 `OANDA_API_TOKEN`、`OANDA_ACCOUNT_ID` | 不尝试未授权账户，不注册金融账户 |

入口参考：[Dukascopy历史导出](https://www.dukascopy.com/swiss/english/marketwatch/historical/)、[Stooq黄金历史](https://stooq.com/q/d/?s=xauusd)、[FXCM官方样本合同](https://github.com/fxcm/MarketData)。

[Dukascopy官方批量历史访问文档](https://www.dukascopy.com/wiki/en/development/data-export/)另列AWS凭据及Requester Pays要求；该入口不是无凭据免费替代品。本轮未启用计费、创建账户或执行收费下载。

## 后续约束

1. 原生日线护栏保留；没有有效备用回执，不重标旧价格或旧预测。
2. 已验证的真实小时线研究链路继续独立使用；现有研究成绩和正式准确率不能合并。
3. 接入备用源前必须验证品种、币种/单位、bid/ask/mid价格侧、日线时段、闭市边界、缺失和重复；不能仅因返回HTTP200就切换。
4. 需要新增账户授权、同意条款或付费时交由兵哥处理；普通网络/程序排错由助手继续承担。
5. 本轮未修改生产代码或数据库，未重跑Maven；不将上一轮测试数作为本轮测试成绩。
