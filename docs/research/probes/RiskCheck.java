package com.opspilot.ai.macrodata;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** 用现有生产历史版本选择器核验VIX窗口，只读、不训练模型。 */
public final class RiskCheck {
    record Window(String date, int count, String latest, String hash) {}
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("请提供真实VIX归档与新的target输出路径");
        Path target=Path.of("target").toRealPath(),out=Path.of(args[1]).toAbsolutePath().normalize();
        if(!out.startsWith(target)||out.equals(target)||Files.exists(out))throw new IllegalArgumentException("不允许覆盖归档或写入target之外");
        // 写入前认证真实父路径，拒绝目录链接逃逸；不在外部创建任何父目录。
        if(!out.getParent().toRealPath().startsWith(target))throw new IllegalArgumentException("输出目录链接超出target边界");
        ObjectMapper json=new ObjectMapper();
        FredHistory history=new FredHistory("VIXCLS",Files.readAllBytes(Path.of(args[0])),json);
        var tree=json.readTree(Files.readAllBytes(Path.of("../docs/research/2026-10-03-tree-check.json")));
        if(tree.path("inputs").size()!=4361||tree.path("promotionAllowed").asBoolean())throw new IllegalArgumentException("冻结参照不一致");
        List<Window> windows=new ArrayList<>();
        for(var node:tree.path("inputs")){
            LocalDate day=LocalDate.parse(node.path("date").asText());
            if(!LocalDate.parse(node.path("target").asText()).isBefore(LocalDate.parse("2024-11-11")))throw new IllegalArgumentException("禁止读取未来目标");
            var rows=history.recent(day,21);StringBuilder text=new StringBuilder();
            for(var row:rows)text.append(row.observationDate()).append('|').append(row.id()).append('|').append(row.value().toPlainString()).append('\n');
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
            windows.add(new Window(day.toString(),rows.size(),rows.isEmpty()?null:rows.getFirst().observationDate().toString(),hash));
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("sourceHash",history.hash);result.put("windows",windows);
        Files.writeString(out,json.writeValueAsString(result),StandardOpenOption.CREATE_NEW);
        System.out.println("VIX_PRODUCTION_WINDOWS="+windows.size());
    }
}
