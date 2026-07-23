package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class K4D2RoutingEventClosureContractTest {

    @Test
    void a4MigrationRegistersSubmittedK4FieldsAndEscalationEventIdempotently() throws Exception {
        String sql = Files.readString(Path.of(
                "scripts/migrations/20260722_k4_d2_routing_event_closure.sql"));

        assertThat(sql)
                .contains("'withdraw.submitted'")
                .contains("'k3_risk_route'", "'k4_priority'", "'k4_risk_score'", "'k4_model_version'", "'k4_as_of'")
                .contains("'risk.withdraw_escalated'")
                .contains("'withdrawal_id'", "'user_no'", "'risk_score'", "'priority'", "'notify_permission'", "'model_version'", "'score_as_of'")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("d2_routing_priority", "d2_k3_risk_route", "d2_k4_risk_score", "d2_k4_model_version")
                .contains("nx_k4_withdrawal_alert_receipt", "event_id,recipient_admin_id")
                .contains("'REGISTERED'");
    }

    @Test
    void producerUsesH1HoldAndCanonicalK4SnapshotAndEscalationKeys() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/application/AppWithdrawalService.java"));

        assertThat(service)
                .contains("(fastTrack || delayed) ? \"EXTENDED_HOLD\"")
                .contains("\"k3_risk_route\"", "\"k4_priority\"", "\"k4_risk_score\"", "\"k4_model_version\"", "\"k4_as_of\"")
                .contains("\"withdrawal_id\"", "\"user_no\"", "\"risk_score\"", "\"priority\"")
                .contains("\"notify_permission\"", "\"model_version\"", "\"score_as_of\"");
    }

    @Test
    void d2ConsumesCanonicalK3SnapshotAndDoesNotReimplementK3Rules() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/application/OpsFinanceService.java"));
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/mapper/WithdrawalOrderMapper.java"));

        assertThat(mapper).contains("d2_k3_risk_route AS k3RiskRoute");
        assertThat(service).doesNotContain(
                "withdrawRuleMatches", "withdrawVelocityRuleMatches", "recordBlockingWithdrawRuleHit", "hasBlockingRisk");
    }

    @Test
    void alertRecipientsFollowA6PermissionAndSuperAdminWithoutInventingLeadRoles() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/risk/mapper/K4WithdrawalAlertMapper.java"));
        String controller = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/risk/web/K4WithdrawalAlertController.java"));
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260722_k4_d2_routing_event_closure.sql"));

        assertThat(mapper)
                .contains("a.status=1", "a.is_deleted=0", "rr.is_deleted=0", "r.status=1", "r.is_deleted=0")
                .contains("rp.is_deleted=0", "p.status=1", "p.is_deleted=0")
                .contains("p.permission_code='risk_k4_user_override'")
                .contains("r.role_code='SUPER_ADMIN' OR p.id IS NOT NULL")
                .doesNotContain("RISK_LEAD");
        assertThat(controller)
                .contains("hasAuthority('risk_k4_user_override')")
                .contains("@superAdminAuthorization.isSuperAdmin(authentication)")
                .doesNotContain("RISK_LEAD");
        assertThat(migration)
                .contains("UNIQUE KEY uk_k4_withdraw_alert_event_recipient(event_id,recipient_admin_id)")
                .contains("'notify_permission','string'")
                .doesNotContain("RISK_LEAD");
    }
}
