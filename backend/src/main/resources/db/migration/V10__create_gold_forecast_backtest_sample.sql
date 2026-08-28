create table gold_forecast_backtest_sample (
    backtest_id uuid not null
        references gold_forecast_backtest(id) on delete cascade,
    position integer not null check (position > 0),
    as_of_date date not null,
    primary key (backtest_id, position),
    unique (backtest_id, as_of_date)
);

insert into gold_forecast_backtest_sample (
    backtest_id,
    position,
    as_of_date
)
select backtest_id,
       row_number() over (
           partition by backtest_id
           order by as_of_date
       )::integer,
       as_of_date
from gold_forecast_backtest_case
on conflict do nothing;
