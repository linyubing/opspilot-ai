package com.opspilot.ai.forecast.learning;

import java.nio.file.*;
import java.time.*;

/** 用生产领域类型与指纹独立检查未来频率基线，不调用模型或数据库。 */
public class CheckForwardPrior {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("需冻结真实数据集和基线记录");
        var data=PriorCompare.JSON.readValue(Files.readString(Path.of(args[0])),GoldDataset.class);
        if(!PriorCompare.HASH.equals(new GoldDatasetFingerprint().hash(data)))throw new IllegalStateException("原数据集指纹变化");
        var r=PriorCompare.JSON.readTree(Path.of(args[1]).toFile());
        var counts=PriorCompare.counts(data.samples());
        for(var entry:counts.entrySet()){
            if(entry.getValue()!=r.path("counts").path(entry.getKey().name()).asInt())throw new IllegalStateException("标签次数不同");
            if(Math.abs((double)entry.getValue()/data.samples().size()-r.path("probabilities").path(entry.getKey().name()).asDouble())>1e-12)
                throw new IllegalStateException("标签频率不同");
        }
        if(data.samples().size()!=1268||r.path("trainingSamples").asInt()!=1268)throw new IllegalStateException("训练数量变化");
        if(!r.path("settlementBasePrice").isNull()||!r.path("actual").isNull()||!r.path("hit").isNull())throw new IllegalStateException("提前填入未来结果");
        if(!Instant.parse(r.path("createdAt").asText()).isBefore(Instant.parse(r.path("targetStart").asText())))throw new IllegalStateException("不是事前基线");
        System.out.println("独立基线核验通过：1268真实样本指纹、三类次数/频率、事前时刻和空结果一致");
    }
}
