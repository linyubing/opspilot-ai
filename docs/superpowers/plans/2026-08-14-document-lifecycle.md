# 知识库文档生命周期实施计划

> **执行要求：** 按任务顺序逐项执行，每个生产改动之前先观察对应测试失败。核心业务代码由兵哥亲手编写，助手负责设计解释、代码审查、测试运行和排障。

**目标：** 为知识库增加文档身份、内容去重、处理状态、列表查询和级联删除能力。

**架构：** Flyway 管理 `knowledge_document` 表，`JdbcTemplate` Repository 管理文档记录，Spring AI `PgVectorStore` 继续管理向量切片。每个向量切片通过 metadata 中的 `documentId` 关联原始文档，应用服务协调上传、失败补偿和删除。

**技术栈：** Java 21、Spring Boot 3.5.14、Spring AI 1.1.8、JdbcTemplate、Flyway、PostgreSQL 17、PGVector 0.8.6、JUnit 5、Mockito、AssertJ。

## 全局约束

- 相同内容使用 SHA-256 判断重复，不能依赖文件名。
- `READY` 或 `PROCESSING` 文档重复上传返回 HTTP 409。
- `FAILED` 文档允许复用原 ID 重试。
- 每个向量切片必须包含 `documentId`、`filename`、`chunkIndex` metadata。
- 删除文档必须同时删除全部关联向量。
- 数据库密码继续通过 `OPSPILOT_DB_PASSWORD` 读取。
- 不保存原始文件，不实现分页、用户隔离、异步任务和版本树。
- AI 特有或不直观代码添加简洁中文注释，不给普通 CRUD 逐行注释。

---

### 任务一：使用 Flyway 创建文档表

**文件：**

- 修改：`backend/pom.xml`
- 修改：`backend/src/main/resources/application.yaml`
- 创建：`backend/src/main/resources/db/migration/V1__create_knowledge_document.sql`
- 测试：`backend/src/test/java/com/opspilot/ai/document/DocumentSchemaTests.java`

**产出：** 应用启动时自动创建 `knowledge_document` 表及内容哈希唯一约束。

- [ ] **步骤 1：编写失败的数据库结构测试**

```java
package com.opspilot.ai.document;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class DocumentSchemaTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsKnowledgeDocumentTable() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'knowledge_document'
                """, Integer.class);

        assertThat(count).isEqualTo(1);
    }
}
```

- [ ] **步骤 2：运行测试并确认红灯**

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd -Dtest=DocumentSchemaTests test
```

预期：断言失败，实际表数量为 0。

- [ ] **步骤 3：添加 Flyway 依赖**

```xml
<!-- 管理业务表结构版本，避免在 Java 代码中散落建表 SQL。 -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

- [ ] **步骤 4：创建 V1 迁移脚本**

```sql
CREATE TABLE knowledge_document (
    id uuid PRIMARY KEY,
    filename varchar(255) NOT NULL,
    content_hash char(64) NOT NULL,
    status varchar(20) NOT NULL,
    chunk_count integer NOT NULL DEFAULT 0,
    error_message varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_knowledge_document_content_hash UNIQUE (content_hash),
    CONSTRAINT ck_knowledge_document_status
        CHECK (status IN ('PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT ck_knowledge_document_chunk_count
        CHECK (chunk_count >= 0)
);

CREATE INDEX idx_knowledge_document_created_at
    ON knowledge_document (created_at DESC);
```

- [ ] **步骤 5：为已有非空 schema 配置 Flyway 基线**

在 `spring` 下加入：

```yaml
  flyway:
    # public schema 已存在 PGVector 表，让 Flyway 从 0 号基线开始接管业务表。
    baseline-on-migrate: true
    baseline-version: 0
```

不能使用默认基线版本 1，否则 V1 迁移会被当成已经执行而跳过。

- [ ] **步骤 6：重新运行结构测试**

```powershell
.\mvnw.cmd -Dtest=DocumentSchemaTests test
```

预期：测试通过，数据库存在 `flyway_schema_history` 和 `knowledge_document`。

- [ ] **步骤 7：提交**

```powershell
git add backend/pom.xml backend/src/main/resources/application.yaml backend/src/main/resources/db/migration backend/src/test/java/com/opspilot/ai/document/DocumentSchemaTests.java
git commit -m "feat: 使用 Flyway 创建知识库文档表"
```

### 任务二：建立文档领域模型和 JDBC Repository

**文件：**

