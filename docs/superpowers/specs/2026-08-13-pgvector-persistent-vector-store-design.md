# PGVector 持久化向量库设计

## 目标

将当前仅保存在 JVM 内存中的 `SimpleVectorStore` 替换为 PostgreSQL 的
PGVector 存储。应用重启后，已经上传的文档向量仍然可以被检索。

## 技术选择

- PostgreSQL 17：保存关系数据和向量数据。
- PGVector：为 PostgreSQL 提供 `vector` 类型及向量相似度检索能力。
- Ollama `nomic-embed-text`：继续在本地生成 768 维向量。
- 智谱 GLM-4.7：继续生成最终回答，不参与向量计算。
- Spring AI `PgVectorStore`：对接 PostgreSQL 和 PGVector。

不引入 MySQL，也不同时维护两套数据库。当前项目暂无必须使用 MySQL 的
业务数据，使用 PostgreSQL 同时处理普通数据和向量数据可以降低学习环境
复杂度。

## 本地安装布局

- PostgreSQL 程序目录：`D:\workFile\postgresql\17`
- PostgreSQL 数据目录：`D:\workFile\postgresql\data`
- PGVector 源码及构建目录：`D:\workFile\pgvector`
- PostgreSQL 端口：`5432`
- 项目数据库：`opspilot_ai`

数据库密码不能写入 Git 仓库，通过 Windows 用户环境变量提供给应用。

## 应用改造

1. 增加 PostgreSQL JDBC 和 Spring AI PGVector 依赖。
2. 在 `application.yaml` 中增加数据源及 PGVector 配置。
3. 删除 `IngestionConfig` 中手工创建的 `SimpleVectorStore` Bean。
4. 保持 `DocumentIngestionService`、`KnowledgeSearchService` 和 `RagService`
   继续依赖 `VectorStore` 接口，不修改业务流程。

## 数据流

上传文档时：Tika 解析文档，切片器拆分文本，Ollama 生成 768 维向量，
`PgVectorStore` 将文本、元数据和向量写入 PostgreSQL。

查询时：Ollama 将问题转换为向量，PGVector 执行相似度检索，RAG 服务将
召回片段交给 GLM-4.7 生成回答。

## 异常处理

- PostgreSQL 未启动：应用启动失败，并明确显示数据库连接异常。
- PGVector 扩展未启用：初始化表结构失败，先执行 `CREATE EXTENSION vector`。
- 向量维度不匹配：配置固定为 768，并通过集成测试验证。
- 数据库密码缺失：不提供代码内默认密码，防止凭据进入仓库。

## 验收标准

1. 完整 Maven 测试通过。
2. 上传文档后能够通过 RAG 接口回答文档中的问题。
3. 关闭并重新启动 Java 应用后，不重新上传文档仍能回答同一问题。
4. Git 差异中不存在数据库密码或 API Key 明文。

## 暂不包含

- MySQL 双数据库架构。
- Docker 或云数据库部署。
- HNSW/IVFFlat 索引调优。
- 多租户、文档版本和向量数据清理策略。
