import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import com.opspilot.ai.analysis.history.JdbcGoldResearchSnapshotRepository;
import com.opspilot.ai.forecast.GoldForecastPromptBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Base64;
import java.util.UUID;

/** 从只读数据库导出一份真实快照及两版提示词；不调用模型，不产生预测成绩。 */
public class NeutralPromptProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("请提供要冻结的正式快照UUID");
        String password = System.getenv("OPSPILOT_DB_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("缺少已有数据库密码环境变量");
        var json = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 不启动Spring应用，避免调度器或Flyway写入；数据库事务明确只读。
        try (var connection = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/opspilot_ai", "postgres", password)) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            try (var statement = connection.createStatement()) {
                statement.execute("set transaction read only");
            }
            var repo = new JdbcGoldResearchSnapshotRepository(
                    new JdbcTemplate(new SingleConnectionDataSource(connection, true)), json);
            var record = repo.findById(UUID.fromString(args[0]))
                    .orElseThrow(() -> new IllegalArgumentException("指定快照不存在"));
            var input = record.snapshot();
            var exportedAt = OffsetDateTime.now(ZoneOffset.UTC);
            if (!GoldResearchSnapshotService.RESEARCH_VERSION.equals(input.researchVersion())
                    || input.input() == null
                    || !input.input().matches(input.latestGoldDate(), input.gold(), exportedAt)) {
                throw new IllegalArgumentException("SNAPSHOT_INPUT_INCOMPLETE: 快照缺少完整已确认黄金窗口，不作为本轮对照输入");
            }
            var builder = new GoldForecastPromptBuilder();
            var output = new LinkedHashMap<String, Object>();
            output.put("researchOnly", true);
            output.put("status", "NOT_SCORED");
            output.put("exportedAt", exportedAt);
            output.put("snapshot", record);
            output.put("baseline", builder.build(record));
            output.put("candidate", builder.buildCandidate(record));
            connection.rollback();
            // Windows终端可能按本地代码页解码标准输出；ASCII封装避免破坏中文及提示词摘要。
            System.out.println("PROMPT_PAIR_BASE64="
                    + Base64.getEncoder().encodeToString(json.writeValueAsBytes(output)));
        }
    }
}
