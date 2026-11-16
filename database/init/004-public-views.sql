BEGIN;
CREATE VIEW api_verified_sources AS
SELECT id, jsonb_build_object('title',title,'url',official_url,'year',admission_year,'updated_at',updated_at) AS source
FROM document_revisions WHERE source_verified AND revoked_at IS NULL;
CREATE VIEW api_admission_cycles AS
SELECT c.*,v.id AS version_id,v.version_no,v.updated_at,s.source,ca.code AS campus_code
FROM admission_cycles c JOIN campuses ca ON ca.id=c.campus_id
JOIN admission_cycle_versions v ON v.cycle_id=c.id AND v.status='PUBLISHED'
JOIN api_verified_sources s ON s.id=v.source_revision_id;
CREATE VIEW api_curricula AS
SELECT v.*,s.source FROM curriculum_versions v JOIN api_verified_sources s ON s.id=v.source_revision_id WHERE v.status='PUBLISHED';
CREATE VIEW api_guides AS
SELECT i.*,v.id AS version_id,v.title,v.body,v.updated_at,s.source,ca.code AS campus_code
FROM guide_items i JOIN campuses ca ON ca.id=i.campus_id
JOIN guide_versions v ON v.guide_id=i.id AND v.status='PUBLISHED' JOIN api_verified_sources s ON s.id=v.source_revision_id;
CREATE VIEW api_finances AS
SELECT i.*,v.id AS version_id,v.title,v.amount_vnd,v.charge_unit,v.eligibility_text,v.due_at,v.body,v.updated_at,s.source,ca.code AS campus_code
FROM financial_policies i JOIN campuses ca ON ca.id=i.campus_id
JOIN financial_policy_versions v ON v.policy_id=i.id AND v.status='PUBLISHED' JOIN api_verified_sources s ON s.id=v.source_revision_id;
CREATE VIEW api_score_rules AS
SELECT i.*,v.id AS version_id,v.formula_key,v.formula_config,v.output_scale_code,v.updated_at,s.source,ca.code AS campus_code,m.code AS method_code
FROM score_rules i JOIN campuses ca ON ca.id=i.campus_id JOIN admission_methods m ON m.id=i.method_id
JOIN score_rule_versions v ON v.score_rule_id=i.id AND v.status='PUBLISHED' JOIN api_verified_sources s ON s.id=v.source_revision_id;
COMMIT;
