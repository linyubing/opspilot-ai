package com.opspilot.ai.chat.api;

import com.opspilot.ai.chat.UpstreamAiException;
import org.apache.coyote.Response;
import org.junit.jupiter.api.Test;
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
}
