create table gold_model_experiment (
    id uuid primary key,
    horizon varchar(30) not null,
    feature_version varchar(100) not null,
    label_version varchar(100) not null,
    split_version varchar(100) not null,
    dataset_hash varchar(64) not null,
    parameter_json jsonb not null,
    data_start date not null,
    data_end date not null,
    train_start date not null,
    validation_start date not null,
    validation_end date not null,
    holdout_start date not null,
    holdout_end date not null,
    validation_samples integer not null,
    holdout_samples integer not null,
    status varchar(30) not null,
    git_commit varchar(100) not null,
    failure_message varchar(1000),
    created_at timestamp with time zone not null,
    started_at timestamp with time zone,
    completed_at timestamp with time zone
);

comment on table gold_model_experiment is '黄金监督学习模型实验记录';

create index idx_gold_model_experiment_created_at on gold_model_experiment(created_at desc);

create table gold_model_experiment_metric (
    experiment_id uuid not null,
    model_type varchar(30) not null,
    sample_count integer not null,
    covered_count integer not null,
    coverage numeric,
    accuracy numeric,
    balanced_accuracy numeric,
    brier_score numeric,
    log_loss numeric,
    recalls jsonb not null,
    confusion_matrix jsonb not null,
    primary key (experiment_id, model_type),
    foreign key (experiment_id) references gold_model_experiment(id)
);

comment on table gold_model_experiment_metric is '黄金模型实验评估指标';
