package com.opspilot.ai.analysis.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证黄金数据新鲜度独立页面的静态资源契约。 */
class GoldDataStatusPageTests {

    @Test
    void pageLoadsDataStatusApi() throws IOException {
        String html = read("data-status.html");
        String script = read("data-status.js");

        assertThat(html)
                .contains("黄金数据新鲜度")
                .contains("data-status.js")
                .contains("id=\"overall\"")
                .contains("id=\"items\"");
        assertThat(script)
                .contains("/api/research/gold/data-status")
                .contains("renderStatus")
                .contains("dollarIndex");
    }

    private String read(String name) throws IOException {
        return Files.readString(
                Path.of("src/main/resources/static", name),
                StandardCharsets.UTF_8
        );
    }
}
