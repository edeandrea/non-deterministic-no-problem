-- A signal for an intake run, written by whichever application instance handles the request and delivered by the application instance that hosts the run.

create sequence intake_run_signal_seq start with 1 increment by 50;

create table intake_run_signal (
	id bigint not null,
	claim_id bigint not null,
	attempts integer not null,
	created_at timestamp(6) with time zone not null,
	kind varchar(255) not null,
	payload text not null,
	run_id varchar(255) not null,
	constraint intake_run_signal_pkey primary key (id),
	constraint intake_run_signal_kind_check check (kind in ('REVIEW_DECIDED')),
	constraint intake_run_signal_claim_fk foreign key (claim_id) references claims on delete cascade
);

create index intake_run_signal_run_id_idx on intake_run_signal (run_id);
