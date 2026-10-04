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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.UUID;

/** 只读导出真实快照的宏观数值候选；不调用模型，不产生预测或准确率。 */
public class MacroPromptProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("请提供正式快照UUID");
        String password = System.getenv("OPSPILOT_DB_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("缺少数据库密码环境变量");
        var json = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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
                throw new IllegalArgumentException("SNAPSHOT_INPUT_INCOMPLETE: 缺少完整已确认黄金窗口");
            }
            var builder = new GoldForecastPromptBuilder();
            var output = new LinkedHashMap<String, Object>();
            output.put("researchOnly", true);
            output.put("status", "INPUT_EXPORTED_NOT_RUN");
            output.put("exportedAt", exportedAt);
            output.put("snapshot", record);
            output.put("baseline", builder.build(record));
            output.put("candidate", builder.buildMacro(record));
            connection.rollback();
            // ASCII封装避免PowerShell代码页破坏中文或提示词摘要。
            System.out.println("MACRO_PAIR_BASE64="
                    + Base64.getEncoder().encodeToString(json.writeValueAsBytes(output)));
        }
    }
}
