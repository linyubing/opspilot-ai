package com.opspilot.ai.chat.api;

import com.opspilot.ai.chat.UpstreamAiException;
import com.opspilot.ai.macrodata.InvalidMacroDataRequestException;
import com.opspilot.ai.macrodata.MacroDataUnavailableException;
import com.opspilot.ai.marketdata.InvalidMarketDataRequestException;
import com.opspilot.ai.marketdata.MarketDataUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

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
     * FRED 等宏观数据来源不可用时，返回 503。
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
     * 宏观数据查询参数不合法时，返回 400。
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
}
