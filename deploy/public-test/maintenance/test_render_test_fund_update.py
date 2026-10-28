"""Offline contract tests. Real MySQL behavior is verified in the isolated clone."""
import copy
import re
import unittest
from unittest.mock import patch

import render_test_fund_update as m


class TestFundUpdateMaintenance(unittest.TestCase):
    def test_fixed_test_target_is_the_first_executable_guard(self):
        sql = m.render('fund-update')
        self.assertIn("BINARY @@server_uuid=BINARY '" + m.TEST_UUID + "'", sql)
        self.assertIn("BINARY DATABASE()=BINARY 'nexion'", sql)
        self.assertLess(sql.index('TEST_FUND_TARGET_MISMATCH'), sql.index('DROP TRIGGER'))
        self.assertNotIn('@@hostname', sql)
        self.assertNotIn('CREATE USER', sql)

    def test_rehearsal_requires_a_distinct_explicit_clone_uuid(self):
        clone = '11111111-1111-4111-8111-111111111111'
        for supplied, rehearsal in [(clone, False), (None, True), (m.TEST_UUID, True), ('bad', True)]:
            with self.subTest(supplied=supplied, rehearsal=rehearsal), self.assertRaises(ValueError):
                m.render('fund-update', supplied, rehearsal)
        sql = m.render('fund-update', clone, True)
        self.assertIn("BINARY @@server_uuid=BINARY '" + clone + "'", sql)
        self.assertNotIn("BINARY @@server_uuid=BINARY '" + m.TEST_UUID + "'", sql)

    def test_only_three_target_update_triggers_are_removed(self):
        sql = m.fund_sql()
        self.assertEqual(re.findall(r'DROP TRIGGER IF EXISTS `(\w+)`;', sql), list(m.UPDATE_TRIGGERS))
        self.assertNotIn('CREATE TRIGGER', sql)
        self.assertNotRegex(sql, r'(?m)^(?:UPDATE|DELETE|INSERT|TRUNCATE) ')
        self.assertNotIn('SET GLOBAL', sql)

    def test_function_requires_update_absence_and_original_delete_guards(self):
        create = m.updated_create()
        for name in m.UPDATE_TRIGGERS:
            self.assertIn(m.absent_trigger(name), create)
        for name in ('nx_support_fund_operation_bd_v1', 'nx_support_fund_leg_bd_v1', 'nx_support_fund_party_bd_v1'):
            self.assertIn("TRIGGER_NAME='" + name + "'", create)
        self.assertIn("AND EVENT_OBJECT_TABLE IN ('nx_support_fund_operation','nx_support_fund_operation_leg'))=2)", create)
        self.assertIn("AND EVENT_OBJECT_TABLE='nx_support_fund_operation_party')=1", create)
        self.assertIn("BINARY 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE''')=1)", create)

    def test_h4_is_verified_in_full_with_the_fifth_wallet_trigger(self):
        create = m.updated_create()
        h4 = next(t for t in m.SOURCE['triggers'] if t['name'] == 'nx_wallet_h4_holding_observation')
        self.assertIn(m.trigger_matches(h4), create)
        self.assertIn("AND EVENT_OBJECT_TABLE='nx_user_wallet')=5", create)
        self.assertIn("'nx_wallet_h4_holding_observation'))=5)", create)
        self.assertIn('AND ACTION_ORDER=2)=1)', create)
        for trigger in m.SOURCE['triggers']:
            if trigger['table'] == 'nx_user_wallet' and trigger['name'] != h4['name']:
                self.assertIn('BINARY ' + m.quoted(trigger['statement']), create)

    def test_every_unrelated_original_guard_clause_is_byte_identical(self):
        old = m.SOURCE['routineCreate']
        new = m.updated_create()
        changed = []
        for clause in old.split(m.CONJUNCTION):
            if clause in new:
                continue
            changed.append(clause)
            self.assertTrue(any(marker in clause for marker in [
                "TRIGGER_NAME='nx_support_fund_operation_bu_v1'", "TRIGGER_NAME='nx_support_fund_leg_bu_v1'",
                "EVENT_OBJECT_TABLE IN ('nx_support_fund_operation','nx_support_fund_operation_leg')",
                "EVENT_OBJECT_TABLE='nx_support_fund_operation_party')=2",
                "EVENT_OBJECT_TABLE='nx_user_wallet')=4",
            ]), clause[:250])
        self.assertEqual(len(changed), 5)
        self.assertIn("AND EVENT_OBJECT_TABLE='nx_user_wallet' AND ACTION_TIMING='BEFORE' AND EVENT_MANIPULATION='UPDATE')=2", new)

    def test_partial_ddl_resume_still_verifies_each_surviving_update_trigger(self):
        preflight = m.preflight_condition(m.updated_create())
        for name in m.UPDATE_TRIGGERS:
            trigger = next(t for t in m.SOURCE['triggers'] if t['name'] == name)
            self.assertIn('(' + m.absent_trigger(name) + ' OR ' + m.trigger_matches(trigger) + ')', preflight)
        self.assertIn('TEST_FUND_SCHEMA_NOT_READY', m.fund_sql())
        self.assertNotIn(' OR 1', preflight)

    def test_original_show_create_matches_the_recorded_mysql_definition(self):
        self.assertEqual(m.metadata_body(m.SOURCE['routineCreate']), m.SOURCE['routineDefinition'])
        broken = copy.deepcopy(m.SOURCE)
        broken['routineCreate'] = broken['routineCreate'].replace("TABLE_NAME='nx_user_wallet'", "TABLE_NAME='other'", 1)
        with patch.object(m, 'SOURCE', broken), self.assertRaisesRegex(ValueError, 'DISAGREE'):
            m.updated_create()

    def test_definer_characteristics_and_original_execute_grants_are_retained(self):
        create = m.updated_create()
        self.assertEqual(create[:create.index('RETURN (')], m.SOURCE['routineCreate'][:m.SOURCE['routineCreate'].index('RETURN (')])
        sql = m.fund_sql()
        grants = re.findall(r'(?m)^GRANT .*;', sql)
        self.assertEqual(len(grants), 2)
        self.assertTrue(all(x.startswith('GRANT EXECUTE ON FUNCTION `nexion`.') for x in grants))
        self.assertIn("TO 'nexion_app'@'%';", sql)
        self.assertIn("TO 'nexion_support_schema'@'localhost';", sql)

    def test_four_asset_checks_have_two_forward_phases_and_no_other_change(self):
        self.assertEqual(len(m.SOURCE['assetChecks']), 4)
        for check in m.SOURCE['assetChecks']:
            old = check['clause'].replace("\\'", "'")
            expanded = m.check_clause(check, 'asset-expand')
            final = m.check_clause(check, 'asset-finalize')
            self.assertEqual(expanded.replace("_utf8mb4'NEX',_utf8mb4'UVT'", "_utf8mb4'NEX'"), old)
            self.assertEqual(final.replace("_utf8mb4'UVT'", "_utf8mb4'NEX'"), old)
        self.assertEqual(m.asset_sql('asset-expand').count('ALTER TABLE '), 4)
        self.assertEqual(m.asset_sql('asset-finalize').count('TEST_ASSET_NEX_ROWS_REMAIN'), 4)
        reservation = next(x for x in m.SOURCE['assetChecks'] if x['name'] == 'chk_promotion_reservation_asset')
        self.assertIn('(`amount` = floor(`amount`))', m.check_clause(reservation, 'asset-finalize'))
        self.assertIn("(`product_no` <> _utf8mb4'')", m.check_clause(reservation, 'asset-finalize'))

    def test_diagnosis_is_read_only_and_preserves_top_level_predicates(self):
        sql = m.render('diagnose')
        self.assertIn("'ORIGINAL' AS contract", sql)
        self.assertIn("'MAINTENANCE_PREFLIGHT' AS contract", sql)
        self.assertNotIn('DROP ', sql)
        self.assertNotIn('ALTER TABLE', sql)
        self.assertGreater(sql.count(' AS clause_no,'), 40)


if __name__ == '__main__':
    unittest.main()
