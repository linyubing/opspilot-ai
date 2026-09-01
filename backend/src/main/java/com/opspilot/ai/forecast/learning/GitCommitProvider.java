package com.opspilot.ai.forecast.learning;

import org.springframework.boot.info.GitProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/** 按优先级提供当前构建对应的Git提交哈希。 */
@Component
public class GitCommitProvider {

    private static final Pattern HEX_PATTERN = Pattern.compile("^[0-9a-f]{7,40}$");

    private static final String REJECT_MESSAGE =
            "无法确定当前代码提交版本，实验未运行。请设置 GIT_COMMIT 环境变量或确认构建期 git.properties 已生成。";

    private final ModelExperimentProperties properties;
    private final Optional<GitProperties> gitProperties;

    public GitCommitProvider(
            ModelExperimentProperties properties,
            @Nullable GitProperties gitProperties
    ) {
        this.properties = properties;
        this.gitProperties = Optional.ofNullable(gitProperties);
    }

    /** 返回当前Git提交哈希；无法确定时返回 {@code unknown}。 */
    public String get() {
        String envValue = properties.gitCommit();
        if (envValue != null && !envValue.isBlank() && !"unknown".equals(envValue)) {
            return envValue;
        }

        return gitProperties
                .map(GitProperties::getCommitId)
                .filter(id -> id != null && !id.isBlank())
                .orElse("unknown");
    }

    /** 返回有效的Git提交哈希；无效时抛出 {@link ModelExperimentException}。 */
    public String getRequired() {
        String commit = get();
        if (commit == null || commit.isBlank() || "unknown".equals(commit)
                || !HEX_PATTERN.matcher(commit).matches()) {
            throw new ModelExperimentException(REJECT_MESSAGE);
        }
        return commit;
    }
}
