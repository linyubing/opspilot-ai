-- 旧快照与预测不更新、不补造输入；新身份协议独立于研究公式版本。
alter table gold_research_snapshot
    add column identity_protocol varchar(32),
    add column input_hash varchar(64);

alter table gold_research_snapshot drop constraint uk_gold_research_snapshot_idempotency;

create unique index uk_gold_snapshot_legacy
    on gold_research_snapshot (analysis_date, research_version)
    where input_hash is null;

create unique index uk_gold_snapshot_facts
    on gold_research_snapshot (analysis_date, research_version, identity_protocol, input_hash)
    where input_hash is not null;

alter table gold_research_snapshot add constraint ck_gold_snapshot_fact_identity check (
    (input_hash is null and identity_protocol is null)
    or (input_hash is not null and identity_protocol = 'gold-facts-v1'
        and input_hash ~ '^[0-9a-f]{64}$' and gold_input is not null
        and jsonb_typeof(gold_input->'macro') = 'object'
        and jsonb_typeof(gold_input->'macro'->'rates') = 'array'
        and jsonb_array_length(gold_input->'macro'->'rates') = 4
        and jsonb_typeof(gold_input->'macro'->'dollars') = 'array'
        and jsonb_array_length(gold_input->'macro'->'dollars') = 4) is true
);
