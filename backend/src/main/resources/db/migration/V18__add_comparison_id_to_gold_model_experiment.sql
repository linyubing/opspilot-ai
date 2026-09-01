alter table gold_model_experiment
    add column if not exists comparison_id uuid;

create index if not exists idx_gold_model_experiment_comparison_id
    on gold_model_experiment (comparison_id);
