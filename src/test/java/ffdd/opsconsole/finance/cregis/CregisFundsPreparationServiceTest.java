package ffdd.opsconsole.finance.cregis;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.mapper.CregisFundsPreparationMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CregisFundsPreparationServiceTest {
    final CregisProperties properties = new CregisProperties();
    final CregisFundsPreparationMapper db = mock(CregisFundsPreparationMapper.class);
    final CregisExceptionAdminGuard admins = mock(CregisExceptionAdminGuard.class);
    final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final CregisGatewayRouter router = mock(CregisGatewayRouter.class);
    final BscDepositProof chain = mock(BscDepositProof.class);
    final CregisPayoutControl payout = mock(CregisPayoutControl.class);
    final CregisGateway vendor = mock(CregisGateway.class);
    final CregisFundsPreparationService service = new CregisFundsPreparationService(properties,db,admins,idempotency,audit,new ObjectMapper(),router,chain,payout);
    final String source = "0x"+"a".repeat(40), no = "CF-12345678-1234-1234-1234-123456789abc";
    final String reason = "核对当前金额与业务来源后准备";
    final Map<String,Object> config = Map.of("platformAddress",CregisFundsPreparationService.PLATFORM,
            "counterpartyAddress",CregisFundsPreparationService.COUNTERPARTY,"network","BSC","asset","USDT");
    @BeforeEach void setup() {
        properties.setProjectId(42);
        when(admins.require(true)).thenReturn(7L);
        when(db.config(42)).thenReturn(config);
        when(db.sourceExists(42,source)).thenReturn(1);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("7","unused",
                List.of(new SimpleGrantedAuthority("finance_d2_withdrawal_approve"))));
        when(idempotency.executeRetained(anyString(),anyString(),anyString(),eq(Map.class),any())).thenAnswer(call -> ((Supplier<?>)call.getArgument(4)).get());
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void fixedConfigurationHasNoExecutionCapability() {
        when(db.sources(42)).thenReturn(List.of(Map.of("address",source,"userId","9")));
        assertThat(service.status()).containsEntry("executionEnabled",false).containsEntry("network","BSC")
                .containsEntry("asset","USDT").containsEntry("mode","PREPARATION_ONLY");
        verifyNoInteractions(router,chain,vendor,payout);
    }
    @Test void arbitraryDestinationOrOtherProjectConfigurationCannotBeUsed() {
        when(db.config(42)).thenReturn(Map.of("platformAddress",source,"counterpartyAddress",source,"network","BSC","asset","USDT"));
        assertThatThrownBy(service::status).hasMessage("CREGIS_PREPARATION_CONFIGURATION_REQUIRED");
    }
    @Test void amountRejectsLossyAndInvalidForms() {
        for (String input : List.of("0","-1","1e2","1.0000001","1000000000000","01"," 1","NaN"))
            assertThatThrownBy(() -> CregisFundsPreparationService.amount(input)).isInstanceOf(BizException.class);
        assertThat(CregisFundsPreparationService.amount("999999999999.999999")).isEqualTo(new BigDecimal("999999999999.999999"));
        assertThat(CregisFundsPreparationService.amount("0.000001")).isEqualTo(new BigDecimal("0.000001"));
    }
    @Test void collectionIsOwnedAndRecordsOnlyNoProviderSideEffects() {
        when(db.insert(anyString(),eq(42L),eq("COLLECTION"),eq(source),eq(CregisFundsPreparationService.PLATFORM),any(),anyString(),anyString(),eq(7L))).thenReturn(1);
        when(db.plan(eq(42L),anyString())).thenReturn(Map.of("state","DRAFT"));
        assertThat(service.create("key","COLLECTION",source,"20","invoice-1",reason)).containsEntry("state","DRAFT");
        verify(audit).recordRequired(any());
        verifyNoInteractions(router,chain,vendor,payout);
    }
    @Test void sourceMustBeAllocatedInCurrentProject() {
        when(db.sourceExists(42,source)).thenReturn(0);
        assertThatThrownBy(() -> service.create("key","COLLECTION",source,"20","invoice-1",reason)).hasMessage("CREGIS_PREPARATION_SOURCE_INVALID");
        assertThatThrownBy(() -> service.create("key","COLLECTION",CregisFundsPreparationService.PLATFORM,"20","invoice-1",reason)).hasMessage("CREGIS_PREPARATION_SOURCE_INVALID");
        verify(db,never()).insert(any(),anyLong(),any(),any(),any(),any(),any(),any(),anyLong());
    }
    @Test void supplierCannotBeDisguisedAsUserWithdrawalOrSourceOverride() {
        assertThatThrownBy(() -> service.create("key","SUPPLIER_PAYMENT",source,"20","invoice-1",reason)).hasMessage("CREGIS_PREPARATION_REQUEST_INVALID");
        assertThatThrownBy(() -> service.create("key","WITHDRAWAL",source,"20","invoice-1",reason)).hasMessage("CREGIS_PREPARATION_REQUEST_INVALID");
        when(db.insert(anyString(),eq(42L),eq("SUPPLIER_PAYMENT"),eq(CregisFundsPreparationService.PLATFORM),eq(CregisFundsPreparationService.COUNTERPARTY),any(),anyString(),anyString(),eq(7L))).thenReturn(1);
        when(db.plan(eq(42L),anyString())).thenReturn(Map.of("state","DRAFT"));
        service.create("other","SUPPLIER_PAYMENT","","20","invoice-2",reason);
        verifyNoInteractions(router,chain,vendor,payout);
    }
    @Test void reviewRequiresDifferentActiveMakerAndExactVersion() {
        var row = row("DRAFT",7,0); when(db.lock(42,no)).thenReturn(row);
        assertThatThrownBy(() -> service.decide("key",no,0,"REVIEW",reason)).hasMessage("CREGIS_PREPARATION_DIFFERENT_CHECKER_REQUIRED");
        when(db.lock(42,no)).thenReturn(row("DRAFT",8,1));
        assertThatThrownBy(() -> service.decide("key",no,0,"REVIEW",reason)).hasMessage("CREGIS_PREPARATION_STATE_CONFLICT");
        when(db.lock(42,no)).thenReturn(row("DRAFT",8,0));
        when(db.decide(42,no,0,"DRAFT","REVIEWED",7,reason)).thenReturn(1);
        when(db.plan(42,no)).thenReturn(row("REVIEWED",8,1));
        service.decide("key",no,0,"REVIEW",reason);
        verify(admins).requireActive(8);
        verifyNoInteractions(router,chain,vendor,payout);
    }
    @Test void staleSourceAndInactiveMakerCannotBeReviewed() {
        when(db.lock(42,no)).thenReturn(row("DRAFT",8,0));
        doThrow(new BizException(403,"inactive")).when(admins).requireActive(8);
        assertThatThrownBy(() -> service.decide("key",no,0,"REVIEW",reason)).hasMessage("inactive");
        doNothing().when(admins).requireActive(8); when(db.sourceExists(42,source)).thenReturn(0);
        assertThatThrownBy(() -> service.decide("key",no,0,"REVIEW",reason)).hasMessage("CREGIS_PREPARATION_SOURCE_INVALID");
    }
    @Test void cancellationUsesCasAndNeverCreatesARealPayment() {
        when(db.lock(42,no)).thenReturn(row("REVIEWED",8,1));
        when(db.decide(42,no,1,"REVIEWED","CANCELLED",7,reason)).thenReturn(1);
        when(db.plan(42,no)).thenReturn(row("CANCELLED",8,2));
        assertThat(service.decide("key",no,1,"CANCEL",reason)).containsEntry("state","CANCELLED");
        when(db.lock(42,no)).thenReturn(row("CANCELLED",8,2));
        assertThatThrownBy(() -> service.decide("key",no,2,"CANCEL",reason)).hasMessage("CREGIS_PREPARATION_STATE_CONFLICT");
        verifyNoInteractions(router,chain,vendor,payout);
    }
    @Test void readonlyPreflightHasUnknownBalancesOnReadFailureAndNeverSends() {
        properties.setMode(CregisProperties.Mode.PROVIDER); when(router.provider()).thenReturn(vendor);
        when(vendor.projectCoins()).thenThrow(new RuntimeException("read unavailable"));
        when(chain.preparationBalance(source)).thenThrow(new RuntimeException("RPC unavailable"));
        when(payout.capability()).thenReturn(Map.of("enabled",false));
        var result=service.preflight("COLLECTION",source,"20");
        assertThat(result).containsEntry("executionEnabled",false).containsEntry("balance",null).containsEntry("providerVerified",false);
        assertThat(result.get("blockers").toString()).contains("PROVIDER_READ_UNAVAILABLE","CHAIN_BALANCE_UNAVAILABLE","EXISTING_PAYOUT_NOT_READY");
        verify(vendor,never()).createPayout(any()); verify(vendor,never()).createAddress(any(),any(),any(),any());
    }
    @Test void preflightPositiveBalanceDoesNotBecomeSendAuthorization() {
        properties.setMode(CregisProperties.Mode.PROVIDER); when(router.provider()).thenReturn(vendor);
        when(vendor.projectCoins()).thenReturn(List.of(new CregisGateway.Coin(CregisConstants.USDT_BEP20_CURRENCY,"USDT","2510",CregisConstants.USDT_BEP20_TOKEN_ID,true,true)));
        when(vendor.addressBelongs("2510",source)).thenReturn(true);
        when(chain.preparationBalance(source)).thenReturn(new BscDepositProof.PreparationBalance("30","1",10,"hash"));
        when(payout.capability()).thenReturn(Map.of("enabled",true));
        assertThat(service.preflight("COLLECTION",source,"20")).containsEntry("executionEnabled",false).containsEntry("gasEstimateRequired",true)
                .containsEntry("walletBindingReviewRequired",true).containsEntry("blockers",List.of());
        verify(vendor,never()).createPayout(any()); verify(vendor,never()).createAddress(any(),any(),any(),any());
    }
    @Test void idempotencyReplayReturnsOriginalPlanWithoutSecondInsert() {
        when(idempotency.executeRetained(anyString(),eq("original"),anyString(),eq(Map.class),any())).thenReturn(Map.of("planNo",no,"state","DRAFT"));
        service.create("original","COLLECTION",source,"20","invoice-1",reason);
        verify(db,never()).insert(any(),anyLong(),any(),any(),any(),any(),any(),any(),anyLong());
    }
    Map<String,Object> row(String state,long maker,long version) {
        return Map.of("state",state,"makerId",Long.toString(maker),"version",version,"kind","COLLECTION","sourceAddress",source,"targetAddress",CregisFundsPreparationService.PLATFORM);
    }
}
