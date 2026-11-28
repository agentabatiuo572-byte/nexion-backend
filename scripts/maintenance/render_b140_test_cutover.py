#!/usr/bin/env python3
"""Render the B140 cutover for a restored local/TEST candidate database."""

from argparse import ArgumentParser
from pathlib import Path
from uuid import UUID


ROOT = Path(__file__).resolve().parents[1]
MARKER = "E.catalog.b140.numbered"
NUMBERS = {
    "cloud-share": "s-cs-001",
    "stellarbox-s1-mini": "e-s1-001",
    "stellarbox-s1": "e-s1-002",
    "stellarbox-s1-plus": "e-s1-003",
    "stellarbox-pro": "p-pro-001",
    "stellarbox-pro-v2": "p-pv2-001",
    "stellarrack-p1-lite": "f-rp1-001",
    "stellarrack-p1": "f-rp1-002",
    "stellarrack-p2": "f-rp2-001",
}


def require(condition, error):
    return (f"SELECT IF(({condition}), 'ok', '{error}') INTO @b140_check;\n"
            f"SELECT @b140_check AS precondition WHERE @b140_check <> 'ok';\n"
            f"INSERT INTO b140_checks VALUES (@b140_check);")


def replace_number(table, column, old, new, *, quoted=False, scope="1=1"):
    old_token, new_token = (f'"{old}"', f'"{new}"') if quoted else (old, new)
    if quoted:
        transform = f"REPLACE({column}, '{old_token}', '{new_token}')"
        match = f"INSTR({column}, '{old_token}') > 0"
    else:
        transform = f"'{new_token}'"
        match = f"{column} = '{old_token}'"
    return f"UPDATE {table} SET {column} = {transform} WHERE {match} AND {scope};"


