# PGVector 持久化向量库实施计划

> **执行要求：** 按任务顺序逐项执行，每一步完成后立即验证。核心 Spring AI 配置由兵哥亲手编写，助手负责讲解、审查和排障。

**目标：** 使用 PostgreSQL 17 + PGVector 替换 `SimpleVectorStore`，使文档向量在应用重启后仍然存在。

**架构：** 保持摄取、检索和 RAG 服务继续依赖 Spring AI `VectorStore` 接口，仅将 Bean 的实现从内存存储切换为自动配置的 `PgVectorStore`。Ollama `nomic-embed-text` 生成 768 维向量，PostgreSQL 负责持久化，GLM-4.7 继续生成回答。

**技术栈：** Java 21、Spring Boot 3.5.14、Spring AI 1.1.8、PostgreSQL 17、PGVector、Ollama `nomic-embed-text`。

## 全局约束

- PostgreSQL 程序和数据全部放在 `D:\workFile\postgresql`。
- PostgreSQL 端口固定为 `5432`，项目数据库名为 `opspilot_ai`。
- 向量维度固定为 `768`，距离类型使用 `COSINE_DISTANCE`。
- 数据库密码只从环境变量 `OPSPILOT_DB_PASSWORD` 读取，禁止写入 Git。
- 不修改文档上传、切片、知识检索和 RAG 的业务接口。
- 新出现的 PGVector 特有配置必须有简洁中文注释。

---

### 任务一：安装 PostgreSQL 17 和 PGVector

**涉及位置：**

- 创建：`D:\workFile\postgresql\17`
- 创建：`D:\workFile\postgresql\data`
- 创建：`D:\workFile\pgvector`

**产出：** 本机 `localhost:5432` 上运行支持 `vector` 扩展的 PostgreSQL。

- [ ] **步骤 1：安装 PostgreSQL 17**

安装时将程序目录设为 `D:\workFile\postgresql\17`，数据目录设为
`D:\workFile\postgresql\data`，保留端口 `5432` 和超级用户 `postgres`。

- [ ] **步骤 2：验证 PostgreSQL**

```powershell
& 'D:\workFile\postgresql\17\bin\psql.exe' --version
Get-Service | Where-Object Name -Like 'postgresql*'
```

预期：显示 PostgreSQL 17 版本，Windows 服务状态为 `Running`。

- [ ] **步骤 3：安装 PGVector**

按照 PGVector 官方 Windows 方式，使用 x64 C++ 编译工具将扩展安装到
`D:\workFile\postgresql\17`。源码固定放在 `D:\workFile\pgvector`。

- [ ] **步骤 4：创建数据库并启用扩展**

进入 `psql` 后执行：

```sql
CREATE DATABASE opspilot_ai;
\c opspilot_ai
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
SELECT extname, extversion
FROM pg_extension
WHERE extname IN ('vector', 'hstore', 'uuid-ossp');
```

预期：查询返回三个扩展，其中包含 `vector`。

### 任务二：用测试描述 PgVectorStore 装配结果

**文件：**

- 修改：`backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java`

**接口：**

- 使用：Spring 容器中的 `VectorStore`
- 验证：实际类型为 `PgVectorStore`

- [ ] **步骤 1：亲手修改现有装配测试**

增加导入：

```java
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
```

将原来的非空断言加强为：

```java
@Test
void createsPgVectorStore() {
    // 不只检查存在 Bean，还要确认底层已经切换为 PostgreSQL 持久化实现。
    assertThat(vectorStore).isInstanceOf(PgVectorStore.class);
}
```

- [ ] **步骤 2：运行测试并确认先失败**

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd -Dtest=OpsPilotApplicationTests test
```

预期：因为尚未添加 PGVector starter，测试编译失败或仍得到
`SimpleVectorStore`。

### 任务三：接入 Spring AI PGVector 自动配置

**文件：**

- 修改：`backend/pom.xml`
- 修改：`backend/src/main/resources/application.yaml`
- 修改：`backend/src/main/java/com/opspilot/ai/ingestion/IngestionConfig.java`

**产出：** Spring 自动创建 `PgVectorStore` Bean，原有业务层无需感知实现变化。

- [ ] **步骤 1：亲手替换 Maven 依赖**

删除学习演示用的 `spring-ai-vector-store` 依赖，加入：

```xml
<!-- 使用 PostgreSQL + PGVector 持久化文档向量。 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>
```

此 starter 会传递引入 JDBC 和 PostgreSQL 驱动；若依赖树验证缺失，再显式补充，
不提前重复声明。

- [ ] **步骤 2：亲手增加数据源配置**

在 `spring` 下增加：

```yaml
  datasource:
    url: jdbc:postgresql://localhost:5432/opspilot_ai
    username: postgres
    # 不提供默认值，环境变量缺失时应直接暴露配置问题。
    password: ${OPSPILOT_DB_PASSWORD}
```

- [ ] **步骤 3：亲手增加 PGVector 配置**

在 `spring.ai` 下增加：

```yaml
    vectorstore:
      pgvector:
        # 首次启动时创建扩展、表和 HNSW 索引。
        initialize-schema: true
        dimensions: 768
        distance-type: COSINE_DISTANCE
        index-type: HNSW
        table-name: vector_store
```

- [ ] **步骤 4：删除内存 VectorStore Bean**

在 `IngestionConfig.java` 中删除：

```java
@Bean
public VectorStore vectorStore(EmbeddingModel embeddingModel) {
    return SimpleVectorStore.builder(embeddingModel).build();
}
```

同时删除不再使用的 `EmbeddingModel` 和 `SimpleVectorStore` import，保留
`VectorStore` import，因为 `ingestionService` 仍通过接口注入存储。

- [ ] **步骤 5：设置当前终端的密码环境变量**

```powershell
$env:OPSPILOT_DB_PASSWORD = Read-Host '请输入 PostgreSQL 密码'
```

该命令只对当前 PowerShell 有效，不会把密码打印到日志或写入仓库。

- [ ] **步骤 6：运行装配测试**

```powershell
.\mvnw.cmd -Dtest=OpsPilotApplicationTests test
```

预期：`createsPgVectorStore` 通过，数据库中生成 `vector_store` 表。

### 任务四：完成持久化集成验收

**验证范围：** 文档上传、数据库落库、应用重启、RAG 召回。

- [ ] **步骤 1：运行全量自动化测试**

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd test
```

预期：全部测试通过。

- [ ] **步骤 2：启动应用并上传测试文档**

```powershell
.\mvnw.cmd spring-boot:run
```

通过现有 `POST /api/documents` 接口上传一份包含唯一事实的文档。

- [ ] **步骤 3：确认向量已经落库**

```sql
SELECT count(*) AS chunk_count FROM vector_store;
SELECT id, left(content, 80) AS content_preview FROM vector_store LIMIT 5;
```

预期：`chunk_count` 大于 0，并能看到文档片段。

- [ ] **步骤 4：重启应用并验证 RAG**

关闭 Java 应用并重新运行 `spring-boot:run`，不重新上传文档，调用现有
`POST /api/rag/chat` 提问文档中的唯一事实。

预期：仍然能够基于数据库中的文档片段回答。

- [ ] **步骤 5：检查敏感信息和提交**

```powershell
cd D:\workFile\demo-ai
git diff --check
git status --short
git diff
```

确认不存在密码明文后，仅暂存本次相关文件并使用中文提交：

```powershell
git commit -m "feat: 使用 PGVector 持久化知识库向量"
```
