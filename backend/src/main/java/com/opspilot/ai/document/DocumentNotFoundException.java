package com.opspilot.ai.document;

/**
 * 表示指定知识库文档不存在。
 */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(String message) {
        super(message);
    }
}
