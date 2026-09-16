package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper.Beneficiary;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper.Quote;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankWithdrawalEligibilityTest {
    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 0, 0);
    Beneficiary recipient(long owner, String no, long version) {
        return new Beneficiary(owner,no,"","****6789","cipher",NOW.plusHours(24),NOW.plusDays(7),version);
    }
    Quote quote() {
        return new Quote("BQ-fixture",71L,"BNK-fixture",1L,"","****6789","cipher",new BigDecimal("100"),
                BigDecimal.ONE,new BigDecimal("99"),new BigDecimal("25000"),new BigDecimal("2475000"),1L,"d5",NOW,NOW.plusMinutes(5),null);
    }
    @Test void existingBindingsAreImmediatelyEligibleWithoutExternalEvidence() {
        assertNull(BankWithdrawalEligibility.beneficiaryBlock(recipient(71,"BNK-fixture",0),71));
        assertNull(BankWithdrawalEligibility.quoteBlock(recipient(71,"BNK-fixture",1),quote()));
        assertEquals(true,BankWithdrawalEligibility.quoteEligibilityView(recipient(71,"BNK-fixture",1),quote()).get("canWithdraw"));
    }
    @Test void missingForeignAndChangedRecipientsStillCannotApproveOrDispatchAnOldQuote() {
        assertNotNull(BankWithdrawalEligibility.quoteBlock(null,quote()));
        assertNotNull(BankWithdrawalEligibility.quoteBlock(recipient(72,"BNK-fixture",1),quote()));
        assertNotNull(BankWithdrawalEligibility.quoteBlock(recipient(71,"BNK-other",1),quote()));
        assertNotNull(BankWithdrawalEligibility.quoteBlock(recipient(71,"BNK-fixture",2),quote()));
        assertNotNull(BankWithdrawalEligibility.quoteBlock(recipient(71,"BNK-fixture",1),null));
    }
}
