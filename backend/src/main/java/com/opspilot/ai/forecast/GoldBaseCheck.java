package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.marketdata.GoldSession;
import com.opspilot.ai.marketdata.GoldSessionCheck;
import com.opspilot.ai.marketdata.GoldSessionCheckResult;
import com.opspilot.ai.marketdata.GoldSessionCheckService;
import com.opspilot.ai.marketdata.MarketDataUnavailableException;
import org.springframework.stereotype.Service;
import java.time.Clock;

/** 在模型调用前核验快照原始基准；不替换价格，也不证明历史可得性。 */
@Service
public class GoldBaseCheck {
    private final GoldSessionCheckService sessions;
    private final Clock clock;

    public GoldBaseCheck(GoldSessionCheckService sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    public GoldSessionCheckResult check(StoredGoldResearchSnapshot snapshot) {
        // 上游已验证完整冻结窗口；必须核验其中的原价，不能重下日线替换。
        var day = snapshot.snapshot().input().bars().getFirst();
        var started = clock.instant();
        GoldSessionCheckResult proof;
        try {
            proof = sessions.check(day);
        } catch (RuntimeException error) {
            // 不向接口或日志透传供应商错误中的地址、凭据等内容。
            throw new MarketDataUnavailableException("基准小时行情暂不可核验，未调用预测模型");
        }
        var now = clock.instant();
        if (proof == null || !proof.matched() || proof.checkedAt() == null
                || proof.checkedAt().isBefore(started) || proof.checkedAt().isAfter(now)
                || !GoldSession.forDate(day.priceDate()).equals(proof.session())
                || proof.input() == null || !day.equals(proof.input().day())
                || !("REQUEST_ONLY_UTC".equals(proof.input().timezoneBasis())
                     || "RESPONSE_UTC".equals(proof.input().timezoneBasis()))
                || !day.isConfirmedAt(java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
                || !new GoldSessionCheck().check(day, proof.input().hours(), proof.checkedAt()).matched()) {
            throw new MarketDataUnavailableException("基准原价未通过生成前小时核验，未调用预测模型");
        }
        return proof;
    }
}
