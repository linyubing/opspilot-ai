-- 原记录保持null，不把当前模型资料回填到历史预测。
alter table gold_direction_forecast add column model_run jsonb;
alter table gold_direction_forecast add constraint gold_forecast_model_run_check check (
    model_run is null or coalesce((
        jsonb_typeof(model_run) = 'object'
        and model_run ->> 'model' = model_name
        and model_run ->> 'model' = reported_model
        and model_run ->> 'promptHash' = prompt_hash
        and model_run ->> 'digest' ~ '^[0-9a-f]{64}$'
    ), false)
);
