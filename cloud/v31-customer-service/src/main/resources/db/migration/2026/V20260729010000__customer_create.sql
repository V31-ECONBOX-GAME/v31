create table customer (
    id                 uuid           not null,
    created_by         varchar(64),
    created_date       timestamptz(6),
    last_modified_by   varchar(64),
    last_modified_date timestamptz(6),
    email              varchar(320)   not null,
    full_name          varchar(100)   not null,
    status             varchar(20)    not null,
    constraint customer_pkey primary key (id),
    constraint uk_customer_email unique (email),
    constraint customer_status_check check (status in ('ACTIVE', 'FROZEN', 'CLOSED'))
);
