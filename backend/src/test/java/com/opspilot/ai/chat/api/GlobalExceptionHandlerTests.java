package com.opspilot.ai.chat.api;

import com.opspilot.ai.chat.UpstreamAiException;
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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class GlobalExceptionHandlerTests {

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
}
