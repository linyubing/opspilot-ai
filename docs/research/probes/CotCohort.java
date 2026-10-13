import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.forecast.ConfiguredGoldTradingCalendar;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.marketdata.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.math.*;
import java.util.*;

/** 只读核验持仓事件所需的两根真实日线，不构建技术特征或训练模型。 */
public class CotCohort {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var root = Path.of(args[0]);
        var reports = json.readTree(Files.readString(root.resolve("docs/research/2026-10-13-cot-archive-record.json"))).get("rows");
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var bars = new TreeMap<LocalDate,GoldDailyBar>();
        var password = System.getenv("OPSPILOT_DB_PASSWORD");
        if(password == null || password.isBlank()) throw new IllegalStateException("数据库密码未配置");
        try(var c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/opspilot_ai","postgres",password)) {
            c.setReadOnly(true);c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            // 在读取前截断；独立事件评分不需要21根技术特征窗口。
            try(var s=c.prepareStatement("""
                select symbol, price_date, open_price, high_price, low_price, close_price,
                       currency, unit, provider, collected_at, closed_day, confirmed_at,
                       confirmation_source, confirmation_hash
                from gold_daily_bar
                where provider = 'twelve_data' and symbol = 'XAUUSD'
                  and price_date >= date '2023-01-01' and price_date < date '2024-01-10'
                order by price_date
                """)) {
                try(var r=s.executeQuery()) { while(r.next()) {
                    var proof=r.getObject(12,OffsetDateTime.class)==null?null:new GoldBarConfirmation(r.getString(13),r.getObject(11,LocalDate.class),r.getObject(12,OffsetDateTime.class),r.getString(14));
                    var bar=new GoldDailyBar(r.getString(1),r.getObject(2,LocalDate.class),r.getBigDecimal(3),r.getBigDecimal(4),r.getBigDecimal(5),r.getBigDecimal(6),r.getString(7),r.getString(8),r.getString(9),r.getObject(10,OffsetDateTime.class),proof);
                    if(bars.put(bar.priceDate(),bar)!=null)throw new IllegalStateException("重复日线");
                }}
            }
            c.rollback();
        }
        var properties=new org.springframework.boot.env.YamlPropertySourceLoader().load("calendar",new org.springframework.core.io.ClassPathResource("application.yaml")).getFirst();
        var holidays=new TreeSet<String>();
        for(int i=0;properties.getProperty("opspilot.forecast.gold.holidays["+i+"]")!=null;i++)holidays.add(properties.getProperty("opspilot.forecast.gold.holidays["+i+"]").toString());
        var calendar=new ConfiguredGoldTradingCalendar();calendar.setHolidays(holidays);
        var window=new GoldBarWindow(calendar);var rule=new GoldForecastRule();
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<reports.size();i++) {
            var report=reports.get(i);var position=LocalDate.parse(report.get("positionDate").asText());
            var earliest=LocalDate.parse(report.get("archiveUpdatedDate").asText()).plusDays(2);
            var base=calendar.nextBusinessDay(earliest.minusDays(1));var target=calendar.nextBusinessDay(base);
            if(!target.isBefore(LocalDate.parse("2024-11-11")))throw new IllegalStateException("禁止读取留出标签");
            var row=new LinkedHashMap<String,Object>();row.put("positionDate",position);row.put("baseDate",base);row.put("targetDate",target);row.put("positionAgeDays",java.time.temporal.ChronoUnit.DAYS.between(position,base));
            if(i==0){row.put("status","NO_PREVIOUS_REPORT");rows.add(row);continue;}
            if(java.time.temporal.ChronoUnit.DAYS.between(position,base)>14){row.put("status","STALE_POSITION");rows.add(row);continue;}
            var a=bars.get(base);var b=bars.get(target);
            if(a==null||b==null){row.put("status","PRICE_MISSING");rows.add(row);continue;}
            row.put("bars",List.of(a,b));
            if(!a.isConfirmedAt(now)||!b.isConfirmedAt(now)){row.put("status","UNCONFIRMED");rows.add(row);continue;}
            if(!window.isContinuous(List.of(a,b))){row.put("status","CALENDAR_CONFLICT");rows.add(row);continue;}
            // 库内币种为usd；只做大小写等价比较，不转换价格或接受其他币种。
            for(var bar:List.of(a,b))if(!"USD".equalsIgnoreCase(bar.currency())||!"troy_ounce".equals(bar.unit())||!Objects.equals(a.unit(),b.unit())||bar.low().signum()<=0||bar.high().compareTo(bar.low())<0||bar.high().compareTo(bar.open())<0||bar.high().compareTo(bar.close())<0||bar.low().compareTo(bar.open())>0||bar.low().compareTo(bar.close())>0)throw new IllegalStateException("日线口径或价格非法");
            var actual=b.close().subtract(a.close()).divide(a.close(),MathContext.DECIMAL128).multiply(new BigDecimal("100"));
            row.put("actualReturn",actual.toPlainString());row.put("actual",rule.classify(actual));row.put("status","ELIGIBLE");rows.add(row);
        }
        // 日期选择改变也不能跳过旧标签核验；原36条逐一复用生产分类器。
        var legacyChecks=new ArrayList<Map<String,Object>>();
        var old=json.readTree(Files.readString(root.resolve("docs/research/2026-10-13-cot-rule-result.json"))).get("rows");
        for(var legacy:old)if("SCORED".equals(legacy.get("status").asText())) {
            var base=LocalDate.parse(legacy.get("baseDate").asText());var target=LocalDate.parse(legacy.get("targetDate").asText());
            var a=bars.get(base);var b=bars.get(target);
            if(a==null||b==null||!a.isConfirmedAt(now)||!b.isConfirmedAt(now)||!window.isContinuous(List.of(a,b)))throw new IllegalStateException("旧评分日线不能通过核验");
            var actual=b.close().subtract(a.close()).divide(a.close(),MathContext.DECIMAL128).multiply(new BigDecimal("100"));
            var label=rule.classify(actual).name();
            if(!label.equals(legacy.get("actual").asText()))throw new IllegalStateException("旧评分标签与真实日线不一致");
            legacyChecks.add(Map.of("baseDate",base,"targetDate",target,"actual",label,"matched",true));
        }
        if(legacyChecks.size()!=36)throw new IllegalStateException("旧评分核验数量异常");
        System.out.println(json.writeValueAsString(Map.of("verifiedAt",now,"holidays",holidays,"source","gold_daily_bar; XAUUSD; twelve_data; read-only; 2023-01-01 <= date < 2024-01-10","rows",rows,"legacyChecks",legacyChecks)));
    }
}
