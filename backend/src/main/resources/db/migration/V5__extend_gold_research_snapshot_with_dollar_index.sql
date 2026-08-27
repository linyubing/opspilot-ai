alter table gold_research_snapshot
    rename column rule_version to research_version;

alter table gold_research_snapshot
    rename column assessment_status to real_rate_status;

alter table gold_research_snapshot
    rename column explanation to real_rate_explanation;

alter table gold_research_snapshot
    rename constraint ck_gold_research_snapshot_status
    to ck_gold_research_snapshot_real_rate_status;

alter table gold_research_snapshot
    add column real_rate_rule_version varchar(64),
    add column latest_dollar_index_date date,
    add column dollar_index numeric(18, 6),
    add column dollar_index_return_1 numeric(12, 4),
    add column dollar_index_return_5 numeric(12, 4),
    add column dollar_index_return_20 numeric(12, 4),
    add column dollar_index_collected_at timestamptz,
    add column dollar_index_status varchar(32),
    add column dollar_index_rule_version varchar(64),
    add column dollar_index_explanation varchar(500);

update gold_research_snapshot
set real_rate_rule_version = research_version;

alter table gold_research_snapshot
    alter column real_rate_rule_version set not null,
    add constraint ck_gold_research_snapshot_dollar_index_status
        check (
            dollar_index_status is null
            or dollar_index_status in (
                'pressuring',
                'supportive',
                'neutral'
            )
        ),
    add constraint ck_gold_research_snapshot_dollar_fields_complete
        check (
            (
                latest_dollar_index_date is null
                and dollar_index is null
                and dollar_index_return_1 is null
                and dollar_index_return_5 is null
                and dollar_index_return_20 is null
                and dollar_index_collected_at is null
                and dollar_index_status is null
                and dollar_index_rule_version is null
                and dollar_index_explanation is null
            )
            or (
                latest_dollar_index_date is not null
                and dollar_index is not null
                and dollar_index > 0
                and dollar_index_return_1 is not null
                and dollar_index_return_5 is not null
                and dollar_index_return_20 is not null
                and dollar_index_collected_at is not null
                and dollar_index_status is not null
                and dollar_index_rule_version is not null
                and dollar_index_explanation is not null
            )
        ),
    add constraint ck_gold_research_snapshot_multifactor_dollar_required
        check (
            research_version <> 'gold-multifactor-v2'
            or latest_dollar_index_date is not null
        );
