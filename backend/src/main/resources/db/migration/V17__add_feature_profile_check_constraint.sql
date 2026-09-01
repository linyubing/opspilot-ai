alter table gold_model_experiment
    add constraint chk_gold_model_experiment_feature_profile
    check (feature_profile in ('BASE_16', 'OHLC_20', 'ALL_36'));