def render(server_uuid, archived_history=False):
    original = (ROOT / "migrations/20261121_lc2_catalog.sql").read_text(encoding="utf-8")
    seed_rows, _ = original.split("START TRANSACTION;\n")
    _, seed_writes = original.split("-- E1 derives the points", 1)
    seed_writes, _ = seed_writes.split("\nCOMMIT;", 1)
    seed_writes = ("-- E1 derives the points" + seed_writes).replace(
        "product_no = 'stellarbox-s1'", "product_no = 'e-s1-002'"
    ).replace(
        "INSERT INTO nx_product (\n  product_no", "INSERT INTO nx_product (\n  id, product_no"
    ).replace(
        "SELECT product_no, name, IF(tier", "SELECT old.id, product_no, name, IF(tier", 1
    ).replace(
        "FROM lc2_catalog\nWHERE @lc2_empty_catalog", "FROM lc2_catalog LEFT JOIN b140_product_ids old USING (product_no)\nWHERE @lc2_empty_catalog", 1
    ).replace(
        "INSERT INTO nx_admin_device_sku (\n  sku_id", "INSERT INTO nx_admin_device_sku (\n  id, sku_id"
    ).replace(
        "SELECT c.product_no, c.name", "SELECT old.id, c.product_no, c.name", 1
    ).replace(
        "FROM lc2_catalog c JOIN nx_product p ON p.product_no = c.product_no\n",
        "FROM lc2_catalog c JOIN nx_product p ON p.product_no = c.product_no\n"
        "LEFT JOIN b140_sku_config old ON old.sku_id = c.product_no\n", 1
    )
    new_numbers = ",".join(f"'{number}'" for number in NUMBERS.values())
    pairs = ",\n".join(f"('{old}','{new}')" for old, new in NUMBERS.items())
    checks = (
        ("NOT EXISTS (SELECT 1 FROM nx_compute_v2_allocation) AND NOT EXISTS (SELECT 1 FROM nx_compute_v2_checkpoint) "
         "AND NOT EXISTS (SELECT 1 FROM nx_compute_v2_event) AND NOT EXISTS (SELECT 1 FROM nx_compute_v2_settlement)",
         "immutable Compute V2 activity needs a reset boundary"),
        ("NOT EXISTS (SELECT 1 FROM nx_purchase_payment_intent) AND NOT EXISTS (SELECT 1 FROM nx_purchase_payment_arrival)",
         "external purchase payment evidence needs a reconciliation boundary"),
        ("NOT EXISTS (SELECT 1 FROM nx_deposit_order) AND NOT EXISTS (SELECT 1 FROM nx_withdrawal_order) "
         "AND NOT EXISTS (SELECT 1 FROM nx_cregis_deposit_delivery) AND NOT EXISTS (SELECT 1 FROM nx_cregis_deposit_event) "
         "AND NOT EXISTS (SELECT 1 FROM nx_hdpay_payin_order) AND NOT EXISTS (SELECT 1 FROM nx_withdrawal_payout_ledger) "
         "AND NOT EXISTS (SELECT 1 FROM nx_user_wallet WHERE pending_withdraw<>0 OR cregis_risk_held<>0)",
         "external wallet history needs a reconciliation boundary"),
        ("NOT EXISTS (SELECT 1 FROM nx_support_fund_operation o WHERE o.source_table IN "
         "('nx_earnings_release_entry','nx_earnings_source_recovery','nx_order','nx_order_item','nx_user_device',"
         "'nx_trial_claim','nx_promotion_reward','nx_wallet_ledger','nx_wallet_bill','nx_compute_receipt',"
         "'nx_commission_event','nx_direct_referral_settlement','nx_unilevel_event_recovery') AND NOT EXISTS ("
         "SELECT 1 FROM nx_b140_test_wallet_reset r JOIN nx_support_fund_operation cleared "
         "ON cleared.op_key=CONCAT('B140:WALLET_RESET:',r.entry_no) "
         "AND cleared.business_type='SUPPORT_B140_TEST_WALLET_RESET' AND cleared.phase='RESET' "
         "AND cleared.source_table='nx_b140_test_wallet_reset' AND cleared.source_key=r.entry_no "
         "WHERE r.original_operation_id=o.id AND r.original_receipt_sha256=o.receipt_sha256 "
         "AND o.business_type='SUPPORT_EARNINGS' AND o.phase='CREDIT' "
         "AND o.source_table='nx_earnings_release_entry' AND o.source_key=r.entry_no)) AND "
         "NOT EXISTS (SELECT 1 FROM nx_user_wallet w JOIN nx_support_fund_operation_party p ON p.customer_id=w.user_id "
         "WHERE w.usdt_available<>0 OR w.nex_available<>0 OR w.pending_withdraw<>0 OR w.cregis_risk_held<>0)",
         "verified Support wallet history needs an auditable reset"),
    )
    statements = [
        "SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;",
        "SET SESSION time_zone = '+08:00';",
        "CREATE TEMPORARY TABLE b140_checks (result VARCHAR(120), CHECK (result = 'ok'));",
        require(f"DATABASE() = 'nexion' AND @@server_uuid = '{server_uuid}'", "wrong database or server"),
        require(f"NOT EXISTS (SELECT 1 FROM nx_config_item WHERE config_key='{MARKER}')", "B140 already applied"),
        "CREATE TEMPORARY TABLE b140_numbers (old_no VARCHAR(64) PRIMARY KEY, new_no VARCHAR(64) UNIQUE) "
        "DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_0900_ai_ci;",
        f"INSERT INTO b140_numbers VALUES\n{pairs};",
        require("NOT EXISTS (SELECT 1 FROM b140_numbers n LEFT JOIN nx_product p ON p.product_no=n.old_no "
                "LEFT JOIN nx_admin_device_sku s ON s.sku_id=n.old_no WHERE (p.id IS NULL) <> (s.id IS NULL))",
                "legacy product and SKU presence differs"),
        require(f"NOT EXISTS (SELECT 1 FROM nx_product WHERE product_no IN ({new_numbers})) AND "
                f"NOT EXISTS (SELECT 1 FROM nx_admin_device_sku WHERE sku_id IN ({new_numbers}))", "number collision"),
        require("(SELECT COUNT(*) FROM nx_admin_phase_config WHERE scope='E1' AND label='P1' "
                "AND status='active' AND is_deleted=0)=1", "missing or ambiguous E1 P1"),
        require("NOT EXISTS (SELECT 1 FROM nx_audit_operation_ticket WHERE status='pending' AND is_deleted=0 "
                "AND JSON_VALID(command_json) AND JSON_UNQUOTE(JSON_EXTRACT(command_json,'$.op')) "
                "IN ('e1_sku_create','e1_phase_create'))", "settle pending E1 creation before cutover"),
        *(require(condition, error) for condition, error in (checks[:1] if archived_history else checks)),
        seed_rows,
        require(f"(SELECT COUNT(*)={len(NUMBERS)} AND COUNT(n.old_no)={len(NUMBERS)} "
                "FROM lc2_catalog c LEFT JOIN b140_numbers n ON BINARY c.product_no=BINARY n.old_no)",
                "canonical catalog seed set differs"),
        "UPDATE lc2_catalog c JOIN b140_numbers n ON n.old_no=c.product_no SET c.product_no=n.new_no;",
        "START TRANSACTION;",
        "CREATE TEMPORARY TABLE b140_product_ids AS SELECT p.id, n.new_no product_no, "
        "p.superseded_by_product_no FROM nx_product p JOIN b140_numbers n ON n.old_no=p.product_no;",
        *((
            "DELETE FROM nx_purchase_payment_arrival;",
            "DELETE FROM nx_purchase_payment_result;",
            "DELETE FROM nx_purchase_payment_intent;",
            "DELETE FROM nx_wallet_asset_adjustment;",
            "UPDATE nx_event_outbox SET is_deleted=1 WHERE is_deleted=0 "
            "AND event_type IN ('admin.balance_adjusted','admin.bill_adjusted');",
        ) if archived_history else ()),
        (ROOT / "maintenance/20261128_b140_test_business_reset.sql").read_text(encoding="utf-8-sig"),
        "CREATE TEMPORARY TABLE b140_sku_config AS SELECT s.id, n.new_no sku_id, s.superseded_by, "
        "s.purchase_gate_json, s.purchase_gate_generation FROM nx_admin_device_sku s "
        "JOIN b140_numbers n ON n.old_no=s.sku_id;",
        "CREATE TEMPORARY TABLE b140_task_policy LIKE nx_sim_product_task_policy_version;",
        "INSERT INTO b140_task_policy SELECT p.* FROM nx_sim_product_task_policy_version p "
        "JOIN b140_numbers n ON n.old_no COLLATE utf8mb4_unicode_ci=p.product_key;",
        "UPDATE b140_task_policy p JOIN b140_numbers n ON n.old_no COLLATE utf8mb4_unicode_ci=p.product_key "
        "SET p.product_key=n.new_no;",
        "DELETE FROM nx_product_faq WHERE product_id NOT IN (SELECT id FROM b140_product_ids);",
        "DELETE FROM nx_product_spec WHERE product_id NOT IN (SELECT id FROM b140_product_ids);",
        "DELETE FROM nx_admin_device_sku;",
        "DELETE FROM nx_product;",
        "DELETE FROM nx_sim_product_task_policy_version;",
        "SET @lc2_catalog_at=NOW(6), @lc2_empty_catalog=1;",
        "SET @lc2_p1=(SELECT id FROM nx_admin_phase_config WHERE scope='E1' AND label='P1' AND status='active' AND is_deleted=0);",
        seed_writes,
        "DELETE p FROM nx_sim_product_task_policy_version p JOIN b140_task_policy old ON old.product_key=p.product_key;",
        "INSERT INTO nx_sim_product_task_policy_version SELECT * FROM b140_task_policy;",
        "UPDATE nx_product p JOIN b140_product_ids old ON old.id=p.id "
        "SET p.superseded_by_product_no=old.superseded_by_product_no;",
        "UPDATE nx_admin_device_sku s JOIN b140_sku_config old ON old.sku_id=s.sku_id "
        "SET s.superseded_by=old.superseded_by, s.purchase_gate_json=old.purchase_gate_json, "
        "s.purchase_gate_generation=old.purchase_gate_generation;",
        "UPDATE nx_growth_trial_policy SET current_value='e-s1-002' "
        "WHERE policy_key='trialProductId' AND current_value='device-trial-standard';",
    ]
    for old, new in NUMBERS.items():
        for table, column in (
            ("nx_onboarding_yield_comparison_config", "product_no"),
            ("nx_team_hardware_quota_tier", "product_no"),
            ("nx_admin_device_generation_gate", "sku_id"),
            ("nx_v_rank_reward_rule", "sku_id"),
            ("nx_growth_promo_banner", "target_device"),
            ("nx_product", "superseded_by_product_no"),
            ("nx_admin_device_sku", "superseded_by"),
            ("nx_tradein_rule", "source_product_no"),
        ):
            statements.append(replace_number(table, column, old, new))
        statements.append(replace_number("nx_growth_trial_policy", "current_value", old, new, scope="policy_key='trialProductId'"))
        for table, column in (
            ("nx_trial_purchase_options", "product_nos"),
            ("nx_trial_purchase_options", "pricing_json"),
            ("nx_growth_voucher", "applicable_skus"),
            ("nx_growth_event_rule", "rules_json"),
            ("nx_genesis_raffle_draw", "prizes_json"),
        ):
            statements.append(replace_number(table, column, old, new, quoted=True))
        for table, column in (("nx_mission", "action_route"), ("nx_event_quest", "cta_href"),
                              ("nx_growth_day_one_instance_item", "action_route"), ("nx_growth_weekly_definition_item", "action_route")):
            statements.append(replace_number(table, column, f"/pages/store/detail?id={old}", f"/pages/store/detail?id={new}"))
        statements.append(f"UPDATE nx_config_item SET config_key=CONCAT('E.gen.{new}.', "
                          f"SUBSTRING(config_key,{len('E.gen.' + old + '.') + 1})) WHERE config_key LIKE 'E.gen.{old}.%';")
        statements.append(replace_number("nx_config_item", "config_value", old, new, quoted=True,
                                         scope="config_group IN ('E','F','H') AND JSON_VALID(config_value)"))

    statements.extend((
        "INSERT INTO nx_e1_sku_number_sequence (prefix,last_number) VALUES "
        "('s-cs',1),('e-s1',3),('p-pro',1),('p-pv2',1),('f-rp1',2),('f-rp2',1) AS seeded "
        "ON DUPLICATE KEY UPDATE last_number=GREATEST(nx_e1_sku_number_sequence.last_number,seeded.last_number);",
        require(f"(SELECT COUNT(*) FROM nx_product WHERE product_no IN ({new_numbers}) AND is_deleted=0 AND store_visible=0) = 9 "
                "AND (SELECT COUNT(*) FROM nx_product) = 9 AND (SELECT COUNT(*) FROM nx_admin_device_sku WHERE status='off' "
                f"AND sku_id IN ({new_numbers}) AND is_deleted=0) = 9 AND (SELECT COUNT(*) FROM nx_admin_device_sku) = 9", "numbered catalog verification failed"),
        require("NOT EXISTS (SELECT 1 FROM nx_order) AND NOT EXISTS (SELECT 1 FROM nx_user_device) "
                "AND NOT EXISTS (SELECT 1 FROM nx_trial_claim) AND NOT EXISTS (SELECT 1 FROM nx_growth_voucher_grant) "
                "AND NOT EXISTS (SELECT 1 FROM nx_promotion) AND NOT EXISTS (SELECT 1 FROM nx_team_hardware_quota_usage) "
                "AND NOT EXISTS (SELECT 1 FROM nx_wallet_ledger) AND NOT EXISTS (SELECT 1 FROM nx_wallet_bill) "
                "AND NOT EXISTS (SELECT 1 FROM nx_user_wallet WHERE usdt_available<>0 OR nex_available<>0 "
                "OR pending_withdraw<>0 OR cregis_risk_held<>0 OR lifetime_earned<>0 OR cumulative_deposit_usdt<>0)",
                "test business reset verification failed"),
        f"INSERT INTO nx_config_item (config_key,config_value,value_type,config_group,visibility,status,created_at,updated_at,is_deleted) "
        f"VALUES ('{MARKER}','true','BOOLEAN','E','INTERNAL',1,NOW(6),NOW(6),0);",
        "COMMIT;",
        "SELECT 'B140_COMMITTED' AS receipt, @@server_uuid AS server_uuid;",
    ))
    return "\n".join(statements) + "\n"


if __name__ == "__main__":
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("expected_server_uuid", type=UUID)
    parser.add_argument("output", type=Path)
    parser.add_argument("--archived-history", action="store_true",
                        help="clear a TEST candidate with verified original payment and Support history archived")
    args = parser.parse_args()
    args.output.write_text(render(args.expected_server_uuid, args.archived_history), encoding="utf-8")
