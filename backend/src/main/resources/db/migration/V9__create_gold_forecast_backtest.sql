create table gold_forecast_backtest (
    id uuid primary key,
    start_date date not null,
    end_date date not null,
    sample_count integer not null,
    model_name varchar(100) not null,
    prompt_version varchar(64) not null,
    rule_version varchar(64) not null,
    status varchar(16) not null,
    completed_count integer not null default 0,
    hit_count integer not null default 0,
    failed_count integer not null default 0,
    last_error varchar(500),
    created_at timestamp with time zone not null,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    constraint ck_gold_forecast_backtest_dates
        check (start_date <= end_date),
    constraint ck_gold_forecast_backtest_samples
        check (sample_count between 1 and 120),
    constraint ck_gold_forecast_backtest_status
        check (status in ('created', 'running', 'completed', 'failed')),
    constraint ck_gold_forecast_backtest_counts
        check (
            completed_count >= 0
            and hit_count >= 0
            and failed_count >= 0
            and hit_count <= completed_count
            and completed_count + failed_count <= sample_count
        )
);

create table gold_forecast_backtest_case (
    id uuid primary key,
    backtest_id uuid not null,
    as_of_date date not null,
    snapshot jsonb not null,
    base_price numeric(19, 8) not null,
    predicted_direction varchar(8) not null,
    reasoning text not null,
    invalidation_conditions jsonb not null,
    target_date date not null,
    target_price numeric(19, 8) not null,
    actual_return numeric(18, 6) not null,
    actual_direction varchar(8) not null,
    hit boolean not null,
    model_name varchar(100) not null,
    prompt_version varchar(64) not null,
    prompt_hash char(64) not null,
    rule_version varchar(64) not null,
    raw_response text not null,
    created_at timestamp with time zone not null,
    constraint fk_gold_forecast_backtest_case_task
        foreign key (backtest_id)
        references gold_forecast_backtest (id)
        on delete cascade,
    constraint uk_gold_forecast_backtest_case
        unique (backtest_id, as_of_date),
    constraint ck_gold_forecast_backtest_case_prices
        check (base_price > 0 and target_price > 0),
    constraint ck_gold_forecast_backtest_case_dates
        check (target_date > as_of_date),
    constraint ck_gold_forecast_backtest_case_predicted_direction
        check (predicted_direction in ('bullish', 'neutral', 'bearish')),
    constraint ck_gold_forecast_backtest_case_actual_direction
        check (actual_direction in ('bullish', 'neutral', 'bearish')),
    constraint ck_gold_forecast_backtest_case_conditions
        check (jsonb_typeof(invalidation_conditions) = 'array'),
    constraint ck_gold_forecast_backtest_case_prompt_hash
        check (prompt_hash ~ '^[0-9a-f]{64}$')
);

create index idx_gold_forecast_backtest_status_created
    on gold_forecast_backtest (status, created_at desc);

create index idx_gold_forecast_backtest_case_task_date
    on gold_forecast_backtest_case (backtest_id, as_of_date desc);
