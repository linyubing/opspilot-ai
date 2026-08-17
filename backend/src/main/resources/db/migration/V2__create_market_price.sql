-- 保存 Alpha Vantage 提供的黄金每日参考价格。
-- 当前数据源不提供开高低收，因此这里只保存可验证的参考价格。
create table market_price (
    symbol varchar(20) not null,
    price_date date not null,
    reference_price numeric(19, 8) not null,
    currency char(3) not null,
    unit varchar(20) not null,
    provider varchar(40) not null,
    collected_at timestamptz not null,

    constraint pk_market_price
        primary key (provider, symbol, price_date),
    constraint ck_market_price_positive
        check (reference_price > 0),
    constraint ck_market_price_currency
        check (currency = 'usd'),
    constraint ck_market_price_unit
        check (unit = 'troy_ounce')
);

-- 后续查询最近价格和计算时间序列指标时，按标的和日期倒序读取。
create index idx_market_price_symbol_date
    on market_price (symbol, price_date desc);
