-- 旧预测保留null，不用后来推断的时段补造历史发布证据。
alter table gold_direction_forecast
    add column promised_target_date date,
    add column target_start timestamptz,
    add column target_end timestamptz,
    add column timing_rule varchar(40);

alter table gold_direction_forecast add constraint ck_gold_forecast_timing check (
    (promised_target_date is null and target_start is null
        and target_end is null and timing_rule is null)
    or
    (promised_target_date is not null and target_start is not null
        and target_end is not null and timing_rule is not null
        and promised_target_date > base_date and target_start < target_end
        and created_at < target_end and timing_rule = 'sydney-0700-candidate-v1')
);
