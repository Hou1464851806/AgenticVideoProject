ALTER TABLE storage_reservations ADD COLUMN future_bytes bigint NOT NULL DEFAULT 0 CHECK (future_bytes >= 0);
-- Existing uploads retain conservative headroom until preparation/analysis reaches a terminal state.
UPDATE storage_reservations SET future_bytes=bytes*2+314572800 WHERE state <> 'RELEASED';

ALTER TABLE tasks ADD COLUMN disk_budget_bytes bigint NOT NULL DEFAULT 0 CHECK (disk_budget_bytes >= 0);
UPDATE tasks SET disk_budget_bytes =
    coalesce((SELECT sum((s->>'sizeBytes')::bigint) FROM jsonb_array_elements(input_json->'sources') s),0)
    + 67108864 + (input_json->'output'->>'durationMs')::bigint *
      ((input_json->'configuration'->>'width')::bigint * (input_json->'configuration'->>'height')::bigint * 90 / 1000
       + 384 + (input_json->'configuration'->>'videoMaxRate')::bigint / 4000)
WHERE type IN ('PREVIEW_RENDER','EXPORT_RENDER');
