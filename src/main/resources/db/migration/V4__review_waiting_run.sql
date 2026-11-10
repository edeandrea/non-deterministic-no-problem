-- The run that has reached its review wait, recorded by the run itself, so any application instance can tell a claim's review is ready for a decision
-- without reading the workflow engine's tables.

alter table claim_intake_state add column review_waiting_run_id varchar(255);