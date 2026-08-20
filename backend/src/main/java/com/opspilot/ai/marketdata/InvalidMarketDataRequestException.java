package com.opspilot.ai.marketdata;

/**
 * 仅表示行情接口的请求参数不合法。
 *
 * 使用专用异常可以避免把其他模块的 IllegalArgumentException
 * 错误地包装成行情参数错误。
 */
public class InvalidMarketDataRequestException extends RuntimeException {

    public InvalidMarketDataRequestException(String message) {
        super(message);
    }
}
