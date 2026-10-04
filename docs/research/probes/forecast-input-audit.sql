-- 只读核查，不读取模型实验或回测留出集，不修改预测或行情。
-- 采集晚于观测日期不自动代表泄漏；必须结合实际预测截止时刻和来源版本。
begin transaction isolation level repeatable read read only;

select provider, count(*) as bars, min(price_date) as first_date,
       max(price_date) as last_date, min(collected_at) as first_collection,
       max(collected_at) as last_collection,
       count(*) filter (where confirmed_at is not null) as confirmed_bars
from gold_daily_bar
group by provider;

select series_id, count(*) as versions,
       count(*) filter (where superseded_at is null) as current_versions,
       min(observation_date) as first_date, max(observation_date) as last_date,
       min(collected_at) as first_collection, max(collected_at) as last_collection
from macro_observation
group by series_id
order by series_id;

select f.base_date, f.created_at as published_at,
       s.created_at as snapshot_saved_at, s.gold_collected_at,
       s.real_rate_collected_at, s.dollar_index_collected_at,
       s.latest_real_rate_date, s.latest_dollar_index_date,
       s.gold_input is not null as frozen_gold_window,
       f.promised_target_date is not null as promised_timing
from gold_direction_forecast f
join gold_research_snapshot s on s.id = f.snapshot_id
order by f.created_at;

-- 核查“现在重建”的窗口，不把它冒充原预测窗口。
select f.base_date, f.created_at as published_at,
       w.bars, w.current_rows_after_publication, w.current_receipts_after_publication
from gold_direction_forecast f
cross join lateral (
    select count(*) as bars,
           count(*) filter (where b.collected_at > f.created_at)
               as current_rows_after_publication,
           count(*) filter (where b.confirmed_at > f.created_at)
               as current_receipts_after_publication
    from (
        select collected_at, confirmed_at
        from gold_daily_bar
        where provider = 'twelve_data' and symbol = 'XAUUSD'
          and price_date <= f.base_date
        order by price_date desc
        limit 21
    ) b
) w
order by f.created_at;

select analysis_date, created_at, research_version,
       gold_input ->> 'checkedAt' as input_checked_at,
       case when gold_input is not null
           then jsonb_array_length(gold_input -> 'bars') end as frozen_bars
from gold_research_snapshot
order by created_at;

-- 两种价格口径只做敏感性核对，不覆盖旧结算、不生成新预测分数。
select f.base_date, f.base_price as saved_base,
       b.close_price as current_base_close, f.target_date,
       f.target_price as saved_target, t.close_price as current_target_close,
       f.actual_return as saved_return,
       round((t.close_price - b.close_price) / b.close_price * 100, 6)
           as current_close_return,
       b.confirmation_source as current_base_source,
       t.confirmation_source as current_target_source
from gold_direction_forecast f
left join gold_daily_bar b
    on b.provider = 'twelve_data' and b.symbol = 'XAUUSD'
    and b.price_date = f.base_date
left join gold_daily_bar t
    on t.provider = 'twelve_data' and t.symbol = 'XAUUSD'
    and t.price_date = f.target_date
order by f.created_at;

rollback;
