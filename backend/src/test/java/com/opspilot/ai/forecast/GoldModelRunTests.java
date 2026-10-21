package com.opspilot.ai.forecast;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

/** 验证运行观察的不可变输入与分组边界；不产生预测样本。 */
class GoldModelRunTests {
    @Test void keyIgnoresOrderTimeAndPromptButNotParameters() throws Exception {
        var json=new ObjectMapper();
        var a=json.readTree("{\"model\":\"test\",\"think\":false,\"stream\":false,\"options\":{\"num_ctx\":8192,\"temperature\":0}}");
        var b=json.readTree("{\"options\":{\"temperature\":0,\"num_ctx\":8192},\"stream\":false,\"think\":false,\"model\":\"test\"}");
        var first=run(a,"a",0);
        var second=run(b,"b",100);
        assertThat(first.key()).isEqualTo(second.key());
        ((com.fasterxml.jackson.databind.node.ObjectNode)a.path("options")).put("num_ctx",4096);
        assertThat(first.parameters().path("options").path("num_ctx").asInt()).isEqualTo(8192);
        assertThat(run(a,"a",0).key()).isNotEqualTo(first.key());
        ((com.fasterxml.jackson.databind.node.ObjectNode)first.parameters()).put("think",true);
        assertThat(first.parameters().path("think").asBoolean()).isFalse();
    }

    @Test void rejectsConflictingThinkingAndFutureOrder() throws Exception {
        var json=new ObjectMapper();
        var a=json.readTree("{\"model\":\"test\",\"think\":false,\"stream\":false,\"options\":{\"think\":true}}");
        assertThatIllegalArgumentException().isThrownBy(() -> run(a,"a",0));
        ((com.fasterxml.jackson.databind.node.ObjectNode)a.path("options")).put("think",false);
        var time=Instant.parse("2026-10-21T00:00:00Z");
        assertThatIllegalArgumentException().isThrownBy(() -> new GoldModelRun("test","a".repeat(64),"0.33.3","b".repeat(64),a,
                time,time,time,time.minusSeconds(1)));
    }

    private GoldModelRun run(com.fasterxml.jackson.databind.JsonNode p,String prompt,long seconds) {
        var time=Instant.parse("2026-10-21T00:00:00Z").plusSeconds(seconds);
        return new GoldModelRun("test","a".repeat(64),"0.33.3",prompt.repeat(64),p,time,time,time,time);
    }
}
