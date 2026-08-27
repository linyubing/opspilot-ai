create table gold_direction_forecast (
    id uuid primary key,
    snapshot_id uuid not null,
    base_date date not null,
    base_price numeric(20, 8) not null,
    predicted_direction varchar(16) not null,
    reasoning text not null,
    invalidation_conditions jsonb not null,
    model_name varchar(100) not null,
    prompt_version varchar(64) not null,
    prompt_hash char(64) not null,
    forecast_rule_version varchar(64) not null,
    raw_response text not null,
    status varchar(16) not null,
    target_date date,
    target_price numeric(20, 8),
    actual_return numeric(12, 6),
    actual_direction varchar(16),
    hit boolean,
    resolved_at timestamptz,
    created_at timestamptz not null,

    constraint fk_gold_direction_forecast_snapshot
        foreign key (snapshot_id)
        references gold_research_snapshot (id)
        on delete restrict,
    constraint uk_gold_direction_forecast_idempotency
        unique (snapshot_id, model_name, prompt_version, forecast_rule_version),
    constraint ck_gold_direction_forecast_base_price
        check (base_price > 0),
    constraint ck_gold_direction_forecast_predicted_direction
        check (predicted_direction in ('bullish', 'neutral', 'bearish')),
    constraint ck_gold_direction_forecast_actual_direction
        check (actual_direction is null or actual_direction in ('bullish', 'neutral', 'bearish')),
    constraint ck_gold_direction_forecast_status
        check (status in ('pending', 'resolved', 'data_missing', 'voided')),
    constraint ck_gold_direction_forecast_conditions
        check (jsonb_typeof(invalidation_conditions) = 'array'),
    constraint ck_gold_direction_forecast_prompt_hash
        check (prompt_hash ~ '^[0-9a-f]{64}$')
);

create index idx_gold_direction_forecast_status_created
    on gold_direction_forecast (status, created_at asc);
