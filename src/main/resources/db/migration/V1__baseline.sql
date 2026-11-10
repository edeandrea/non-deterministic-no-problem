-- The application's schema, as Hibernate generated it from the entities, with readable constraint names.
-- Flow's own tables come from quarkus-flow-db-migration, under the flow-runtime Flyway configuration.
-- Hibernate validates tables, columns, types and sequences against this, but not column defaults:
-- claims.claim_number's default is what numbers a claim, so keep it.

create sequence claims_seq start with 1 increment by 50;
create sequence claim_images_seq start with 1 increment by 50;
create sequence claim_correspondence_seq start with 1 increment by 50;
create sequence claim_number_seq start with 1000000 increment by 1009 maxvalue 99999999;

create table claims (
	id bigint not null,
	claim_number varchar(255) not null default 'CLM' || lpad(nextval('claim_number_seq')::text, 8, '0'),
	category varchar(255),
	client_name varchar(255),
	email_address varchar(255),
	inception_date date,
	incident_date date,
	incident_time time(0),
	policy_number varchar(255),
	sentiment varchar(5000),
	status varchar(255),
	summary varchar(5000),
	subject text,
	body text,
	location text,
	constraint claims_pkey primary key (id),
	constraint claims_claim_number_key unique (claim_number),
	constraint claims_category_check check (category in ('SINGLE_VEHICLE', 'MULTIPLE_VEHICLE', 'THEFT', 'OTHER'))
);

create table claim_correspondence (
	id bigint not null,
	claim_id bigint,
	created_claim boolean not null,
	direction varchar(255) not null,
	message_id varchar(255) not null,
	received_at timestamp(6) with time zone not null,
	sender varchar(255),
	sent_date timestamp(6) with time zone not null,
	stripped_text text,
	subject text,
	constraint claim_correspondence_pkey primary key (id),
	constraint claim_correspondence_message_id_key unique (message_id),
	constraint claim_correspondence_direction_check check (direction in ('INBOUND', 'OUTBOUND')),
	constraint claim_correspondence_claim_fk foreign key (claim_id) references claims on delete cascade
);

create table claim_images (
	id bigint not null,
	claim_id bigint not null,
	correspondence_id bigint,
	content_type varchar(255) not null,
	created_at timestamp(6) with time zone not null,
	data bytea not null,
	file_name text not null,
	kind varchar(255) not null,
	constraint claim_images_pkey primary key (id),
	constraint claim_images_content_type_check check (content_type in ('JPEG', 'PNG', 'GIF', 'WEBP')),
	constraint claim_images_kind_check check (kind in ('ORIGINAL', 'PROCESSED')),
	constraint claim_images_claim_fk foreign key (claim_id) references claims on delete cascade,
	constraint claim_images_correspondence_fk foreign key (correspondence_id) references claim_correspondence on delete set null
);

create table claim_intake_state (
	id bigint not null,
	deciding_run_id varchar(255),
	intake_conversation_id varchar(255),
	policy_number_generated boolean not null,
	review_opened_at timestamp(6) with time zone,
	review_run_id varchar(255),
	version bigint not null,
	constraint claim_intake_state_pkey primary key (id),
	constraint claim_intake_state_claim_fk foreign key (id) references claims on delete cascade
);

create table claim_requested_items (
	claim_id bigint not null,
	item varchar(255) not null,
	constraint claim_requested_items_pkey primary key (claim_id, item),
	constraint claim_requested_items_item_check check (item in ('INCIDENT_DESCRIPTION', 'INCIDENT_DATE', 'LOCATION', 'CATEGORY')),
	constraint claim_requested_items_state_fk foreign key (claim_id) references claim_intake_state on delete cascade
);

create index claim_correspondence_claim_id_idx on claim_correspondence (claim_id);
create index claim_images_claim_id_idx on claim_images (claim_id);
create index claim_images_correspondence_id_idx on claim_images (correspondence_id);
