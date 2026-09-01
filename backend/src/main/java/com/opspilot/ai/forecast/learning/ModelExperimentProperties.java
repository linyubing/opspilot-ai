package com.opspilot.ai.forecast.learning;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.regex.Pattern;

/** 模型实验配置属性。 */
@ConfigurationProperties(prefix = "opspilot.build")
public record ModelExperimentProperties(String gitCommit) {

    private static final Pattern HEX_PATTERN = Pattern.compile("^[0-9a-f]{7,40}$");

    public ModelExperimentProperties {
        if (gitCommit == null || gitCommit.isBlank()) {
            gitCommit = "unknown";
        }
    }

    /** 校验 Git 提交哈希是否有效；无效时抛出中文异常。 */
    public void validateGitCommit() {
        String commit = gitCommit();
        if (commit == null || commit.isBlank() || "unknown".equals(commit)
                || !HEX_PATTERN.matcher(commit).matches()) {
            throw new ModelExperimentException(
                    "无法确定当前代码提交版本，实验未运行。请设置 GIT_COMMIT 环境变量或确认构建期 git.properties 已生成。"
            );
        }
    }

    public String gitCommit() {
        return gitCommit;
    }
}
