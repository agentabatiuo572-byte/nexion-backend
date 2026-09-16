package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper.Verification;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankWithdrawalEligibilityTest {
    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 0, 0);
    static Verification trusted(long version, String type, String ownership, String capability, LocalDateTime expires) {
        // Trusted provider evidence is deliberately test-only; production has no configured verification provider.
        return new Verification("BNK-fixture", 71L, version, "verified", capability, ownership, type,
                null, NOW.minusMinutes(1), expires, "fixture-evidence", "fixture-capability-v1", "fixture-provider", NOW);
    }
    @Test void onlyCurrentCompleteUnexpiredEvidenceCanAuthorizeAPaymentAccount() {
        assertNull(BankWithdrawalEligibility.block(trusted(1, "payment_account", "matched", "supported", NOW.plusHours(1)), "BNK-fixture", 71, 1, NOW));
        assertNotNull(BankWithdrawalEligibility.block(null, "BNK-fixture", 71, 1, NOW));
        assertNotNull(BankWithdrawalEligibility.block(trusted(0, "payment_account", "matched", "supported", NOW.plusHours(1)), "BNK-fixture", 71, 1, NOW));
        assertNotNull(BankWithdrawalEligibility.block(trusted(1, "payment_account", "matched", "supported", NOW), "BNK-fixture", 71, 1, NOW));
        for (String type : new String[]{"credit_card", "prepaid", "unknown"})
            assertNotNull(BankWithdrawalEligibility.block(trusted(1, type, "matched", "supported", NOW.plusHours(1)), "BNK-fixture", 71, 1, NOW));
        for (String ownership : new String[]{"mismatched", "unknown"})
            assertNotNull(BankWithdrawalEligibility.block(trusted(1, "payment_account", ownership, "supported", NOW.plusHours(1)), "BNK-fixture", 71, 1, NOW));
        for (String capability : new String[]{"unsupported", "unknown"})
            assertNotNull(BankWithdrawalEligibility.block(trusted(1, "payment_account", "matched", capability, NOW.plusHours(1)), "BNK-fixture", 71, 1, NOW));
    }
    @Test void providerMissingCannotBeEnabledByAnAdministrativeFlag() {
        assertFalse(BankWithdrawalEligibility.capabilityReady(BankWithdrawalEligibility.capabilitySummary()));
        assertEquals("unavailable", BankWithdrawalEligibility.capabilitySummary().get("status"));
        assertNull(BankWithdrawalEligibility.capabilitySummary().get("provider"));
    }
}
