package com.opspilot.ai.chat.api;

import com.opspilot.ai.analysis.InsufficientResearchDataException;
import com.opspilot.ai.analysis.InvalidResearchDataException;
import com.opspilot.ai.analysis.narrative.GoldResearchSnapshotNotFoundException;
import com.opspilot.ai.analysis.narrative.InvalidResearchAiResponseException;
import com.opspilot.ai.analysis.narrative.ResearchAiUnavailableException;
import com.opspilot.ai.analysis.narrative.UnsafeResearchNarrativeException;
import com.opspilot.ai.chat.UpstreamAiException;
import com.opspilot.ai.forecast.GoldForecastAiUnavailableException;
import com.opspilot.ai.forecast.InvalidGoldForecastAiResponseException;
import com.opspilot.ai.forecast.InvalidGoldForecastSnapshotException;
import com.opspilot.ai.forecast.UnsafeGoldForecastException;
import com.opspilot.ai.macrodata.InvalidMacroDataRequestException;
import com.opspilot.ai.macrodata.MacroDataUnavailableException;
import com.opspilot.ai.marketdata.InvalidMarketDataRequestException;
import com.opspilot.ai.marketdata.MarketDataUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class GlobalExceptionHandlerTests {

    @Test
    void returnsUnprocessableEntityForInvalidForecastSnapshot() {
        ResponseEntity<ApiError> response = new GlobalExceptionHandler()
                .handleInvalidGoldForecastSnapshot(new InvalidGoldForecastSnapshotException("old-v1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("INVALID_GOLD_FORECAST_SNAPSHOT");
    }

    @Test
    void returnsServiceUnavailableForForecastAiFailure() {
        ResponseEntity<ApiError> response = new GlobalExceptionHandler()
                .handleGoldForecastAiUnavailable(new GoldForecastAiUnavailableException("不可用", new RuntimeException()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo("GOLD_FORECAST_AI_UNAVAILABLE");
    }

    @Test
    void returnsBadGatewayForInvalidForecastAiResponse() {
        ResponseEntity<ApiError> response = new GlobalExceptionHandler()
                .handleInvalidGoldForecastAiResponse(new InvalidGoldForecastAiResponseException("非法", new RuntimeException()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().code()).isEqualTo("INVALID_GOLD_FORECAST_AI_RESPONSE");
    }

    @Test
    void returnsUnprocessableEntityForUnsafeForecast() {
        ResponseEntity<ApiError> response = new GlobalExceptionHandler()
                .handleUnsafeGoldForecast(new UnsafeGoldForecastException("越界"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("UNSAFE_GOLD_FORECAST");
    }

    @Test
    void returnsNotFoundForMissingResearchSnapshot() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<ApiError> response = handler.handleGoldResearchSnapshotNotFound(
                new GoldResearchSnapshotNotFoundException(UUID.randomUUID())
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code())
                .isEqualTo("GOLD_RESEARCH_SNAPSHOT_NOT_FOUND");
    }

    @Test
    void returnsServiceUnavailableForResearchAiFailure() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<ApiError> response = handler.handleResearchAiUnavailable(
                new ResearchAiUnavailableException(
                        "黄金研究大模型暂时不可用，请稍后重试",
                        new IllegalStateException()
                )
        );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code())
                .isEqualTo("RESEARCH_AI_UNAVAILABLE");
    }

    @Test
    void returnsBadGatewayForInvalidResearchAiResponse() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<ApiError> response = handler.handleInvalidResearchAiResponse(
                new InvalidResearchAiResponseException(
                        "大模型未返回合法的结构化研究解读",
                        new IllegalArgumentException()
                )
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().code())
                .isEqualTo("INVALID_RESEARCH_AI_RESPONSE");
    }

    @Test
    void returnsUnprocessableEntityForUnsafeNarrative() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<ApiError> response = handler.handleUnsafeResearchNarrative(
                new UnsafeResearchNarrativeException("研究解读包含禁止内容")
        );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code())
                .isEqualTo("UNSAFE_RESEARCH_NARRATIVE");
    }

    @Test
    void returnsBadGatewayForUpstreamAiFailure(){
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        UpstreamAiException exception = new UpstreamAiException(
                "AI 服务暂时不可用，请稍后重试",
                new IllegalStateException("上游原始错误")
        );

        ResponseEntity<ApiError> response =
                handler.handleUpstreamAiException(exception);

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.BAD_GATEWAY);

        assertThat(response.getBody())
                .isEqualTo(new ApiError(
                        "AI_SERVICE_UNAVAILABLE",
                        "AI 服务暂时不可用，请稍后重试"
                ));
    }

    @Test
    @DisplayName("行情供应商不可用时返回 503")
    void returnsServiceUnavailableForMarketDataFailure() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiError> response =
                handler.handleMarketDataUnavailableException(
                        new MarketDataUnavailableException(
                                "黄金行情服务暂时不可用"
                        )
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .isEqualTo(new ApiError(
                        "MARKET_DATA_UNAVAILABLE",
                        "黄金行情服务暂时不可用"
                ));
    }

    @Test
    @DisplayName("行情查询参数无效时返回 400")
    void returnsBadRequestForInvalidMarketDataRequest() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiError> response =
                handler.handleInvalidMarketDataRequest(
                        new InvalidMarketDataRequestException(
                                "limit 必须在 1 到 500 之间"
                        )
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .isEqualTo(new ApiError(
                        "INVALID_MARKET_DATA_REQUEST",
                        "limit 必须在 1 到 500 之间"
                ));
    }

    @Test
    @DisplayName("不会把所有普通参数异常标记成行情请求错误")
    void doesNotHandleEveryIllegalArgumentException() {
        boolean handlesIllegalArgumentException = Arrays.stream(
                        GlobalExceptionHandler.class.getDeclaredMethods()
                )
                .map(method -> method.getAnnotation(ExceptionHandler.class))
                .filter(annotation -> annotation != null)
                .flatMap(annotation -> Arrays.stream(annotation.value()))
                .anyMatch(IllegalArgumentException.class::equals);

        assertThat(handlesIllegalArgumentException).isFalse();
    }

    @Test
    @DisplayName("宏观数据来源不可用时返回 503")
    void returnsServiceUnavailableForMacroDataFailure() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiError> response =
                handler.handleMacroDataUnavailable(
                        new MacroDataUnavailableException(
                                "FRED 实际利率服务暂时不可用"
                        )
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(new ApiError(
                "MACRO_DATA_UNAVAILABLE",
                "FRED 实际利率服务暂时不可用"
        ));
    }

    @Test
    @DisplayName("宏观查询参数无效时返回 400")
    void returnsBadRequestForInvalidMacroDataRequest() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiError> response =
                handler.handleInvalidMacroDataRequest(
                        new InvalidMacroDataRequestException(
                                "limit 必须在 1 到 500 之间"
                        )
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(new ApiError(
                "INVALID_MACRO_DATA_REQUEST",
                "limit 必须在 1 到 500 之间"
        ));
    }

    @Test
    @DisplayName("研究数据不足时返回 422")
    void returnsUnprocessableEntityForInsufficientResearchData() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiError> response =
                handler.handleInsufficientResearchData(
                        new InsufficientResearchDataException(
                                "共同观测日期不足"
                        )
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isEqualTo(new ApiError(
                "INSUFFICIENT_RESEARCH_DATA",
                "共同观测日期不足"
        ));
    }

    @Test
    @DisplayName("研究数据非法时返回 422")
    void returnsUnprocessableEntityForInvalidResearchData() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiError> response =
                handler.handleInvalidResearchData(
                        new InvalidResearchDataException(
                                "黄金价格必须大于 0"
                        )
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isEqualTo(new ApiError(
                "INVALID_RESEARCH_DATA",
                "黄金价格必须大于 0"
        ));
    }
}
