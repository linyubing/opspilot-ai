# 真实黄金日参考价基础实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 接入 Alpha Vantage 的真实 XAU/USD 每日参考价格，将有效工作日数据幂等保存到 PostgreSQL，并提供手动同步和查询接口。

**架构：** 使用端口与适配器边界隔离外部供应商。`GoldPriceProvider` 只负责读取并转换 Alpha Vantage 响应，`GoldPriceSyncService` 负责过滤周末和编排保存，`MarketPriceRepository` 负责 PostgreSQL 幂等写入；Controller 只暴露手动同步和查询结果。

**技术栈：** Java 21、Spring Boot 3.5.14、Spring Web `RestClient`、Spring JDBC、PostgreSQL 17、Flyway、JUnit 5、AssertJ、MockMvc。

## 全局约束

- 第一阶段只实现 XAU/USD 每日参考价，不实现现货接口、宏观数据、指标、简报和预测评分。
- Alpha Vantage 的 `GOLD_SILVER_HISTORY` 当前返回 `nominal` 与 `data[{date, price}]`，没有开高低收字段，禁止自行补造 OHLC。
- 周六和周日响应必须过滤，不得进入 `market_price`；工作日是否有数据由供应商响应决定。
- 外部 API Key 只读取环境变量 `ALPHA_VANTAGE_API_KEY`，不得写进代码、测试资源、日志或 Git。
- SQL 关键字、表名、列名和数据库枚举值统一小写；Java 标识符遵循 Java 规范。
- 不编造假行情、假新闻；公式或存储契约测试中的固定数字必须明确说明不代表市场事实。
- 测试方法使用简洁英文名称，并紧邻中文 `@DisplayName`；不常见的外部数据解析和幂等 SQL 添加中文注释。
- 学习协作：助手先提供测试和接口骨架，兵哥主写 `AlphaVantageGoldPriceProvider` 的响应校验/转换及 `GoldPriceSyncService` 的过滤编排，助手随后审查、运行测试和排障。
- 每个任务完成后执行对应测试、检查 `git diff`，使用中文提交信息并推送 `master`。
- 不暂存根目录旧设计文档，以及当前暂停的 `DocumentLifecycleService`、`DocumentNotFoundException`、`DocumentLifecycleServiceTests`。

## 文件结构

### 新建文件

- `backend/src/main/resources/db/migration/V2__create_market_price.sql`：创建黄金参考价表及约束。
- `backend/src/main/java/com/opspilot/ai/marketdata/MarketPrice.java`：每日参考价领域记录。
- `backend/src/main/java/com/opspilot/ai/marketdata/MarketPriceRepository.java`：价格持久化端口。
- `backend/src/main/java/com/opspilot/ai/marketdata/JdbcMarketPriceRepository.java`：PostgreSQL 适配器。
- `backend/src/main/java/com/opspilot/ai/marketdata/GoldPriceProvider.java`：外部黄金价格端口。
- `backend/src/main/java/com/opspilot/ai/marketdata/MarketDataProperties.java`：Alpha Vantage 配置映射。
- `backend/src/main/java/com/opspilot/ai/marketdata/MarketDataConfiguration.java`：`RestClient` 与配置注册。
- `backend/src/main/java/com/opspilot/ai/marketdata/MarketDataUnavailableException.java`：上游数据不可用异常。
- `backend/src/main/java/com/opspilot/ai/marketdata/AlphaVantageGoldPriceProvider.java`：真实供应商适配器。
- `backend/src/main/java/com/opspilot/ai/marketdata/GoldPriceSyncService.java`：同步编排和周末过滤。
- `backend/src/main/java/com/opspilot/ai/marketdata/GoldPriceSyncResult.java`：同步数量与最新价格日期。
- `backend/src/main/java/com/opspilot/ai/marketdata/api/GoldPriceController.java`：手动同步与查询接口。
- `backend/src/main/java/com/opspilot/ai/marketdata/api/GoldPriceResponse.java`：接口响应。
- `backend/src/main/java/com/opspilot/ai/marketdata/api/GoldPriceSyncResponse.java`：同步结果响应。
- `backend/src/test/java/com/opspilot/ai/marketdata/MarketPriceSchemaTests.java`：真实 PostgreSQL 表结构测试。
- `backend/src/test/java/com/opspilot/ai/marketdata/JdbcMarketPriceRepositoryTests.java`：Repository 集成测试。
- `backend/src/test/java/com/opspilot/ai/marketdata/AlphaVantageGoldPriceProviderLiveTests.java`：显式执行的真实接口测试。
- `backend/src/test/java/com/opspilot/ai/marketdata/GoldPriceSyncServiceTests.java`：同步规则单元测试。
- `backend/src/test/java/com/opspilot/ai/marketdata/api/GoldPriceControllerTests.java`：Controller 测试。

