-- Forward release receipt: verify the registration snapshot schema before startup.
-- SELECT ... LIMIT 0 resolves table/column names without reading or changing users.
-- Any missing table or required column fails the non-force migration client.
SELECT id, user_id FROM nx_growth_day_one_instance LIMIT 0;
SELECT id, instance_id FROM nx_growth_day_one_instance_item LIMIT 0;
SELECT id, instance_id FROM nx_growth_day_one_instance_binding LIMIT 0;