- 创建：`backend/src/main/java/com/opspilot/ai/document/DocumentStatus.java`
- 创建：`backend/src/main/java/com/opspilot/ai/document/KnowledgeDocument.java`
- 创建：`backend/src/main/java/com/opspilot/ai/document/DocumentRepository.java`
- 创建：`backend/src/main/java/com/opspilot/ai/document/JdbcDocumentRepository.java`
- 创建：`backend/src/test/java/com/opspilot/ai/document/JdbcDocumentRepositoryTests.java`

**接口：**

- `Optional<KnowledgeDocument> findByHash(String contentHash)`
- `Optional<KnowledgeDocument> findById(UUID id)`
- `List<KnowledgeDocument> findAll()`
- `KnowledgeDocument create(UUID id, String filename, String contentHash)`
- `void restart(UUID id)`
- `void markReady(UUID id, int chunkCount)`
- `void markFailed(UUID id, String errorMessage)`
- `void deleteById(UUID id)`

- [ ] **步骤 1：编写 Repository 集成测试**

测试使用真实 PostgreSQL，并在每个测试前后按固定测试哈希删除测试数据。至少覆盖：

```java
@Test
void createsAndFindsDocumentByHash() {
    UUID id = UUID.randomUUID();

    repository.create(id, "运维手册.txt", TEST_HASH);

    assertThat(repository.findByHash(TEST_HASH))
            .get()
            .satisfies(document -> {
                assertThat(document.id()).isEqualTo(id);
                assertThat(document.filename()).isEqualTo("运维手册.txt");
                assertThat(document.status()).isEqualTo(DocumentStatus.PROCESSING);
            });
}

@Test
void updatesDocumentToReady() {
    UUID id = UUID.randomUUID();
    repository.create(id, "运维手册.txt", TEST_HASH);

    repository.markReady(id, 3);

    assertThat(repository.findById(id))
            .get()
            .satisfies(document -> {
                assertThat(document.status()).isEqualTo(DocumentStatus.READY);
                assertThat(document.chunkCount()).isEqualTo(3);
            });
}
```

- [ ] **步骤 2：运行测试并确认编译失败**

```powershell
.\mvnw.cmd -Dtest=JdbcDocumentRepositoryTests test
```

预期：领域类型和 Repository 尚不存在。

- [ ] **步骤 3：实现领域类型**

```java
public enum DocumentStatus {
    PROCESSING,
    READY,
    FAILED
}
```

```java
public record KnowledgeDocument(
        UUID id,
        String filename,
        String contentHash,
        DocumentStatus status,
        int chunkCount,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
```

- [ ] **步骤 4：实现 Repository**

`JdbcDocumentRepository` 使用构造器注入 `JdbcTemplate`，并用一个私有
`RowMapper<KnowledgeDocument>` 统一映射。`findAll()` 固定按
`created_at DESC` 排序。`markFailed()` 将异常摘要截断到 500 个字符，禁止保存堆栈。

- [ ] **步骤 5：运行 Repository 测试**

```powershell
.\mvnw.cmd -Dtest=JdbcDocumentRepositoryTests test
```

预期：全部通过。

- [ ] **步骤 6：提交**

```powershell
git add backend/src/main/java/com/opspilot/ai/document backend/src/test/java/com/opspilot/ai/document
git commit -m "feat: 添加知识库文档数据访问层"
```

### 任务三：计算文件内容哈希

**文件：**

- 创建：`backend/src/main/java/com/opspilot/ai/document/ContentHashCalculator.java`
- 创建：`backend/src/test/java/com/opspilot/ai/document/ContentHashCalculatorTests.java`

**接口：** `String calculate(byte[] content)`，返回 64 位小写十六进制 SHA-256。

- [ ] **步骤 1：编写失败测试**

```java
@Test
void returnsStableLowercaseSha256() {
    byte[] content = "OpsPilot AI".getBytes(StandardCharsets.UTF_8);

    String first = calculator.calculate(content);
    String second = calculator.calculate(content);

    assertThat(first)
            .hasSize(64)
            .matches("[0-9a-f]{64}")
            .isEqualTo(second);
}
```

- [ ] **步骤 2：确认红灯，然后实现最小代码**

使用 `MessageDigest.getInstance("SHA-256")` 和 `HexFormat.of().formatHex(...)`。
`SHA-256` 是 JDK 必备算法；若算法不存在，将 `NoSuchAlgorithmException` 转换为
`IllegalStateException`。

- [ ] **步骤 3：运行测试并提交**

```powershell
.\mvnw.cmd -Dtest=ContentHashCalculatorTests test
git add backend/src/main/java/com/opspilot/ai/document/ContentHashCalculator.java backend/src/test/java/com/opspilot/ai/document/ContentHashCalculatorTests.java
git commit -m "feat: 添加文档内容哈希计算"
```

