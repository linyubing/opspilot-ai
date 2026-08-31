package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 将正式黄金研究快照转换为受约束的方向预测提示词。 */
@Component
public class GoldForecastPromptBuilder {

    public static final String PROMPT_VERSION =
            "gold-direction-forecast-prompt-v2";

    public GoldForecastPrompt build(StoredGoldResearchSnapshot record) {
        Objects.requireNonNull(record, "正式快照记录不能为空");
        GoldResearchSnapshot snapshot = record.snapshot();

        String content = """
                你是个人黄金投资研究助手。请基于给定正式快照，
                预测黄金下一个有效交易日的方向。

                只能使用下列事实，不得修改、重新计算或补充外部数据。

                【正式快照】
                快照编号：%s
                分析日期：%s
                黄金数据日期：%s
                实际利率数据日期：%s
                美元指数数据日期：%s
                研究版本：%s
                黄金当前价格：%s
                黄金1期收益率：%s%%
                黄金5期收益率：%s%%
                黄金20期收益率：%s%%
                黄金20期波动率：%s
                实际利率因子：%s
                实际利率解释：%s
                美元指数因子：%s
                美元指数解释：%s

                【方向合同】
                后续真实涨跌幅大于 0.5%% 为 BULLISH；
                小于 -0.5%% 为 BEARISH；
                其余包括两个边界值均为 NEUTRAL（中性）。

                【第二版校正规则】
                1. 预测的是下一个有效交易日，不是中长期趋势。
                2. 不得因为 20 期收益率为正就自动判断为 BULLISH。
                3. 1 期收益率为负时，必须说明短线转弱风险。
                4. 5 期和 1 期方向冲突时，必须降低方向确信度。
                5. 20 期涨幅很大但 1 期转负时，必须考虑高位回落或获利了结。
                6. 美元指数数据明显滞后时，必须降低美元指数因子的权重。
                7. 波动率较高时，优先考虑 NEUTRAL 或反转风险。
                8. 如果证据只支持轻微涨跌，不能判断为 BULLISH 或 BEARISH，应判断为 NEUTRAL。
                9. reasoning 必须说明短线动量、中期趋势、宏观因子和最终取舍。

                【安全边界】
                只返回 JSON，不要使用 Markdown 代码块。
                不得生成新闻、市场事件或任何未提供的事实。
                不得给出目标价、涨跌概率、止损位、仓位或买卖建议。

                严格返回：
                {"direction":"BULLISH|NEUTRAL|BEARISH","reasoning":"研究依据","invalidationConditions":["失效条件"]}
                """.formatted(
                record.id(),
                snapshot.analysisDate(),
                snapshot.latestGoldDate(),
                snapshot.latestRealRateDate(),
                snapshot.latestDollarIndexDate(),
                snapshot.researchVersion(),
                snapshot.gold().currentPrice().toPlainString(),
                snapshot.gold().return1().toPlainString(),
                snapshot.gold().return5().toPlainString(),
                snapshot.gold().return20().toPlainString(),
                snapshot.gold().volatility20() == null
                        ? "无"
                        : snapshot.gold().volatility20().toPlainString(),
                snapshot.realRateAssessment().status().name(),
                snapshot.realRateAssessment().explanation(),
                snapshot.dollarIndexAssessment().status().name(),
                snapshot.dollarIndexAssessment().explanation()
        );
        return new GoldForecastPrompt(PROMPT_VERSION, content, sha256(content));
    }

    /** 使用 UTF-8 生成稳定摘要，用于预测审计和幂等判断。 */
    private String sha256(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "当前 Java 环境不支持 SHA-256",
                    exception
            );
        }
    }
}
