package com.opspilot.ai.forecast.learning;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 独立复核真实输入信息清单，不评分、不查询行情或修改预测。 */
public class CheckInputAudit {
    static void check(boolean ok) { if(!ok)throw new IllegalStateException("输入审计复算失败"); }
    public static void main(String[] args) throws Exception {
        String raw=Files.readString(Path.of(args[0]),StandardCharsets.UTF_8);
        var data=PriorCompare.JSON.readValue(raw,GoldDataset.class);
        var result=PriorCompare.JSON.readTree(Files.readString(Path.of(args[1]),StandardCharsets.UTF_8));
        check(PriorCompare.HASH.equals(new GoldDatasetFingerprint().hash(data)));
        check(PriorCompare.sha(raw).equals(result.path("inputHash").asText()));
        check(PriorCompare.sha(Files.readString(Path.of("../backend/src/main/java/com/opspilot/ai/forecast/learning/GoldFeatures.java"),StandardCharsets.UTF_8))
                .equals(result.path("featureSchemaHash").asText()));
        check(!result.path("holdoutRead").asBoolean()&&!result.path("trainingPerformed").asBoolean());
        check(data.samples().size()==1268&&result.path("sampleCount").asInt()==1268);
        Set<String> gold=new HashSet<>(),macro=new HashSet<>();
        for(String name:GoldFeatures.NAMES)if(name.startsWith("real_rate")||name.startsWith("dollar_"))macro.add(name);else gold.add(name);
        Set<String> savedGold=new HashSet<>(),savedMacro=new HashSet<>();
        result.path("goldFeatures").forEach(n->savedGold.add(n.asText()));
        result.path("macroFeatures").forEach(n->savedMacro.add(n.asText()));
        check(gold.equals(savedGold)&&macro.equals(savedMacro)&&gold.size()==27&&macro.size()==9);
        for(String name:List.of("real_rate_age","dollar_age")) {
            int max=0,sum=0;var bins=new HashMap<String,Integer>();
            for(String bin:List.of("0..1","2..4","5..7",">7"))bins.put(bin,0);
            for(var sample:data.samples()) {
                check(sample.targetDate().isBefore(PriorCompare.LIMIT));
                double v=sample.features().values().get(name);check(v>=0&&v==Math.rint(v));
                int age=(int)v;max=Math.max(max,age);sum+=age;
                bins.merge(age<=1?"0..1":age<=4?"2..4":age<=7?"5..7":">7",1,Integer::sum);
            }
            var saved=result.path("macroObservationAge").path(name);
            check(saved.path("max").asInt()==max&&saved.path("sum").asInt()==sum);
            for(var bin:bins.entrySet())check(saved.path("buckets").path(bin.getKey()).asInt()==bin.getValue());
        }
        check(result.path("duplicatePairs").size()==2);
        for(var pair:result.path("duplicatePairs")) {
            int count=0;double max=0;
            for(var sample:data.samples()) {
                double difference=Math.abs(sample.features().values().get(pair.path("a").asText())
                        -sample.features().values().get(pair.path("b").asText()));
                if(difference==0)count++;max=Math.max(max,difference);
            }
            check(count==pair.path("exactCount").asInt()&&max==pair.path("maxAbsDifference").asDouble());
        }
        check(result.path("eventScheduleFeatures").isEmpty()&&result.path("economicSurpriseFeatures").isEmpty());
        System.out.println("VERIFIED_1268_REAL_INPUTS_FEATURE_SCHEMA_MACRO_AGES_AND_DUPLICATE_COLUMNS");
    }
}
