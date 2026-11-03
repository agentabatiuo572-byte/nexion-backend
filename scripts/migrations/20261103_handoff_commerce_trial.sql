-- NULL is unlimited on a new claim. Existing numeric snapshots, including zero, remain intact.
ALTER TABLE nx_trial_claim MODIFY offset_cap_usdt DECIMAL(18,6) NULL DEFAULT NULL;

-- Policy values are strings. A blank value denotes an unlimited cap and leaves discountCapUSD independent.
INSERT IGNORE INTO nx_growth_trial_policy
  (policy_key,policy_name,description,current_value,value_type,hot,section,server_only,sort_order,is_deleted)
VALUES ('trialOffsetCapUSD','试用收益抵扣上限','留空不设上限，按领取时设置计算','','NUMBER',0,'newonly',0,125,0);
UPDATE nx_growth_trial_policy
   SET current_value='', description='留空不设上限，按领取时设置计算', hot=0
 WHERE policy_key='trialOffsetCapUSD' AND current_value='50'
   AND COALESCE(description,'') <> '留空不设上限，按领取时设置计算'
   AND NOT EXISTS (SELECT 1 FROM nx_audit_log
                    WHERE action='H2_TRIAL_PARAM_CHANGED' AND resource_type='TRIAL_PARAM' AND resource_id='trialOffsetCapUSD');
UPDATE nx_growth_trial_policy SET description='留空不设上限，按领取时设置计算',hot=0,section='newonly'
 WHERE policy_key='trialOffsetCapUSD' AND (COALESCE(description,'') <> '留空不设上限，按领取时设置计算' OR hot<>0 OR section<>'newonly');
