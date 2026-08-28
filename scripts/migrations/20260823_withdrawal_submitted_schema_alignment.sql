-- Keep A4's withdraw.submitted registry identical to AppWithdrawalService's
-- current server-authoritative payload.  The prior D5 migration registered
-- obsolete network rate/min/max fields and predated the policy, SLA and review
-- fields now emitted atomically with the withdrawal order.

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('withdraw.submitted','withdraw','monetization','AppWithdrawalService','A4/H1/D2/D4',
   1,'100%',309,'ACTIVE','migration:withdrawal-submitted-schema-alignment',
   'Exact current server-authoritative withdrawal policy, fee, risk and SLA snapshot',0)
ON DUPLICATE KEY UPDATE
  owner_domain='withdraw',family_key='monetization',producer='AppWithdrawalService',
  consumers='A4/H1/D2/D4',is_server_authoritative=1,sampling_policy='100%',
  current_revision=309,status='ACTIVE',
  updated_by='migration:withdrawal-submitted-schema-alignment',
  reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='withdraw.submitted'
   AND p.property_name NOT IN (
     'withdrawal_id','amount_usdt','chain','network_confirm_usd','network_fee',
     'policy_version','use_nex_fee_offset','penalty_fee_rate','penalty_fee','gross_fee',
     'nex_burned','fee_waived','penalty_fee_waived','network_fee_waived',
     'actual_penalty_fee','actual_network_fee','actual_fee','net_receive',
     'cooldown_days','hold_until','small_amount_auto_review','small_amount_threshold_usd',
     'payout_sla_hours','payout_due_at','risk_route','k3_risk_route','strong_review',
     'strong_review_threshold_usdt','risk_rule_id','k4_priority','k4_risk_score',
     'k4_model_version','k4_as_of'
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,309,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'withdrawal_id' property_name,'id' property_type,1 required_field UNION ALL
    SELECT 'amount_usdt','number',1 UNION ALL
    SELECT 'chain','enum',1 UNION ALL
    SELECT 'network_confirm_usd','number',1 UNION ALL
    SELECT 'network_fee','number',1 UNION ALL
    SELECT 'policy_version','string',1 UNION ALL
    SELECT 'use_nex_fee_offset','boolean',1 UNION ALL
    SELECT 'penalty_fee_rate','number',1 UNION ALL
    SELECT 'penalty_fee','number',1 UNION ALL
    SELECT 'gross_fee','number',1 UNION ALL
    SELECT 'nex_burned','number',1 UNION ALL
    SELECT 'fee_waived','number',1 UNION ALL
    SELECT 'penalty_fee_waived','number',1 UNION ALL
    SELECT 'network_fee_waived','number',1 UNION ALL
    SELECT 'actual_penalty_fee','number',1 UNION ALL
    SELECT 'actual_network_fee','number',1 UNION ALL
    SELECT 'actual_fee','number',1 UNION ALL
    SELECT 'net_receive','number',1 UNION ALL
    SELECT 'cooldown_days','number',1 UNION ALL
    SELECT 'hold_until','timestamp',1 UNION ALL
    SELECT 'small_amount_auto_review','boolean',1 UNION ALL
    SELECT 'small_amount_threshold_usd','number',1 UNION ALL
    SELECT 'payout_sla_hours','number',1 UNION ALL
    SELECT 'payout_due_at','timestamp',1 UNION ALL
    SELECT 'risk_route','enum',1 UNION ALL
    SELECT 'k3_risk_route','enum',1 UNION ALL
    SELECT 'strong_review','boolean',1 UNION ALL
    SELECT 'strong_review_threshold_usdt','number',1 UNION ALL
    SELECT 'risk_rule_id','id',0 UNION ALL
    SELECT 'k4_priority','enum',1 UNION ALL
    SELECT 'k4_risk_score','number',1 UNION ALL
    SELECT 'k4_model_version','string',1 UNION ALL
    SELECT 'k4_as_of','timestamp',1
  ) p
 WHERE s.event_name='withdraw.submitted'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=309,is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,309)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,309);
