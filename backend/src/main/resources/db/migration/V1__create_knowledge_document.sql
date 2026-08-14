-- 保存文档本身的身份、处理状态和错误信息。
-- 文档切片及向量继续保存在 vector_store 表中。
CREATE TABLE knowledge_document (
                                    id uuid PRIMARY KEY,
                                    filename varchar(255) NOT NULL,
                                    content_hash char(64) NOT NULL,
                                    status varchar(20) NOT NULL,
                                    chunk_count integer NOT NULL DEFAULT 0,
                                    error_message varchar(500),
                                    created_at timestamptz NOT NULL DEFAULT now(),
                                    updated_at timestamptz NOT NULL DEFAULT now(),

    -- 同一份文件内容只允许存在一条记录，与文件名无关。
                                    CONSTRAINT uk_knowledge_document_content_hash
                                        UNIQUE (content_hash),

    -- 限制状态值，避免数据库中出现未知状态。
                                    CONSTRAINT ck_knowledge_document_status
                                        CHECK (status IN ('PROCESSING', 'READY', 'FAILED')),

                                    CONSTRAINT ck_knowledge_document_chunk_count
                                        CHECK (chunk_count >= 0)
);

-- 文档列表按创建时间倒序查询，该索引用于提高查询效率。
CREATE INDEX idx_knowledge_document_created_at
    ON knowledge_document (created_at DESC);