alter table gold_model_experiment_metric
    add constraint chk_gold_model_experiment_metric_model_type
    check (model_type in ('MAJORITY', 'LOGISTIC', 'XGBOOST'));
