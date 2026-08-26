create table gold_research_snapshot (
    id uuid primary key,
    analysis_date date not null,
    latest_gold_date date not null,
    latest_real_rate_date date not null,
    gold_price numeric(19, 8) not null,
    gold_return_1 numeric(12, 4) not null,
    gold_return_5 numeric(12, 4) not null,
    gold_return_20 numeric(12, 4) not null,
    gold_collected_at timestamptz not null,
    real_rate numeric(18, 6) not null,
    real_rate_change_1 numeric(18, 6) not null,
    real_rate_change_5 numeric(18, 6) not null,
    real_rate_change_20 numeric(18, 6) not null,
    real_rate_collected_at timestamptz not null,
    assessment_status varchar(32) not null,
    rule_version varchar(64) not null,
    explanation varchar(500) not null,
    disclaimer varchar(500) not null,
    created_at timestamptz not null,

    constraint ck_gold_research_snapshot_price
        check (gold_price > 0),
    constraint ck_gold_research_snapshot_status
        check (assessment_status in (
            'pressuring',
            'supportive',
            'neutral'
        )),
    constraint uk_gold_research_snapshot_idempotency
        unique (analysis_date, rule_version)
);

create index idx_gold_research_snapshot_recent
    on gold_research_snapshot (
        analysis_date desc,
        created_at desc
    );
