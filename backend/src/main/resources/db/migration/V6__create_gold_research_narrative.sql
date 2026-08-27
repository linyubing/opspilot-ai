create table gold_research_narrative (
    id uuid primary key,
    snapshot_id uuid not null,
    summary varchar(500) not null,
    real_rate_analysis text not null,
    dollar_index_analysis text not null,
    risks jsonb not null,
    watch_list jsonb not null,
    disclaimer varchar(500) not null,
    model_name varchar(100) not null,
    prompt_version varchar(64) not null,
    prompt_hash char(64) not null,
    raw_response text not null,
    created_at timestamptz not null,
    constraint fk_gold_research_narrative_snapshot
        foreign key (snapshot_id)
        references gold_research_snapshot (id)
        on delete restrict,
    constraint uk_gold_research_narrative_idempotency
        unique (snapshot_id, model_name, prompt_version),
    constraint ck_gold_research_narrative_risks_array
        check (jsonb_typeof(risks) = 'array'),
    constraint ck_gold_research_narrative_watch_list_array
        check (jsonb_typeof(watch_list) = 'array')
);

create index idx_gold_research_narrative_snapshot_created
    on gold_research_narrative (snapshot_id, created_at desc);