### 修改文件

- `backend/src/main/resources/application.yaml`：增加 Alpha Vantage 地址、Key 环境变量和超时配置。
- `backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java`：将市场数据不可用映射为 `503`。
- `backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java`：验证新的异常响应。

---

### 任务 1：建立黄金参考价表和领域模型

**文件：**

- 新建：`backend/src/main/resources/db/migration/V2__create_market_price.sql`
- 新建：`backend/src/main/java/com/opspilot/ai/marketdata/MarketPrice.java`
- 新建：`backend/src/test/java/com/opspilot/ai/marketdata/MarketPriceSchemaTests.java`

**接口：**

- 产出：`MarketPrice(String symbol, LocalDate priceDate, BigDecimal referencePrice, String currency, String unit, String provider, OffsetDateTime collectedAt)`。
- 后续任务依赖：`market_price` 表和 `MarketPrice`。

- [ ] **步骤 1：编写失败的表结构测试**

```java
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class MarketPriceSchemaTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway 创建黄金参考价表")
    void createsMarketPriceTable() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = 'public'
                  and table_name = 'market_price'
                """, Long.class);

        assertThat(count).isEqualTo(1L);
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd -Dtest=MarketPriceSchemaTests test
```

预期：失败，原因是 `market_price` 表不存在。

- [ ] **步骤 3：创建 Flyway 迁移**

```sql
create table market_price (
    symbol varchar(20) not null,
    price_date date not null,
    reference_price numeric(19, 8) not null,
    currency char(3) not null,
    unit varchar(20) not null,
    provider varchar(40) not null,
    collected_at timestamptz not null,

    constraint pk_market_price
        primary key (provider, symbol, price_date),
    constraint ck_market_price_positive
        check (reference_price > 0),
    constraint ck_market_price_currency
        check (currency = 'usd'),
    constraint ck_market_price_unit
        check (unit = 'troy_ounce')
);

create index idx_market_price_symbol_date
    on market_price (symbol, price_date desc);
```

- [ ] **步骤 4：创建不可变领域记录**

```java
public record MarketPrice(
        String symbol,
        LocalDate priceDate,
        BigDecimal referencePrice,
        String currency,
        String unit,
        String provider,
        OffsetDateTime collectedAt
) {
}
```

- [ ] **步骤 5：运行测试确认通过**

运行：`.\mvnw.cmd -Dtest=MarketPriceSchemaTests test`

预期：`BUILD SUCCESS`。

- [ ] **步骤 6：检查并提交**

```powershell
git diff --check
git add backend/src/main/resources/db/migration/V2__create_market_price.sql `
        backend/src/main/java/com/opspilot/ai/marketdata/MarketPrice.java `
        backend/src/test/java/com/opspilot/ai/marketdata/MarketPriceSchemaTests.java
git commit -m "feat: 创建黄金参考价数据表"
git push origin master
```

---

