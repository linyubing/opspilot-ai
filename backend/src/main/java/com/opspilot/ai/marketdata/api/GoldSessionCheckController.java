package com.opspilot.ai.marketdata.api;

import com.opspilot.ai.marketdata.GoldSessionCheckService;
import com.opspilot.ai.marketdata.GoldSessionCheckResult;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/** 公开候选时段诊断，不返回原始价格，也不生成预测。 */
@RestController
@RequestMapping("/api/market-data/gold/session-check")
public class GoldSessionCheckController {
    private final GoldSessionCheckService service;

    public GoldSessionCheckController(GoldSessionCheckService service) {
        this.service = service;
    }

    @GetMapping
    public Response check(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        var result = service.check(date);
        return new Response(result.session().date(), result.session().start(), result.session().end(),
                result.checkedAt(), GoldSessionCheckResult.RULE_VERSION, result.status(), result.expectedHours(),
                result.receivedRows(), result.missingHours(), result.reason(), result.matched(),
                "候选时段核验，不是历史可得性或正式预测晋级证明");
    }

    /** 诊断边界只公开时间、计数与原因，故意不含价格及凭据。 */
    public record Response(@JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate date,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant start,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant end,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant checkedAt,
            String ruleVersion, GoldSessionCheckResult.Status status, int expectedHours,
            int receivedRows, @JsonFormat(shape = JsonFormat.Shape.STRING) List<Instant> missingHours,
            String reason, boolean matched, String warning) {
    }
}
