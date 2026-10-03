-- 旧快照不回填依据；完整窗口作为单个不可变JSON字段保存。
alter table gold_research_snapshot add column gold_input jsonb;

alter table gold_research_snapshot add constraint ck_gold_snapshot_input_shape check (
    gold_input is null or coalesce(
        jsonb_typeof(gold_input) = 'object'
        and case when jsonb_typeof(gold_input -> 'bars') = 'array'
            then jsonb_array_length(gold_input -> 'bars') = 21 else false end
        and jsonb_typeof(gold_input -> 'checkedAt') = 'string'
        and length(trim(gold_input ->> 'checkedAt')) > 0,
        false
    )
);

alter table gold_research_snapshot add constraint ck_gold_snapshot_confirmed_fields check (
    research_version <> 'gold-multifactor-confirmed-v3'
    or (gold_input is not null and latest_dollar_index_date is not null)
);
