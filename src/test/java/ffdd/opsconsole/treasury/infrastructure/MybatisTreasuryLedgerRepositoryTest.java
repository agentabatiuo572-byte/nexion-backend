package ffdd.opsconsole.treasury.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MybatisTreasuryLedgerRepositoryTest {

    @Test
    void withdrawalRefundRestoresReservedUsdtAndBurnedNexWithExactPostBalances() {
        TreasuryLedgerMapper mapper = mock(TreasuryLedgerMapper.class);
        MybatisTreasuryLedgerRepository repository = new MybatisTreasuryLedgerRepository(mapper);
        when(mapper.actualUserBalance(7L, "USDT")).thenReturn(new BigDecimal("20.000000"));
        when(mapper.actualUserBalance(7L, "NEX")).thenReturn(new BigDecimal("5.000000"));
        when(mapper.releasePendingWithdrawalWithNex(
                7L, new BigDecimal("100.000000"), new BigDecimal("50.000000"))).thenReturn(1);
        when(mapper.insertLedgerEntry(
                "D2-REFUND-WD-1", 7L, "WITHDRAW_REFUND", "USDT", "IN",
                new BigDecimal("100.000000"), new BigDecimal("120.000000"), "SUCCESS", "rejected"))
                .thenReturn(1);
        when(mapper.insertLedgerEntry(
                "D2-NEX-REFUND-WD-1", 7L, "WITHDRAW_FEE_OFFSET_REFUND", "NEX", "IN",
                new BigDecimal("50.000000"), new BigDecimal("55.000000"), "SUCCESS", "rejected"))
                .thenReturn(1);

        repository.refundWithdrawal(
                "WD-1", 7L, new BigDecimal("100"), "USDT", new BigDecimal("50"), "rejected");

        verify(mapper).releasePendingWithdrawalWithNex(
                7L, new BigDecimal("100.000000"), new BigDecimal("50.000000"));
        verify(mapper).insertLedgerEntry(
                "D2-REFUND-WD-1", 7L, "WITHDRAW_REFUND", "USDT", "IN",
                new BigDecimal("100.000000"), new BigDecimal("120.000000"), "SUCCESS", "rejected");
        verify(mapper).insertLedgerEntry(
                "D2-NEX-REFUND-WD-1", 7L, "WITHDRAW_FEE_OFFSET_REFUND", "NEX", "IN",
                new BigDecimal("50.000000"), new BigDecimal("55.000000"), "SUCCESS", "rejected");
    }
}
