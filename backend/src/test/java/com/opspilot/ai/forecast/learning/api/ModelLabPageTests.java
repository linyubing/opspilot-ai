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
    void showsFeatureProfileTabs() throws IOException {
        String html = read("model-lab.html");

        assertThat(html)
                .contains("data-profile=\"BASE_16\"")
                .contains("data-profile=\"OHLC_20\"")
                .contains("data-profile=\"ALL_36\"");
    }

    @Test
    void showsExperimentHistorySection() throws IOException {
        String html = read("model-lab.html");

        assertThat(html)
                .contains("实验历史")
                .contains("保存实验")
                .contains("experimentList")
                .contains("experimentDetail");
    }

    @Test
    void showsDevNoticeAndRelativeImprovement() throws IOException {
        String html = read("model-lab.html");

        assertThat(html)
                .contains("开发验证结果")
                .contains("相对多数类基线提升")
                .contains("相对 BASE_16 提升");
    }

    @Test
    void initialLoadDoesNotPost() throws IOException {
        String js = read("model-lab.js");

        assertThat(js)
                .contains("loadHistory()")
                .doesNotContain("load(\"NEXT_DAY\"");
    }

    @Test
    void historyDisplaysProfileAndAccuracy() throws IOException {
        String js = read("model-lab.js");

        assertThat(js)
                .contains("featureProfile")
                .contains("Accuracy")
                .contains("accuracy");
    }

    @Test
    void detailDisplaysBothModelMetrics() throws IOException {
        String js = read("model-lab.js");

        assertThat(js)
                .contains("majority")
                .contains("logistic");
    }

    @Test
    void showsXgboostAndStage8Comparison() throws IOException {
        String html = read("model-lab.html");
        String js = read("model-lab.js");

        assertThat(html)
                .contains("id=\"runComparison\"")
                .contains("id=\"xgboostAccuracy\"")
                .contains("id=\"candidateResult\"")
                .contains("开发验证结果，尚未进行最终留出集验收");
        assertThat(js)
                .contains("/compare?horizon=")
                .contains("stage8Candidate")
                .contains("candidateReason")
                .contains("data.xgboost");
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
