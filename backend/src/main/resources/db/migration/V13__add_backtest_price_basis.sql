alter table gold_forecast_backtest
    add column price_basis varchar(32) not null
    default 'legacy_reference';

alter table gold_forecast_backtest
    alter column price_basis drop default;
