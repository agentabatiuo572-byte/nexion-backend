package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.auth.application.UserOtpDeliveryService;
import ffdd.opsconsole.finance.hdpay.*;
import ffdd.opsconsole.finance.mapper.*;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import org.springframework.mock.env.MockEnvironment;
import java.time.*;
import java.math.BigDecimal;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankWithdrawalServiceTest {
    final BankWithdrawalMapper bank=mock(BankWithdrawalMapper.class);
    final AppWithdrawalMapper wallet=mock(AppWithdrawalMapper.class);
    final AppWithdrawalService withdrawals=mock(AppWithdrawalService.class);
    final AppPayoutAddressMapper addresses=mock(AppPayoutAddressMapper.class);
    final AdminIdempotencyService idem=mock(AdminIdempotencyService.class);
    final MockEnvironment env=new MockEnvironment();
    final LocalDateTime now=LocalDateTime.of(2026,9,15,0,0);
    final String qn="BQ-"+"a".repeat(32);
    final BankWithdrawalService service=new BankWithdrawalService(bank,wallet,addresses,mock(UserOtpDeliveryService.class),
            mock(PayoutAddressOtpAttemptService.class),mock(FinanceSensitiveDataCipher.class),withdrawals,
            mock(PayoutVndConfigService.class),mock(HdPayProperties.class),mock(HdPayPayoutProperties.class),idem,
            mock(AuditLogService.class),env,Clock.fixed(now.toInstant(ZoneOffset.UTC),ZoneOffset.UTC));
    BankWithdrawalMapper.Quote quote(long owner) {
        return new BankWithdrawalMapper.Quote(qn,owner,"BNK-fixture",1L,"VCB","***6789","cipher",new BigDecimal("100"),
                BigDecimal.ONE,new BigDecimal("99"),new BigDecimal("25000"),new BigDecimal("2475000"),1L,"d5-v1",now,now.plusMinutes(5),null);
    }
    @BeforeEach void setup() {
        env.setActiveProfiles("dev"); when(wallet.findActiveUser(71L)).thenReturn(71L); when(wallet.lockActiveUser(71L)).thenReturn(71L);
        when(idem.executeRetained(anyString(),anyString(),anyString(),any(),any())).thenAnswer(i->((Supplier<?>)i.getArgument(4)).get());
    }
    @Test void quoteRecoveryCannotExposeAnotherUsersRecipientOrAmounts() {
        when(bank.quote(qn)).thenReturn(quote(72));
        assertThrows(RuntimeException.class,()->service.recoverQuote(71,qn));
        verifyNoInteractions(withdrawals);
    }
    @Test void cancellationWritesDurableFenceAndLateSubmitCannotReserve() {
        when(bank.lockQuote(qn,71L)).thenReturn(quote(71)); when(bank.cancelQuote(qn)).thenReturn(1);
        assertEquals("ABANDONED",service.abandonQuote(71,qn).getData().get("state"));
        verify(bank).cancelQuote(qn);
        when(bank.cancelled(qn)).thenReturn(1);
        assertThrows(RuntimeException.class,()->service.submit(71,qn,"fixture-late-submit"));
        verifyNoInteractions(withdrawals);
    }
    @Test void expiredQuoteCannotReserveOrCallProvider() {
        var old=quote(71);
        when(bank.lockQuote(qn,71L)).thenReturn(new BankWithdrawalMapper.Quote(qn,71L,old.beneficiaryNo(),1L,"VCB","***6789","cipher",
                old.amountUsdt(),old.feeUsdt(),old.netUsdt(),old.rateVnd(),old.amountVnd(),1L,"d5-v1",now.minusMinutes(6),now.minusMinutes(1),null));
        assertThrows(RuntimeException.class,()->service.submit(71,qn,"fixture-expired"));
        verifyNoInteractions(withdrawals);
    }
    @Test void wrongOtpPurposeOrInvalidAccountNeverUsesOtpOrWritesRecipient() {
        assertThrows(RuntimeException.class,()->service.bind(71,new BankWithdrawalService.BindRequest("VCB","0123456789","NGUYEN VAN A",
                "PAYOUT-"+"a".repeat(32),"123456"),"fixture-bind"));
        assertThrows(RuntimeException.class,()->service.bind(71,new BankWithdrawalService.BindRequest("VCB","invalid","NGUYEN VAN A",
                "PAYOUT-BANK-"+"a".repeat(32),"123456"),"fixture-bind"));
        verifyNoInteractions(addresses,bank);
    }
    @Test void sandboxAccountsAreRejectedBeforeQuoteReads() {
        when(wallet.isSandboxUser(71L)).thenReturn(1);
        assertThrows(RuntimeException.class,()->service.recoverQuote(71,qn)); verifyNoInteractions(bank);
    }
}