### 任务 2：实现黄金参考价持久化端口

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/marketdata/MarketPriceRepository.java`
- 新建：`backend/src/main/java/com/opspilot/ai/marketdata/JdbcMarketPriceRepository.java`
- 新建：`backend/src/test/java/com/opspilot/ai/marketdata/JdbcMarketPriceRepositoryTests.java`

**接口：**

- 消费：任务 1 的 `MarketPrice` 和 `market_price`。
- 产出：`void saveAll(List<MarketPrice> prices)`、`Optional<MarketPrice> findLatest(String symbol)`、`List<MarketPrice> findRecent(String symbol, int limit)`。

- [ ] **步骤 1：编写 Repository 接口和失败测试**

```java
public interface MarketPriceRepository {

    void saveAll(List<MarketPrice> prices);

    Optional<MarketPrice> findLatest(String symbol);

    List<MarketPrice> findRecent(String symbol, int limit);
}
```

```java
@Test
@DisplayName("同一天价格重复同步时更新而不新增记录")
void upsertsSameDate() {
    MarketPrice first = price(LocalDate.of(2026, 8, 14), "100.00000000");
    MarketPrice corrected = price(LocalDate.of(2026, 8, 14), "101.00000000");

    repository.saveAll(List.of(first));
    repository.saveAll(List.of(corrected));

    assertThat(repository.findRecent(TEST_SYMBOL, 10))
            .singleElement()
            .extracting(MarketPrice::referencePrice)
            .isEqualTo(new BigDecimal("101.00000000"));
}
```

测试中的固定价格只验证数据库幂等契约，不代表真实黄金行情。

- [ ] **步骤 2：运行测试确认失败**

运行：`.\mvnw.cmd -Dtest=JdbcMarketPriceRepositoryTests test`

预期：失败，原因是 `JdbcMarketPriceRepository` 尚未实现或不是 Spring Bean。

- [ ] **步骤 3：实现批量幂等写入**

核心 SQL：

```sql
insert into market_price (
    symbol,
    price_date,
    reference_price,
    currency,
    unit,
    provider,
    collected_at
)
values (?, ?, ?, ?, ?, ?, ?)
on conflict (provider, symbol, price_date)
do update set
    reference_price = excluded.reference_price,
    currency = excluded.currency,
    unit = excluded.unit,
    collected_at = excluded.collected_at
```

使用 `JdbcTemplate.batchUpdate` 完成批量参数绑定；查询统一按 `price_date desc` 排序。`findRecent` 必须校验 `limit > 0`，SQL 使用 `limit ?`。

- [ ] **步骤 4：补齐查询行为测试**

```java
@Test
@DisplayName("最近价格按日期倒序返回并限制数量")
void findsRecentPrices() {
    repository.saveAll(List.of(
            price(LocalDate.of(2026, 8, 13), "99.00000000"),
            price(LocalDate.of(2026, 8, 14), "100.00000000")
    ));

    assertThat(repository.findRecent(TEST_SYMBOL, 1))
            .extracting(MarketPrice::priceDate)
            .containsExactly(LocalDate.of(2026, 8, 14));
    assertThat(repository.findLatest(TEST_SYMBOL))
            .map(MarketPrice::priceDate)
            .contains(LocalDate.of(2026, 8, 14));
}

@Test
@DisplayName("没有价格时最新查询返回空结果")
void returnsEmptyWhenNoPriceExists() {
    assertThat(repository.findLatest("NO_DATA")).isEmpty();
}

@Test
@DisplayName("查询数量必须大于零")
void rejectsNonPositiveLimit() {
    assertThatIllegalArgumentException()
            .isThrownBy(() -> repository.findRecent(TEST_SYMBOL, 0))
            .withMessage("limit 必须大于 0");
}
```

- [ ] **步骤 5：运行 Repository 测试**

运行：`.\mvnw.cmd -Dtest=JdbcMarketPriceRepositoryTests test`

预期：全部通过。

- [ ] **步骤 6：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/marketdata/MarketPriceRepository.java `
        backend/src/main/java/com/opspilot/ai/marketdata/JdbcMarketPriceRepository.java `
        backend/src/test/java/com/opspilot/ai/marketdata/JdbcMarketPriceRepositoryTests.java
git commit -m "feat: 添加黄金参考价数据访问层"
git push origin master
```

