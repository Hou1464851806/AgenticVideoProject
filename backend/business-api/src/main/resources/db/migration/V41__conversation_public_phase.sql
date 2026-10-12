ALTER TABLE tasks ADD COLUMN public_phase VARCHAR(32)
    CHECK(public_phase IN ('SEARCHING','READING_EVIDENCE','MODELING','SELF_CHECKING','VALIDATING'));
CREATE INDEX public_events_retention ON public_events(occurred_at,project_id);
