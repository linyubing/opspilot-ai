package com.opspilot.ai.analysis;

import com.opspilot.ai.macrodata.MacroObservation;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 对完整事实输入计算身份；不把核验/采集时间及确认锚点推进当成新行情。 */
public final class GoldSnapshotKey {
    public static final String PROTOCOL = "gold-facts-v1";
    private GoldSnapshotKey() { }

    public static String hash(GoldResearchSnapshot snapshot) {
        if (snapshot.input() == null || snapshot.input().macro() == null)
            throw new IllegalArgumentException("完整输入身份缺少宏观原始观测");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, PROTOCOL);
            add(digest, snapshot.analysisDate());
            add(digest, snapshot.researchVersion());
            add(digest, snapshot.realRateAssessment().ruleVersion());
            add(digest, snapshot.dollarIndexAssessment().ruleVersion());
            for (var bar : snapshot.input().bars()) {
                add(digest, bar.symbol()); add(digest, bar.provider());
                add(digest, bar.currency()); add(digest, bar.unit()); add(digest, bar.priceDate());
                add(digest, bar.open()); add(digest, bar.high()); add(digest, bar.low()); add(digest, bar.close());
            }
            macro(digest, snapshot.input().macro().rates());
            macro(digest, snapshot.input().macro().dollars());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("当前运行环境缺少SHA-256", unavailable);
        }
    }

    private static void macro(MessageDigest digest, List<MacroObservation> rows) {
        for (var row : rows) {
            add(digest, row.seriesId()); add(digest, row.provider()); add(digest, row.unit());
            add(digest, row.observationDate()); add(digest, row.value());
        }
    }

    /** 长度前缀避免拼接歧义；十进制尾随零不改变事实数值。 */
    private static void add(MessageDigest digest, Object value) {
        String text = value instanceof BigDecimal number ? number.stripTrailingZeros().toPlainString()
                : value == null ? null : value.toString();
        byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(text == null ? -1 : bytes.length).array());
        digest.update(bytes);
    }
}
