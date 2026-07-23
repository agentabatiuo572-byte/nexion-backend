package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class K3A4EventClosureContractTest {

    @Test
    void withdrawalHoldEventIsGovernedBeforeItsProducerPublishes() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/finance/application/AppWithdrawalService.java"),
                StandardCharsets.UTF_8);
        String migration = Files.readString(
                Path.of("scripts/migrations/20260722_k3_withdraw_routing_closure.sql"),
                StandardCharsets.UTF_8);

        assertThat(service)
                .contains("risk.withdraw_held")
                .contains("withdrawalRiskRuleFacade.evaluate")
                .contains("riskKycReviewFacade.triggerLargeWithdrawalReview");
        assertThat(migration)
                .contains("INSERT INTO nx_event_schema_registry")
                .contains("INSERT INTO nx_event_schema_property")
                .contains("INSERT INTO nx_event_domain_extension")
                .contains("'risk.withdraw_held'")
                .contains("'rule_id'", "'action'", "'withdrawal_id'", "'amount_usdt'", "'dimension'")
                .contains("'withdraw.submitted'")
                .contains("'risk_route'", "'risk_rule_id'", "'k5_ticket_id'")
                .contains("is_server_authoritative=1");
    }

    @Test
    void currentBuiltInRolesReceiveTheK3PermissionMatrixWithoutLegacyLeadRoles() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260722_k3_withdraw_routing_closure.sql"),
                StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("r.role_code IN ('FINANCE','AUDITOR')")
                .contains("p.permission_code='risk_k3_read'")
                .contains("r.role_code='RISK'")
                .contains("'risk_k3_write','risk_k3_rule_create','risk_k3_rule_toggle','risk_k3_rule_archive'")
                .contains("r.role_code='SUPER_ADMIN'")
                .contains("INSERT IGNORE INTO nx_admin_role_menu")
                .contains("m.menu_code='K3'")
                .doesNotContain("r.role_code='RISK_LEAD'");
    }
}
