package com.opspilot.ai.forecast.learning;

import org.junit.jupiter.api.Test;
import org.springframework.boot.info.GitProperties;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitCommitProviderTests {

    @Test
    void envVariableTakesPriorityOverGitProperties() {
        ModelExperimentProperties props = new ModelExperimentProperties("abc1234");
        GitProperties gitProps = createGitProperties("def5678");
        GitCommitProvider provider = new GitCommitProvider(props, gitProps);

        assertThat(provider.get()).isEqualTo("abc1234");
    }

    @Test
    void unknownEnvValueFallsBackToGitProperties() {
        ModelExperimentProperties props = new ModelExperimentProperties("unknown");
        GitProperties gitProps = createGitProperties("def567890");
        GitCommitProvider provider = new GitCommitProvider(props, gitProps);

        assertThat(provider.get()).isEqualTo("def567890");
    }

    @Test
    void blankEnvValueFallsBackToGitProperties() {
        ModelExperimentProperties props = new ModelExperimentProperties("  ");
        GitProperties gitProps = createGitProperties("aabbccdd");
        GitCommitProvider provider = new GitCommitProvider(props, gitProps);

        assertThat(provider.get()).isEqualTo("aabbccdd");
    }

    @Test
    void nullEnvValueFallsBackToGitProperties() {
        ModelExperimentProperties props = new ModelExperimentProperties(null);
        GitProperties gitProps = createGitProperties("1122334455");
        GitCommitProvider provider = new GitCommitProvider(props, gitProps);

        assertThat(provider.get()).isEqualTo("1122334455");
    }

    @Test
    void bothInvalidReturnsUnknown() {
        ModelExperimentProperties props = new ModelExperimentProperties("unknown");
        GitCommitProvider provider = new GitCommitProvider(props, null);

        assertThat(provider.get()).isEqualTo("unknown");
    }

    @Test
    void getRequiredRejectsUnknown() {
        ModelExperimentProperties props = new ModelExperimentProperties("unknown");
        GitCommitProvider provider = new GitCommitProvider(props, null);

        assertThatThrownBy(provider::getRequired)
                .isInstanceOf(ModelExperimentException.class)
                .hasMessageContaining("无法确定当前代码提交版本");
    }

    @Test
    void getRequiredRejectsNonHex() {
        ModelExperimentProperties props = new ModelExperimentProperties("xyz12345");
        GitCommitProvider provider = new GitCommitProvider(props, null);

        assertThatThrownBy(provider::getRequired)
                .isInstanceOf(ModelExperimentException.class)
                .hasMessageContaining("无法确定当前代码提交版本");
    }

    @Test
    void getRequiredAccepts7CharAbbreviatedHash() {
        ModelExperimentProperties props = new ModelExperimentProperties("7e57c99");
        GitCommitProvider provider = new GitCommitProvider(props, null);

        assertThat(provider.getRequired()).isEqualTo("7e57c99");
    }

    @Test
    void getRequiredAccepts40CharFullHash() {
        String fullHash = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
        ModelExperimentProperties props = new ModelExperimentProperties(fullHash);
        GitCommitProvider provider = new GitCommitProvider(props, null);

        assertThat(provider.getRequired()).isEqualTo(fullHash);
    }

    @Test
    void getRequiredRejectsTooShort() {
        ModelExperimentProperties props = new ModelExperimentProperties("abc123");
        GitCommitProvider provider = new GitCommitProvider(props, null);

        assertThatThrownBy(provider::getRequired)
                .isInstanceOf(ModelExperimentException.class);
    }

    @Test
    void getRequiredRejectsUppercase() {
        ModelExperimentProperties props = new ModelExperimentProperties("ABC1234");
        GitCommitProvider provider = new GitCommitProvider(props, null);

        assertThatThrownBy(provider::getRequired)
                .isInstanceOf(ModelExperimentException.class);
    }

    private GitProperties createGitProperties(String commitId) {
        Properties p = new Properties();
        p.setProperty("commit.id", commitId);
        return new GitProperties(p);
    }
}
