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

/** 构造明确涨跌结算阈值的第四版黄金回测提示词。 */
@Component
public class CalibratedBacktestPromptBuilder {

    public static final String VERSION = "gold-backtest-prompt-v4";

    private final BacktestPromptBuilder base;

    public CalibratedBacktestPromptBuilder(BacktestPromptBuilder base) {
        this.base = base;
    }

    public GoldForecastPrompt build(UUID caseId, GoldResearchSnapshot snapshot) {
        Objects.requireNonNull(caseId, "回测明细编号不能为空");
        Objects.requireNonNull(snapshot, "历史研究快照不能为空");

        String content = base.build(caseId, snapshot).content()
                + "\n\n"
                + rules();
        return new GoldForecastPrompt(VERSION, content, sha256(content));
    }

    /**
     * 定义模型判断下一交易日方向时必须遵守的校准规则。
     */
    String rules() {
        return """
            【第四版校准规则】
            1. 下一有效交易日的真实收益率大于 0.5% 时，方向为 BULLISH。
            2. 下一有效交易日的真实收益率小于 -0.5% 时，方向为 BEARISH。
            3. 收益率处于 -0.5% 至 0.5% 之间时，包括两个边界值，方向为 NEUTRAL。
            4. 20 期收益率只表示较长期背景，不能单独证明下一交易日会涨或会跌。
            5. 5 期收益率用于判断中期趋势，1 期收益率只用于判断短期变化。
            6. 实际利率上升通常压制黄金，下降通常支撑黄金。
            7. 美元指数上涨通常压制黄金，下跌通常支撑黄金。
            8. 必须判断现有证据是否足以支持下一交易日涨跌幅超过 0.5%。
            9. 如果证据只说明方向略有倾向，但不足以支持超过 0.5%，应判断为 NEUTRAL。
            10. 不得因为 20 期收益率为正就自动判断为 BULLISH。
            11. reasoning 必须说明短期动量、中期趋势、长期背景和宏观因子的取舍。
            12. 不得使用预测日期之后的数据，不得虚构新闻或市场事件。
            """;
    }

    /** 生成提示词摘要，保证回测结果能够追溯到固定内容。 */
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
