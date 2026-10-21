package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.finance.mapper.CregisExceptionRefundMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class CregisExceptionRefundServiceTest {
    final CregisExceptionRefundMapper db=mock(CregisExceptionRefundMapper.class);
    final CregisDepositMapper deposits=mock(CregisDepositMapper.class);
    final CregisExceptionOwnershipService ownership=mock(CregisExceptionOwnershipService.class);
    final CregisExceptionSourceService sources=mock(CregisExceptionSourceService.class);
    final CregisProperties config=new CregisProperties();
    final CregisGatewayRouter router=mock(CregisGatewayRouter.class);
    final CregisPayoutControl transport=mock(CregisPayoutControl.class);
    final AdminOperatorRoleResolver admins=mock(AdminOperatorRoleResolver.class);
    final AdminIdempotencyService idem=mock(AdminIdempotencyService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper locks=mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class);
    final CregisExceptionRefundService service=new CregisExceptionRefundService(db,deposits,ownership,sources,config,router,transport,admins,idem,audit,new ObjectMapper(),locks);
    final String sender="0x"+"a".repeat(40),address="0x"+"b".repeat(40),tx="0x"+"c".repeat(64),reason="供应商和链证据已重新核验，执行本笔处理";
    @BeforeEach void setup(){
        config.setMode(CregisProperties.Mode.PROVIDER);config.setProjectId(42);
        authenticate("ADMIN",7,List.of("finance_d1_read","finance_d1_channel_manage","finance_d2_withdrawal_approve"));
        when(admins.isActiveAdministrator(anyLong())).thenReturn(true);when(transport.requiredConfirmations()).thenReturn(15);when(transport.configured()).thenReturn(true);
        when(deposits.lockProvisionGate()).thenReturn(CregisExceptionRefundService.map("state","BLOCKED","switchReason","CREGIS_DEPOSIT_INCIDENT"));
        when(db.byId(42,1)).thenAnswer(call->row("MAKER_DONE"));when(db.lockCase(42,1)).thenAnswer(call->row("MAKER_DONE"));
        when(ownership.preflight(any())).thenAnswer(call->proof());
        when(ownership.verify(any(),any(),eq("REFUND_ORIGIN"),isNull())).thenAnswer(call->new CregisExceptionOwnershipService.VerifiedOwnership("challenge","REFUND_ORIGIN",null,proof(),"signature-hash"));
        when(idem.recoveryResult(any(),any(),any(),eq(Map.class))).thenReturn(new AdminIdempotencyService.RecoveryResult<>(AdminIdempotencyService.RecoveryStatus.NOT_FOUND,null));
        when(idem.executeRetained(any(),any(),any(),eq(Map.class),any())).thenAnswer(call->call.<Supplier<Map>>getArgument(4).get());
        when(db.state(anyLong(),anyLong(),anyString(),anyString(),any(),any())).thenReturn(1);
        when(db.terminal(anyLong(),anyLong(),anyLong(),anyString(),any(),any())).thenReturn(1);
        when(db.ledger(any(),any(),any(),any(),any(),any())).thenReturn(1);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void defaultFeeIsUnconfiguredAndProposalsCannotReserveOrSend(){
        assertThat(service.policy().get("mode")).isEqualTo("UNCONFIGURED");
        assertThat(service.preview("challenge","signature")).containsEntry("canPropose",false).containsEntry("disabledReason","CREGIS_REFUND_FEE_NOT_CONFIGURED");
        assertThatThrownBy(()->service.propose("key","challenge","signature","hash",reason)).hasMessage("CREGIS_REFUND_FEE_NOT_CONFIGURED");
        verifyNoInteractions(sources,router);verify(db,never()).insertCase(any());
    }
    @ParameterizedTest @ValueSource(strings={"-1","0.0000001","1000000000000","oops"})
    void feeRejectsNegativeOverPrecisionAndOverRange(String fee){
        assertThatThrownBy(()->service.proposePolicy("key",0,"FIXED_USDT",fee,reason)).hasMessage("CREGIS_REFUND_REQUEST_INVALID");
        verify(db,never()).proposePolicy(anyLong(),anyLong(),any(),any(),anyLong(),any());
    }
    @Test void feeApprovalRequiresAnotherAdminAndExactVersion(){
        var policyCase=CregisExceptionRefundService.map("id",8L,"projectId",42L,"expectedPolicyVersion",0L,"mode","FIXED_USDT","feeUsdt",BigDecimal.ONE,"makerId",7L,"state","MAKER_DONE","version",0L);
        when(db.lockPolicyCase(8)).thenReturn(policyCase);
        assertThatThrownBy(()->service.decidePolicy("key",8,0,"APPROVE",reason)).hasMessage("CREGIS_REFUND_DIFFERENT_CHECKER_REQUIRED");
        authenticate("ADMIN",8,List.of("finance_d1_read","finance_d1_channel_manage","finance_d2_withdrawal_approve"));
        when(db.lockPolicy(42)).thenReturn(CregisExceptionRefundService.map("version",1L));
        assertThatThrownBy(()->service.decidePolicy("key2",8,0,"APPROVE",reason)).hasMessage("CREGIS_REFUND_STATE_CONFLICT");
        when(db.lockPolicy(42)).thenReturn(CregisExceptionRefundService.map("version",0L));
        when(db.applyPolicy(42,0,"FIXED_USDT",BigDecimal.ONE)).thenReturn(1);when(db.decidePolicy(8,0,8,"RESOLVED")).thenReturn(1);
        assertThat(service.decidePolicy("key3",8,0,"APPROVE",reason)).containsEntry("state","RESOLVED");
        verify(audit).recordRequired(argThat(a->a.getAction().equals("CREGIS_EXCEPTION_REFUND_FEE_APPROVE")));
    }
    @Test void grossAtOrBelowFeeCannotProposeAndOldCaseKeepsFrozenSnapshot(){
        when(db.policy(42)).thenReturn(CregisExceptionRefundService.map("mode","FIXED_USDT","feeUsdt",new BigDecimal("5"),"version",2L));
        var preview=service.preview("challenge","signature");assertThat(preview).containsEntry("canPropose",false);
        assertThatThrownBy(()->service.propose("key","challenge","signature",preview.get("evidenceHash").toString(),reason)).hasMessage("CREGIS_REFUND_NET_NOT_POSITIVE");
        authenticate("ADMIN",8,List.of("finance_d1_read","finance_d1_channel_manage","finance_d2_withdrawal_approve"));
        service.decide("approve",1,0,"APPROVE",reason);
        verify(db).state(1,0,"MAKER_DONE","APPROVED",8L,null);
        assertThat(row("MAKER_DONE").get("netAmount")).isEqualTo(new BigDecimal("4"));
    }
    @Test void retainedProposalReplayDoesNotConsumeSignatureTwice(){
        when(idem.recoveryResult(any(),any(),any(),eq(Map.class))).thenReturn(new AdminIdempotencyService.RecoveryResult<>(AdminIdempotencyService.RecoveryStatus.SUCCEEDED,Map.of("caseNo","original")));
        assertThat(service.propose("key","challenge","signature","hash",reason)).containsEntry("caseNo","original");
        verifyNoInteractions(ownership,sources,router);
    }
    @Test void crossUserOrMissingApprovalGrantCannotCreateExternalRefund(){
        authenticate("USER",7,List.of("finance_d1_read","finance_d1_channel_manage","finance_d2_withdrawal_approve"));
        assertThatThrownBy(()->service.propose("key","challenge","signature","hash",reason)).hasMessage("CREGIS_REFUND_ADMIN_REQUIRED");
        authenticate("ADMIN",7,List.of("finance_d1_read","finance_d1_channel_manage"));
        assertThatThrownBy(()->service.preview("challenge","signature")).hasMessage("CREGIS_REFUND_ADMIN_REQUIRED");
        verifyNoInteractions(ownership,sources,router);
    }
    @Test void sourceEmergencyAndOtherP0BlockDispatchButUnrelatedOrdinaryPayoutOffDoesNot(){
        var approved=row("APPROVED");when(db.byId(42,1)).thenReturn(approved);when(db.lockCase(42,1)).thenReturn(approved);when(db.intent(1,0)).thenReturn(1);
        when(deposits.lockProvisionGate()).thenReturn(CregisExceptionRefundService.map("state","BLOCKED","switchReason","EMERGENCY:operator"));
        assertThatThrownBy(()->service.beginDispatch(1,proof())).hasMessage("CREGIS_REFUND_DISPATCH_RISK_HOLD");
        when(deposits.lockProvisionGate()).thenReturn(CregisExceptionRefundService.map("state","BLOCKED","switchReason","CREGIS_DEPOSIT_INCIDENT"));
        when(db.otherCriticalAlerts(42,901)).thenReturn(1);
        assertThatThrownBy(()->service.beginDispatch(1,proof())).hasMessage("CREGIS_REFUND_DISPATCH_RISK_HOLD");
        when(db.otherCriticalAlerts(42,901)).thenReturn(0);assertThat(service.beginDispatch(1,proof())).isNotNull();
        verify(db).intent(1,0);verifyNoInteractions(router);
    }
    @Test void providerSixNeedsRealFifteenConfirmationsAndStateSevenNeverFailsOrReleases(){
        var sent=row("SENT");when(db.byId(42,1)).thenReturn(sent);when(db.lockCase(42,1)).thenReturn(sent);
        service.observed(1,payout(CregisGateway.PayoutStatus.SUCCEEDED,tx),null);
        service.observed(1,payout(CregisGateway.PayoutStatus.SUCCEEDED,tx),new BscDepositProof.Proof(3,100,"hash",14));
        verify(db,never()).terminal(anyLong(),anyLong(),anyLong(),any(),any(),any());
        when(db.unknown(1,0,"CREGIS_REFUND_AMBIGUOUS_PROVIDER_STATUS")).thenReturn(1);
        service.observed(1,payout(CregisGateway.PayoutStatus.FAILED,null),null);
        verify(sources,never()).release(any(),any(),any());verify(sources,never()).complete(any(),any(),any(),any());
        service.observed(1,payout(CregisGateway.PayoutStatus.SUCCEEDED,tx),new BscDepositProof.Proof(3,100,"hash",15));
        verify(db).terminal(1,0,902,"COMPLETED",tx,null);verify(sources).complete(any(),eq("REFUND"),eq("CR-RF-fixture"),eq("REFUNDED"));
    }
    @Test void successEvidencePreventsContradictoryPrebroadcastFailure(){
        var sent=row("SENT");when(db.byId(42,1)).thenReturn(sent);when(db.lockCase(42,1)).thenReturn(sent);when(db.successProgressCount("CR-RF-fixture")).thenReturn(1);
        when(db.unknown(1,0,"CREGIS_REFUND_AMBIGUOUS_PROVIDER_STATUS")).thenReturn(1);
        service.observed(1,payout(CregisGateway.PayoutStatus.AUDIT_REJECTED,null),null);
        verify(db,never()).terminal(anyLong(),anyLong(),anyLong(),any(),any(),any());verify(sources,never()).release(any(),any(),any());
    }
    @ParameterizedTest @ValueSource(strings={"cid","key","amount","address"})
    void wrongProviderSnapshotNeverBindsOrCompletes(String field){
        var gateway=mock(CregisGateway.class);when(router.provider()).thenReturn(gateway);
        var p=payout(CregisGateway.PayoutStatus.AWAITING_AUDIT,null);
        when(gateway.queryPayout(any())).thenReturn(new CregisGateway.PayoutOrder(field.equals("cid")?903:902,p.currency(),p.chainId(),p.tokenId(),field.equals("address")?address:p.address(),
                field.equals("amount")?BigDecimal.ONE:p.amount(),field.equals("key")?"wrong":p.thirdPartyId(),p.status(),null));
        assertThatThrownBy(()->service.query(row("UNKNOWN"),902)).hasMessage("CREGIS_REFUND_PROVIDER_CONFLICT");
        verify(db,never()).bind(anyLong(),anyLong(),anyLong());
    }
    @Test void manualReproposalRequiresIndependentOldProviderRejectionAndNoLateEvidence(){
        var failed=row("FAILED_NO_TRANSFER");when(db.byId(42,1)).thenReturn(failed);when(db.lockCase(42,1)).thenReturn(failed);
        var gateway=mock(CregisGateway.class);when(router.provider()).thenReturn(gateway);
        when(gateway.queryPayout(any())).thenReturn(payout(CregisGateway.PayoutStatus.SUCCEEDED,tx));
        assertThatThrownBy(()->service.retry("retry1",1,0,reason)).hasMessage("CREGIS_REFUND_STATE_CONFLICT");
        when(gateway.queryPayout(any())).thenReturn(payout(CregisGateway.PayoutStatus.AUDIT_REJECTED,null));
        when(db.successCallback("CR-RF-fixture")).thenReturn(1);
        assertThatThrownBy(()->service.retry("retry2",1,0,reason)).hasMessage("CREGIS_REFUND_STATE_CONFLICT");
        verify(sources,never()).release(any(),any(),any());verify(db,never()).insertCase(any());
        failed.put("providerCid",null);when(db.callbackCids("CR-RF-fixture")).thenReturn(List.of(902L));
        assertThatThrownBy(()->service.retry("retry3",1,0,reason)).hasMessage("CREGIS_REFUND_STATE_CONFLICT");
    }
    @Test void lateEvidenceCancelsOnlyUnsentReplacementAndRestoresOriginalSourceForQuery(){
        var old=row("REPROPOSED");var replacement=row("APPROVED");replacement.put("id",2L);replacement.put("caseNo","CR-RF-replacement");replacement.put("businessKey","CR-RF-replacement");replacement.put("providerCid",null);
        when(db.byKey(42,"CR-RF-fixture")).thenReturn(old);when(db.byKey(42,"CR-RF-replacement")).thenReturn(replacement);
        when(db.guard(42,tx,3)).thenReturn(CregisExceptionRefundService.map("state","RESERVED","kind","REFUND","ownerCase","CR-RF-replacement"));
        when(db.lateEvidence("CR-RF-fixture")).thenReturn(1);
        service.lateCallback("CR-RF-fixture");
        verify(db).state(2,0,"APPROVED","CANCELLED_LATE_EVIDENCE",null,"CREGIS_LATE_PROVIDER_EVIDENCE");
        var order=inOrder(sources,db);order.verify(sources).release(any(),eq("REFUND"),eq("CR-RF-replacement"));order.verify(sources).reserve(any(),eq("REFUND"),eq("CR-RF-fixture"));order.verify(db).lateEvidence("CR-RF-fixture");
        verify(deposits,never()).blockProvisionGateAny();verifyNoInteractions(router);
    }
    @Test void lateEvidenceAfterReplacementIntentKeepsBothCasesInInvestigationWithoutReleasing(){
        var old=row("REPROPOSED");var replacement=row("SUBMITTING");replacement.put("caseNo","CR-RF-replacement");replacement.put("dispatchIntentAt",java.time.LocalDateTime.now());
        when(db.byKey(42,"CR-RF-fixture")).thenReturn(old);when(db.byKey(42,"CR-RF-replacement")).thenReturn(replacement);
        when(db.guard(42,tx,3)).thenReturn(CregisExceptionRefundService.map("state","RESERVED","kind","REFUND","ownerCase","CR-RF-replacement"));
        when(db.lateEvidence("CR-RF-fixture")).thenReturn(1);service.lateCallback("CR-RF-fixture");
        verify(deposits).blockProvisionGateAny();verify(deposits).insertRiskAlert(eq(42L),any(),eq("P0"),eq("REFUND_LATE_EVIDENCE"),any());
        verify(sources,never()).release(any(),any(),any());verify(sources,never()).reserve(any(),any(),any());
    }
    @Test void a2SourceOrUserLocksRejectApprovalAndRejectionButDoNotBlockSubmittedObservation(){
        authenticate("ADMIN",8,List.of("finance_d1_read","finance_d1_channel_manage","finance_d2_withdrawal_approve"));
        when(locks.countActiveByTarget("D","deposit","CR-901")).thenReturn(1);
        assertThatThrownBy(()->service.decide("locked-approve",1,0,"APPROVE",reason)).hasMessage("OBJECT_LOCKED_BY_A2");
        assertThatThrownBy(()->service.decide("locked-reject",1,0,"REJECT",reason)).hasMessage("OBJECT_LOCKED_BY_A2");
        when(locks.countActiveByTarget("D","deposit","CR-901")).thenReturn(0);when(locks.countActiveByTarget("C","user","42")).thenReturn(1);
        assertThatThrownBy(()->service.decide("user-lock",1,0,"APPROVE",reason)).hasMessage("OBJECT_LOCKED_BY_A2");
        verify(db,never()).state(anyLong(),anyLong(),any(),any(),any(),any());
        var sent=row("SENT");when(db.byId(42,1)).thenReturn(sent);when(db.lockCase(42,1)).thenReturn(sent);
        service.observed(1,payout(CregisGateway.PayoutStatus.SUCCEEDED,tx),new BscDepositProof.Proof(3,100,"hash",15));
        verify(db).terminal(1,0,902,"COMPLETED",tx,null);
    }
    @Test void a2LockRejectsProposalCancellationRetryAndNewDispatch(){
        when(db.policy(42)).thenReturn(CregisExceptionRefundService.map("mode","PLATFORM_PAYS","feeUsdt",BigDecimal.ZERO,"version",1L));
        String hash=service.preview("challenge","signature").get("evidenceHash").toString();
        when(locks.countActiveByTarget("D","deposit","CR-901")).thenReturn(1);
        assertThatThrownBy(()->service.propose("locked-proposal","challenge","signature",hash,reason)).hasMessage("OBJECT_LOCKED_BY_A2");
        assertThatThrownBy(()->service.decide("locked-cancel",1,0,"CANCEL",reason)).hasMessage("OBJECT_LOCKED_BY_A2");
        var failed=row("FAILED_NO_TRANSFER");failed.put("providerCid",null);when(db.byId(42,1)).thenReturn(failed);when(db.lockCase(42,1)).thenReturn(failed);
        assertThatThrownBy(()->service.retry("locked-retry",1,0,reason)).hasMessage("OBJECT_LOCKED_BY_A2");
        var approved=row("APPROVED");when(db.byId(42,1)).thenReturn(approved);when(db.lockCase(42,1)).thenReturn(approved);
        assertThatThrownBy(()->service.beginDispatch(1,proof())).hasMessage("OBJECT_LOCKED_BY_A2");
        verify(ownership,never()).consume(any(),any());verify(db,never()).insertCase(any());verify(db,never()).intent(anyLong(),anyLong());
        verify(sources,never()).release(any(),any(),any());verifyNoInteractions(router);
    }
    @Test void deactivatedMakerCannotBeApprovedByAnotherActiveAdmin(){
        authenticate("ADMIN",8,List.of("finance_d1_read","finance_d1_channel_manage","finance_d2_withdrawal_approve"));when(admins.isActiveAdministrator(7)).thenReturn(false);
        assertThatThrownBy(()->service.decide("disabled-maker",1,0,"APPROVE",reason)).hasMessage("CREGIS_REFUND_MAKER_INACTIVE");
        verify(db,never()).state(anyLong(),anyLong(),any(),any(),any(),any());
    }
    @Test void activeCheckerCanRejectDeactivatedMakersRefundAndFeePolicy(){
        authenticate("ADMIN",8,List.of("finance_d1_read","finance_d1_channel_manage","finance_d2_withdrawal_approve"));
        when(admins.isActiveAdministrator(7)).thenReturn(false);
        service.decide("reject-inactive-maker",1,0,"REJECT",reason);
        verify(db).state(1,0,"MAKER_DONE","REJECTED",8L,null);
        verify(sources).release(any(),eq("REFUND"),eq("CR-RF-fixture"));
        var policyCase=CregisExceptionRefundService.map("id",8L,"projectId",42L,"expectedPolicyVersion",0L,"mode","FIXED_USDT","feeUsdt",BigDecimal.ONE,"makerId",7L,"state","MAKER_DONE","version",0L);
        when(db.lockPolicyCase(8)).thenReturn(policyCase);when(db.decidePolicy(8,0,8,"REJECTED")).thenReturn(1);
        assertThat(service.decidePolicy("reject-inactive-policy-maker",8,0,"REJECT",reason)).containsEntry("state","REJECTED");
        verify(db,never()).applyPolicy(anyLong(),anyLong(),any(),any());verify(db,never()).intent(anyLong(),anyLong());
    }
    @Test void blockedDispatchRecordsOnlySafeCodesWithoutAdvancingStateOrIntent(){
        when(db.dispatchBlocked(42,1,"OBJECT_LOCKED_BY_A2")).thenReturn(1);
        service.dispatchBlocked(1,new ffdd.opsconsole.shared.exception.BizException(409,"OBJECT_LOCKED_BY_A2"));
        service.dispatchBlocked(1,new RuntimeException("provider URL or secret details must not be persisted"));
        verify(db).dispatchBlocked(42,1,"CREGIS_REFUND_VERIFICATION_UNAVAILABLE");
        verify(audit).recordRequired(argThat(a->a.getAction().equals("CREGIS_EXCEPTION_REFUND_DISPATCH_BLOCKED")));
        verify(db,never()).intent(anyLong(),anyLong());verify(db,never()).state(anyLong(),anyLong(),any(),any(),any(),any());
    }
    CregisExceptionOwnershipService.Evidence proof(){return new CregisExceptionOwnershipService.Evidence(new CregisExceptionOwnershipService.SourceRef("EVENT",3),
            new CregisExceptionSourceService.Source(42,901,tx,3,address,new BigDecimal("5")),sender,"DUST_HOLD",42L,100,"hash",15,"source-hash",Instant.now());}
    Map<String,Object> row(String state){return CregisExceptionRefundService.map("id",1L,"caseNo","CR-RF-fixture","projectId",42L,"sourceType","EVENT","sourceId",3L,"claimantUserId",42L,"txid",tx,"logIndex",3,
            "depositCid",901L,"returnAddress",sender,"depositAddress",address,"grossAmount",new BigDecimal("5"),"feeAmount",BigDecimal.ONE,"netAmount",new BigDecimal("4"),"feePolicyVersion",1L,
            "ownershipReceipt","challenge","evidenceHash","combined","sourceEvidenceHash","source-hash","state",state,"version",0L,"makerId",7L,"providerCid",902L,"businessKey","CR-RF-fixture");}
    CregisGateway.PayoutOrder payout(CregisGateway.PayoutStatus status,String txid){return new CregisGateway.PayoutOrder(902,CregisConstants.USDT_BEP20_CURRENCY,CregisConstants.BSC_CHAIN_ID,CregisConstants.USDT_BEP20_TOKEN_ID,sender,new BigDecimal("4"),"CR-RF-fixture",status,txid);}
    void authenticate(String type,long id,List<String> grants){var auth=new UsernamePasswordAuthenticationToken(Long.toString(id),"unused",grants.stream().map(SimpleGrantedAuthority::new).toList());auth.setDetails(Map.of("subjectType",type));SecurityContextHolder.getContext().setAuthentication(auth);}
}
