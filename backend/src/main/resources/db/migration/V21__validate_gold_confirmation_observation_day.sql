-- 确认响应不能提前确认它被观察时仍属于未来的日期。
-- V20 已执行，不修改原迁移；旧未确认行保持为空。
alter table gold_daily_bar
    add constraint ck_gold_confirmation_observed_day check (
        closed_day is null
        or closed_day <= (confirmed_at at time zone 'UTC')::date
    );
