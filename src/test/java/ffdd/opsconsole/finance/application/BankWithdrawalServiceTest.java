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
    final HdPayPayoutProperties payout = spy(new HdPayPayoutProperties());
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
        assertThrows(RuntimeException.class,()->service.submit(71,qn,"fixture-late-submit","203.0.113.7"));
        verifyNoInteractions(withdrawals);
    }
    @Test void expiredQuoteCannotReserveOrCallProvider() {
        var old=quote(71);
        when(bank.lockQuote(qn,71L)).thenReturn(new BankWithdrawalMapper.Quote(qn,71L,old.beneficiaryNo(),1L,"VCB","***6789","cipher",
                old.amountUsdt(),old.feeUsdt(),old.netUsdt(),old.rateVnd(),old.amountVnd(),1L,"d5-v1",now.minusMinutes(6),now.minusMinutes(1),null));
        assertEquals("BANK_QUOTE_EXPIRED", service.submit(71,qn,"fixture-expired","203.0.113.7").getMessage());
        verify(bank).sealExpiredQuotes(71, now);
        verifyNoInteractions(withdrawals);
    }
    @Test void submissionPersistsOriginalIpAndIdempotentReplayCannotOverwriteIt() {
        doReturn(true).when(payout).ready(any());
        when(d7.overview()).thenReturn(ApiResult.ok(Map.of("providerReady",true,"channelEnabled",false,"version",1L)));
        when(bank.lockQuote(qn,71L)).thenReturn(quote(71)); when(bank.activeQuotes(71L)).thenReturn(List.of(quote(71)));
        when(bank.lockBeneficiary(71L)).thenReturn(new BankWithdrawalMapper.Beneficiary(71L,"BNK-fixture","","****6789","cipher",now,now,1L));
        when(withdrawals.reserveBank(eq(71L),any(),anyString())).thenReturn(ApiResult.ok(Map.of("withdrawalNo","WD-fixture")));
        when(bank.useQuote(qn,"WD-fixture")).thenReturn(1);
        when(bank.insertOrder(eq("WD-fixture"),eq(qn),eq(71L),eq(now),anyString())).thenReturn(1);
        var tested = spy(service);
        doReturn(ApiResult.ok(Map.of("withdrawalNo","WD-fixture"))).when(tested).orderView(71L,"WD-fixture");
        assertThrows(HdPayGatewayException.class,()->tested.submit(71,qn,"same-key","invalid-ip"));
        verify(withdrawals,never()).reserveBank(anyLong(),any(),anyString());
        var receipt = new java.util.concurrent.atomic.AtomicReference<Object>();
        when(idem.executeRetained(eq("BANK_WITHDRAW:71"),eq("same-key"),eq(HdPayPayoutDigest.sha("71|"+qn)),any(),any()))
                .thenAnswer(i->{ if(receipt.get()==null) receipt.set(((Supplier<?>)i.getArgument(4)).get()); return receipt.get(); });
        assertEquals(0,tested.submit(71,qn,"same-key","203.0.113.7").getCode());
        assertEquals(0,tested.submit(71,qn,"same-key","2001:db8::9").getCode());
        verify(bank,times(1)).insertOrder("WD-fixture",qn,71L,now,"203.0.113.7");
        verify(bank,never()).insertOrder(anyString(),anyString(),anyLong(),any(),eq("2001:db8::9"));
        verify(withdrawals,times(1)).reserveBank(anyLong(),any(),anyString());
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

        var config = service.config(71).getData();
        assertEquals(List.of(), config.get("banks"));
        assertEquals(false, config.get("bankCodeRequired"));
        assertEquals(false, config.get("bindingOtpRequired"));
        assertEquals("BANKQR", config.get("payType"));
        assertEquals(false, config.get("enabled"));
        assertEquals(List.of(), service.config(71).getData().get("banks"));
        assertEquals(List.of(), service.config(71).getData().get("banks"));
    }
    @Test void configProjectsPcLimitsAndCapacityWithoutInventingFallbacks() {
        var values = Map.<String,Object>of("minAmountUsd",new BigDecimal("5"),"maxAmountUsd",new BigDecimal("80"),"version",3L);
        var capacity = Map.<String,Object>of("maxWithdrawableUsdt",new BigDecimal("40"),"dailyRemainingCount",1L);
        when(d7.overview()).thenReturn(ApiResult.ok(values));
        when(withdrawals.bankCapacity(71L)).thenReturn(capacity);
        var result = service.config(71).getData();
        assertEquals(values,result.get("policy")); assertEquals(capacity,result.get("capacity"));
        when(withdrawals.bankCapacity(71L)).thenThrow(new ffdd.opsconsole.shared.exception.BizException(503,"WITHDRAWAL_WALLET_UNAVAILABLE"));
        assertNull(service.config(71).getData().get("capacity"));
        verify(withdrawals,never()).reserveBank(anyLong(),any(),anyString());
    }
    @Test void proxiedCapacityFailureDoesNotRollbackConfigButDatabaseFailuresDo() throws Exception {
        var source = mock(javax.sql.DataSource.class);
        var connection = mock(java.sql.Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        var attributes = new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
        var capacityTarget = new AppWithdrawalService(wallet, mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class),
                null, idem, null, null, null, null, null, env, Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC), bank);
        var capacityProxy = new org.springframework.aop.framework.ProxyFactory(capacityTarget);
        capacityProxy.setProxyTargetClass(true);
        capacityProxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager, attributes));
        var target = new BankWithdrawalService(bank,wallet,addresses,delivery,otp,cipher,
                (AppWithdrawalService)capacityProxy.getProxy(),d7,mock(HdPayProperties.class),payout,idem,
                mock(AuditLogService.class),env,Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        var configProxy = new org.springframework.aop.framework.ProxyFactory(target);
        configProxy.setProxyTargetClass(true);
        configProxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager, attributes));
        var proxied = (BankWithdrawalService)configProxy.getProxy();
        when(d7.overview()).thenReturn(ApiResult.ok(Map.of()));
        // Missing wallet is a recoverable display failure. Original requests must remain readable.
        assertNull(proxied.config(71).getData().get("capacity"));
        verify(connection).commit(); verify(connection,never()).rollback();
        clearInvocations(connection);
        when(wallet.walletForEligibility(71L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->proxied.config(71));
        verify(connection).rollback(); verify(connection,never()).commit();
    }
    @Test void newNonemptyBankCodeIsRejectedWithoutWritingOrConsumingOtp() {
        when(d7.overview()).thenReturn(ApiResult.ok(Map.of()));
        service.config(71); clearInvocations(bank, withdrawals);

        var error = assertThrows(RuntimeException.class, () -> service.bind(71, binding(), "bank-fixture"));
        assertEquals("BANK_CODE_MUST_BE_EMPTY", error.getMessage());
        verifyNoInteractions(bank, addresses, otp, withdrawals);
    }
    @Test void successfulLegacyIdempotencyReplaySurvivesBankQrUpgrade() {
        var receipt = ApiResult.ok(Map.<String, Object>of("beneficiary", Map.of("bankCode", "VCB", "maskedAccount", "****6789")));
        doReturn(receipt).when(idem).executeRetained(anyString(), anyString(), anyString(), any(), any());

        assertSame(receipt, service.bind(71, binding(), "bank-already-succeeded"));
        verifyNoInteractions(bank, addresses, otp, withdrawals);
    }
    @Test void firstBindingIsImmediateWithoutOtpOrExternalVerificationAndKeepsLeadingZeros() {
        when(cipher.encrypt(anyString(), anyString())).thenReturn("encrypted-fixture");
        when(bank.saveBeneficiary(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), any(), anyLong(), any())).thenReturn(1);
        when(bank.beneficiary(71L)).thenReturn(new BankWithdrawalMapper.Beneficiary(71L,"BNK-fixture","","****6789","encrypted-fixture",now,now.plusDays(7),0L));
        var result = service.bind(71, new BankWithdrawalService.BindRequest("", "00123456789", "NGUYEN VAN A", null, null), "fixture-no-otp");
        assertEquals(0, result.getCode());
        assertEquals("BANKQR", ((Map<?, ?>)result.getData().get("beneficiary")).get("bankName"));
        verify(cipher).encrypt(eq("00123456789\nNGUYEN VAN A"), startsWith("BANK-BENEFICIARY:71:"));
        verify(bank).saveBeneficiary(eq(71L), anyString(), eq(""), eq("****6789"), eq("encrypted-fixture"), eq(now), eq(now), eq(0L), eq(now));
        verifyNoInteractions(otp, delivery, withdrawals);
    }
    @Test void firstBindingDoesNotSendSms() {
        var error = assertThrows(RuntimeException.class, () -> service.sendOtp(71));
        assertEquals("BANK_CHANGE_OTP_NOT_REQUIRED", error.getMessage());
        verifyNoInteractions(otp, delivery, addresses);
    }
    @Test void bindingStillRequiresAnActiveNonSandboxOwner() {
        when(wallet.findActiveUser(72L)).thenReturn(null);
        for (long owner : new long[]{0,72}) assertThrows(RuntimeException.class, () -> service.bind(owner, emptyBinding(), "fixture-other"));
        when(wallet.isSandboxUser(71L)).thenReturn(1);
        assertThrows(RuntimeException.class, () -> service.bind(71, emptyBinding(), "fixture-sandbox"));
        verifyNoInteractions(bank, cipher, otp, delivery);
    }
    @Test void replacementNeedsOtpAndUnsettledWithdrawalsStillBlockIt() {
        when(bank.lockBeneficiary(71L)).thenReturn(new BankWithdrawalMapper.Beneficiary(71L,"BNK-fixture","VCB","****6789","encrypted-fixture",now.minusHours(1),now.plusDays(6),0L));
        assertEquals("BANK_CHANGE_OTP_INVALID", assertThrows(RuntimeException.class, () -> service.bind(71, emptyBinding(), "fixture-cooldown")).getMessage());
        when(addresses.unsettledWithdrawalCount(71L)).thenReturn(1);
        assertEquals("BANK_WITHDRAWAL_IN_FLIGHT", assertThrows(RuntimeException.class, () -> service.bind(71, emptyBinding(), "fixture-inflight")).getMessage());
        verifyNoInteractions(otp, delivery, withdrawals);
        verify(bank, never()).saveBeneficiary(anyLong(),anyString(),anyString(),anyString(),anyString(),any(),any(),anyLong(),any());
    }
    @Test void accountDiscoveryRetainsAllLegacyIntentsWithChannelDisabled() {
        when(d7.overview()).thenReturn(ApiResult.ok(Map.of()));
        when(bank.activeQuotes(71)).thenReturn(List.of(quote(71), new BankWithdrawalMapper.Quote("BQ-"+"b".repeat(32),71L,
                "BNK-fixture",1L,"","****6789","cipher",BigDecimal.TEN,BigDecimal.ONE,new BigDecimal("9"),
                new BigDecimal("25000"),new BigDecimal("225000"),1L,"d5-v1",now,now.plusMinutes(5),null)));
        Map<?,?> intent = (Map<?,?>) service.config(71).getData().get("unresolvedIntent");
        assertEquals("MULTIPLE", intent.get("state")); assertNull(intent.get("quoteNo"));
        assertEquals(2, ((List<?>) intent.get("intents")).size());
        clearInvocations(withdrawals);
        assertEquals("BANK_WITHDRAWAL_UNRESOLVED_INTENT", assertThrows(RuntimeException.class, () -> service.quote(71, BigDecimal.TEN)).getMessage());
        verifyNoInteractions(withdrawals);
    }
    @Test void retiredVerificationEndpointNeverWritesOrClaimsVerificationAndLegacyBindingIsReady() {
        when(d7.overview()).thenReturn(ApiResult.ok(Map.of()));
        var recipient = new BankWithdrawalMapper.Beneficiary(71L,"BNK-fixture","","****6789","cipher",now.plusHours(24),now.plusDays(7),0L);
        when(bank.beneficiary(71L)).thenReturn(recipient);
        assertEquals("BANK_BENEFICIARY_VERIFICATION_NOT_REQUIRED", assertThrows(RuntimeException.class, () -> service.verifyBeneficiary(71)).getMessage());
        var config = service.config(71).getData();
        assertEquals(0, config.get("bindingDelayHours"));
        assertEquals(true, ((Map<?,?>)config.get("beneficiary")).get("canWithdraw"));
        assertFalse(((Map<?,?>)config.get("beneficiary")).containsKey("verificationStatus"));
        verify(bank, never()).saveVerification(any());
        verify(bank, never()).verification(anyString());
    }
    @Test void replacementWithinSevenDaysTakesEffectImmediatelyWithOtp() {
        when(bank.lockBeneficiary(71L)).thenReturn(new BankWithdrawalMapper.Beneficiary(71L,"BNK-old","","****6789","cipher",now.minusHours(1),now.plusDays(7),0L));
        when(cipher.encrypt(anyString(),anyString())).thenReturn("encrypted-fixture");
        when(bank.saveBeneficiary(anyLong(),anyString(),anyString(),anyString(),anyString(),any(),any(),anyLong(),any())).thenReturn(1);
        when(otp.verifyAndConsume(71L, bankChallenge(), "123456")).thenReturn(true);
        assertEquals(0,service.bind(71,changeBinding(),"replacement-immediate").getCode());
        verify(otp).verifyAndConsume(71L,bankChallenge(),"123456");
        verify(bank).saveBeneficiary(eq(71L),anyString(),eq(""),eq("****6789"),eq("encrypted-fixture"),eq(now),eq(now),eq(1L),eq(now));
        verify(bank,never()).saveVerification(any());
    }

    @Test void terminalLabelWithoutSettlementEvidenceStaysDiscoverable() {
        var order = new BankWithdrawalMapper.Order("WD-fixture",qn,71L,"FAILED",123L,5,null);
        when(bank.unresolvedOrders(71)).thenReturn(List.of(order)); when(bank.quote(qn)).thenReturn(quote(71));
        assertEquals("COMMITTED", service.recovery(71).getData().get("state"));
        assertEquals("unconfirmed", BankWithdrawalService.settlementView(order,null).get("status"));
        when(bank.unresolvedOrders(71)).thenReturn(List.of());
        assertNull(service.recovery(71).getData());
        verify(bank,never()).settlementEvidence(anyString());
    }
    @Test void quoteAndSubmitReachWalletWithoutVerificationButStillRejectChangedRecipient() {
        var pricing = new java.util.HashMap<String,Object>(Map.of("channelEnabled",true,"providerReady",true,
                "version",1L,"quoteTtlMinWithdraw",5,"minAmountUsd",20,"maxAmountUsd",5000,"feeRatePct",1,"feeMinUsd",1,"feeMaxUsd",25));
        pricing.put("baseRateVndPerUsdt",25000); pricing.put("sellSpreadPct",0);
        doReturn(true).when(payout).ready(any()); when(d7.overview()).thenReturn(ApiResult.ok(pricing));
        var b = new BankWithdrawalMapper.Beneficiary(71L,"BNK-fixture","","****6789","cipher",now.plusHours(24),now.plusDays(7),1L);
        when(bank.lockBeneficiary(71L)).thenReturn(b);
        when(withdrawals.policy(71L)).thenReturn(ApiResult.ok(Map.of("withdrawalEnabled",true,"policyVersion","d5-v1")));
        when(cipher.decrypt(anyString(),anyString())).thenReturn("0123456789\nNGUYEN VAN A");
        when(cipher.encrypt(anyString(),anyString())).thenReturn("snapshot"); when(bank.insertQuote(any())).thenReturn(1);
        assertEquals(0,service.quote(71,new BigDecimal("100")).getCode());
        when(bank.lockQuote(qn,71)).thenReturn(quote(71)); when(bank.activeQuotes(71)).thenReturn(List.of(quote(71)));
        when(withdrawals.reserveBank(anyLong(),any(),anyString())).thenReturn(ApiResult.fail(409,"FIXTURE_WALLET_BLOCKED"));
        assertEquals("FIXTURE_WALLET_BLOCKED",assertThrows(RuntimeException.class,()->service.submit(71,qn,"without-verification","203.0.113.7")).getMessage());
        verify(withdrawals).reserveBank(eq(71L),any(),anyString());
        verify(bank,never()).verification(anyString());
        clearInvocations(withdrawals);
        when(bank.lockBeneficiary(71L)).thenReturn(new BankWithdrawalMapper.Beneficiary(71L,"BNK-new","","****4321","cipher",now,now.plusDays(7),2L));
        assertEquals("BANK_BENEFICIARY_CHANGED",assertThrows(RuntimeException.class,()->service.submit(71,qn,"changed-recipient","203.0.113.7")).getMessage());
        verify(withdrawals,never()).reserveBank(anyLong(),any(),anyString());
    }

    @Test void changeOtpIsRequiredAndCannotUseAnotherPurpose() {
        when(bank.lockBeneficiary(71L)).thenReturn(new BankWithdrawalMapper.Beneficiary(71L,"BNK-old","","****6789","cipher",now,now,0L));
        for (String challenge : new String[]{null,"PAYOUT-"+"a".repeat(32),"REGISTER-"+"a".repeat(32),"PAYOUT-BANK-short"}) {
            var request = new BankWithdrawalService.BindRequest("","00123456789","NGUYEN VAN A",challenge,"123456");
            assertEquals("BANK_CHANGE_OTP_INVALID",assertThrows(RuntimeException.class,()->service.bind(71,request,"wrong-purpose")).getMessage());
        }
        verifyNoInteractions(otp);
        assertEquals("BANK_CHANGE_OTP_INVALID",assertThrows(RuntimeException.class,()->service.bind(71,changeBinding(),"wrong-code")).getMessage());
        verify(otp).verifyAndConsume(71L,bankChallenge(),"123456");
        verify(bank,never()).saveBeneficiary(anyLong(),anyString(),anyString(),anyString(),anyString(),any(),any(),anyLong(),any());
    }
    @Test void changeSmsUsesOnlyRegisteredPhoneAndSharedRateLimits() {
        when(bank.lockBeneficiary(71L)).thenReturn(new BankWithdrawalMapper.Beneficiary(71L,"BNK-old","","****6789","cipher",now,now,0L));
        when(addresses.userContact(71L)).thenReturn(new AppPayoutAddressMapper.UserContact("+84","912345678"));
        when(delivery.available("+84")).thenReturn(true); when(delivery.verificationCode("+84")).thenReturn("123456");
        when(addresses.insertOtp(eq(71L),startsWith("PAYOUT-BANK-"),eq("123456"))).thenReturn(1);
        var result=service.sendOtp(71).getData();
        assertEquals(300,result.get("expiresInSeconds")); assertEquals(60,result.get("retryAfterSeconds"));
        assertFalse(result.containsKey("code")); assertFalse(result.containsKey("phone"));
        verify(delivery).deliver(eq("+84"),eq("912345678"),eq(result.get("challengeNo").toString()),eq("123456"),eq(5));
        clearInvocations(delivery,addresses);
        when(addresses.recentOtpCount(71L)).thenReturn(1);
        assertEquals("BANK_CHANGE_OTP_COOLDOWN",assertThrows(RuntimeException.class,()->service.sendOtp(71)).getMessage());
        when(addresses.recentOtpCount(71L)).thenReturn(0); when(addresses.todayOtpCount(71L)).thenReturn(10);
        assertEquals("BANK_CHANGE_OTP_DAILY_LIMIT",assertThrows(RuntimeException.class,()->service.sendOtp(71)).getMessage());
        when(delivery.available("+84")).thenReturn(false);
        assertEquals("BANK_CHANGE_OTP_UNAVAILABLE",assertThrows(RuntimeException.class,()->service.sendOtp(71)).getMessage());
        verify(delivery,never()).deliver(anyString(),anyString(),anyString(),anyString(),anyInt());
        when(addresses.unsettledWithdrawalCount(71L)).thenReturn(1);
        assertEquals("BANK_WITHDRAWAL_IN_FLIGHT",assertThrows(RuntimeException.class,()->service.sendOtp(71)).getMessage());
    }
    private String bankChallenge() { return "PAYOUT-BANK-"+"a".repeat(32); }
    private BankWithdrawalService.BindRequest changeBinding() { return new BankWithdrawalService.BindRequest("","00123456789","NGUYEN VAN A",bankChallenge(),"123456"); }
    private BankWithdrawalService.BindRequest emptyBinding() { return new BankWithdrawalService.BindRequest("", "00123456789", "NGUYEN VAN A", null, null); }
    private BankWithdrawalService.BindRequest binding() {
        return new BankWithdrawalService.BindRequest("VCB", "00123456789", "NGUYEN VAN A", "PAYOUT-BANK-" + "a".repeat(32), "123456");
    }
}
