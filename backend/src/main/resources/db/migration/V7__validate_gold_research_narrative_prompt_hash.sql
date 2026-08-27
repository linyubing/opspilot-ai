alter table gold_research_narrative
    add constraint ck_gold_research_narrative_prompt_hash
    check (prompt_hash ~ '^[0-9a-f]{64}$');
