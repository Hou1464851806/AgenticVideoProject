INSERT INTO analysis_stages(asset_id,analysis_id,stage_key,status,task_id)
SELECT h.asset_id,h.analysis_id,'EMBEDDING',
 CASE WHEN g.id IS NULL THEN 'NOT_STARTED'
 WHEN NOT EXISTS(SELECT 1 FROM embedding_batches b WHERE b.generation_id=g.id AND b.status<>'SUCCEEDED') THEN 'SUCCEEDED'
 WHEN t.status IN ('FAILED','CANCELLED') THEN t.status ELSE 'PENDING' END,g.task_id
FROM asset_analysis_heads h
LEFT JOIN LATERAL(SELECT * FROM search_index_generations WHERE analysis_id=h.analysis_id ORDER BY created_at DESC LIMIT 1) g ON true
LEFT JOIN tasks t ON t.id=g.task_id
ON CONFLICT(analysis_id,stage_key) DO NOTHING;
