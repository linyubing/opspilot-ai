alter table gold_forecast_backtest
    add column sample_set varchar(32) not null
    default 'default';

alter table gold_forecast_backtest
    alter column sample_set drop default;
