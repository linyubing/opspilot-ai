package com.opspilot.ai.forecast.learning;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 计算黄金数据集的确定性SHA-256指纹。 */
@Component
public class GoldDatasetFingerprint {

    public String hash(GoldDataset dataset) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256不可用", e);
        }

        List<GoldSample> sorted = dataset.samples().stream()
                .sorted((a, b) -> a.asOfDate().compareTo(b.asOfDate()))
                .toList();

        for (GoldSample sample : sorted) {
            update(digest, sample.asOfDate().toString());
            update(digest, sample.targetDate().toString());
            update(digest, sample.label().name());
            update(digest, sample.horizon().name());

            List<String> featureNames = sample.features().values().keySet().stream()
                    .sorted()
                    .toList();
            for (String name : featureNames) {
                update(digest, name);
                update(digest, String.valueOf(sample.features().values().get(name)));
            }
        }

        byte[] hash = digest.digest();
        return HexFormat.of().formatHex(hash);
    }

    private void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
