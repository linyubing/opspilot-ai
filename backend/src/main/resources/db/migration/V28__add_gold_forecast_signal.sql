alter table gold_direction_forecast
    add column signal_status varchar(16),
    alter column predicted_direction drop not null;

alter table gold_direction_forecast
    add constraint ck_gold_direction_forecast_signal
    check (
        case
            when signal_status is null then predicted_direction is not null
            when signal_status = 'predicted' then predicted_direction is not null
            when signal_status = 'abstained' then predicted_direction is null and hit is null
            else false
        end
    );

comment on column gold_direction_forecast.signal_status is
    '显式出信号或弃权；旧记录保留空值，不追认新协议';
