package com.opspilot.ai.ingestion;

/**
 * 文档摄取结果。
 *
 * @param sourceDocumentCount 原始文档数量
 * @param chunkCount          切片后的文档块数量
 */
public record IngestionResult(int sourceDocumentCount,int chunkCount) {
}
