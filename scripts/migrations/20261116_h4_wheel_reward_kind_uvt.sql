-- Normalize the legacy asset alias on retained wheel definitions only.
-- Keep names, identifiers, probabilities, amounts, status and spin history unchanged.
-- Preserve updated_at rather than making this code-only rename look like an operator edit.
UPDATE nx_growth_wheel_tier
   SET reward_kind='uvt',updated_at=updated_at
 WHERE is_deleted=0 AND LOWER(TRIM(reward_kind))='nex';