### 任务四：为向量切片补充文档元数据

**文件：**

- 修改：`backend/src/main/java/com/opspilot/ai/ingestion/DocumentIngestionService.java`
- 修改：`backend/src/test/java/com/opspilot/ai/ingestion/DocumentIngestionServiceTests.java`

**接口：**

```java
IngestionResult ingest(DocumentReader reader, UUID documentId, String filename)
```

- [ ] **步骤 1：先扩展测试**

捕获写入 `DocumentWriter` 的切片，断言每个切片包含：

```java
assertThat(writtenChunks.get(0).getMetadata())
        .containsEntry("documentId", documentId.toString())
        .containsEntry("filename", "运维手册.txt")
        .containsEntry("chunkIndex", 0);
```

- [ ] **步骤 2：运行测试确认红灯**

预期：现有 `ingest(DocumentReader)` 不接受文档身份，测试无法编译。

- [ ] **步骤 3：实现 metadata 增强**

遍历切片时使用：

```java
Document enriched = chunk.mutate()
        .metadata("documentId", documentId.toString())
        .metadata("filename", filename)
        .metadata("chunkIndex", index)
        .build();
```

`mutate()` 会保留 Tika 和分块器已有的文本及 metadata，再添加生命周期字段。

- [ ] **步骤 4：更新所有调用方测试并运行**

```powershell
.\mvnw.cmd -Dtest=DocumentIngestionServiceTests,DocumentUploadServiceTests,DocumentControllerTests test
```

- [ ] **步骤 5：提交**

```powershell
git add backend/src/main/java/com/opspilot/ai/ingestion/DocumentIngestionService.java backend/src/test/java/com/opspilot/ai/ingestion
git commit -m "feat: 关联文档记录与向量切片"
```

### 任务五：编排幂等上传和失败补偿

**文件：**

- 创建：`backend/src/main/java/com/opspilot/ai/document/DuplicateDocumentException.java`
- 修改：`backend/src/main/java/com/opspilot/ai/ingestion/DocumentUploadService.java`
- 修改：`backend/src/main/java/com/opspilot/ai/ingestion/IngestionConfig.java`
- 修改：`backend/src/test/java/com/opspilot/ai/ingestion/DocumentUploadServiceTests.java`

**接口：**

```java
KnowledgeDocument upload(Resource resource, String filename)
```

- [ ] **步骤 1：编写三个失败测试**

覆盖：

1. `READY` 哈希已存在时抛出 `DuplicateDocumentException`，不调用摄取。
2. 新文档成功后调用 `markReady(id, chunkCount)`。
3. 摄取失败时调用 `vectorStore.delete("documentId == 'UUID'")` 和
   `markFailed(id, message)`，然后继续抛出原异常。

- [ ] **步骤 2：运行测试确认红灯**

```powershell
.\mvnw.cmd -Dtest=DocumentUploadServiceTests test
```

- [ ] **步骤 3：实现上传编排**

流程固定为：读取最多 10 MB 的资源字节、计算哈希、查询已有记录、创建或重启记录、
构造可重复读取的 `ByteArrayResource`、调用摄取、标记 `READY`。失败补偿删除过滤表达式
必须只由应用生成的 UUID 拼接，不能使用用户输入。

`FAILED` 记录执行 `restart(id)` 后复用；`READY` 或 `PROCESSING` 抛重复异常。
捕获数据库唯一约束异常并转换为 `DuplicateDocumentException`。

- [ ] **步骤 4：注册新的依赖**

在 `IngestionConfig` 中为 `ContentHashCalculator` 和新的
`DocumentUploadService` 构造参数增加 Bean 装配；Repository 由 `@Repository` 注册。

- [ ] **步骤 5：运行上传服务测试并提交**

```powershell
.\mvnw.cmd -Dtest=DocumentUploadServiceTests test
git add backend/src/main/java/com/opspilot/ai/document backend/src/main/java/com/opspilot/ai/ingestion backend/src/test/java/com/opspilot/ai/ingestion
git commit -m "feat: 实现知识库文档幂等上传"
```

### 任务六：实现文档列表和删除用例

**文件：**

- 创建：`backend/src/main/java/com/opspilot/ai/document/DocumentNotFoundException.java`
- 创建：`backend/src/main/java/com/opspilot/ai/document/DocumentLifecycleService.java`
- 创建：`backend/src/test/java/com/opspilot/ai/document/DocumentLifecycleServiceTests.java`

**接口：**

```java
List<KnowledgeDocument> list()
void delete(UUID id)
```

