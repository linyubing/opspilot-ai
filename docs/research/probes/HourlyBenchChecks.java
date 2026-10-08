package com.opspilot.ai.forecast.learning;

import java.util.List;

/** 用手算分类案例检查评估器；案例是分类结果，不是假行情。 */
public class HourlyBenchChecks {
    public static void main(String[] args) throws Exception {
        var metric = HourlyBench.metrics(List.of(
                new HourlyBench.Score("BULLISH", "BULLISH", new double[]{.8,.1,.1}),
                new HourlyBench.Score("NEUTRAL", "BULLISH", new double[]{.6,.3,.1}),
                new HourlyBench.Score("BEARISH", "BEARISH", new double[]{.1,.2,.7})));
        near(metric.accuracy(), 2.0/3, "accuracy");
        near(metric.balancedAccuracy(), 2.0/3, "balanced accuracy");
        near(metric.brier(), (.06+.86+.14)/3, "three-class Brier sum");
        near(metric.logLoss(), -(Math.log(.8)+Math.log(.3)+Math.log(.7))/3, "logLoss");
        var absent = HourlyBench.metrics(List.of(
                new HourlyBench.Score("NEUTRAL", "NEUTRAL", new double[]{0,1,0})));
        if (absent.recalls()[0] != null || absent.recalls()[2] != null || absent.balancedAccuracy() != null) {
            throw new AssertionError("Absent actual classes must not get invented recall");
        }
        boolean rejected=false;
        try { HourlyBench.metrics(List.of(new HourlyBench.Score("NEUTRAL","NEUTRAL",new double[]{.1,.1,.1}))); }
        catch (IllegalArgumentException ex) { rejected=true; }
        if (!rejected) throw new AssertionError("Invalid probabilities accepted");
        System.out.println("metrics checks: 6 passed");
        if (HourlyBench.trainEnd(61)!=60) throw new AssertionError("Immediate previous label was not purged");
        if (HourlyBench.foldBase(69,61,10)!=61) throw new AssertionError("Models did not share frozen training fold");
        var scale=HourlyBench.fit(List.of(new double[]{2,7},new double[]{4,7}));
        near(scale.means()[0],3,"training mean");
        near(scale.apply(new double[]{5,99})[0],2,"frozen scaling");
        near(scale.apply(new double[]{5,99})[1],0,"constant training column");
        near(scale.means()[0],3,"prediction must not refit");
        System.out.println("training isolation checks: 6 passed");
        var training=List.of(
                new HourlyBench.Sample("","","BEARISH",0,new double[]{1}),
                new HourlyBench.Sample("","","BEARISH",0,new double[]{2}),
                new HourlyBench.Sample("","","BEARISH",0,new double[]{3}),
                new HourlyBench.Sample("","","BULLISH",0,new double[]{0}));
        var p=HourlyBench.transition(training,1);
        near(p[0],1.0/6,"conditional bullish count");
        near(p[1],1.0/6,"conditional neutral count");
        near(p[2],2.0/3,"conditional bearish count");
        near(java.util.Arrays.stream(p).sum(),1,"conditional probability sum");
        System.out.println("transition checks: 4 passed");
        var end=java.time.OffsetDateTime.parse("2026-10-08T21:00:00Z");
        if(HourlyBench.canSettle(end.minusSeconds(1),end)) throw new AssertionError("Unfinished target admitted");
        if(!HourlyBench.canSettle(end,end)) throw new AssertionError("Completed target not admitted");
        System.out.println("settlement boundary checks: 2 passed");
        if(args.length==2) settlementChecks(args[0],args[1]);
    }

    /** 用真实小时回执检查结算分支；历史预测壳标记TEST_ONLY，不是实盘样本。 */
    private static void settlementChecks(String source,String forecast) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var root=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("backend/target"),"hourly-settlement-check-");
        var pending=root.resolve("pending.json");
        HourlyBench.settle(forecast,source,pending.toString());
        var actual=mapper.readTree(java.nio.file.Files.readString(pending));
        if(!actual.path("status").asText().equals("WAITING_WINDOW_END") || actual.has("correct")) {
            throw new AssertionError("Unfinished actual forecast counted as a miss");
        }
        var checked=mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(source+".check.json")));
        var template=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(forecast)));
        template.put("version","TEST_ONLY");
        // 只用开发段已经评估的真实6月28日基准，不再使用预留位置。
        var base=java.util.stream.StreamSupport.stream(checked.path("bars").spliterator(),false)
                .filter(row->row.path("endExclusive").asText().startsWith("2026-06-28T21:00:00")).findFirst().orElseThrow();
        fixture(template,base);
        ((com.fasterxml.jackson.databind.node.ObjectNode)template.path("forecast")).put("direction","BEARISH");
        var fixturePath=root.resolve("historical-test-forecast.json");
        java.nio.file.Files.writeString(fixturePath,mapper.writeValueAsString(template));
        var out=root.resolve("historical-test-outcome.json");
        HourlyBench.settle(fixturePath.toString(),source,out.toString());
        actual=mapper.readTree(java.nio.file.Files.readString(out));
        if(!actual.path("status").asText().equals("RESEARCH_SETTLED") || !actual.path("correct").asBoolean()
                || actual.path("trustedAccuracyEligible").asBoolean(true) || !actual.path("forecastVersion").asText().equals("TEST_ONLY")) {
            throw new AssertionError("Historical classification fixture settlement failed");
        }
        // 只损坏预测基准副本，不修改真实行情源；应拒绝结算。
        ((com.fasterxml.jackson.databind.node.ObjectNode)template.path("bars").get(0)).put("close","1");
        java.nio.file.Files.writeString(fixturePath,mapper.writeValueAsString(template));
        out=root.resolve("corrupt-baseline.json");
        HourlyBench.settle(fixturePath.toString(),source,out.toString());
        actual=mapper.readTree(java.nio.file.Files.readString(out));
        if(!actual.path("status").asText().equals("BASE_SOURCE_REVISION") || actual.has("correct")) {
            throw new AssertionError("Changed baseline admitted");
        }
        var oldBase=java.util.stream.StreamSupport.stream(checked.path("bars").spliterator(),false)
                .filter(row->row.path("endExclusive").asText().startsWith("2026-04-03T21:00:00")).findFirst().orElseThrow();
        fixture(template,oldBase);
        java.nio.file.Files.writeString(fixturePath,mapper.writeValueAsString(template));
        out=root.resolve("missing-real-hour-window.json");
        HourlyBench.settle(fixturePath.toString(),source,out.toString());
        actual=mapper.readTree(java.nio.file.Files.readString(out));
        if(!actual.path("status").asText().equals("MISSING_COMPLETE_WINDOW") || actual.has("correct")) {
            throw new AssertionError("Missing real target hour admitted");
        }
        System.out.println("real receipt settlement checks: 4 passed");
    }

    private static void fixture(com.fasterxml.jackson.databind.node.ObjectNode template,com.fasterxml.jackson.databind.JsonNode base) {
        var end=java.time.OffsetDateTime.parse(base.path("endExclusive").asText());
        template.put("baseEnd",end.toString()); template.put("targetEnd",end.plusDays(1).toString());
        template.put("createdAt",end.plusMinutes(5).toString()); template.put("completedAt",end.plusMinutes(6).toString());
        template.putArray("bars").add(base.deepCopy());
    }

    private static void near(Double actual, double expected, String name) {
        if (actual == null || Math.abs(actual-expected) > 1e-10) throw new AssertionError(name+" mismatch");
    }
}
