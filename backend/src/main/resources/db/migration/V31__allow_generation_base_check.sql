-- 仅增加生成前基准核验类型；旧记录不回填。
alter table gold_forecast_check drop constraint gold_forecast_check_code_check;
alter table gold_forecast_check add constraint gold_forecast_check_code_check check (code in (
    'generation_base_checked',
    'waiting_bar', 'invalid_time', 'unconfirmed_bar', 'invalid_timing', 'waiting_end',
    'base_unavailable', 'base_changed', 'base_session_failed', 'target_session_failed',
    'source_unavailable', 'check_passed', 'legacy_price_only'
));