---

### 任务 3：接入 Alpha Vantage 真实黄金历史接口

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/marketdata/GoldPriceProvider.java`
- 新建：`backend/src/main/java/com/opspilot/ai/marketdata/MarketDataProperties.java`
- 新建：`backend/src/main/java/com/opspilot/ai/marketdata/MarketDataConfiguration.java`
- 新建：`backend/src/main/java/com/opspilot/ai/marketdata/MarketDataUnavailableException.java`
- 新建：`backend/src/main/java/com/opspilot/ai/marketdata/AlphaVantageGoldPriceProvider.java`
- 新建：`backend/src/test/java/com/opspilot/ai/marketdata/AlphaVantageGoldPriceProviderLiveTests.java`
- 修改：`backend/src/main/resources/application.yaml`

**接口：**

- 产出：`List<MarketPrice> fetchDailyPrices()`。
- 真实请求：`GET /query?function=GOLD_SILVER_HISTORY&symbol=XAU&interval=daily&apikey=...`。

- [ ] **步骤 1：配置环境变量和属性映射**

在 `application.yaml` 添加：

```yaml
opspilot:
  market-data:
    alpha-vantage:
      base-url: https://www.alphavantage.co
      api-key: ${ALPHA_VANTAGE_API_KEY:}
      connect-timeout: 5s
      read-timeout: 20s
```

属性类型：

```java
@ConfigurationProperties("opspilot.market-data.alpha-vantage")
public record MarketDataProperties(
        URI baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout
) {
}
```

`MarketDataConfiguration` 必须注册属性、HTTP 客户端和时钟：

```java
@Configuration
@EnableConfigurationProperties(MarketDataProperties.class)
public class MarketDataConfiguration {

