package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.finance.cregis.*;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class CregisPayoutRecoveryServiceTest {
    final WithdrawalPayoutMapper db = mock(WithdrawalPayoutMapper.class);
    final CregisGatewayRouter router = mock(CregisGatewayRouter.class);
    final CregisGateway gateway = mock(CregisGateway.class);
    final AdminOperatorRoleResolver admins = mock(AdminOperatorRoleResolver.class);
    final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final TreasuryLedgerPostingFacade ledger = mock(TreasuryLedgerPostingFacade.class);
    final SupportWithdrawalFundSourceService funds = mock(SupportWithdrawalFundSourceService.class);
    final EventOutboxService outbox = mock(EventOutboxService.class);
    final WithdrawalPayoutFinalizer finalizer = new WithdrawalPayoutFinalizer(db,audit,ledger,Clock.systemUTC(),funds,outbox);
    final CregisPayoutRecoveryService service = new CregisPayoutRecoveryService(db,router,finalizer,idempotency,admins);
    final String reason = "供应商已核实此CID属于原提现，恢复观察";

    @BeforeEach void setup() {
        authenticate("ADMIN",List.of("finance_d2_read","finance_d2_withdrawal_approve"));
        when(admins.isActiveAdministrator(7L)).thenReturn(true);
        when(db.payout("WD-recovery")).thenReturn(row(null,"TX_ORPHANED","provider"));
        when(db.lockCashCustomer(42L)).thenReturn(42L);
        when(db.recoverOrphanToSent(eq("WD-recovery"),eq(901L),eq("original-business-key"),any())).thenReturn(1);
        when(router.provider()).thenReturn(gateway);
        when(gateway.queryPayout(any())).thenReturn(observation(901,"original-business-key","9",address()));
        when(idempotency.recoveryResult(any(),any(),any(),eq(CregisPayoutRecoveryService.Result.class)))
                .thenReturn(new AdminIdempotencyService.RecoveryResult<>(AdminIdempotencyService.RecoveryStatus.NOT_FOUND,null));
        when(idempotency.executeRetained(any(),any(),any(),eq(CregisPayoutRecoveryService.Result.class),any()))
                .thenAnswer(call -> call.<Supplier<CregisPayoutRecoveryService.Result>>getArgument(4).get());
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void lostCallbackCidIsIndependentlyQueriedThenRestoredWithoutFundsOrCreate() {
        var result = recover();
        assertThat(result.state()).isEqualTo("SENT");
        assertThat(result.providerCid()).isEqualTo("901");
        var ordered = inOrder(gateway,db,audit);
        ordered.verify(gateway).queryPayout(new CregisGateway.PayoutQuery(901,"original-business-key",address(),new BigDecimal("9")));
        ordered.verify(db).lockCashCustomer(42L);
        ordered.verify(db).lockPayoutSource("WD-recovery");
        ordered.verify(db).payout("WD-recovery");
        ordered.verify(db).recoverOrphanToSent(eq("WD-recovery"),eq(901L),eq("original-business-key"),any());
        verify(audit).recordRequired(argThat(e -> "D2_CREGIS_PAYOUT_CID_RECOVERED".equals(e.getAction())
                && "ADMIN".equals(e.getActorType()) && "finance-operator".equals(e.getActorUsername())
                && e.getDetail() instanceof Map<?,?> detail && reason.equals(detail.get("reason"))));
        noMoney();
    }

    @ParameterizedTest @ValueSource(strings={"cid","key","amount","address","asset"})
    void wrongProviderIdentityOrImmutableSnapshotCannotRecover(String wrong) {
        var observed = observation(wrong.equals("cid")?902:901,wrong.equals("key")?"another-order":"original-business-key",
                wrong.equals("amount")?"8":"9",wrong.equals("address")?"0x"+"c".repeat(40):address());
        if (wrong.equals("asset")) observed = new CregisGateway.PayoutOrder(901,"BTC","1","0",address(),new BigDecimal("9"),
                "original-business-key",CregisGateway.PayoutStatus.AWAITING_AUDIT,null);
        when(gateway.queryPayout(any())).thenReturn(observed);
        assertThatThrownBy(this::recover).hasMessage("CREGIS_PAYOUT_RECOVERY_EVIDENCE_CONFLICT");
        verify(db,never()).recoverOrphanToSent(any(),anyLong(),any(),any()); noMoney();
    }

    @Test void unavailableOrUnrecognizedObservationKeepsOriginalReservation() {
        when(gateway.queryPayout(any())).thenThrow(new CregisGatewayException(CregisGatewayException.Kind.UNAVAILABLE,"outage"));
        assertThatThrownBy(this::recover).hasMessage("CREGIS_PAYOUT_RECOVERY_QUERY_UNAVAILABLE");
        verify(db,never()).recoverOrphanToSent(any(),anyLong(),any(),any()); noMoney();
    }

    @Test void retainedReplayDoesNotRequeryAndMismatchedKeyIsRejected() {
        var result = new CregisPayoutRecoveryService.Result("WD-recovery","901","SENT");
        when(idempotency.recoveryResult(any(),any(),any(),eq(CregisPayoutRecoveryService.Result.class)))
                .thenReturn(new AdminIdempotencyService.RecoveryResult<>(AdminIdempotencyService.RecoveryStatus.SUCCEEDED,result));
        assertThat(recover()).isEqualTo(result);
        verifyNoInteractions(gateway);
        when(idempotency.recoveryResult(any(),any(),any(),eq(CregisPayoutRecoveryService.Result.class)))
                .thenReturn(new AdminIdempotencyService.RecoveryResult<>(AdminIdempotencyService.RecoveryStatus.MISMATCH,null));
        assertThatThrownBy(this::recover).hasMessage("CREGIS_PAYOUT_RECOVERY_IN_PROGRESS_OR_CONFLICT");
        noMoney();
    }

    @Test void sameCidAlreadySentIsIdempotentButAnotherCidAndRacingCasAreRejected() {
        when(db.payout("WD-recovery")).thenReturn(row(901L,"SENT","provider"));
        assertThat(recover().state()).isEqualTo("SENT");
        verify(db,never()).recoverOrphanToSent(any(),anyLong(),any(),any());
        when(db.payout("WD-recovery")).thenReturn(row(902L,"SENT","provider"));
        assertThatThrownBy(this::recover).hasMessage("CREGIS_PAYOUT_RECOVERY_STATE_CONFLICT");
        when(db.payout("WD-recovery")).thenReturn(row(null,"TX_ORPHANED","provider"));
        when(db.recoverOrphanToSent(any(),anyLong(),any(),any())).thenReturn(0);
        assertThatThrownBy(this::recover).hasMessage("CREGIS_PAYOUT_RECOVERY_STATE_CONFLICT"); noMoney();
    }

    @Test void lockFailureAndSnapshotChangeDuringQueryCannotRecover() {
        when(db.lockCashCustomer(42L)).thenReturn(null);
        assertThatThrownBy(this::recover).hasMessage("提现账户暂不可处理，请复核后重试");
        when(db.lockCashCustomer(42L)).thenReturn(42L);
        when(db.payout("WD-recovery")).thenReturn(row(null,"TX_ORPHANED","provider"),row(null,"TX_ORPHANED","mock"));
        assertThatThrownBy(this::recover).hasMessage("CREGIS_PAYOUT_RECOVERY_STATE_CONFLICT");
        verify(db,never()).recoverOrphanToSent(any(),anyLong(),any(),any()); noMoney();
    }

    @Test void userForgedGrantsReadOnlyAndDisabledAdminCannotRecover() {
        authenticate("USER",List.of("finance_d2_read","finance_d2_withdrawal_approve"));
        assertThatThrownBy(this::recover).hasMessage("ADMIN_FINANCE_AUTH_REQUIRED");
        authenticate("ADMIN",List.of("finance_d2_read"));
        assertThatThrownBy(this::recover).hasMessage("ADMIN_FINANCE_AUTH_REQUIRED");
        authenticate("ADMIN",List.of("finance_d2_read","finance_d2_withdrawal_approve"));
        when(admins.isActiveAdministrator(7L)).thenReturn(false);
        assertThatThrownBy(this::recover).hasMessage("ADMIN_FINANCE_AUTH_REQUIRED");
        verifyNoInteractions(gateway,idempotency); noMoney();
    }

    @Test void detailProjectsRealProviderBoundaryAndNeverQueriesSupplier() {
        authenticate("ADMIN",List.of("finance_d2_read"));
        assertThat(service.detail("WD-recovery").canRecover()).isTrue();
        when(db.payout("WD-recovery")).thenReturn(row(null,"TX_ORPHANED",null));
        assertThat(service.detail("WD-recovery").source()).isNull();
        assertThat(service.detail("WD-recovery").canRecover()).isFalse();
        when(db.payout("WD-recovery")).thenReturn(row(901L,"SENT","provider"));
        assertThat(service.detail("WD-recovery").providerCid()).isEqualTo("901");
        assertThat(service.detail("WD-recovery").canRecover()).isFalse();
        verifyNoInteractions(gateway,idempotency); noMoney();
    }

    CregisPayoutRecoveryService.Result recover() { return service.recover("WD-recovery","recover-key","901",reason); }
    void noMoney() {
        verify(gateway,never()).createPayout(any());
        verify(db,never()).settlePending(any(),any(),any()); verify(db,never()).refundPending(any(),any(),any(),any());
        verifyNoInteractions(funds,ledger,outbox);
    }
    void authenticate(String type,List<String> grants) {
        var auth = new UsernamePasswordAuthenticationToken("7","unused",grants.stream().map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(Map.of("subjectType",type,"username","finance-operator"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
    String address() { return "0x"+"b".repeat(40); }
    WithdrawalPayoutMapper.PayoutRow row(Long cid,String state,String source) {
        return new WithdrawalPayoutMapper.PayoutRow("WD-recovery",42L,"USDT-BEP20",address(),BigDecimal.TEN,
                new BigDecimal("9"),BigDecimal.ZERO,state,null,cid,"original-business-key",source,1);
    }
    CregisGateway.PayoutOrder observation(long cid,String key,String amount,String address) {
        return new CregisGateway.PayoutOrder(cid,CregisConstants.USDT_BEP20_CURRENCY,CregisConstants.BSC_CHAIN_ID,
                CregisConstants.USDT_BEP20_TOKEN_ID,address,new BigDecimal(amount),key,CregisGateway.PayoutStatus.AWAITING_AUDIT,null);
    }
}
