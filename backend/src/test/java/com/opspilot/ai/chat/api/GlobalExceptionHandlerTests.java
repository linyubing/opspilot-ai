package com.opspilot.ai.chat.api;

import com.opspilot.ai.chat.UpstreamAiException;
import com.opspilot.ai.marketdata.MarketDataUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
                        new IllegalArgumentException(
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
}
