create table gold_daily_bar (
    symbol varchar(20) not null,
    price_date date not null,
    open_price numeric(19, 8) not null,
    high_price numeric(19, 8) not null,
    low_price numeric(19, 8) not null,
    close_price numeric(19, 8) not null,
    currency char(3) not null,
    unit varchar(20) not null,
    provider varchar(40) not null,
    collected_at timestamptz not null,

    constraint pk_gold_daily_bar
        primary key (provider, symbol, price_date),
    constraint ck_gold_daily_bar_positive
        check (open_price > 0 and high_price > 0
            and low_price > 0 and close_price > 0),
    constraint ck_gold_daily_bar_high
        check (high_price >= open_price and high_price >= close_price),
    constraint ck_gold_daily_bar_low
        check (low_price <= open_price and low_price <= close_price),
    constraint ck_gold_daily_bar_currency check (currency = 'usd'),
    constraint ck_gold_daily_bar_unit check (unit = 'troy_ounce')
);

create index idx_gold_daily_bar_symbol_date
    on gold_daily_bar (symbol, price_date desc);
