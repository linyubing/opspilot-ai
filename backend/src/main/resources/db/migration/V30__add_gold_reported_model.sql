-- 旧记录无响应身份资料，保持null，不从配置名回填。
alter table gold_direction_forecast add column reported_model text;
alter table gold_direction_forecast add constraint gold_forecast_reported_model_match
    check (reported_model is null or (reported_model = model_name and length(trim(reported_model)) > 0));
