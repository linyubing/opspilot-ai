alter table gold_model_experiment_metric
    add column paired_metrics jsonb;

comment on column gold_model_experiment_metric.paired_metrics is '完整日期指标、信号日期及同日期基线；旧实验缺少配对证据时保留空值';
