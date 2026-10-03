-- 旧日线保留未确认状态，不能用历史日期推断闭市依据。
alter table gold_daily_bar
    add column closed_day date,
    add column confirmed_at timestamptz,
    add column confirmation_source varchar(40),
    add column confirmation_hash varchar(64);

alter table gold_daily_bar add constraint ck_gold_bar_confirmation check (
    (closed_day is null and confirmed_at is null
        and confirmation_source is null and confirmation_hash is null)
    or
    (closed_day is not null and confirmed_at is not null
        and confirmation_source is not null and confirmation_hash is not null
        and closed_day >= price_date and confirmed_at >= collected_at
        and confirmation_source = 'twelve_data_quote_eod_v1'
        and confirmation_hash ~ '^[0-9a-f]{64}$')
);
