-- Which inbound email an outbound reply answers, and whether an inbound email's reply was merged into its claim: what a new leader reads to tell how far
-- a dead run got with an email left in the processing folder.

alter table claim_correspondence add column in_reply_to varchar(255);
alter table claim_correspondence add column merged_into_claim boolean not null default false;
create index claim_correspondence_in_reply_to_idx on claim_correspondence (in_reply_to);
