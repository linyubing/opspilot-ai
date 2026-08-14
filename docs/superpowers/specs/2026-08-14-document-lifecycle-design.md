# 知识库文档生命周期设计

## 目标

在现有文档上传和 RAG 流程上增加文档身份、重复检测、列表查询和删除能力，
保证一份原始文档与它在 PGVector 中的全部向量切片可以被统一管理。

## 范围

本阶段实现：

- 相同内容重复上传时返回 HTTP 409。
- 文件名相同但内容不同，作为不同文档保存。
- 查询文档列表及处理状态。
- 按文档 ID 删除文档记录及全部向量切片。
- 上传失败时记录失败状态，并补偿清理可能已经写入的向量。

本阶段不保存原始文件，不实现文档版本树、分页、用户隔离和异步任务队列。

## 技术选择

使用 `JdbcTemplate` 操作 `knowledge_document` 表，使用 Flyway 管理数据库结构。
不引入 JPA，避免把学习重点转移到 ORM 映射。向量仍由 Spring AI
`PgVectorStore` 管理。

## 数据模型

新增表 `knowledge_document`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 文档唯一标识，由应用生成 |
| `filename` | `varchar(255)` | 上传时的原始文件名 |
| `content_hash` | `char(64)` | 文件内容的 SHA-256，小写十六进制 |
| `status` | `varchar(20)` | `PROCESSING`、`READY` 或 `FAILED` |
| `chunk_count` | `integer` | 成功写入的切片数量 |
| `error_message` | `varchar(500)` | 失败原因摘要，不保存堆栈和敏感信息 |
| `created_at` | `timestamptz` | 创建时间 |
| `updated_at` | `timestamptz` | 最后更新时间 |

`content_hash` 建立唯一约束，用数据库约束兜住并发重复上传。

每个写入 `vector_store` 的切片都增加以下 metadata：

- `documentId`：对应 `knowledge_document.id` 的字符串形式。
- `filename`：原始文件名，用于展示来源。
- `chunkIndex`：切片在当前文档中的顺序，从 0 开始。

## 组件边界

### DocumentRecord

领域记录，表示一份原始文档的身份和处理状态，不包含向量内容。

### DocumentRepository

使用 `JdbcTemplate` 完成文档记录的新增、按哈希查询、列表、状态更新和删除。
SQL 集中在该组件中，Controller 不直接访问数据库。

### DocumentUploadService

协调一次完整上传：读取字节、计算 SHA-256、检测重复、创建文档记录、调用
摄取服务、更新结果状态。它负责用例编排，不负责 HTTP 参数校验。

### DocumentIngestionService

继续负责 Extract、Transform、Load，但接收文档 ID 和文件名，为每个切片补充
metadata 后再写入 `VectorStore`。

### DocumentLifecycleService

负责文档列表和删除。删除时先按 `documentId` 过滤并删除 PGVector 切片，再删除
`knowledge_document` 记录；文档不存在时返回 404。

### DocumentController

保留 `POST /api/documents`，新增：

- `GET /api/documents`：返回文档列表。
- `DELETE /api/documents/{id}`：删除文档及其向量，成功返回 HTTP 204。

## 上传流程

1. Controller 完成文件为空、大小、扩展名和真实类型校验。
2. UploadService 读取文件字节并计算 SHA-256。
3. Repository 按哈希检查；`READY` 或 `PROCESSING` 状态已存在时抛出
   `DuplicateDocumentException`。如果已有记录为 `FAILED`，则复用原文档 ID，
   清空错误信息并重置为 `PROCESSING`，允许重新处理。
4. 创建状态为 `PROCESSING` 的文档记录。
5. Tika 解析并切片，为切片写入 `documentId`、`filename`、`chunkIndex`。
6. PgVectorStore 生成向量并写入 PostgreSQL。
7. 文档记录更新为 `READY`，保存 `chunk_count`。
8. 任一步失败时，按 `documentId` 删除可能存在的切片，将记录更新为 `FAILED`，
   然后继续抛出原始业务异常。

唯一约束冲突同样转换为重复文档错误，避免并发上传绕过应用层检查。
失败重试仍复用同一条唯一哈希记录，不新增重复数据。

## 删除流程

1. 按 ID 查询文档，不存在时返回 404。
2. 调用 `VectorStore.delete("documentId == '...'" )` 删除全部向量切片。
3. 删除 `knowledge_document` 记录。
4. 返回 HTTP 204。

删除操作使用数据库事务。PGVectorStore 和 Repository 共享同一个 DataSource，
因此 JDBC 删除可以加入同一 Spring 事务。

## HTTP 响应

上传成功返回：

```json
{
  "id": "文档 UUID",
  "filename": "运维手册.pdf",
  "status": "READY",
  "chunkCount": 12,
  "createdAt": "2026-08-14T19:00:00+08:00"
}
```

文档列表返回上述结构的 JSON 数组，不返回 `content_hash` 和内部错误堆栈。

错误映射：

- 重复内容：HTTP 409，错误码 `DOCUMENT_ALREADY_EXISTS`。
- 文档不存在：HTTP 404，错误码 `DOCUMENT_NOT_FOUND`。
- 解析或向量写入失败：保持现有异常处理，并将文档状态记录为 `FAILED`。

## 测试策略

单元测试覆盖：

- 相同字节得到相同 SHA-256。
- 重复哈希被拒绝，且不调用摄取服务。
- `FAILED` 状态的相同哈希允许重试并复用文档 ID。
- 上传成功后记录变为 `READY` 并保存切片数。
- 上传失败时执行向量补偿删除并标记 `FAILED`。
- 删除不存在的文档返回领域异常。
- 删除存在文档时使用 `documentId` 过滤向量。

Controller 测试覆盖 201、409、列表 200、删除 204 和不存在 404。

集成验收覆盖：上传文档、列表可见、数据库存在向量、删除文档、列表消失、
数据库对应向量归零、RAG 不再召回已删除内容。

## 验收标准

1. 相同测试文件第二次上传返回 HTTP 409，向量行数不增加。
2. `GET /api/documents` 能看到文件名、状态和切片数量。
3. 删除文档后，文档记录及其所有向量切片均不存在。
4. 删除后的内容不能再被 RAG 召回。
5. 完整 Maven 测试通过，Git 差异中不存在密码或 API Key 明文。
