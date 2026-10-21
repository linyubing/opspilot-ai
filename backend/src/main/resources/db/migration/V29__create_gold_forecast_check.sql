-- 追加核验记录；不回填旧预测的历史证据。
create table gold_forecast_check (
    id uuid primary key,
    forecast_id uuid not null references gold_direction_forecast(id) on delete cascade,
    checked_at timestamptz not null,
    code varchar(40) not null check (code in (
        'waiting_bar', 'invalid_time', 'unconfirmed_bar', 'invalid_timing', 'waiting_end',
        'base_unavailable', 'base_changed', 'base_session_failed', 'target_session_failed',
        'source_unavailable', 'check_passed', 'legacy_price_only'
    )),
    details jsonb not null check (jsonb_typeof(details) = 'object')
);
create index gold_forecast_check_recent_idx on gold_forecast_check(forecast_id, checked_at desc, id desc);
