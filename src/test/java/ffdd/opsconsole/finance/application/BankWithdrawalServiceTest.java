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
import java.util.Map;
import java.util.Set;
import java.util.List;
import ffdd.opsconsole.shared.api.ApiResult;
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
    final HdPayPayoutProperties payout = new HdPayPayoutProperties();
    final PayoutVndConfigService d7 = mock(PayoutVndConfigService.class);
    final PayoutAddressOtpAttemptService otp = mock(PayoutAddressOtpAttemptService.class);
    final UserOtpDeliveryService delivery = mock(UserOtpDeliveryService.class);
    final FinanceSensitiveDataCipher cipher = mock(FinanceSensitiveDataCipher.class);
    final BankWithdrawalService service=new BankWithdrawalService(bank,wallet,addresses,delivery,
            otp,cipher,withdrawals,
            d7,mock(HdPayProperties.class),payout,idem,
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
    @Test void invalidRecipientNeverWritesOrUsesOtp() {
        assertThrows(RuntimeException.class,()->service.bind(71,new BankWithdrawalService.BindRequest("","invalid","NGUYEN VAN A",null,null),"fixture-bind"));
        assertThrows(RuntimeException.class,()->service.bind(71,new BankWithdrawalService.BindRequest("","0123456789","A",null,null),"fixture-bind"));
        assertThrows(RuntimeException.class,()->service.bind(71,new BankWithdrawalService.BindRequest(null,"0123456789","NGUYEN VAN A",null,null),"fixture-bind"));
        verifyNoInteractions(addresses,bank);
    }
    @Test void sandboxAccountsAreRejectedBeforeQuoteReads() {
        when(wallet.isSandboxUser(71L)).thenReturn(1);
        assertThrows(RuntimeException.class,()->service.recoverQuote(71,qn)); verifyNoInteractions(bank);
    }
    @Test void bankQrConfigExplicitlyRequiresNeitherBankSelectionNorOtpAndDoesNotEnablePayout() {
        when(d7.overview()).thenReturn(ApiResult.ok(Map.of()));
        payout.setBankCodes(Set.of("VCB", "ACB", "UNKNOWN"));
        var config = service.config(71).getData();
        assertEquals(List.of(), config.get("banks"));
        assertEquals(false, config.get("bankCodeRequired"));
        assertEquals(false, config.get("bindingOtpRequired"));
        assertEquals("BANKQR", config.get("payType"));
        assertEquals(false, config.get("enabled"));
        payout.setBankCodes(Set.of()); assertEquals(List.of(), service.config(71).getData().get("banks"));
        payout.setBankCodes(null); assertEquals(List.of(), service.config(71).getData().get("banks"));
    }
    @Test void newNonemptyBankCodeIsRejectedWithoutWritingOrConsumingOtp() {
        when(d7.overview()).thenReturn(ApiResult.ok(Map.of()));
        payout.setBankCodes(Set.of("VCB")); service.config(71); clearInvocations(bank);
        payout.setBankCodes(Set.of("ACB"));
        var error = assertThrows(RuntimeException.class, () -> service.bind(71, binding(), "bank-fixture"));
        assertEquals("BANK_CODE_MUST_BE_EMPTY", error.getMessage());
        verifyNoInteractions(bank, addresses, otp, withdrawals);
    }
    @Test void successfulLegacyIdempotencyReplaySurvivesBankQrUpgrade() {
        var receipt = ApiResult.ok(Map.<String, Object>of("beneficiary", Map.of("bankCode", "VCB", "maskedAccount", "****6789")));
        doReturn(receipt).when(idem).executeRetained(anyString(), anyString(), anyString(), any(), any());
        payout.setBankCodes(Set.of());
        assertSame(receipt, service.bind(71, binding(), "bank-already-succeeded"));
        verifyNoInteractions(bank, addresses, otp, withdrawals);
    }
    @Test void emptyBankCodeBindsWithoutAnyOtpAndKeepsLeadingZerosAndCoolingPeriods() {
        when(cipher.encrypt(anyString(), anyString())).thenReturn("encrypted-fixture");
        when(bank.saveBeneficiary(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), any(), anyLong(), any())).thenReturn(1);
        when(bank.beneficiary(71L)).thenReturn(new BankWithdrawalMapper.Beneficiary(71L,"BNK-fixture","","****6789","encrypted-fixture",now.plusHours(24),now.plusDays(7),0L));
        var result = service.bind(71, new BankWithdrawalService.BindRequest("", "00123456789", "NGUYEN VAN A", null, null), "fixture-no-otp");
        assertEquals(0, result.getCode());
        assertEquals("BANKQR", ((Map<?, ?>)result.getData().get("beneficiary")).get("bankName"));
        verify(cipher).encrypt(eq("00123456789\nNGUYEN VAN A"), startsWith("BANK-BENEFICIARY:71:"));
        verify(bank).saveBeneficiary(eq(71L), anyString(), eq(""), eq("****6789"), eq("encrypted-fixture"), eq(now.plusHours(24)), eq(now.plusDays(7)), eq(0L), eq(now));
        verifyNoInteractions(otp, delivery, withdrawals);
    }
    @Test void removedOtpEndpointDoesNotSendSms() {
        var error = assertThrows(RuntimeException.class, () -> service.sendOtp(71));
        assertEquals("BANK_BINDING_OTP_NOT_REQUIRED", error.getMessage());
        verifyNoInteractions(otp, delivery, addresses, bank);
    }
    @Test void bindingStillRequiresAnActiveNonSandboxOwner() {
        when(wallet.findActiveUser(72L)).thenReturn(null);
        for (long owner : new long[]{0,72}) assertThrows(RuntimeException.class, () -> service.bind(owner, emptyBinding(), "fixture-other"));
        when(wallet.isSandboxUser(71L)).thenReturn(1);
        assertThrows(RuntimeException.class, () -> service.bind(71, emptyBinding(), "fixture-sandbox"));
        verifyNoInteractions(bank, cipher, otp, delivery);
    }
    @Test void cooldownAndUnsettledWithdrawalsStillBlockRecipientReplacement() {
        when(bank.lockBeneficiary(71L)).thenReturn(new BankWithdrawalMapper.Beneficiary(71L,"BNK-fixture","VCB","****6789","encrypted-fixture",now.minusHours(1),now.plusDays(6),0L));
        assertEquals("BANK_CHANGE_COOLDOWN", assertThrows(RuntimeException.class, () -> service.bind(71, emptyBinding(), "fixture-cooldown")).getMessage());
        when(addresses.unsettledWithdrawalCount(71L)).thenReturn(1);
        assertEquals("BANK_WITHDRAWAL_IN_FLIGHT", assertThrows(RuntimeException.class, () -> service.bind(71, emptyBinding(), "fixture-inflight")).getMessage());
        verifyNoInteractions(cipher, otp, delivery, withdrawals);
        verify(bank, never()).saveBeneficiary(anyLong(),anyString(),anyString(),anyString(),anyString(),any(),any(),anyLong(),any());
    }
    private BankWithdrawalService.BindRequest emptyBinding() { return new BankWithdrawalService.BindRequest("", "00123456789", "NGUYEN VAN A", null, null); }
    private BankWithdrawalService.BindRequest binding() {
        return new BankWithdrawalService.BindRequest("VCB", "00123456789", "NGUYEN VAN A", "PAYOUT-BANK-" + "a".repeat(32), "123456");
    }
}
