package com.opspilot.ai.chat.api;

import com.opspilot.ai.analysis.InsufficientResearchDataException;
import com.opspilot.ai.analysis.GoldDailyResearchReportNotFoundException;
import com.opspilot.ai.analysis.InvalidResearchDataException;
import com.opspilot.ai.analysis.narrative.GoldResearchSnapshotNotFoundException;
import com.opspilot.ai.analysis.narrative.InvalidResearchAiResponseException;
import com.opspilot.ai.analysis.narrative.ResearchAiUnavailableException;
import com.opspilot.ai.analysis.narrative.UnsafeResearchNarrativeException;
import com.opspilot.ai.analysis.history.InvalidResearchHistoryRequestException;
import com.opspilot.ai.chat.UpstreamAiException;
import com.opspilot.ai.forecast.GoldForecastAiUnavailableException;
import com.opspilot.ai.forecast.InvalidGoldForecastAiResponseException;
import com.opspilot.ai.forecast.InvalidGoldForecastSnapshotException;
import com.opspilot.ai.forecast.StaleGoldForecastDataException;
import com.opspilot.ai.forecast.UnsafeGoldForecastException;
import com.opspilot.ai.forecast.backtest.BacktestDataInsufficientException;
import com.opspilot.ai.forecast.backtest.BacktestNotFoundException;
import com.opspilot.ai.forecast.backtest.InvalidBacktestRequestException;
import com.opspilot.ai.forecast.backtest.review.BacktestReviewAiUnavailableException;
import com.opspilot.ai.forecast.backtest.review.InvalidBacktestReviewAiResponseException;
import com.opspilot.ai.forecast.backtest.review.NoBacktestErrorsException;
import com.opspilot.ai.forecast.review.GoldForecastReviewAiUnavailableException;
import com.opspilot.ai.forecast.review.InsufficientForecastReviewSamplesException;
import com.opspilot.ai.forecast.review.InvalidGoldForecastReviewAiResponseException;
import com.opspilot.ai.macrodata.InvalidMacroDataRequestException;
import com.opspilot.ai.macrodata.DollarIndexNotFoundException;
import com.opspilot.ai.macrodata.MacroDataUnavailableException;
import com.opspilot.ai.marketdata.InvalidMarketDataRequestException;
import com.opspilot.ai.marketdata.MarketDataUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将各业务模块的专用异常转换成稳定的 HTTP 错误响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoBacktestErrorsException.class)
    public ResponseEntity<ApiError> handleNoBacktestErrors(
            NoBacktestErrorsException exception
    ) {
        return ResponseEntity.unprocessableEntity().body(
                new ApiError("NO_BACKTEST_ERRORS", exception.getMessage())
        );
    }

    @ExceptionHandler(BacktestReviewAiUnavailableException.class)
    public ResponseEntity<ApiError> handleBacktestReviewAiUnavailable(
            BacktestReviewAiUnavailableException exception
    ) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                new ApiError(
                        "BACKTEST_REVIEW_AI_UNAVAILABLE",
                        exception.getMessage()
                )
        );
    }

    @ExceptionHandler(InvalidBacktestReviewAiResponseException.class)
    public ResponseEntity<ApiError> handleInvalidBacktestReviewResponse(
            InvalidBacktestReviewAiResponseException exception
    ) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                new ApiError(
                        "INVALID_BACKTEST_REVIEW_AI_RESPONSE",
                        exception.getMessage()
                )
        );
    }

    @ExceptionHandler(BacktestNotFoundException.class)
    public ResponseEntity<ApiError> handleBacktestNotFound(
            BacktestNotFoundException exception
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ApiError("BACKTEST_NOT_FOUND", exception.getMessage())
        );
    }

    @ExceptionHandler(InvalidBacktestRequestException.class)
    public ResponseEntity<ApiError> handleInvalidBacktestRequest(
            InvalidBacktestRequestException exception
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                new ApiError("INVALID_BACKTEST_REQUEST", exception.getMessage())
        );
    }

    @ExceptionHandler(BacktestDataInsufficientException.class)
    public ResponseEntity<ApiError> handleBacktestDataInsufficient(
            BacktestDataInsufficientException exception
    ) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                new ApiError("BACKTEST_DATA_INSUFFICIENT", exception.getMessage())
        );
    }

    @ExceptionHandler(InsufficientForecastReviewSamplesException.class)
    public ResponseEntity<ApiError> handleReviewSamples(
            InsufficientForecastReviewSamplesException exception
    ) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                new ApiError(
                        "INSUFFICIENT_FORECAST_REVIEW_SAMPLES",
                        exception.getMessage()
                )
        );
    }

    @ExceptionHandler(GoldForecastReviewAiUnavailableException.class)
    public ResponseEntity<ApiError> handleReviewAiUnavailable(
            GoldForecastReviewAiUnavailableException exception
    ) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                new ApiError(
                        "FORECAST_REVIEW_AI_UNAVAILABLE",
                        exception.getMessage()
                )
        );
    }

    @ExceptionHandler(InvalidGoldForecastReviewAiResponseException.class)
    public ResponseEntity<ApiError> handleInvalidReviewResponse(
            InvalidGoldForecastReviewAiResponseException exception
    ) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                new ApiError(
                        "INVALID_FORECAST_REVIEW_AI_RESPONSE",
                        exception.getMessage()
                )
        );
    }

    @ExceptionHandler(GoldDailyResearchReportNotFoundException.class)
    public ResponseEntity<ApiError> handleGoldDailyResearchReportNotFound(
            GoldDailyResearchReportNotFoundException exception
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ApiError(
                        "GOLD_DAILY_REPORT_NOT_FOUND",
                        exception.getMessage()
                )
        );
    }

    @ExceptionHandler(StaleGoldForecastDataException.class)
    public ResponseEntity<ApiError> handleStaleGoldForecastData(
            StaleGoldForecastDataException exception
    ) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                new ApiError("FORECAST_DATA_STALE", exception.getMessage())
        );
    }

    @ExceptionHandler(InvalidGoldForecastSnapshotException.class)
    public ResponseEntity<ApiError> handleInvalidGoldForecastSnapshot(
            InvalidGoldForecastSnapshotException exception
    ) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                new ApiError("INVALID_GOLD_FORECAST_SNAPSHOT", exception.getMessage())
        );
    }

    @ExceptionHandler(GoldForecastAiUnavailableException.class)
    public ResponseEntity<ApiError> handleGoldForecastAiUnavailable(
            GoldForecastAiUnavailableException exception
    ) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                new ApiError("GOLD_FORECAST_AI_UNAVAILABLE", exception.getMessage())
        );
    }

    @ExceptionHandler(InvalidGoldForecastAiResponseException.class)
    public ResponseEntity<ApiError> handleInvalidGoldForecastAiResponse(
            InvalidGoldForecastAiResponseException exception
    ) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                new ApiError("INVALID_GOLD_FORECAST_AI_RESPONSE", exception.getMessage())
        );
    }

    @ExceptionHandler(UnsafeGoldForecastException.class)
    public ResponseEntity<ApiError> handleUnsafeGoldForecast(
            UnsafeGoldForecastException exception
    ) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                new ApiError("UNSAFE_GOLD_FORECAST", exception.getMessage())
        );
    }

    @ExceptionHandler(GoldResearchSnapshotNotFoundException.class)
    public ResponseEntity<ApiError> handleGoldResearchSnapshotNotFound(
            GoldResearchSnapshotNotFoundException exception
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ApiError(
                        "GOLD_RESEARCH_SNAPSHOT_NOT_FOUND",
                        exception.getMessage()
                )
        );
    }

    @ExceptionHandler(ResearchAiUnavailableException.class)
    public ResponseEntity<ApiError> handleResearchAiUnavailable(
            ResearchAiUnavailableException exception
    ) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                new ApiError(
                        "RESEARCH_AI_UNAVAILABLE",
                        exception.getMessage()
                )
        );
    }

    @ExceptionHandler(InvalidResearchAiResponseException.class)
    public ResponseEntity<ApiError> handleInvalidResearchAiResponse(
            InvalidResearchAiResponseException exception
    ) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                new ApiError(
                        "INVALID_RESEARCH_AI_RESPONSE",
                        exception.getMessage()
                )
        );
    }

    @ExceptionHandler(UnsafeResearchNarrativeException.class)
    public ResponseEntity<ApiError> handleUnsafeResearchNarrative(
            UnsafeResearchNarrativeException exception
    ) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                new ApiError(
                        "UNSAFE_RESEARCH_NARRATIVE",
                        exception.getMessage()
                )
        );
    }

    @ExceptionHandler(DollarIndexNotFoundException.class)
    public ResponseEntity<ApiError> handleDollarIndexNotFound(
            DollarIndexNotFoundException exception
    ) {
        ApiError error = new ApiError(
                "DOLLAR_INDEX_NOT_FOUND",
                exception.getMessage()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(InvalidResearchHistoryRequestException.class)
    public ResponseEntity<ApiError> handleInvalidResearchHistoryRequest(
            InvalidResearchHistoryRequestException exception
    ) {
        ApiError error = new ApiError(
                "INVALID_RESEARCH_REQUEST",
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error);
    }

    @ExceptionHandler(UpstreamAiException.class)
    public ResponseEntity<ApiError> handleUpstreamAiException(
            UpstreamAiException exception
    ) {
        ApiError error = new ApiError(
                "AI_SERVICE_UNAVAILABLE",
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(error);
    }

    @ExceptionHandler(MarketDataUnavailableException.class)
    public ResponseEntity<ApiError> handleMarketDataUnavailableException(
            MarketDataUnavailableException exception
    ) {
        ApiError error = new ApiError(
                "MARKET_DATA_UNAVAILABLE",
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error);
    }

    @ExceptionHandler(InvalidMarketDataRequestException.class)
    public ResponseEntity<ApiError> handleInvalidMarketDataRequest(
            InvalidMarketDataRequestException exception
    ) {
        ApiError error = new ApiError(
                "INVALID_MARKET_DATA_REQUEST",
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error);
    }

    /**
     * FRED 等宏观数据来源不可用时返回 503。
     */
    @ExceptionHandler(MacroDataUnavailableException.class)
    public ResponseEntity<ApiError> handleMacroDataUnavailable(
            MacroDataUnavailableException exception
    ) {
        ApiError error = new ApiError(
                "MACRO_DATA_UNAVAILABLE",
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error);
    }

    /**
     * 宏观数据查询参数不合法时返回 400。
     */
    @ExceptionHandler(InvalidMacroDataRequestException.class)
    public ResponseEntity<ApiError> handleInvalidMacroDataRequest(
            InvalidMacroDataRequestException exception
    ) {
        ApiError error = new ApiError(
                "INVALID_MACRO_DATA_REQUEST",
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error);
    }

    /**
     * 原始数据存在，但共同日期数量不足，无法可靠计算指标。
     */
    @ExceptionHandler(InsufficientResearchDataException.class)
    public ResponseEntity<ApiError> handleInsufficientResearchData(
            InsufficientResearchDataException exception
    ) {
        ApiError error = new ApiError(
                "INSUFFICIENT_RESEARCH_DATA",
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(error);
    }

    /**
     * 原始研究数据违反完整性要求时拒绝生成结论。
     */
    @ExceptionHandler(InvalidResearchDataException.class)
    public ResponseEntity<ApiError> handleInvalidResearchData(
            InvalidResearchDataException exception
    ) {
        ApiError error = new ApiError(
                "INVALID_RESEARCH_DATA",
                exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(error);
    }
}
