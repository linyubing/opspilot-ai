create table macro_observation (
                                   id uuid primary key,
                                   series_id varchar(64) not null,
                                   observation_date date not null,
                                   observation_value numeric(18, 6) not null,
                                   unit varchar(32) not null,
                                   provider varchar(32) not null,
                                   collected_at timestamptz not null,
                                   superseded_at timestamptz null,
                                   constraint ck_macro_observation_version_time
                                       check (
                                           superseded_at is null
                                               or superseded_at >= collected_at
                                           )
);

create unique index uk_macro_observation_current
    on macro_observation (
                          series_id,
                          observation_date
        )
    where superseded_at is null;

create index idx_macro_observation_as_of
    on macro_observation (
                          series_id,
                          observation_date desc,
                          collected_at,
                          superseded_at
        );
