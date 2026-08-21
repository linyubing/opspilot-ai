package com.opspilot.ai.macrodata;

public class MacroDataUnavailableException extends RuntimeException {

    public MacroDataUnavailableException(String message) {
        super(message);
    }

    public MacroDataUnavailableException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
