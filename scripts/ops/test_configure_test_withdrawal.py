import copy
import unittest
from configure_test_withdrawal import ORDER, VALUES, plan


def fixture():
    config={**VALUES,"growth.phase.month.3.withdrawCooldownDays":"30","growth.phase.withdraw_cooldown_days":"30",
            "withdrawal.daily_count_limit":"2","wallet.withdrawal.daily_count_limit":"2",
            "withdrawal.d5.version":"3","H1.rhythm.currentMonth":"3","growth.phase.current_month":"3"}
    del config["withdrawal.k4_time_validation_enabled"]
    return {"config":[{"key":key,"value":value,"status":1,"deleted":0} for key,value in sorted(config.items())],
            "orders":[{"order":ORDER,"status":"REVIEW_PENDING","attempts":0,"hold":"2026-10-16 18:52:55",
                       "created":"2026-09-16 18:52:55","due":"2026-10-17 18:52:55","version":0,"owner":None,"previous":None}],
            "payout":[{"order":ORDER,"state":"READY","provider":None,"risk":None}]}


class PlanTest(unittest.TestCase):
    def test_expected_changes_and_replay(self):
        state=fixture()
        before=copy.deepcopy(state)
        result=plan(state)
        self.assertEqual(result["changes"],VALUES)
        self.assertEqual(result["new_d5_version"],"4")
        self.assertTrue(result["adjust_pending_order"])
        self.assertEqual(state,before)
        for row in state["config"]:
            if row["key"] in VALUES:
                row["value"]=VALUES[row["key"]]
        state["config"].append({"key":"withdrawal.k4_time_validation_enabled","value":"false","status":1,"deleted":0})
        state["orders"][0]["hold"]=state["orders"][0]["created"]
        self.assertTrue(plan(state)["already_applied"])

    def test_never_changes_orders_that_can_dispatch_or_were_already_sent(self):
        for key,value in [("status","REVIEW_PASSED"),("attempts",1),("owner","H1_ZERO_DAY_AUTO_REVIEW")]:
            state=fixture()
            state["orders"][0][key]=value
            with self.assertRaises(ValueError): plan(state)
        state=fixture()
        state["payout"][0]["provider"]="already-sent"
        with self.assertRaises(ValueError): plan(state)

    def test_source_changes_require_a_new_digest(self):
        state=fixture()
        before=plan(state)["source_sha256"]
        state["orders"][0]["version"]+=1
        self.assertNotEqual(plan(state)["source_sha256"],before)


if __name__=="__main__": unittest.main()
