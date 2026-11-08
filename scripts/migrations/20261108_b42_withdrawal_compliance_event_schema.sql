-- AppWithdrawalService always emits these submission snapshots, including
-- false/false/0 for BANK-VND. Keep the active A4 revision and all other fields.
-- A deployment already repaired through A4's two-admin approval is a no-op.
INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'compliance_review_required' property_name,'boolean' property_type UNION ALL
    SELECT 'compliance_hold_applies','boolean' UNION ALL
    SELECT 'compliance_hold_days','number'
  ) p
 WHERE s.event_name='withdraw.submitted' AND s.status='ACTIVE' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;
