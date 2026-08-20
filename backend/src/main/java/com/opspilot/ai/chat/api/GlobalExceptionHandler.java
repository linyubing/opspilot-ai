package com.opspilot.ai.chat.api;

import com.opspilot.ai.chat.UpstreamAiException;
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
    ){
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
}
