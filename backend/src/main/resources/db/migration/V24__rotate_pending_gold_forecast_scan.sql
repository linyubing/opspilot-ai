-- 扫描时间只控制待结算队列顺序，不改变预测承诺或命中结果。
alter table gold_direction_forecast add column last_scanned_at timestamptz;

create index idx_gold_forecast_pending_scan
    on gold_direction_forecast (last_scanned_at asc nulls first, created_at asc, id asc)
    where status = 'pending';
