CREATE TABLE revision_asset_refs (
    revision_id UUID NOT NULL REFERENCES plan_revisions(id),
    asset_id UUID NOT NULL REFERENCES assets(id),
    PRIMARY KEY(revision_id,asset_id)
);
CREATE TABLE revision_evidence_refs (
    revision_id UUID NOT NULL REFERENCES plan_revisions(id),
    evidence_id UUID NOT NULL REFERENCES evidence_segments(id),
    PRIMARY KEY(revision_id,evidence_id)
);
