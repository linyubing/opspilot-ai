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

/** 构造减少中性方向膨胀的第三版黄金回测提示词。 */
@Component
public class ImprovedBacktestPromptBuilder {

    public static final String VERSION = "gold-backtest-prompt-v3";

    private final BacktestPromptBuilder base = new BacktestPromptBuilder();

    public GoldForecastPrompt build(
            UUID caseId,
            GoldResearchSnapshot snapshot
    ) {
        Objects.requireNonNull(caseId, "回测明细编号不能为空");
        Objects.requireNonNull(snapshot, "历史研究快照不能为空");

        String content = base.build(caseId, snapshot).content()
                + "\n\n"
                + rules();
        return new GoldForecastPrompt(VERSION, content, sha256(content));
    }

    /** 定义本次需要在留出样本上验证的第三版规则。 */
    String rules() {
        return """
            【第三版候选规则】
            1. 使用黄金20期收益率判断长期方向，5期收益率判断中期确认，1期收益率只能作为短期补充。
            2. 实际利率上升通常压制黄金，下降通常支撑黄金。
            3. 美元指数上涨通常压制黄金，下跌通常支撑黄金。
            4. 实际利率和美元指数方向一致时，优先采用两个宏观因子的共同方向。
            5. 两个宏观因子冲突时，使用黄金20期和5期趋势打破平局。
            6. 不得因为任意两个因子冲突就直接判断为 NEUTRAL。
            7. 中性只能用于黄金长期和中期趋势不一致，并且两个宏观因子也相互抵消的情况。
            8. reasoning 必须依次说明长期趋势、中期确认、宏观因素和最终取舍。
            9. 不得使用未来数据。
            """;
    }

    /** 计算提示词摘要，确保测试结果可以对应到固定内容。 */
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
