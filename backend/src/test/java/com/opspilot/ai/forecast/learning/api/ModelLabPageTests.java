package com.opspilot.ai.forecast.learning.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ModelLabPageTests {

    @Test
    void showsFixedHorizonsAndModelMetrics() throws IOException {
        String html = read("model-lab.html");

        assertThat(html)
                .contains("1 个交易日")
                .contains("5 个交易日")
                .contains("20 个交易日")
                .contains("多数类基线")
                .contains("逻辑回归")
                .contains("总体准确率")
                .contains("平衡准确率")
                .contains("覆盖率")
                .contains("最终留出样本尚未启用");
    }

    @Test
    void forecastPageLinksToModelLab() throws IOException {
        assertThat(read("forecast.html"))
                .contains("href=\"/model-lab.html\"")
                .contains("模型实验");
    }

    private String read(String name) throws IOException {
        return Files.readString(
                Path.of("src/main/resources/static", name),
                StandardCharsets.UTF_8
        );
    }
}
