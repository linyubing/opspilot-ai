package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.forecast.GoldForecastPrompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;

/** 构造黄金历史回测的候选提示词，不影响正式使用的基准版本。 */
@Component
public class CandidateBacktestPromptBuilder {

    public static final String VERSION = "gold-backtest-prompt-v2";

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

    /**
     * 定义本次实验要验证的候选规则。
     *
     * 这里是本步骤唯一需要兵哥手敲的 AI 提示词逻辑。
     */
    String rules() {
        return """
            【候选规则】
            1. 先分别判断黄金动量、实际利率和美元指数对黄金方向的影响。
            2. 因子发生冲突时，不得只根据单个因子判断方向。
            3. 只有至少两个独立因子方向一致时，才能判断为 BULLISH 或 BEARISH。
            4. 因子相互冲突或者证据不足时，优先判断为 NEUTRAL。
            5. reasoning 必须说明采用了哪些因子，以及忽略其他因子的原因。
            6. 不得使用未来数据。
            """;
    }

    /** 计算候选提示词摘要，保证每次实验内容可追溯。 */
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
