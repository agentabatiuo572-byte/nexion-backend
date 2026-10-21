package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.finance.mapper.*;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CregisExceptionClaimServiceTest {
    final CregisProperties config=new CregisProperties();
    final CregisExceptionOwnershipService ownership=mock(CregisExceptionOwnershipService.class);
    final CregisExceptionSourceService sources=mock(CregisExceptionSourceService.class);
    final CregisExceptionAdminGuard admins=mock(CregisExceptionAdminGuard.class);
    final CregisExceptionClaimMapper db=mock(CregisExceptionClaimMapper.class);
    final CregisDepositMapper deposits=mock(CregisDepositMapper.class);
    final AdminIdempotencyService idempotency=mock(AdminIdempotencyService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final TreasuryLedgerRepository treasury=mock(TreasuryLedgerRepository.class);
    final SupportPaymentAttributionFacade attribution=mock(SupportPaymentAttributionFacade.class);
    final AuditObjectLockMapper locks=mock(AuditObjectLockMapper.class);
    final CregisExceptionClaimService service=new CregisExceptionClaimService(config,ownership,sources,admins,db,deposits,idempotency,audit,treasury,attribution,locks);
    final String tx="0x"+"a".repeat(64),address="0x"+"b".repeat(40),block="0x"+"c".repeat(64);
    final CregisExceptionSourceService.Source source=new CregisExceptionSourceService.Source(88,77,tx,3,address,BigDecimal.TEN);
    final CregisExceptionOwnershipService.Evidence proof=new CregisExceptionOwnershipService.Evidence(new CregisExceptionOwnershipService.SourceRef("OBSERVATION",101),source,
            "0x"+"d".repeat(40),"UNATTRIBUTED_HOLD",null,20,block,15,"e".repeat(64),Instant.now());
    final Map<String,Object> row=new HashMap<>();
    @BeforeEach void setup(){config.setMode(CregisProperties.Mode.PROVIDER);config.setProjectId(88);config.setDepositCreditEnabled(true);
        when(admins.require(true)).thenReturn(9L);when(admins.require(false)).thenReturn(9L);
        when(idempotency.recoveryResult(any(),any(),any(),eq(Map.class))).thenReturn(new AdminIdempotencyService.RecoveryResult<>(AdminIdempotencyService.RecoveryStatus.NOT_FOUND,null));
        when(idempotency.executeRetained(any(),any(),any(),eq(Map.class),any())).thenAnswer(i->i.<Supplier<Map<String,Object>>>getArgument(4).get());
        when(ownership.verify("challenge","signature","CLAIM_CREDIT",42L)).thenReturn(new CregisExceptionOwnershipService.VerifiedOwnership("challenge","CLAIM_CREDIT",42L,proof,"f".repeat(64)));
        when(ownership.preflight(proof.ref())).thenReturn(proof);
        when(deposits.lockProvisionGate()).thenReturn(Map.of("state","BLOCKED","switchReason","CREGIS_DEPOSIT_INCIDENT","version",2));
        when(db.lockObservation(88,101)).thenReturn(Map.of("status","UNATTRIBUTED_HOLD","txid",tx,"logIndex",3,"address",address,"grossAmount","10","blockNumber",20L));
        row.putAll(Map.of("id",1L,"case_no","CL-original","source_type","OBSERVATION","source_id",101L,"state","MAKER_DONE","version",0L,"maker_id",7L,"target_user_id",42L,"project_id",88L));
        row.putAll(Map.of("cid",77L,"txid",tx,"log_index",3,"address",address,"gross_amount",BigDecimal.TEN,"evidence_hash",proof.evidenceHash(),"challenge_id","challenge"));
        when(db.snapshot(88,1)).thenReturn(row);when(db.lock(88,1)).thenReturn(row);when(db.insertCase(any())).thenReturn(1);when(db.lastId()).thenReturn(1L);
        when(db.decide(anyLong(),anyLong(),anyLong(),anyLong(),anyString(),anyString())).thenReturn(1);
        when(deposits.lockActiveUser(42)).thenReturn(42L);
        when(deposits.insertEvent(anyLong(),anyLong(),anyLong(),any(),anyInt(),any(),any(),any(),any(),anyLong(),any(),anyInt(),any())).thenReturn(1);
        when(deposits.recordTransferSender(anyLong(),anyLong(),anyString())).thenReturn(1);
        when(deposits.lockWallet(42)).thenReturn(Map.of("usdtAvailable",BigDecimal.ZERO,"version",3L));
        when(deposits.creditWallet(new BigDecimal("9"),42,3)).thenReturn(1);
        when(attribution.prepare(anyLong(),any(),any(),any())).thenReturn(mock(SupportPaymentAttributionFacade.Prepared.class));
        when(attribution.insertLedger(any(),any(),any(),any())).thenReturn(1);
        when(deposits.ledgerId("CR-77")).thenReturn(11L);when(deposits.eventId(88,77)).thenReturn(12L);when(deposits.linkLedger(11,12)).thenReturn(1);
        when(deposits.insertDepositOrder(42,"CR-77",tx,3,new BigDecimal("9"),15,11)).thenReturn(1);
    }
    @Test void proposalConsumesNonceAndReservesSourceWithoutWalletMutation(){
        var result=service.propose("key","challenge","signature",42,proof.evidenceHash(),"核实原始转账者签名后发起认领");
        assertThat(result.get("state")).isEqualTo("MAKER_DONE");verify(sources).reserve(source,"CLAIM","CL-challenge");verify(ownership).consume(any(),eq("CL-challenge"));
        verify(deposits,never()).creditWallet(any(),anyLong(),anyLong());verifyNoInteractions(attribution,treasury);
    }
    @Test void differentCheckerCreditsExactlyThroughExistingWalletLedgerDepositReserveAndAttribution(){
        var result=service.decide("key",1,0,"APPROVE","独立复核原始来源及新鲜链上证据");
        assertThat(result.get("state")).isEqualTo("CREDITED");
        var order=inOrder(sources,deposits,attribution,treasury);
        order.verify(sources).ensureReserved(source,"CLAIM","CL-original");
        order.verify(deposits).lockActiveUser(42);order.verify(attribution).prepare(eq(42L),any(),eq("CR-77"),eq("88"));
        order.verify(deposits).insertEvent(eq(42L),eq(88L),eq(77L),eq(tx),eq(3),eq(address),eq(BigDecimal.TEN),eq(BigDecimal.ONE),eq(new BigDecimal("9")),eq(20L),eq(block),eq(15),eq("CREDITED"));
        order.verify(deposits).lockWallet(42);order.verify(attribution).beforeCash(any());order.verify(deposits).creditWallet(new BigDecimal("9"),42,3);
        order.verify(attribution).insertLedger(any(),eq(new BigDecimal("9")),eq(new BigDecimal("9")),any());order.verify(treasury).recordTopupReserve("CR-77",new BigDecimal("9"),"CREGIS:77");
        order.verify(deposits).insertDepositOrder(42,"CR-77",tx,3,new BigDecimal("9"),15,11);order.verify(attribution).record(any());order.verify(sources).complete(source,"CLAIM","CL-original","CREDITED");
        verify(deposits,never()).setSwitches(anyInt(),anyInt(),anyInt(),any(),anyLong());
    }
    @Test void makerCannotSelfApproveAndOtherCriticalRiskOrPendingBlocksCredit(){
        when(admins.require(true)).thenReturn(7L);assertThatThrownBy(()->service.decide("key",1,0,"APPROVE","独立复核原始来源及新鲜链上证据")).hasMessage("CREGIS_EXCEPTION_DIFFERENT_CHECKER_REQUIRED");
        when(admins.require(true)).thenReturn(9L);when(db.otherCritical(88,77)).thenReturn(1);
        assertThatThrownBy(()->service.decide("key",1,0,"APPROVE","独立复核原始来源及新鲜链上证据")).hasMessage("CREGIS_EXCEPTION_RISK_GATE_BLOCKED");
        verify(deposits,never()).creditWallet(any(),anyLong(),anyLong());
    }
    @Test void changedEvidenceAndRejectedCaseNeverCreditAndRejectionReleasesOnlyOwnedSource(){
        row.put("evidence_hash","changed");assertThatThrownBy(()->service.decide("key",1,0,"APPROVE","独立复核原始来源及新鲜链上证据")).hasMessage("CREGIS_EXCEPTION_EVIDENCE_CHANGED");
        service.decide("key",1,0,"REJECT","原始来源凭据待补充当前拒绝认领");verify(sources).release(source,"CLAIM","CL-original");verifyNoInteractions(attribution,treasury);
    }
    @Test void retainedReplayReturnsOriginalResultWithoutRequeryAndPreservesLongIds(){
        when(idempotency.recoveryResult(any(),any(),any(),eq(Map.class))).thenReturn(new AdminIdempotencyService.RecoveryResult<>(AdminIdempotencyService.RecoveryStatus.SUCCEEDED,Map.of("caseId","9223372036854775807","state","CREDITED")));
        assertThat(service.decide("old",1,0,"APPROVE","独立复核原始来源及新鲜链上证据").get("caseId")).isEqualTo("9223372036854775807");
        verifyNoInteractions(sources,deposits,ownership,attribution,treasury);
    }
    @Test void checkerListExposesExactFeeAndNetUsedByCredit(){
        when(db.page(eq(88L),anyLong(),anyInt())).thenReturn(List.of(row));
        var result=service.page(0,20);
        var item=(Map<?,?>)((List<?>)result.get("records")).get(0);
        assertThat(item.get("grossAmount")).isEqualTo("10");
        assertThat(item.get("feeAmount")).isEqualTo("1.000000");assertThat(item.get("netAmount")).isEqualTo("9.000000");
        assertThat(item.get("targetUserId")).isEqualTo("42");
    }
    @ParameterizedTest
    @CsvSource({"IDLE,1,NONE,true","IDLE,0,NONE,false","BLOCKED,0,CREGIS_DEPOSIT_INCIDENT,true",
            "BLOCKED,0,CREGIS_UNRESOLVED_EXPOSURE_500,true","BLOCKED,0,MANUAL_OFF,false",
            "BLOCKED,0,EMERGENCY:MANUAL,false","BUSY,1,CREGIS_DEPOSIT_INCIDENT,false","BLOCKED,1,UNKNOWN,false"})
    void onlyExplicitAutomaticOrCheckedRepairGatesCanPropose(String state,int enabled,String reason,boolean allowed){
        when(deposits.lockProvisionGate()).thenReturn(Map.of("state",state,"creditEnabled",enabled,"switchReason",reason));
        if(allowed)assertThat(service.propose("key","challenge","signature",42,proof.evidenceHash(),"核实原始转账者签名后发起认领").get("state")).isEqualTo("MAKER_DONE");
        else assertThatThrownBy(()->service.propose("key","challenge","signature",42,proof.evidenceHash(),"核实原始转账者签名后发起认领")).hasMessage("CREGIS_EXCEPTION_RISK_GATE_BLOCKED");
        verify(deposits,never()).setSwitches(anyInt(),anyInt(),anyInt(),any(),anyLong());
    }
    @Test void environmentDisabledCannotUseIncidentRepairAndManualA2SourceOrUserLocksBlock(){
        config.setDepositCreditEnabled(false);
        assertThatThrownBy(()->service.propose("key","challenge","signature",42,proof.evidenceHash(),"核实原始转账者签名后发起认领")).hasMessage("CREGIS_EXCEPTION_RISK_GATE_BLOCKED");
        config.setDepositCreditEnabled(true);when(locks.countActiveByTarget("D","deposit","CR-77")).thenReturn(1);
        assertThatThrownBy(()->service.propose("key","challenge","signature",42,proof.evidenceHash(),"核实原始转账者签名后发起认领")).hasMessage("CREGIS_EXCEPTION_OBJECT_LOCKED_BY_A2");
        when(locks.countActiveByTarget("D","deposit","CR-77")).thenReturn(0);when(locks.countActiveByTarget("C","user","42")).thenReturn(1);
        assertThatThrownBy(()->service.decide("key",1,0,"APPROVE","独立复核原始来源及新鲜链上证据")).hasMessage("CREGIS_EXCEPTION_OBJECT_LOCKED_BY_A2");
        verify(db,never()).insertCase(any());verify(deposits,never()).creditWallet(any(),anyLong(),anyLong());
    }
    @Test void deactivatedMakerCannotBeApproved(){
        doThrow(new ffdd.opsconsole.shared.exception.BizException(403,"ADMIN_FINANCE_AUTH_REQUIRED")).when(admins).requireActive(7);
        assertThatThrownBy(()->service.decide("key",1,0,"APPROVE","独立复核原始来源及新鲜链上证据")).hasMessage("ADMIN_FINANCE_AUTH_REQUIRED");
        verify(deposits,never()).creditWallet(any(),anyLong(),anyLong());verifyNoInteractions(attribution,treasury);
    }
}
