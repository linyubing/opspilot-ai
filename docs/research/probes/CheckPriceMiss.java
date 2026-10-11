import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.forecast.GoldForecastRule;
import java.math.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 独立用生产分类规则核对错判幅度审计，不查询数据库或重训模型。 */
public class CheckPriceMiss {
    static void check(boolean ok) { if (!ok) throw new IllegalStateException("审计复算不一致"); }
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        byte[] raw = Files.readAllBytes(Path.of(args[0]));
        var old = json.readTree(raw);
        var result = json.readTree(Files.readAllBytes(Path.of(args[1])));
        check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))
                .equals(result.path("inputHash").asText()));
        check(!result.path("holdoutRead").asBoolean() && !result.path("trainingPerformed").asBoolean());
        var originals = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        old.path("folds").forEach(f -> f.path("rows").forEach(r -> originals.put(r.path("date").asText(), r)));
        String[] names = {"BULLISH", "NEUTRAL", "BEARISH"};
        String[] fields = {"count","hits","misses","signals","signalHits","opposite","directionAsNeutral","neutralAsDirection"};
        var sums = new TreeMap<String,int[]>();
        var seen = new HashSet<String>();
        for (var row : result.path("rows")) {
            String date = row.path("date").asText(), target = row.path("targetDate").asText();
            check(seen.add(date) && date.compareTo(target)<0 && target.compareTo("2024-11-11")<0);
            var src = originals.get(date);
            check(src != null && src.path("target").asText().equals(target)
                    && src.path("logistic").equals(row.path("probabilities")));
            var base = new BigDecimal(row.path("base").asText());
            var ret = new BigDecimal(row.path("target").asText()).subtract(base)
                    .multiply(BigDecimal.valueOf(100)).divide(base, MathContext.DECIMAL128);
            check(ret.subtract(new BigDecimal(row.path("actualReturn").asText())).abs()
                    .compareTo(new BigDecimal("0.000000000001"))<0);
            String label = new GoldForecastRule().classify(ret).name();
            check(label.equals(src.path("actual").asText()) && label.equals(row.path("label").asText()));
            var abs = ret.abs();
            String bucket = abs.compareTo(new BigDecimal("0.45"))<0 ? "CORE"
                    : abs.compareTo(new BigDecimal("0.55"))<=0 ? "BOUNDARY"
                    : abs.compareTo(BigDecimal.ONE)<0 ? "MODERATE" : "LARGE";
            check(bucket.equals(row.path("bucket").asText()));
            int max = 0;
            double[] p = {src.path("logistic").path("bullish").asDouble(),
                    src.path("logistic").path("neutral").asDouble(),src.path("logistic").path("bearish").asDouble()};
            for(int i=1;i<3;i++) if(p[i]>p[max]) max=i;
            String prediction = names[max];
            boolean hit = prediction.equals(label), signal = p[max]>=0.55;
            check(prediction.equals(row.path("predicted").asText()) && hit==row.path("hit").asBoolean());
            for(String key : List.of("ALL","FOLD_"+row.path("fold").asInt(),bucket,"ACTUAL_"+label)) {
                int[] sum = sums.computeIfAbsent(key,k->new int[8]);
                sum[0]++; if(hit)sum[1]++;else sum[2]++;
                if(signal) {sum[3]++;if(hit)sum[4]++;}
                if(!hit) {
                    if(!label.equals("NEUTRAL")&&!prediction.equals("NEUTRAL"))sum[5]++;
                    if(!label.equals("NEUTRAL")&&prediction.equals("NEUTRAL"))sum[6]++;
                    if(label.equals("NEUTRAL"))sum[7]++;
                }
            }
        }
        check(seen.size()==720 && sums.get("ALL")[1]==265 && sums.size()==result.path("summary").size());
        for(var entry:sums.entrySet())for(int i=0;i<fields.length;i++)
            check(entry.getValue()[i]==result.path("summary").path(entry.getKey()).path(fields[i]).asInt());
        System.out.println("VERIFIED_720_PRICES_LABELS_PROBABILITIES_AND_ALL_BUCKET_COUNTS; NO_TRAINING");
    }
}
