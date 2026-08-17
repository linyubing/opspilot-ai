package com.opspilot.ai.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ContentHashCalculatorTests {

    private final ContentHashCalculator calculator = new ContentHashCalculator();

    @Test
    @DisplayName("相同内容生成稳定且正确的小写SHA-256")
    void calculatesStableSha256() {
        byte[] content = "OpsPilot AI".getBytes(StandardCharsets.UTF_8);

        String first = calculator.calculate(content);
        String second = calculator.calculate(content);

        assertThat(first)
                .isEqualTo(
                        "d1f392865a3b201f4f82fc74eb2f67c2"
                        + "a72f036bfd74477e4c11a765b6b2bd66"
                )
                .isEqualTo(second)
                .matches("[0-9a-f]{64}");
    }
}
