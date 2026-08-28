package ffdd.opsconsole.finance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppWithdrawalP0ContractTest {
    private static String read(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java").resolve(relative));
    }

    @Test
    void appHasUserScopedDetailRead() throws Exception {
        String controller = read("ffdd/opsconsole/finance/web/AppWithdrawalController.java");
        String service = read("ffdd/opsconsole/finance/application/AppWithdrawalService.java");
        String mapper = read("ffdd/opsconsole/finance/mapper/AppWithdrawalMapper.java");
        assertThat(controller).contains("@GetMapping(\"/{withdrawalNo}\")");
        assertThat(controller).contains("service.get(userId, withdrawalNo)");
        assertThat(controller).contains("USER_AUTH_REQUIRED");
        assertThat(service).contains("public ApiResult<Map<String, Object>> get(Long userId, String withdrawalNo)");
        assertThat(service).contains("WITHDRAWAL_NOT_FOUND");
        assertThat(service).contains("idempotency.execute");
        assertThat(mapper).contains("userWithdrawal");
        assertThat(mapper).contains("w.user_id=#{userId}");
        assertThat(mapper).contains("JOIN nx_user u ON u.id=w.user_id AND u.status='ACTIVE' AND u.is_deleted=0");
        assertThat(mapper).doesNotContain("COALESCE(u.sandbox,0)=0");
    }

    @Test
    void projectionCarriesTerminalAndRefundFacts() throws Exception {
        String mapper = read("ffdd/opsconsole/finance/mapper/AppWithdrawalMapper.java");
        assertThat(mapper).contains("terminalReason");
        assertThat(mapper).contains("retriable");
        assertThat(mapper).contains("nexRefunded");
        assertThat(mapper).contains("nexRefundedAt");
        String payout = read("ffdd/opsconsole/finance/application/AppPayoutAddressService.java");
        assertThat(payout).contains("PAYOUT_ADDRESS_SANDBOX_USER_FORBIDDEN");
    }

    @Test
    void smallAmountGateIsPartOfFastTrackAndSchemaHasDurableFacts() throws Exception {
        String service = read("ffdd/opsconsole/finance/application/AppWithdrawalService.java");
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        assertThat(service).contains("boolean fastTrack = smallAmountEligible &&");
        assertThat(schema).contains("terminal_reason");
        assertThat(schema).contains("retriable");
        assertThat(schema).contains("nex_refunded");
        assertThat(schema).contains("nex_refunded_at");
        String migration = Files.readString(
                Path.of("scripts/migrations/20260823_withdrawal_terminal_refund_projection.sql"));
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        assertThat(migration).contains("terminal_reason", "retriable", "nex_refunded", "nex_refunded_at");
        assertThat(startup).contains("20260823_withdrawal_terminal_refund_projection.sql");
    }

    @Test
    void ambiguousAttemptCanOnlyBeAbandonedThroughTheServerFence() throws Exception {
        String controller = read("ffdd/opsconsole/finance/web/AppWithdrawalController.java");
        String service = read("ffdd/opsconsole/finance/application/AppWithdrawalService.java");
        String mapper = read("ffdd/opsconsole/finance/mapper/AppWithdrawalMapper.java");
        String initializer = read("ffdd/opsconsole/finance/application/WithdrawalAttemptSchemaInitializer.java");
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String migration = Files.readString(Path.of("scripts/migrations/20260816_withdrawal_attempt_authority.sql"));
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(controller).contains("@PostMapping(\"/attempts/{idempotencyKey}/abandon\")");
        assertThat(controller).contains("service.abandonAttempt(userId, idempotencyKey");
        assertThat(service).contains("mapper.lockActiveUser(userId)");
        assertThat(service).contains("WITHDRAWAL_ATTEMPT_ABANDONED");
        assertThat(service).contains("WITHDRAWAL_ATTEMPT_READBACK_UNAVAILABLE");
        assertThat(mapper).contains("LIMIT 1 FOR UPDATE", "status='ABANDONED'", "status='COMMITTED'",
                "findWithdrawalNoByIdempotencyKey", "d2_idempotency_key",
                "withdrawal_no=#{withdrawalNo}", "information_schema.TABLE_CONSTRAINTS",
                "CONSTRAINT_TYPE='CHECK'")
                .doesNotContain("information_schema.CHECK_CONSTRAINTS");
        assertThat(schema).contains("nx_withdrawal_attempt_control", "uk_withdrawal_attempt_user_key",
                "chk_withdrawal_attempt_status", "d2_idempotency_key");
        assertThat(initializer).contains("withdrawalAttemptStatusCheckCount", "WITHDRAWAL_ATTEMPT_STATUS_CHECK_MISSING");
        assertThat(migration)
                .contains("PREPARE", "chk_withdrawal_attempt_status", "d2_idempotency_key",
                        "INFORMATION_SCHEMA.TABLE_CONSTRAINTS", "CONSTRAINT_TYPE='CHECK'")
                .doesNotContain("INFORMATION_SCHEMA.CHECK_CONSTRAINTS\n          WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME");
        assertThat(startup).contains("20260816_withdrawal_attempt_authority.sql");
    }
}
