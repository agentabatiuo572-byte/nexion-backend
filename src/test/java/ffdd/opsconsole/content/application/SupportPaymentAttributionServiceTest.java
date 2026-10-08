package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade.Prepared;
import ffdd.opsconsole.content.mapper.SupportPaymentAttributionMapper;
import ffdd.opsconsole.content.mapper.SupportPaymentAttributionMapper.*;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade.BeforeSource;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade.FreshLedgerReceipt;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SupportPaymentAttributionServiceTest {
    private final SupportPaymentAttributionMapper mapper=mock(SupportPaymentAttributionMapper.class);
    private final FinanceSupportPaymentFactsFacade finance=mock(FinanceSupportPaymentFactsFacade.class);
    private final AuditLogService audit=mock(AuditLogService.class);
    private final DataSource ds=mock(DataSource.class);
    private final Object resource=new Object();
    private final ObjectMapper json=new ObjectMapper();
    private final SupportPaymentAttributionService service=new SupportPaymentAttributionService(mapper,finance,audit,ds,json);
    private final LocalDateTime at=LocalDateTime.of(2026,10,8,1,2,3,456789000);
    private final BeforeSource before=mock(BeforeSource.class);
    private final FreshLedgerReceipt receipt=mock(FreshLedgerReceipt.class);

    @BeforeEach void begin() {
        TransactionSynchronizationManager.bindResource(ds,resource);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        when(mapper.lockCustomer(11L)).thenReturn(new Customer(11L,0,"ACTIVE",0));
        when(mapper.databaseUtc()).thenReturn(at);
        when(finance.beforeSource(11L,Source.WALLET_ORDER,"O-11",null)).thenReturn(before);
        when(before.customerId()).thenReturn(11L);when(before.source()).thenReturn(Source.WALLET_ORDER);
        when(before.stableBusinessKey()).thenReturn("O-11");when(before.businessZone()).thenReturn("Asia/Shanghai");
        doReturn(Optional.of(fact(new BigDecimal("12.345678")))).when(finance).readSettled(before,receipt);
        when(receipt.ledgerId()).thenReturn(88L);
        when(finance.insertFreshPaymentLedger(eq(before),any(BigDecimal.class),any(BigDecimal.class),anyString())).thenReturn(receipt);
        doReturn(Optional.of(fact(new BigDecimal("12.345678")))).when(finance).readSettled(before);
        when(mapper.insert(any())).thenReturn(1);
    }
    @AfterEach void cleanup() {
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.unbindResourceIfPossible(ds);
        TransactionSynchronizationManager.clear();
    }
    private Fact fact(BigDecimal amount) { return new Fact("PURCHASE:O-11",Kind.DEVICE_PURCHASE,Source.WALLET_ORDER,List.of("O-11"),11L,88L,"O-11","O-11","BUY",null,"USDT",amount,at.withNano(0),"paid_at",0,null,at.withNano(0),at.withNano(0),"v1",Status.UNKNOWN); }
    private Binding binding() { return new Binding(1L,11L,20L,"ACTIVE",at.minusDays(1),null,0,3L,"MANUAL","bind-1",11L,0,null,1L); }
    private Member member(Long group) { return new Member(2L,20L,group,at.minusDays(1),null,4L,"member-1"); }
    private void known() {
        when(mapper.assignments(11L)).thenReturn(List.of(binding()));
        when(mapper.planMembers(20L)).thenReturn(List.of(member(7L)));
        when(mapper.planGroup(7L)).thenReturn(new Group(7L,10L,"ENABLED",5L));
        when(mapper.lockAdmin(20L)).thenReturn(new Admin(20L,1,8L,0));
        when(mapper.lockAdmin(10L)).thenReturn(new Admin(10L,1,9L,0));
        when(mapper.lockGroup(7L)).thenReturn(new Group(7L,10L,"ENABLED",5L));
        when(mapper.members(20L)).thenReturn(List.of(member(7L)));
        when(mapper.owners(7L)).thenReturn(List.of(new Owner(3L,7L,10L,at.minusDays(1),null,6L,"owner-1")));
    }
    private Prepared prepare() { return service.prepare(11L,Source.WALLET_ORDER,"O-11"); }
    private Prepared ready(Prepared prepared) {
        if(!before.oldSource())service.insertLedger(prepared,new BigDecimal("12.345678"),BigDecimal.TEN,"paid");
        return prepared;
    }
    private Prepared paidPrepared() { return ready(prepare()); }
    private StoredRow captured() { var c=ArgumentCaptor.forClass(StoredRow.class);verify(mapper).insert(c.capture());return c.getValue(); }

    @Test void anchorsAreSortedAndAllCurrentHistoryPrecedesClockAndSourceLocks() {
        known();var p=paidPrepared();service.record(p);
        var order=inOrder(mapper,finance);
        order.verify(mapper).lockCustomer(11L);order.verify(mapper).assignments(11L);order.verify(mapper).routes(11L);
        order.verify(mapper).planMembers(20L);order.verify(mapper).planGroup(7L);
        order.verify(mapper).lockAdmin(10L);order.verify(mapper).lockAdmin(20L);order.verify(mapper).lockGroup(7L);
        order.verify(mapper).members(20L);order.verify(mapper).owners(7L);
        order.verify(mapper).qualifications(10L);order.verify(mapper).qualifications(20L);
        order.verify(mapper).databaseUtc();order.verify(finance).beforeSource(11L,Source.WALLET_ORDER,"O-11",null);
        order.verify(finance).insertFreshPaymentLedger(before,new BigDecimal("12.345678"),BigDecimal.TEN,"paid");
        order.verify(finance).readSettled(before,receipt);
        assertThat(captured()).extracting(StoredRow::agentAdminId,StoredRow::groupId,StoredRow::ownerAdminId).containsExactly(20L,7L,10L);
    }
    @Test void disabledQualificationAndAccountDoNotEraseAnExistingValidBinding() {
        known();when(mapper.lockAdmin(20L)).thenReturn(new Admin(20L,0,8L,1));
        when(mapper.qualifications(20L)).thenReturn(List.of(new Qualification(4L,20L,"SERVICE","DISABLED",at.minusDays(1),null,7L,"disable")));
        service.record(paidPrepared());var row=captured();
        assertThat(row.agentStatus()).isEqualTo("KNOWN");assertThat(row.groupStatus()).isEqualTo("KNOWN");
        assertThat(row.evidenceJson()).contains("DISABLED","disable");
    }
    @Test void changedMembershipDoesNotChaseALockAfterGroupLocksAndRetainsProvenAgent() {
        known();when(mapper.members(20L)).thenReturn(List.of(member(9L)));
        service.record(paidPrepared());var row=captured();
        assertThat(row.agentAdminId()).isEqualTo(20L);assertThat(row.groupId()).isNull();
        assertThat(row.evidenceJson()).contains("LOCK_PLAN_CHANGED");
        verify(mapper,never()).lockGroup(9L);verify(mapper,never()).owners(9L);
    }
    @Test void changedOwnerDoesNotChaseAnotherAdminAndRetainsGroup() {
        known();when(mapper.lockGroup(7L)).thenReturn(new Group(7L,99L,"ENABLED",6L));
        when(mapper.owners(7L)).thenReturn(List.of(new Owner(3L,7L,99L,at.minusDays(1),null,7L,"changed")));
        service.record(paidPrepared());var row=captured();
        assertThat(row.groupId()).isEqualTo(7L);assertThat(row.ownerAdminId()).isNull();
        verify(mapper,never()).lockAdmin(99L);
    }
    @Test void oldSourceDiscardsEveryCurrentOwnershipReference() {
        known();when(before.oldSource()).thenReturn(true);service.record(paidPrepared());var row=captured();
        assertThat(row.captureMode()).isEqualTo("OLD_SOURCE");assertThat(row.agentStatus()).isEqualTo("UNKNOWN");
        assertThat(row.agentAdminId()).isNull();assertThat(row.groupId()).isNull();assertThat(row.ownerAdminId()).isNull();
        assertThat(row.evidenceJson()).contains("OLD_SOURCE").doesNotContain("bind-1","member-1","owner-1","qualifications");
    }
    @Test void futureEndedConflictingAssignmentIsNotHiddenByActiveOrOpenFilters() {
        known();var b=binding();when(mapper.assignments(11L)).thenReturn(List.of(b,new Binding(5L,11L,21L,"INACTIVE",at.minusHours(2),at.plusHours(2),0,1L,"MANUAL","closed-future",11L,0,null,1L)));
        service.record(paidPrepared());assertThat(captured().agentStatus()).isEqualTo("UNKNOWN");
    }
    @Test void emptyAssignmentProvesOnlyAgentUnassignedAndMissingRouteRemainsUnknown() {
        service.record(paidPrepared());var row=captured();assertThat(row.agentStatus()).isEqualTo("UNASSIGNED");assertThat(row.groupStatus()).isEqualTo("UNKNOWN");
    }
    @Test void explicitNullRouteProvesQueueUnassigned() {
        when(mapper.routes(11L)).thenReturn(List.of(new Route(1L,11L,null,at.minusDays(1),null,2L,"queue-none")));
        service.record(paidPrepared());assertThat(captured().groupStatus()).isEqualTo("UNASSIGNED");
    }
    @Test void sourceFailuresAndRequiredAuditFailuresPropagate() {
        var p=paidPrepared();when(finance.readSettled(before,receipt)).thenThrow(new DataAccessResourceFailureException("source-failed"));
        assertThatThrownBy(()->service.record(p)).hasMessage("source-failed");verify(mapper,never()).insert(any());
        doReturn(Optional.of(fact(new BigDecimal("12.345678")))).when(finance).readSettled(before,receipt);
        doThrow(new IllegalStateException("audit-failed")).when(audit).recordRequired(any());
        assertThatThrownBy(()->service.record(p)).hasMessage("audit-failed");
    }
    @Test void explicitlyExcludedFactDoesNotInsertOrAudit() { when(finance.readSettled(before)).thenReturn(Optional.empty());service.record(prepare());verify(mapper,never()).insert(any());verifyNoInteractions(audit); }
    @Test void sameFactReplayKeepsTheOriginalEvidenceAndOneRequiredAudit() {
        known();var p=paidPrepared();service.record(p);var first=captured();when(mapper.findEvidence(first.factId())).thenReturn(first);
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate-fact"));
        when(finance.readSettled(before,receipt)).thenReturn(Optional.of(fact(new BigDecimal("12.3456780"))));service.record(p);
        verify(mapper,times(2)).insert(any());verify(mapper,times(1)).findEvidence(first.factId());
        verify(audit,times(1)).recordRequired(any());verify(finance,times(2)).readSettled(before,receipt);
    }
    @Test void conflictingAmountThrowsRatherThanOverwritingEvidence() {
        var p=paidPrepared();service.record(p);var first=captured();
        when(mapper.findEvidence("PURCHASE:O-11")).thenReturn(first);
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate-fact"));
        when(finance.readSettled(before,receipt)).thenReturn(Optional.of(fact(BigDecimal.ONE)));
        assertThatThrownBy(()->service.record(p)).hasMessage("SUPPORT_PAYMENT_FACT_CONFLICT");verify(mapper,times(2)).insert(any());
        verify(audit,times(1)).recordRequired(any());
    }
    @Test void storesMicrosecondCaptureSeparatelyFromTheOriginalSecondPrecisionBusinessTime() throws Exception {
        known();service.record(paidPrepared());var row=captured();
        var payload=json.readTree(row.sourceFactJson());
        assertThat(payload.get("amount").decimalValue()).isEqualByComparingTo("12.345678");
        assertThat(payload.get("succeededAt").textValue()).isEqualTo("2026-10-08T01:02:03.000000");
        assertThat(payload.get("succeededAtInstant").textValue()).isEqualTo("2026-10-07T17:02:03.000000Z");
        assertThat(row.sourceBusinessZone()).isEqualTo("Asia/Shanghai");
        assertThat(row.evidenceJson()).contains("2026-10-08T01:02:03.456789","UTC","bind-1","member-1","owner-1");
    }
    @Test void fakeTokenAndCrossServiceTokenAreRejected() {
        assertThatThrownBy(()->service.record(new Prepared(){})).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
        assertThatThrownBy(()->service.insertLedger(new Prepared(){},BigDecimal.ONE,BigDecimal.TEN,"paid")).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
        var other=new SupportPaymentAttributionService(mapper,finance,audit,ds,json);
        assertThatThrownBy(()->other.record(prepare())).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
        assertThatThrownBy(()->other.insertLedger(prepare(),BigDecimal.ONE,BigDecimal.TEN,"paid")).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
        verify(finance,never()).insertFreshPaymentLedger(any(),any(),any(),any());
    }
    @Test void suspendedResourceRejectsOuterTokenAndResumedResourceAcceptsIt() {
        var p=paidPrepared();TransactionSynchronizationManager.unbindResource(ds);TransactionSynchronizationManager.bindResource(ds,new Object());
        assertThatThrownBy(()->service.record(p)).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
        assertThatThrownBy(()->service.insertLedger(p,BigDecimal.ONE,BigDecimal.TEN,"paid")).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
        TransactionSynchronizationManager.unbindResource(ds);TransactionSynchronizationManager.bindResource(ds,resource);service.record(p);
    }
    @Test void completionInvalidatesEvenReusedResourceIdentity() {
        var p=paidPrepared();TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        assertThatThrownBy(()->service.record(p)).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
        assertThatThrownBy(()->service.insertLedger(p,BigDecimal.ONE,BigDecimal.TEN,"paid")).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
    }
    @Test void realActiveWritableSynchronizedDatasourceTransactionIsRequired() {
        var prepared=prepare();
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(this::prepare).hasMessage("SUPPORT_PAYMENT_TRANSACTION_REQUIRED");
        assertThatThrownBy(()->service.insertLedger(prepared,BigDecimal.ONE,BigDecimal.TEN,"paid")).hasMessage("SUPPORT_PAYMENT_TRANSACTION_REQUIRED");
        assertThatThrownBy(()->service.record(prepared)).hasMessage("SUPPORT_PAYMENT_TRANSACTION_REQUIRED");
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(this::prepare).hasMessage("SUPPORT_PAYMENT_TRANSACTION_REQUIRED");
        assertThatThrownBy(()->service.insertLedger(prepared,BigDecimal.ONE,BigDecimal.TEN,"paid")).hasMessage("SUPPORT_PAYMENT_TRANSACTION_REQUIRED");
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.unbindResource(ds);
        assertThatThrownBy(this::prepare).hasMessage("SUPPORT_PAYMENT_TRANSACTION_REQUIRED");
        assertThatThrownBy(()->service.insertLedger(prepared,BigDecimal.ONE,BigDecimal.TEN,"paid")).hasMessage("SUPPORT_PAYMENT_TRANSACTION_REQUIRED");
        TransactionSynchronizationManager.bindResource(ds,resource);TransactionSynchronizationManager.clearSynchronization();
        assertThatThrownBy(()->service.insertLedger(prepared,BigDecimal.ONE,BigDecimal.TEN,"paid")).hasMessage("SUPPORT_PAYMENT_TRANSACTION_REQUIRED");
        verify(finance,never()).insertFreshPaymentLedger(any(),any(),any(),any());
    }
    @Test void affectedRowFailurePropagatesBeforeAudit() {
        when(mapper.insert(any())).thenReturn(0);assertThatThrownBy(()->service.record(paidPrepared())).hasMessage("SUPPORT_PAYMENT_INSERT_FAILED");verifyNoInteractions(audit);
    }
    @Test void trialAliasRetainsTheCanonicalFinanceKey() {
        when(finance.beforeSource(11L,Source.TRIAL_CONVERT,"USER:11",null)).thenReturn(before);
        when(before.source()).thenReturn(Source.TRIAL_CONVERT);when(before.stableBusinessKey()).thenReturn("TR-11:CHARGE");
        Fact f=new Fact("PURCHASE:TR-11:CHARGE",Kind.DEVICE_PURCHASE,Source.TRIAL_CONVERT,List.of("TR-11"),11,88,"TR-11:CHARGE","TR-11:CHARGE","TRIAL_CONVERT",null,"USDT",BigDecimal.ONE,at.withNano(0),"charged_at",0,null,at.withNano(0),at.withNano(0),"v1",Status.UNKNOWN);
        when(finance.readSettled(before,receipt)).thenReturn(Optional.of(f));service.record(ready(service.prepare(11L,Source.TRIAL_CONVERT,"USER:11")));
        assertThat(captured().evidenceJson()).contains("TR-11:CHARGE").doesNotContain("USER:11");
    }
    @Test void sourcePartitionsAreRestrictedToTheActualPartitionedRails() {
        assertThatThrownBy(()->service.prepare(11L,Source.DEPOSIT_ORDER,"CR-1")).hasMessage("SUPPORT_PAYMENT_SOURCE_KEY_INVALID");
        assertThatThrownBy(()->service.prepare(11L,Source.VIETQR,"R-1")).hasMessage("SUPPORT_PAYMENT_SOURCE_KEY_INVALID");
        assertThatThrownBy(()->service.prepare(11L,Source.WALLET_ORDER,"O-11","project")).hasMessage("SUPPORT_PAYMENT_SOURCE_KEY_INVALID");
        assertThatThrownBy(()->service.prepare(11L,Source.FREE_TRIAL,"T-1")).hasMessage("SUPPORT_PAYMENT_SOURCE_KEY_INVALID");
        verify(mapper,never()).lockCustomer(anyLong());
    }
    @Test void canonicalPartitionMustMatchTheFinanceObservation() {
        when(finance.beforeSource(11L,Source.VIETQR,"receipt","intent-11")).thenReturn(before);
        when(before.source()).thenReturn(Source.VIETQR);when(before.sourcePartition()).thenReturn("intent-OTHER");
        assertThatThrownBy(()->service.prepare(11L,Source.VIETQR,"receipt","intent-11")).hasMessage("SUPPORT_PAYMENT_BEFORE_MISMATCH");
    }
    @Test void refundRetainsItsOriginalPaymentReferenceWithoutBackfillingThatPayment() {
        known();when(finance.beforeSource(11L,Source.ORDER_REFUND,"O-11",null)).thenReturn(before);when(before.source()).thenReturn(Source.ORDER_REFUND);
        when(receipt.ledgerId()).thenReturn(89L);
        Fact refund=new Fact("ORDER_REFUND:89",Kind.DEVICE_PURCHASE_REFUND,Source.ORDER_REFUND,List.of("O-11","89"),11,89,"O-11","O-11","BUY","PURCHASE:O-11","USDT",BigDecimal.ONE,at.withNano(0),"ledger.created_at",0,null,at.withNano(0),at.withNano(0),"v1",Status.UNKNOWN);
        when(finance.readSettled(before,receipt)).thenReturn(Optional.of(refund));service.record(ready(service.prepare(11L,Source.ORDER_REFUND,"O-11")));
        assertThat(captured().originalFactId()).isEqualTo("PURCHASE:O-11");verify(mapper,never()).findEvidence("PURCHASE:O-11");
    }
    @Test void invalidFinancialFactsCannotCreateEvidence() {
        var p=paidPrepared();Fact base=fact(BigDecimal.ONE);
        for(Fact f:List.of(fact(BigDecimal.ZERO),fact(new BigDecimal("0.0000001")),
                new Fact(base.factId(),base.kind(),base.source(),base.sourceIds(),12,base.ledgerId(),base.sourceBusinessId(),base.orderNo(),base.orderType(),null,base.currency(),base.amount(),base.succeededAt(),base.successTimeField(),0,null,null,null,"v1",Status.UNKNOWN),
                new Fact(base.factId(),base.kind(),Source.TRADE_IN,base.sourceIds(),11,base.ledgerId(),base.sourceBusinessId(),base.orderNo(),base.orderType(),null,base.currency(),base.amount(),base.succeededAt(),base.successTimeField(),0,null,null,null,"v1",Status.UNKNOWN),
                new Fact("PURCHASE:wrong",base.kind(),base.source(),base.sourceIds(),11,base.ledgerId(),base.sourceBusinessId(),base.orderNo(),base.orderType(),null,base.currency(),base.amount(),base.succeededAt(),base.successTimeField(),0,null,null,null,"v1",Status.UNKNOWN))) {
            when(finance.readSettled(before,receipt)).thenReturn(Optional.of(f));assertThatThrownBy(()->service.record(p)).hasMessage("SUPPORT_PAYMENT_FACT_INVALID");
        }
        verify(mapper,never()).insert(any());verifyNoInteractions(audit);
    }
    @Test void missingOrOverlappingMemberAndOwnerOnlyDowngradeTheAffectedLayer() {
        known();when(mapper.members(20L)).thenReturn(List.of(member(7L),new Member(9L,20L,7L,at.minusHours(1),null,1L,"overlap")));
        service.record(paidPrepared());var row=captured();assertThat(row.agentStatus()).isEqualTo("KNOWN");assertThat(row.groupStatus()).isEqualTo("UNKNOWN");
        assertThat(row.evidenceJson()).contains("HISTORY_OVERLAP");
    }
    @Test void prepareSourceAndAnchorFailuresPropagateWithoutAnUnknownFallback() {
        when(finance.beforeSource(11L,Source.WALLET_ORDER,"O-11",null)).thenThrow(new DataAccessResourceFailureException("before-failed"));
        assertThatThrownBy(this::prepare).hasMessage("before-failed");
        when(mapper.assignments(11L)).thenThrow(new DataAccessResourceFailureException("history-failed"));
        assertThatThrownBy(this::prepare).hasMessage("history-failed");verify(mapper,never()).insert(any());
    }
    @Test void futureHistoryNeverInventsUnassignedAndDisabledGroupIsStillARealGroup() {
        known();when(mapper.members(20L)).thenReturn(List.of(new Member(2L,20L,7L,at.plusSeconds(1),null,4L,"future")));
        var p=paidPrepared();service.record(p);assertThat(captured().groupStatus()).isEqualTo("UNKNOWN");
        clearInvocations(mapper);when(mapper.members(20L)).thenReturn(List.of(member(7L)));
        when(mapper.lockGroup(7L)).thenReturn(new Group(7L,10L,"DISABLED",5L));service.record(paidPrepared());
        assertThat(captured().groupStatus()).isEqualTo("KNOWN");
    }
    @Test void missingMemberKeepsTheIndependentlyProvenAgent() {
        known();when(mapper.members(20L)).thenReturn(List.of());service.record(paidPrepared());var row=captured();
        assertThat(row.agentStatus()).isEqualTo("KNOWN");assertThat(row.groupStatus()).isEqualTo("UNKNOWN");assertThat(row.ownerStatus()).isEqualTo("UNKNOWN");
    }
    @Test void missingOwnerKeepsTheIndependentlyProvenGroup() {
        known();when(mapper.owners(7L)).thenReturn(List.of());service.record(paidPrepared());var row=captured();
        assertThat(row.groupStatus()).isEqualTo("KNOWN");assertThat(row.ownerStatus()).isEqualTo("UNKNOWN");
    }
    @Test void oldReplayCannotUpgradeAPreviouslyUnknownCaptureToTodaysKnownGroup() {
        service.record(paidPrepared());StoredRow original=captured();assertThat(original.groupStatus()).isEqualTo("UNKNOWN");
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate-fact"));
        when(mapper.findEvidence(original.factId())).thenReturn(original);known();when(before.oldSource()).thenReturn(true);
        service.record(paidPrepared());verify(mapper,times(2)).insert(any());verify(audit,times(1)).recordRequired(any());
    }
    @Test void newFactInsertsBeforeAnyEvidenceReadAndOnlyTheWinnerIsAudited() {
        var p=paidPrepared();clearInvocations(mapper,finance,audit);service.record(p);
        var order=inOrder(finance,mapper,audit);order.verify(finance).readSettled(before,receipt);order.verify(mapper).insert(any());order.verify(audit).recordRequired(any());
        verify(mapper,never()).findEvidence(anyString());
    }
    @Test void duplicateWithoutAVisibleExistingFactPropagatesTheOriginalFailure() {
        var duplicate=new DuplicateKeyException("duplicate-without-evidence");when(mapper.insert(any())).thenThrow(duplicate);
        assertThatThrownBy(()->service.record(paidPrepared())).isSameAs(duplicate);
        var order=inOrder(mapper);order.verify(mapper).insert(any());order.verify(mapper).findEvidence("PURCHASE:O-11");verifyNoInteractions(audit);
    }
    @Test void nonDuplicateInsertFailureNeverTriggersEvidenceReadOrAudit() {
        var failure=new DataAccessResourceFailureException("insert-disconnected");when(mapper.insert(any())).thenThrow(failure);
        assertThatThrownBy(()->service.record(paidPrepared())).isSameAs(failure);verify(mapper,never()).findEvidence(anyString());verifyNoInteractions(audit);
    }
    @Test void duplicateCurrentReadFailurePropagatesInsteadOfBeingAcceptedAsReplay() {
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate-fact"));
        var failure=new DataAccessResourceFailureException("current-read-failed");when(mapper.findEvidence("PURCHASE:O-11")).thenThrow(failure);
        assertThatThrownBy(()->service.record(paidPrepared())).isSameAs(failure);verifyNoInteractions(audit);
    }
    @Test void duplicateAuditFailureIsOutsideTheFactDuplicateRecoveryPath() {
        var failure=new DuplicateKeyException("required-audit-duplicate");doThrow(failure).when(audit).recordRequired(any());
        assertThatThrownBy(()->service.record(paidPrepared())).isSameAs(failure);verify(mapper,never()).findEvidence(anyString());
    }
    @Test void freshFactWithoutAnActualLedgerReceiptCannotCaptureTodaysOwnership() {
        known();var prepared=prepare();
        assertThatThrownBy(()->service.record(prepared)).hasMessage("SUPPORT_PAYMENT_FRESH_LEDGER_REQUIRED");
        verify(finance).readSettled(before);verify(finance,never()).readSettled(eq(before),any(FreshLedgerReceipt.class));
        verify(mapper,never()).insert(any());verifyNoInteractions(audit);
    }
    @Test void ledgerInsertDelegatesTheExactPaymentOnceBeforeRecordingItsReceipt() {
        known();var prepared=prepare();BigDecimal amount=new BigDecimal("12.345678"),balance=new BigDecimal("87.654322");
        assertThat(service.insertLedger(prepared,amount,balance,"original remark")).isEqualTo(1);
        service.record(prepared);
        var order=inOrder(finance,mapper,audit);
        order.verify(finance).beforeSource(11L,Source.WALLET_ORDER,"O-11",null);
        order.verify(finance).insertFreshPaymentLedger(before,amount,balance,"original remark");
        order.verify(finance).readSettled(before,receipt);order.verify(mapper).insert(any());order.verify(audit).recordRequired(any());
        assertThat(captured().agentStatus()).isEqualTo("KNOWN");verify(finance,never()).readSettled(before);
        assertThatThrownBy(()->service.insertLedger(prepared,amount,balance,"original remark")).hasMessage("SUPPORT_PAYMENT_LEDGER_ALREADY_ATTEMPTED");
        verify(finance,times(1)).insertFreshPaymentLedger(any(),any(),any(),any());
    }
    @Test void aReceiptStoredInOnePreparedCannotAuthorizeAnotherPrepared() {
        var first=paidPrepared();var second=prepare();
        assertThatThrownBy(()->service.record(second)).hasMessage("SUPPORT_PAYMENT_FRESH_LEDGER_REQUIRED");
        service.record(first);verify(finance,times(1)).insertFreshPaymentLedger(any(),any(),any(),any());
        verify(finance).readSettled(before,receipt);verify(mapper,times(1)).insert(any());
    }
    @Test void ledgerFailurePropagatesAndTheSamePreparedCannotAttemptAnotherMoneyWrite() {
        var failure=new DataAccessResourceFailureException("ledger-failed");
        when(finance.insertFreshPaymentLedger(any(),any(),any(),any())).thenThrow(failure);var prepared=prepare();
        assertThatThrownBy(()->service.insertLedger(prepared,BigDecimal.ONE,BigDecimal.TEN,"paid")).isSameAs(failure);
        assertThatThrownBy(()->service.insertLedger(prepared,BigDecimal.ONE,BigDecimal.TEN,"paid")).hasMessage("SUPPORT_PAYMENT_LEDGER_ALREADY_ATTEMPTED");
        assertThatThrownBy(()->service.record(prepared)).hasMessage("SUPPORT_PAYMENT_FRESH_LEDGER_REQUIRED");
        verify(finance,times(1)).insertFreshPaymentLedger(any(),any(),any(),any());verify(mapper,never()).insert(any());verifyNoInteractions(audit);
    }
    @Test void missingAndNonpositiveLedgerReceiptsCannotAuthorizeCapture() {
        when(finance.insertFreshPaymentLedger(any(),any(),any(),any())).thenReturn(null);var missing=prepare();
        assertThatThrownBy(()->service.insertLedger(missing,BigDecimal.ONE,BigDecimal.TEN,"paid")).hasMessage("SUPPORT_PAYMENT_LEDGER_RECEIPT_MISSING");
        when(finance.insertFreshPaymentLedger(any(),any(),any(),any())).thenReturn(receipt);
        for(long id:new long[]{0,-1}) {
            when(receipt.ledgerId()).thenReturn(id);var prepared=prepare();
            assertThatThrownBy(()->service.insertLedger(prepared,BigDecimal.ONE,BigDecimal.TEN,"paid")).hasMessage("SUPPORT_PAYMENT_LEDGER_RECEIPT_INVALID");
            assertThatThrownBy(()->service.record(prepared)).hasMessage("SUPPORT_PAYMENT_FRESH_LEDGER_REQUIRED");
        }
        verify(mapper,never()).insert(any());verifyNoInteractions(audit);
    }
    @Test void settledLedgerMustMatchTheActualInsertedReceipt() {
        when(receipt.ledgerId()).thenReturn(99L);var prepared=paidPrepared();
        assertThatThrownBy(()->service.record(prepared)).hasMessage("SUPPORT_PAYMENT_LEDGER_RECEIPT_MISMATCH");
        verify(mapper,never()).insert(any());verifyNoInteractions(audit);
    }
    @Test void evenAFreshLedgerReceiptCannotWashAnOldSourceIntoNewOwnership() {
        known();when(before.oldSource()).thenReturn(true);var prepared=prepare();
        service.insertLedger(prepared,BigDecimal.ONE,BigDecimal.TEN,"repair");service.record(prepared);var row=captured();
        assertThat(row.captureMode()).isEqualTo("OLD_SOURCE");assertThat(row.agentStatus()).isEqualTo("UNKNOWN");
        assertThat(row.evidenceJson()).doesNotContain("bind-1","member-1","owner-1");
    }
}