    @Bean
    RestClient alphaVantageRestClient(MarketDataProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    Clock marketDataClock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **步骤 2：创建供应商端口和异常**

```java
public interface GoldPriceProvider {

    List<MarketPrice> fetchDailyPrices();
}
```

```java
public class MarketDataUnavailableException extends RuntimeException {

    public MarketDataUnavailableException(String message) {
        super(message);
    }

    public MarketDataUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **步骤 3：编写真实接口测试**

```java
@EnabledIfEnvironmentVariable(named = "ALPHA_VANTAGE_API_KEY", matches = ".+")
class AlphaVantageGoldPriceProviderLiveTests {

    @Test
    @DisplayName("从 Alpha Vantage 获取真实 XAU 每日参考价")
    void fetchesRealXauPrices() {
        MarketDataProperties properties = new MarketDataProperties(
                URI.create("https://www.alphavantage.co"),
                System.getenv("ALPHA_VANTAGE_API_KEY"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(20)
        );
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .build();
        GoldPriceProvider provider = new AlphaVantageGoldPriceProvider(
                restClient,
                properties,
                Clock.systemUTC()
        );

        List<MarketPrice> prices = provider.fetchDailyPrices();

        assertThat(prices).isNotEmpty();
        assertThat(prices)
                .allSatisfy(price -> {
                    assertThat(price.symbol()).isEqualTo("XAUUSD");
                    assertThat(price.referencePrice()).isPositive();
                    assertThat(price.provider()).isEqualTo("alpha_vantage");
                });
    }
}
```

- [ ] **步骤 4：运行测试确认失败**

```powershell
$env:ALPHA_VANTAGE_API_KEY = [Environment]::GetEnvironmentVariable('ALPHA_VANTAGE_API_KEY', 'User')
.\mvnw.cmd -Dtest=AlphaVantageGoldPriceProviderLiveTests test
```

预期：测试被执行并失败，因为适配器尚未实现；如果显示 `Skipped`，先设置用户级环境变量并重新打开终端。

- [ ] **步骤 5：兵哥实现真实响应校验和转换**

使用 `RestClient` 获取 `JsonNode`。必须校验：

```java
JsonNode root = restClient.get()
        .uri(uriBuilder -> uriBuilder
                .path("/query")
                .queryParam("function", "GOLD_SILVER_HISTORY")
                .queryParam("symbol", "XAU")
                .queryParam("interval", "daily")
                .queryParam("apikey", properties.apiKey())
                .build())
        .retrieve()
        .body(JsonNode.class);
```

- API Key 为空时，在发请求前抛出 `MarketDataUnavailableException("未配置 ALPHA_VANTAGE_API_KEY")`；
- `root` 为空、`data` 不是数组、`nominal` 不是 `XAUUSD` 时拒绝响应；
- `Information`、`Note` 或 `Error Message` 存在时，将不含 Key 的摘要写入异常；
- 每个元素只读取 `date` 和 `price`；日期或正数价格无法解析时拒绝整批响应；
- `currency="usd"`、`unit="troy_ounce"`、`provider="alpha_vantage"`；
- `collectedAt` 在一次调用开始时统一取一次 `OffsetDateTime.now(Clock)`；
- 日志只记录记录数、最早日期、最晚日期和耗时，禁止记录请求 URL 与 API Key。

- [ ] **步骤 6：运行真实接口测试确认通过**

运行：`.\mvnw.cmd -Dtest=AlphaVantageGoldPriceProviderLiveTests test`

预期：`BUILD SUCCESS`，且返回真实 `XAUUSD` 正数价格。

- [ ] **步骤 7：提交并推送**

```powershell
git add backend/src/main/resources/application.yaml `
        backend/src/main/java/com/opspilot/ai/marketdata/GoldPriceProvider.java `
        backend/src/main/java/com/opspilot/ai/marketdata/MarketDataProperties.java `
        backend/src/main/java/com/opspilot/ai/marketdata/MarketDataConfiguration.java `
        backend/src/main/java/com/opspilot/ai/marketdata/MarketDataUnavailableException.java `
        backend/src/main/java/com/opspilot/ai/marketdata/AlphaVantageGoldPriceProvider.java `
        backend/src/test/java/com/opspilot/ai/marketdata/AlphaVantageGoldPriceProviderLiveTests.java
git commit -m "feat: 接入真实黄金历史价格"
git push origin master
```

---

### 任务 4：实现有效工作日过滤和同步编排

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/marketdata/GoldPriceSyncService.java`
- 新建：`backend/src/main/java/com/opspilot/ai/marketdata/GoldPriceSyncResult.java`
- 新建：`backend/src/test/java/com/opspilot/ai/marketdata/GoldPriceSyncServiceTests.java`

**接口：**

- 消费：`GoldPriceProvider.fetchDailyPrices()`、`MarketPriceRepository.saveAll(...)`。
- 产出：`GoldPriceSyncResult syncDailyPrices()`，其中 `GoldPriceSyncResult(int receivedCount, int savedCount, int weekendSkippedCount, LocalDate latestPriceDate)`。

- [ ] **步骤 1：编写周末过滤失败测试**

```java
@Test
@DisplayName("同步时排除周六和周日价格")
void skipsWeekendPrices() {
    List<MarketPrice> providerPrices = List.of(
            price(LocalDate.of(2026, 8, 14)), // 周五
            price(LocalDate.of(2026, 8, 15)), // 周六
            price(LocalDate.of(2026, 8, 16)), // 周日
            price(LocalDate.of(2026, 8, 17))  // 周一
    );
    GoldPriceProvider provider = () -> providerPrices;
    RecordingMarketPriceRepository repository = new RecordingMarketPriceRepository();
    GoldPriceSyncService service = new GoldPriceSyncService(provider, repository);

    GoldPriceSyncResult result = service.syncDailyPrices();

    assertThat(repository.savedPrices())
            .extracting(MarketPrice::priceDate)
            .containsExactly(
                    LocalDate.of(2026, 8, 14),
                    LocalDate.of(2026, 8, 17)
            );
    assertThat(result.weekendSkippedCount()).isEqualTo(2);
}

private static final class RecordingMarketPriceRepository
        implements MarketPriceRepository {

    private List<MarketPrice> savedPrices = List.of();

    @Override
    public void saveAll(List<MarketPrice> prices) {
        savedPrices = List.copyOf(prices);
    }

    List<MarketPrice> savedPrices() {
        return savedPrices;
    }

    @Override
    public Optional<MarketPrice> findLatest(String symbol) {
        return Optional.empty();
    }

    @Override
    public List<MarketPrice> findRecent(String symbol, int limit) {
        return List.of();
    }
}
```

固定日期只验证星期过滤规则，不代表行情内容。

- [ ] **步骤 2：运行测试确认失败**

运行：`.\mvnw.cmd -Dtest=GoldPriceSyncServiceTests test`

预期：失败，原因是同步服务和结果类型不存在。

- [ ] **步骤 3：兵哥实现最小同步逻辑**

过滤条件必须独立成易读私有方法：

```java
private boolean isWeekday(MarketPrice price) {
    DayOfWeek day = price.priceDate().getDayOfWeek();
    return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
}
```

`syncDailyPrices()` 按以下顺序执行：获取全部响应、统计数量、过滤周末、按日期升序排序、批量幂等保存、返回统计结果。供应商返回空集合时抛出 `MarketDataUnavailableException("黄金历史价格为空")`。

- [ ] **步骤 4：补齐边界测试**

```java
@Test
@DisplayName("供应商返回空数据时拒绝同步")
void rejectsEmptyProviderResponse() {
    GoldPriceProvider provider = List::of;
    RecordingMarketPriceRepository repository = new RecordingMarketPriceRepository();
    GoldPriceSyncService service = new GoldPriceSyncService(provider, repository);

    assertThatThrownBy(service::syncDailyPrices)
            .isInstanceOf(MarketDataUnavailableException.class)
            .hasMessage("黄金历史价格为空");
    assertThat(repository.savedPrices()).isEmpty();
}

@Test
@DisplayName("同步前按价格日期升序排列")
void sortsPricesBeforeSaving() {
    GoldPriceProvider provider = () -> List.of(
            price(LocalDate.of(2026, 8, 17)),
            price(LocalDate.of(2026, 8, 14))
    );
    RecordingMarketPriceRepository repository = new RecordingMarketPriceRepository();
    GoldPriceSyncService service = new GoldPriceSyncService(provider, repository);

    GoldPriceSyncResult result = service.syncDailyPrices();

    assertThat(repository.savedPrices())
            .extracting(MarketPrice::priceDate)
            .containsExactly(
                    LocalDate.of(2026, 8, 14),
                    LocalDate.of(2026, 8, 17)
            );
    assertThat(result.latestPriceDate()).isEqualTo(LocalDate.of(2026, 8, 17));
}

@Test
@DisplayName("供应商只返回周末数据时拒绝同步")
void rejectsWeekendOnlyResponse() {
    GoldPriceProvider provider = () -> List.of(
            price(LocalDate.of(2026, 8, 15)),
            price(LocalDate.of(2026, 8, 16))
    );
    GoldPriceSyncService service = new GoldPriceSyncService(
            provider,
            new RecordingMarketPriceRepository()
    );

    assertThatThrownBy(service::syncDailyPrices)
            .isInstanceOf(MarketDataUnavailableException.class)
            .hasMessage("黄金历史价格没有有效工作日数据");
}
```

- [ ] **步骤 5：运行测试确认通过**

运行：`.\mvnw.cmd -Dtest=GoldPriceSyncServiceTests test`

预期：全部通过。

- [ ] **步骤 6：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/marketdata/GoldPriceSyncService.java `
        backend/src/main/java/com/opspilot/ai/marketdata/GoldPriceSyncResult.java `
        backend/src/test/java/com/opspilot/ai/marketdata/GoldPriceSyncServiceTests.java
git commit -m "feat: 实现黄金价格同步规则"
git push origin master
```

---

### 任务 5：提供手动同步和查询 API

**文件：**

- 新建：`backend/src/main/java/com/opspilot/ai/marketdata/api/GoldPriceController.java`
- 新建：`backend/src/main/java/com/opspilot/ai/marketdata/api/GoldPriceResponse.java`
- 新建：`backend/src/main/java/com/opspilot/ai/marketdata/api/GoldPriceSyncResponse.java`
- 新建：`backend/src/test/java/com/opspilot/ai/marketdata/api/GoldPriceControllerTests.java`
- 修改：`backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java`
- 修改：`backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java`

**接口：**

- `post /api/market-data/gold/daily/sync`：手动同步，返回同步统计。
- `get /api/market-data/gold/daily/latest`：返回最近有效工作日价格。
- `get /api/market-data/gold/daily?limit=60`：返回最近 N 条，允许 `1..500`。

- [ ] **步骤 1：编写 Controller 失败测试**

```java
@Test
@DisplayName("查询最近黄金参考价时返回中文口径字段")
void returnsLatestPrice() throws Exception {
    when(repository.findLatest("XAUUSD")).thenReturn(Optional.of(testPrice()));

    mockMvc.perform(get("/api/market-data/gold/daily/latest"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.symbol").value("XAUUSD"))
            .andExpect(jsonPath("$.currency").value("usd"))
            .andExpect(jsonPath("$.unit").value("troy_ounce"));
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`.\mvnw.cmd -Dtest=GoldPriceControllerTests test`

预期：失败，原因是 Controller 不存在。

- [ ] **步骤 3：实现响应记录和 Controller**

```java
public record GoldPriceResponse(
        String symbol,
        LocalDate priceDate,
        BigDecimal referencePrice,
        String currency,
        String unit,
        String provider,
        OffsetDateTime collectedAt
) {
    static GoldPriceResponse from(MarketPrice price) {
        return new GoldPriceResponse(
                price.symbol(),
                price.priceDate(),
                price.referencePrice(),
                price.currency(),
                price.unit(),
                price.provider(),
                price.collectedAt()
        );
    }
}
```

`latest` 没有数据时返回 `404`，列表 `limit` 超出 `1..500` 时返回 `400`。同步接口只负责调用 `GoldPriceSyncService`，不得直接调用供应商或 Repository。

- [ ] **步骤 4：将上游异常映射为 503**

在 `GlobalExceptionHandler` 增加：

```java
@ExceptionHandler(MarketDataUnavailableException.class)
ResponseEntity<ApiError> handleMarketDataUnavailable(
        MarketDataUnavailableException exception
) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ApiError("MARKET_DATA_UNAVAILABLE", exception.getMessage()));
}
```

测试不得断言或输出 API Key，只断言状态码和安全摘要。

- [ ] **步骤 5：运行相关测试**

运行：

```powershell
.\mvnw.cmd -Dtest=GoldPriceControllerTests,GlobalExceptionHandlerTests test
```

预期：全部通过。

- [ ] **步骤 6：提交并推送**

```powershell
git add backend/src/main/java/com/opspilot/ai/marketdata/api `
        backend/src/test/java/com/opspilot/ai/marketdata/api `
        backend/src/main/java/com/opspilot/ai/chat/api/GlobalExceptionHandler.java `
        backend/src/test/java/com/opspilot/ai/chat/api/GlobalExceptionHandlerTests.java
git commit -m "feat: 添加黄金价格同步查询接口"
git push origin master
```

---

### 任务 6：完成真实数据验收和阶段收尾

**文件：**

- 不新增生产文件。
- 可能修改：本阶段文件中真实验收暴露出的解析或错误处理问题。

**验收：**

- 真实 Alpha Vantage XAU 数据成功保存；
- 数据库不存在周六和周日记录；
- 重复同步不增加重复记录；
- 查询接口返回最近有效工作日参考价；
- 默认测试不需要 Alpha Vantage 网络和 Key；
- 显式真实接口测试使用用户环境变量中的 Key。

- [ ] **步骤 1：运行全部离线测试**

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd test
```

预期：`BUILD SUCCESS`。`AlphaVantageGoldPriceProviderLiveTests` 在未注入环境变量时允许显示 `Skipped`。

- [ ] **步骤 2：运行真实供应商测试**

```powershell
$env:ALPHA_VANTAGE_API_KEY = [Environment]::GetEnvironmentVariable('ALPHA_VANTAGE_API_KEY', 'User')
if ([string]::IsNullOrWhiteSpace($env:ALPHA_VANTAGE_API_KEY)) {
    throw '用户环境变量 ALPHA_VANTAGE_API_KEY 未设置'
}
.\mvnw.cmd -Dtest=AlphaVantageGoldPriceProviderLiveTests test
```

预期：测试实际执行且 `BUILD SUCCESS`，不能是 `Skipped`。

- [ ] **步骤 3：启动应用并触发真实同步**

终端一：

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd spring-boot:run
```

终端二：

```powershell
$sync = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/market-data/gold/daily/sync'
$latest = Invoke-RestMethod -Method Get -Uri 'http://localhost:8080/api/market-data/gold/daily/latest'
$sync | Format-List
$latest | Format-List
```

预期：同步统计中保存数大于 0；最新记录的 `symbol` 为 `XAUUSD`、`currency` 为 `usd`、`unit` 为 `troy_ounce`。

- [ ] **步骤 4：直接验证数据库真实性与周末过滤**

在 Navicat 或 psql 执行：

```sql
select symbol,
       price_date,
       reference_price,
       provider,
       collected_at
from market_price
order by price_date desc
limit 10;

select count(*) as weekend_count
from market_price
where extract(isodow from price_date) in (6, 7);
```

预期：第一条查询返回真实 XAU/USD 价格；第二条 `weekend_count` 为 `0`。

- [ ] **步骤 5：重复同步验证幂等**

```powershell
$before = Invoke-RestMethod -Uri 'http://localhost:8080/api/market-data/gold/daily?limit=500'
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/market-data/gold/daily/sync' | Out-Null
$after = Invoke-RestMethod -Uri 'http://localhost:8080/api/market-data/gold/daily?limit=500'
if ($before.Count -ne $after.Count) {
    throw "重复同步后记录数变化：$($before.Count) -> $($after.Count)"
}
```

预期：不抛异常。

- [ ] **步骤 6：最终差异和工作区检查**

```powershell
git diff --check
git status --short
git log -6 --oneline
```

预期：本阶段生产代码和测试已提交；只剩先前明确保留的未跟踪旧文件。

- [ ] **步骤 7：如验收产生修复则提交**

```powershell
git add backend/src/main/java/com/opspilot/ai/marketdata `
        backend/src/test/java/com/opspilot/ai/marketdata `
        backend/src/main/resources/application.yaml `
        backend/src/main/resources/db/migration/V2__create_market_price.sql
git commit -m "fix: 完善真实黄金价格同步"
git push origin master
```

若验收没有产生任何修改，则跳过本步骤，不创建空提交。

## 阶段完成后的下一计划

本计划验收完成后，再编写第二阶段“真实宏观数据接入”计划。后续顺序固定为：真实宏观数据、确定性黄金指标、研究资料来源、简报生成、下一有效工作日预测评分、历史表现统计。每个阶段独立设计、实施、验收和提交。
