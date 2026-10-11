-- 旧快照不删除、不回填；新连续日历协议使用独立版本和幂等键。
alter table gold_research_snapshot drop constraint ck_gold_snapshot_confirmed_fields;
alter table gold_research_snapshot add constraint ck_gold_snapshot_confirmed_fields check (
    research_version not in ('gold-multifactor-confirmed-v3', 'gold-multifactor-contiguous-v4')
    or (gold_input is not null and latest_dollar_index_date is not null)
);