- [ ] **步骤 1：编写失败测试**

覆盖：

```java
@Test
void deletesVectorsBeforeDocumentRecord() {
    when(repository.findById(documentId)).thenReturn(Optional.of(document));

    service.delete(documentId);

    InOrder order = inOrder(vectorStore, repository);
    order.verify(vectorStore)
            .delete("documentId == '" + documentId + "'");
    order.verify(repository).deleteById(documentId);
}
```

以及文档不存在时抛出 `DocumentNotFoundException`，且不调用 `vectorStore.delete`。

- [ ] **步骤 2：实现最小服务**

`delete(UUID id)` 使用 `@Transactional`；过滤表达式只使用已解析的 UUID。
`list()` 直接返回 Repository 已按时间倒序排列的记录。

- [ ] **步骤 3：运行测试并提交**

```powershell
.\mvnw.cmd -Dtest=DocumentLifecycleServiceTests test
git add backend/src/main/java/com/opspilot/ai/document backend/src/test/java/com/opspilot/ai/document
git commit -m "feat: 添加知识库文档列表和删除服务"
```

### 任务七：扩展文档 HTTP API 和错误映射

**文件：**

- 创建：`backend/src/main/java/com/opspilot/ai/ingestion/api/DocumentResponse.java`
- 修改：`backend/src/main/java/com/opspilot/ai/ingestion/api/DocumentController.java`
- 修改：`backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java`
- 修改：`backend/src/test/java/com/opspilot/ai/ingestion/api/DocumentControllerTests.java`
- 修改：`backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java`

**HTTP 接口：**

- `POST /api/documents`：成功返回 201 和 `DocumentResponse`。
- `GET /api/documents`：返回 200 和数组。
- `DELETE /api/documents/{id}`：成功返回 204。

- [ ] **步骤 1：编写 Controller 失败测试**

覆盖上传响应、列表和删除。上传时把 `file.getOriginalFilename()` 传给服务：

```java
verify(uploadService).upload(file.getResource(), "运维手册.txt");
```

- [ ] **步骤 2：实现响应 DTO 和接口**

```java
public record DocumentResponse(
        UUID id,
        String filename,
        DocumentStatus status,
        int chunkCount,
        OffsetDateTime createdAt
) {
    public static DocumentResponse from(KnowledgeDocument document) {
        return new DocumentResponse(
                document.id(),
                document.filename(),
                document.status(),
                document.chunkCount(),
                document.createdAt()
        );
    }
}
```

- [ ] **步骤 3：增加异常映射测试和实现**

- `DuplicateDocumentException` → HTTP 409，`DOCUMENT_ALREADY_EXISTS`。
- `DocumentNotFoundException` → HTTP 404，`DOCUMENT_NOT_FOUND`。

复用现有 `ApiError`，不创建第二套错误结构。

- [ ] **步骤 4：运行 API 测试并提交**

```powershell
.\mvnw.cmd -Dtest=DocumentControllerTests,GlobalExceptionHandlerTests test
git add backend/src/main/java/com/opspilot/ai/ingestion/api backend/src/main/java/com/opspilot/ai/chat/api backend/src/test/java/com/opspilot/ai/ingestion/api backend/src/test/java/com/opspilot/ai/chat/api
git commit -m "feat: 添加知识库文档管理接口"
```

### 任务八：完成回归和端到端验收

**验证文件：**

- 使用：`backend/test-data/pgvector-persistence-test.txt`

- [ ] **步骤 1：运行完整测试**

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd test
```

预期：全部测试通过。

- [ ] **步骤 2：启动应用并首次上传**

```powershell
curl.exe -sS -X POST `
  -F "file=@test-data/pgvector-persistence-test.txt;type=text/plain" `
  http://localhost:8080/api/documents
```

预期：HTTP 201，返回文档 UUID、`READY` 和切片数量。

- [ ] **步骤 3：重复上传**

再次执行同一命令，预期 HTTP 409，数据库向量数量不增加。

- [ ] **步骤 4：查询列表**

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/documents"
```

预期：列表包含测试文件及 `READY` 状态。

- [ ] **步骤 5：删除并验证**

```powershell
Invoke-RestMethod -Method Delete `
  -Uri "http://localhost:8080/api/documents/$documentId"
```

预期：HTTP 204；列表不再包含该 ID，`vector_store.metadata` 中对应
`documentId` 的切片数量为 0。

- [ ] **步骤 6：检查敏感信息、提交和推送**

```powershell
cd D:\workFile\demo-ai
git diff --check
git status --short
git diff
```

确认不存在密码或 API Key 明文，运行最新完整测试后再提交与推送。
