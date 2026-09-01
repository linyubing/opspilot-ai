alter table gold_model_experiment
    add column feature_profile varchar(30) not null default 'ALL_36';

comment on column gold_model_experiment.feature_profile is '特征组合：BASE_16、OHLC_20、ALL_36';
