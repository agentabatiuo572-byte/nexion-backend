-- Run with the application's database user after restoring an isolated acceptance database.
SELECT nx_support_fund_schema_ready_v1() AS funds_ready,
       EXISTS(SELECT 1 FROM nx_support_rules WHERE id=1) AS rules_ready;
