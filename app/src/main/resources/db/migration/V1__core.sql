create table term_version (id varchar(255) primary key, terms_json text not null, terms_hash varchar(255) not null);
create table enquiry (
 id uuid primary key, version bigint not null, product varchar(255), terms_version varchar(255) references term_version(id),
 state varchar(255), client_id varchar(255), operator_id varchar(255), eligibility varchar(255), terms_json text,
 terms_hash varchar(255), selected_offer_id uuid, selection_rationale text, instruction_json text, created_at timestamptz
);
create table offer (
 id uuid primary key, enquiry_id uuid not null references enquiry(id), request_id uuid, source_offer_id uuid, issuer varchar(255),
 kind varchar(255), status varchar(255), terms_version varchar(255), terms_hash varchar(255), currency varchar(255),
 size numeric(38,8), price numeric(38,8), fee numeric(38,8), price_convention varchar(255), credit_information varchar(255),
 terms_json text, details text, received_at timestamptz, expires_at timestamptz
);
create index offer_enquiry_idx on offer(enquiry_id);
create table trade (
 id uuid primary key, enquiry_id uuid not null unique references enquiry(id), idempotency_key varchar(255) not null unique,
 offer_id uuid references offer(id), product varchar(255), terms_version varchar(255), state varchar(255), terms_json text,
 original_purchase_price numeric(38,8), maximum_purchase_exposure numeric(38,8), booked_at timestamptz
);
create table audit_event (id uuid primary key, aggregate_id uuid, event_type varchar(255), correlation_id varchar(255), payload text, occurred_at timestamptz);
create index audit_aggregate_idx on audit_event(aggregate_id);
create table outbox (id uuid primary key, trade_id uuid references trade(id), schema_version integer not null, event_type varchar(255), state varchar(255), payload text, created_at timestamptz);
create table obligation (
 id uuid primary key, trade_id uuid references trade(id), event_key varchar(255), observation_date date,
 payment_date date, kind varchar(255), cash numeric(38,8), shares numeric(38,8), currency varchar(255), state varchar(255),
 unique(trade_id,event_key)
);
create table valuation (id uuid primary key, enquiry_id uuid references enquiry(id), created_at timestamptz, request_json text, result_json text);
create table lifecycle_fixing (id uuid primary key, trade_id uuid references trade(id), observation_date date, spot numeric(38,8), unique(trade_id,observation_date));
