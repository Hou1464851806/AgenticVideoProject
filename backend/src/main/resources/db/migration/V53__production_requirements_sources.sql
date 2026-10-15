-- Original messages and declared defaults are client-owned provenance, separate from validated conditions.
ALTER TABLE plan_revisions ADD COLUMN production_requirements_sources_json JSONB;
