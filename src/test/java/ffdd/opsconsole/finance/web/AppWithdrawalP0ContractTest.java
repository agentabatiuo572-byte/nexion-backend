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
        assertThat(mapper).contains("COALESCE(u.sandbox,0)=0");
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
    }
}
