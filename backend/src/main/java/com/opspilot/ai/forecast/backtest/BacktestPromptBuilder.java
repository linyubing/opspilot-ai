package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.forecast.GoldForecastPrompt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** 把历史研究快照转换成不包含未来答案的回测提示词。 */
@Component
public class BacktestPromptBuilder {

    public static final String VERSION = "gold-backtest-prompt-v1";

    public GoldForecastPrompt build(
            UUID caseId,
            GoldResearchSnapshot snapshot
    ) {
        Objects.requireNonNull(caseId, "回测明细编号不能为空");
        Objects.requireNonNull(snapshot, "历史研究快照不能为空");

        String content = buildContent(caseId, snapshot);
        return new GoldForecastPrompt(VERSION, content, sha256(content));
    }

    /**
     * 构造历史盲测提示词，只允许模型看到预测日期当时已知的数据。
     */
    private String buildContent(
            UUID caseId,
            GoldResearchSnapshot snapshot
    ) {
        return """
            你是个人黄金投资研究助手。
            这是历史日期的盲测输入，请预测黄金下一有效交易日的方向。
            只能使用下面提供的历史事实，不得补充新闻、事件或外部数据。
            你不知道下一交易日的真实价格和真实方向。

            【回测信息】
            回测明细编号：%s
            分析日期：%s
            黄金数据日期：%s
            实际利率数据日期：%s
            美元指数数据日期：%s
            研究版本：%s

            【黄金价格】
            当前价格：%s
            1期收益率：%s%%
            5期收益率：%s%%
            20期收益率：%s%%

            【实际利率因素】
            当前实际利率：%s
            5期变化：%s个基点
            20期变化：%s个基点
            因子状态：%s
            因子解释：%s

            【美元指数因素】
            当前指数：%s
            5期收益率：%s%%
            20期收益率：%s%%
            因子状态：%s
            因子解释：%s

            【方向规则】
            只能选择 BULLISH、NEUTRAL、BEARISH。
            BULLISH 表示看涨，NEUTRAL 表示中性，BEARISH 表示看跌。

            【安全要求】
            不得生成未提供的市场事实。
            不得给出目标价、涨跌概率、止损位、仓位或买卖建议。
            只返回 JSON，不要使用 Markdown 代码块。

            严格返回：
            {"direction":"BULLISH|NEUTRAL|BEARISH","reasoning":"研究依据","invalidationConditions":["失效条件"]}
            """.formatted(
                caseId,
                snapshot.analysisDate(),
                snapshot.latestGoldDate(),
                snapshot.latestRealRateDate(),
                snapshot.latestDollarIndexDate(),
                snapshot.researchVersion(),
                snapshot.gold().currentPrice().toPlainString(),
                snapshot.gold().return1().toPlainString(),
                snapshot.gold().return5().toPlainString(),
                snapshot.gold().return20().toPlainString(),
                snapshot.realRate().currentRate().toPlainString(),
                snapshot.realRate().basisPointChange5().toPlainString(),
                snapshot.realRate().basisPointChange20().toPlainString(),
                snapshot.realRateAssessment().status().name(),
                snapshot.realRateAssessment().explanation(),
                snapshot.dollarIndex().currentIndex().toPlainString(),
                snapshot.dollarIndex().return5().toPlainString(),
                snapshot.dollarIndex().return20().toPlainString(),
                snapshot.dollarIndexAssessment().status().name(),
                snapshot.dollarIndexAssessment().explanation()
        );
    }

    /** 使用 UTF-8 生成稳定摘要，便于审计每次回测实际使用的提示词。 */
    private String sha256(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 环境不支持 SHA-256", exception);
        }
    }
}
