package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade;
import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade.Envelope;
import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade.Identity;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade.BeforeSource;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade.FreshLedgerReceipt;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper.Marker;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceSql;
import org.mockito.stubbing.Answer;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;

/** Unit checks simulate resource identity. Real manager/DB proofs belong to the runtime acceptance. */
class SupportPaymentSourceServiceTest {
    private final SupportPaymentSourceMapper mapper=mock(SupportPaymentSourceMapper.class);
    private final SupportPaymentFactService history=mock(SupportPaymentFactService.class);
    private final DataSource dataSource=mock(DataSource.class);
    private final ObjectMapper json=new ObjectMapper();
    private final SupportPaymentCaptureHistoryFacade capturedHistory=mock(SupportPaymentCaptureHistoryFacade.class);
    private final SupportPaymentSourceService service=new SupportPaymentSourceService(mapper,history,dataSource,json,capturedHistory);
    private final LocalDateTime at=LocalDateTime.of(2026,10,8,12,0);
    private final Object resource=new Object();
    private final Map<String,Map<String,Object>> observed=new HashMap<>();
    private final Map<BeforeSource,FreshLedgerReceipt> receipts=new IdentityHashMap<>();
    @BeforeEach void transaction() {
        when(mapper.currentBefore(any(),anyLong(),anyString(),anyLong())).thenAnswer(i -> {
            var row=observed.get("ROOT:"+i.getArgument(0)+":"+i.getArgument(3));
            return row==null?List.of():List.of(row);
        });
        when(mapper.currentSourceProof(anyString())).thenAnswer(i -> mapper.originalSourceProof(i.getArgument(0)));
        when(mapper.settledVietqrCounterpart(anyString())).thenAnswer(i -> mapper.ledgers(i.getArgument(0)));
        when(mapper.currentMarker(any(),anyLong())).thenAnswer(i -> observed.get(i.getArgument(0)+":"+i.getArgument(1)));
        when(mapper.currentSettled(any(),anyList(),anyString(),anyMap())).thenAnswer(i -> {
            Map<String,Object> row=i.getArgument(3);return List.of(row);
        });
        when(mapper.insertLedger(any(),anyMap())).thenAnswer(i -> {
            Source source=i.getArgument(0);Map<String,Object> write=i.getArgument(1);
            long id=source==Source.WALLET_ORDER?201L:source==Source.ORDER_REFUND?301L:101L;write.put("id",id);
            var row=new HashMap<String,Object>();row.put("id",id);row.put("customerId",write.get("customer"));
            row.put("businessId",write.get("key"));row.put("ledgerType",SupportPaymentSourceSql.ledgerType(source));
            row.put("currency","USDT");row.put("direction",SupportPaymentSourceSql.ledgerDirection(source));
            row.put("status",source==Source.TRIAL_CONVERT?"POSTED":"SUCCESS");row.put("deleted",0);
            row.put("amount",write.get("amount"));row.put("balanceAfter",write.get("balanceAfter"));row.put("remark",write.get("remark"));
            row.put("successAt",at);row.put("updatedAt",at);observed.put(Marker.LEDGER+":"+id,row);return 1;
        });
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.bindResource(dataSource,resource);
    }
    @AfterEach void cleanup() {
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            for(var synchronization:TransactionSynchronizationManager.getSynchronizations())
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        TransactionSynchronizationManager.clear();
    }
    @Test void historyRequiresBoundPhysicalRepeatableReadWithoutOpeningAnotherConnection() throws Exception {
        assertThatThrownBy(() -> service.readHistory(List.of(7L))).hasMessage("SUPPORT_PAYMENT_HISTORY_SNAPSHOT_REQUIRED");
        var connection=historyTransaction();
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        assertThatThrownBy(() -> service.readHistory(List.of(7L))).hasMessage("SUPPORT_PAYMENT_HISTORY_SNAPSHOT_REQUIRED");
        when(connection.getTransactionIsolation()).thenThrow(new java.sql.SQLException("unavailable"));
        assertThatThrownBy(() -> service.readHistory(List.of(7L))).hasMessage("SUPPORT_PAYMENT_HISTORY_SNAPSHOT_REQUIRED");
        verifyNoInteractions(dataSource,capturedHistory,history);
        var annotation=SupportPaymentSourceService.class.getMethod("readHistory",Collection.class)
            .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(annotation.readOnly()).isTrue();
        assertThat(annotation.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
    }
    @Test void historyUsesExactCrossSecondProofAndOrdinaryLedgerWithoutCurrentReadsOrMoneyWrites() throws Exception {
        historyTransaction();
        var row=historyWallet();var envelope=historyEnvelope(row,null);
        row.put("sourceVersion","today-version");
        stubHistory(envelope,row);
        var snapshot=service.readHistory(List.of(7L,7L));
        assertThat(snapshot.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.succeededAt()).isEqualTo(at.plusSeconds(1).withNano(100_000_000));
            assertThat(fact.sourceConfirmationAt()).isEqualTo(at.plusSeconds(2));
            assertThat(fact.sourceVersion()).isEqualTo("saved-version");
            assertThat(fact.historicalEnvironmentStatus()).isEqualTo(Status.UNKNOWN);
        });
        assertThat(snapshot.issues()).isEmpty();
        verify(mapper).historyLedgers(List.of(7L),List.of(201L));
        verify(mapper,never()).currentSourceProof(anyString());
        verify(mapper,never()).currentBefore(any(),anyLong(),anyString(),anyLong());
        verify(mapper,never()).currentMarker(any(),anyLong());
        verify(mapper,never()).currentSettled(any(),anyList(),anyString(),anyMap());
        verify(mapper,never()).insertLedger(any(),anyMap());
        verifyNoInteractions(dataSource);
    }
    @Test void malformedHistoryJsonAndMismatchedScalarStayIssuesWithoutQuarantiningValidLegacy() throws Exception {
        historyTransaction();var row=historyWallet();var e=historyEnvelope(row,null);
        when(capturedHistory.readNewFinancialProofs(anyCollection())).thenReturn(List.of(new Envelope(e.identity(),null,at,
            e.captureMode(),e.captureSchemaVersion(),"{",e.beforeSourceJson(),e.evidenceCaptureMode(),e.evidenceSchemaVersion())));
        var result=service.readHistory(List.of(7L));
        assertThat(result.issues()).extracting(Issue::reason).containsExactly("INVALID_PERSISTED_SOURCE_PROOF");
        var argument=org.mockito.ArgumentCaptor.forClass(SupportPaymentFactService.CapturedFinancialHistory.class);
        verify(history).readWithCapturedHistory(eq(List.of(7L)),argument.capture());
        assertThat(argument.getValue().conflictingFactIds()).isEmpty();
        var i=e.identity();var wrong=new Identity(i.factId(),7,i.kind(),i.source(),i.ledgerId(),i.sourceBusinessId(),i.orderNo(),i.orderType(),
            i.originalFactId(),i.currency(),BigDecimal.ONE,i.succeededAt(),i.sourceBusinessZone(),i.successTimeField(),i.fractionalSecondDigits());
        when(capturedHistory.readNewFinancialProofs(anyCollection())).thenReturn(List.of(new Envelope(wrong,null,at,e.captureMode(),
            e.captureSchemaVersion(),e.sourceFactJson(),e.beforeSourceJson(),e.evidenceCaptureMode(),e.evidenceSchemaVersion())));
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("CAPTURED_SOURCE_PROOF_MISMATCH");
        verify(mapper,never()).historyLedgers(anyList(),anyList());
    }
    @ParameterizedTest
    @EnumSource(value=Source.class,names={"CARD_TOPUP","WALLET_ORDER"})
    void mismatchedJsonRejectsSavedIdentityWithoutRelabelingAnIndependentLegacyFact(Source claimedSource) throws Exception {
        historyTransaction();
        var original=payment(Source.CARD_TOPUP,"saved-card",101,"10");original.put("sourceId","nx_payment_record:9");
        var saved=historyEnvelope(original,null);
        var claimed=historyEnvelope(payment(claimedSource,"another-payment",202,"80"),null);
        assertThat(SupportPaymentCapturedSourceProof.decodeSourceFact(claimed.sourceFactJson(),json).source()).isEqualTo(claimedSource);
        when(capturedHistory.readNewFinancialProofs(anyCollection())).thenReturn(List.of(new Envelope(saved.identity(),null,at,
            saved.captureMode(),saved.captureSchemaVersion(),claimed.sourceFactJson(),saved.beforeSourceJson(),
            saved.evidenceCaptureMode(),saved.evidenceSchemaVersion())));
        var factMapper=mock(ffdd.opsconsole.finance.mapper.SupportPaymentFactMapper.class);
        when(factMapper.cards(anyList())).thenReturn(List.of(original));
        var actualPipeline=new SupportPaymentSourceService(mapper,new SupportPaymentFactService(factMapper),dataSource,json,capturedHistory);
        var result=actualPipeline.readHistory(List.of(7L));
        assertThat(result.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.factId()).isEqualTo(saved.identity().factId());
            assertThat(fact.amount()).isEqualByComparingTo("10");
        });
        assertThat(result.issues()).containsExactly(new Issue(Source.CARD_TOPUP,saved.identity().factId(),"CAPTURED_SOURCE_PROOF_MISMATCH"));
        verify(mapper,never()).historyLedgers(anyList(),anyList());
    }
    @Test void historicalActualLedgerAmountContradictionQuarantinesIdentityButMissingLedgerDoesNot() throws Exception {
        historyTransaction();var row=historyWallet();stubHistory(historyEnvelope(row,null),row);
        var ledger=historyLedger(row);ledger.put("amount",BigDecimal.ONE);
        when(mapper.historyLedgers(anyList(),anyList())).thenReturn(List.of(ledger));
        var snapshot=service.readHistory(List.of(7L));
        assertThat(snapshot.facts()).isEmpty();assertThat(snapshot.issues()).extracting(Issue::reason).containsExactly("SETTLEMENT_MISMATCH");
        var argument=org.mockito.ArgumentCaptor.forClass(SupportPaymentFactService.CapturedFinancialHistory.class);
        verify(history).readWithCapturedHistory(eq(List.of(7L)),argument.capture());
        assertThat(argument.getValue().conflictingFactIds()).containsExactly("PURCHASE:order-1");
        when(mapper.historyLedgers(anyList(),anyList())).thenReturn(List.of());
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("MISSING_SETTLEMENT_LEDGER");
    }
    @Test void historicalProofBatchFailureAndKnownMetadataErrorsKeepLegacyPipelineButScopeStillThrows() throws Exception {
        historyTransaction();
        doThrow(new DataAccessResourceFailureException("private")).when(capturedHistory).readNewFinancialProofs(anyCollection());
        var snapshot=service.readHistory(List.of(7L));
        assertThat(snapshot.issues()).hasSize(9).allSatisfy(issue -> assertThat(issue.reason()).isEqualTo("SOURCE_READ_FAILED"));
        assertThat(snapshot.issues()).extracting(Issue::source).doesNotContain(Source.FREE_TRIAL,Source.UNMATCHED_LEDGER);
        for(String error:List.of("INVALID_CAPTURE_HISTORY_SCHEMA","INVALID_CAPTURE_HISTORY_ROW")) {
            doThrow(new IllegalStateException(error)).when(capturedHistory).readNewFinancialProofs(anyCollection());
            assertThat(service.readHistory(List.of(7L)).issues()).hasSize(9)
                .allSatisfy(issue -> assertThat(issue.reason()).isEqualTo("INVALID_PERSISTED_SOURCE_PROOF"));
        }
        doThrow(new IllegalStateException("INVALID_CAPTURE_HISTORY_SCOPE")).when(capturedHistory).readNewFinancialProofs(anyCollection());
        assertThatThrownBy(() -> service.readHistory(List.of(7L))).hasMessage("INVALID_CAPTURE_HISTORY_SCOPE");
        doThrow(new IllegalStateException("UNKNOWN_READER_ERROR")).when(capturedHistory).readNewFinancialProofs(anyCollection());
        assertThatThrownBy(() -> service.readHistory(List.of(7L))).hasMessage("UNKNOWN_READER_ERROR");
        var e=historyEnvelope(historyWallet(),null);
        doReturn(List.of(e)).when(capturedHistory).readNewFinancialProofs(anyCollection());
        assertThatThrownBy(() -> service.readHistory(List.of(8L))).hasMessage("CAPTURED_CUSTOMER_OUTSIDE_SCOPE");
    }
    @Test void historicalSoftDeletedRootCanRetainSavedFinanceButChangedOwnerCannot() throws Exception {
        historyTransaction();var row=historyWallet();stubHistory(historyEnvelope(row,null),row);
        var root=rawOrder("PAID",at.plusSeconds(1).withNano(100_000_000));root.put("deleted",1);
        when(mapper.before(Source.WALLET_ORDER,7,"order-1")).thenReturn(List.of(root));
        assertThat(service.readHistory(List.of(7L)).facts()).hasSize(1);
        root.put("customerId",8L);
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("SETTLEMENT_MISMATCH");
    }
    @Test void malformedCaptureBatchStillReturnsIndependentlyValidLegacyFromTheRealPipeline() throws Exception {
        historyTransaction();
        var factMapper=mock(ffdd.opsconsole.finance.mapper.SupportPaymentFactMapper.class);
        var card=payment(Source.CARD_TOPUP,"independent-card",101,"10");card.put("sourceId","nx_payment_record:9");
        when(factMapper.cards(anyList())).thenReturn(List.of(card));
        var realPipeline=new SupportPaymentSourceService(mapper,new SupportPaymentFactService(factMapper),dataSource,json,capturedHistory);
        when(capturedHistory.readNewFinancialProofs(anyCollection())).thenThrow(new IllegalStateException("INVALID_CAPTURE_HISTORY_SCHEMA"));
        var result=realPipeline.readHistory(List.of(7L));
        assertThat(result.facts()).singleElement().satisfies(fact -> assertThat(fact.sourceBusinessId()).isEqualTo("independent-card"));
        assertThat(result.issues()).hasSize(9).allSatisfy(issue -> assertThat(issue.reason()).isEqualTo("INVALID_PERSISTED_SOURCE_PROOF"));
        assertThat(result.coverage()).allSatisfy(coverage -> {
            assertThat(coverage.historyStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(coverage.refundStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(coverage.historicalEnvironmentStatus()).isEqualTo(Status.UNKNOWN);
        });
    }
    @Test void historicalCregisUsesSavedProjectAndUniqueCreditedEvent() throws Exception {
        historyTransaction();var row=payment(Source.DEPOSIT_ORDER,"CR-51",101,"10");row.put("sourceId","nx_deposit_order:1");
        row.put("successTimeField","nx_deposit_order.credited_at");
        row.put("sourceVersion","saved-version");stubHistory(historyEnvelope(row,"72"),row);
        var event=new HashMap<String,Object>(Map.of("projectId",72L,"cid",51L,"customerId",7L,"ledgerId",101L,
            "status","CREDITED","amount",BigDecimal.TEN,"successAt",at.minusSeconds(1)));
        when(mapper.cregisEvents(72,51)).thenReturn(List.of(event));
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("MISSING_AUTHORITATIVE_SOURCE");
        var root=new HashMap<String,Object>(Map.of("id",1L,"customerId",7L,"businessId","CR-51","amount",BigDecimal.TEN,
            "currency","USDT","ledgerId",101L,"successAt",at,"deleted",1));
        when(mapper.before(Source.DEPOSIT_ORDER,7,"CR-51")).thenReturn(List.of(root));
        assertThat(service.readHistory(List.of(7L)).facts()).singleElement().satisfies(fact -> assertThat(fact.succeededAt()).isEqualTo(at));
        root.put("amount",new BigDecimal("11"));
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("SETTLEMENT_MISMATCH");
        root.put("amount",BigDecimal.TEN);
        event.put("projectId",73L);
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("BROKEN_CREGIS_EVENT_LINK");
        verify(mapper,times(2)).cregisEvents(72,51);
    }
    @Test void historicalVietqrIntentPartitionIsTheLogicalPaymentKeyAndWrongAmountRejects() throws Exception {
        historyTransaction();var row=payment(Source.VIETQR,"D1-VIETQR-recon-1",101,"10");row.put("sourceId","nx_vietqr_reconciliation:1");
        row.put("sourceVersion","saved-version");row.put("duplicateKey","intent-1");stubHistory(historyEnvelope(row,"intent-1"),row);
        when(mapper.before(Source.VIETQR,7,"D1-VIETQR-recon-1")).thenReturn(List.of(Map.of("id",1L,"customerId",7L,
            "businessId","D1-VIETQR-recon-1","intentNo","intent-1","amount",BigDecimal.TEN)));
        when(mapper.intent("intent-1")).thenReturn(List.of(intent("MANUAL","CREDITED","10")));
        assertThat(service.readHistory(List.of(7L)).facts()).hasSize(1);
        var argument=org.mockito.ArgumentCaptor.forClass(SupportPaymentFactService.CapturedFinancialHistory.class);
        verify(history).readWithCapturedHistory(eq(List.of(7L)),argument.capture());
        assertThat(argument.getValue().candidates().get(0).logicalPaymentKey()).isEqualTo("intent-1");
        when(mapper.intent("intent-1")).thenReturn(List.of(intent("MANUAL","CREDITED","11")));
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("BROKEN_INTENT_IDENTITY");
    }
    @Test void historicalLedgerFailureAndMissingWalletConfirmationNeverCreateTrustedCandidates() throws Exception {
        historyTransaction();var row=historyWallet();stubHistory(historyEnvelope(row,null),row);
        when(mapper.historyLedgers(anyList(),anyList())).thenThrow(new DataAccessResourceFailureException("private"));
        var failure=service.readHistory(List.of(7L));
        assertThat(failure.facts()).isEmpty();assertThat(failure.issues()).hasSize(9);
        doReturn(List.of(historyLedger(row))).when(mapper).historyLedgers(anyList(),anyList());
        when(mapper.payments("order-1")).thenReturn(List.of());
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("MISSING_SOURCE_CONFIRMATION_TIME");
    }
    @Test void historyWalletConfirmationRejectsFailedStatusAndWrongExistingLedgerButAcceptsRefundedAndNullLink() throws Exception {
        historyTransaction();var row=historyWallet();stubHistory(historyEnvelope(row,null),row);
        var payment=new HashMap<>(confirmation(at.plusSeconds(2),"80"));
        when(mapper.payments("order-1")).thenReturn(List.of(payment));
        for(String status:List.of("FAILED","CANCELLED","EXPIRED","PENDING")) {
            payment.put("status",status);
            assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("SETTLEMENT_MISMATCH");
        }
        payment.put("status","PAID");
        for(long ledger:List.of(0L,202L)) {
            payment.put("ledgerId",ledger);
            assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("SETTLEMENT_MISMATCH");
        }
        for(String status:List.of("PAID","CONFIRMED","SUCCESS","REFUNDED")) {
            payment.put("status",status);payment.put("ledgerId",null);
            assertThat(service.readHistory(List.of(7L)).facts()).hasSize(1);
            payment.put("ledgerId",201L);
            assertThat(service.readHistory(List.of(7L)).facts()).hasSize(1);
        }
    }
    @Test void writeWalletConfirmationUsesTheSameSuccessfulStatusAndOptionalLedgerContract() {
        var before=wallet(false,false);var payment=new HashMap<>(confirmation(at.plusSeconds(2),"80"));
        when(mapper.payments("order-1")).thenAnswer(remember(Marker.PAYMENT,List.of(payment)));
        for(String status:List.of("FAILED","CANCELLED","EXPIRED","PENDING")) {
            payment.put("status",status);
            assertThatThrownBy(() -> read(before)).hasMessage("BROKEN_SOURCE_LINK");
        }
        payment.put("status","PAID");
        for(long ledger:List.of(0L,202L)) {
            payment.put("ledgerId",ledger);
            assertThatThrownBy(() -> read(before)).hasMessage("BROKEN_SOURCE_LINK");
        }
        for(String status:List.of("PAID","CONFIRMED","SUCCESS","REFUNDED")) {
            payment.put("status",status);payment.put("ledgerId",null);
            assertThat(read(before)).isPresent();
            payment.put("ledgerId",201L);
            assertThat(read(before)).isPresent();
        }
        verify(mapper,times(1)).insertLedger(eq(Source.WALLET_ORDER),anyMap());
    }
    @Test void retainedPurchaseRootsMustMatchActualOrderAmountAndSoftDeleteStillRetainsValidEvidence() throws Exception {
        historyTransaction();
        for(Source source:List.of(Source.WALLET_ORDER,Source.TRADE_IN,Source.CAPACITY_KEEP)) {
            var row=source==Source.WALLET_ORDER?historyWallet():payment(source,"order-1",201,"80");
            row.put("sourceId","nx_order:1");row.put("sourceVersion","saved-version");
            row.put("orderType",source==Source.WALLET_ORDER?"SINGLE":source.name());
            var root=rawOrder("PAID",at.plusSeconds(1).withNano(100_000_000));root.put("orderType",row.get("orderType"));root.put("deleted",1);
            stubHistory(historyEnvelope(row,null),row);
            when(mapper.before(source,7,"order-1")).thenReturn(List.of(root));
            assertThat(service.readHistory(List.of(7L)).facts()).hasSize(1);
            row.put("orderNo","other-order");
            stubHistory(historyEnvelope(row,null),row);
            doReturn(List.of(root)).when(mapper).before(source,7,"order-1");
            assertThat(service.readHistory(List.of(7L)).facts()).isEmpty();
            assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("SETTLEMENT_MISMATCH");
            row.put("orderNo","order-1");
            stubHistory(historyEnvelope(row,null),row);
            doReturn(List.of(root)).when(mapper).before(source,7,"order-1");
            root.put("amount",new BigDecimal("81"));
            assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("SETTLEMENT_MISMATCH");
            root.put("amount",new BigDecimal("80"));
            String wrongType=source==Source.WALLET_ORDER?"CAPACITY_KEEP":"SINGLE";
            row.put("orderType",wrongType);root.put("orderType",wrongType);
            stubHistory(historyEnvelope(row,null),row);
            doReturn(List.of(root)).when(mapper).before(source,7,"order-1");
            assertThat(service.readHistory(List.of(7L)).facts()).isEmpty();
            assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("SETTLEMENT_MISMATCH");
            when(mapper.before(source,7,"order-1")).thenReturn(List.of());
            assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("MISSING_AUTHORITATIVE_SOURCE");
        }
    }
    @Test void historicalOldProofAndReadyEnvironmentCannotTurnIntoTrustedNewSuccess() throws Exception {
        historyTransaction();var row=historyWallet();var e=historyEnvelope(row,null);
        var before=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(e.beforeSourceJson());before.put("oldSource",true);
        when(capturedHistory.readNewFinancialProofs(anyCollection())).thenReturn(List.of(new Envelope(e.identity(),null,at,
            e.captureMode(),e.captureSchemaVersion(),e.sourceFactJson(),json.writeValueAsString(before),e.evidenceCaptureMode(),e.evidenceSchemaVersion())));
        assertThat(service.readHistory(List.of(7L)).facts()).isEmpty();
        var saved=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(e.sourceFactJson());saved.put("historicalEnvironmentStatus","READY");
        when(capturedHistory.readNewFinancialProofs(anyCollection())).thenReturn(List.of(new Envelope(e.identity(),null,at,
            e.captureMode(),e.captureSchemaVersion(),json.writeValueAsString(saved),e.beforeSourceJson(),e.evidenceCaptureMode(),e.evidenceSchemaVersion())));
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("INVALID_PERSISTED_SOURCE_PROOF");
        verify(mapper,never()).historyLedgers(anyList(),anyList());
    }
    @Test void historicalMissingPurchaseRootsRemainUnknownWhileRefundKeepsItsIndependentLedgerIdentity() throws Exception {
        historyTransaction();
        for(var source:List.of(Source.TRADE_IN,Source.CAPACITY_KEEP,Source.ORDER_REFUND)) {
            String key=source==Source.ORDER_REFUND?"E4-REFUND-order-1":"order-1";
            var row=payment(source,key,source==Source.ORDER_REFUND?301:201,"10");
            row.put("sourceId",source==Source.ORDER_REFUND?"nx_wallet_ledger:301":"nx_order:1");
            row.put("orderType",source==Source.ORDER_REFUND?"SINGLE":source.name());row.put("orderNo","order-1");
            row.put("sourceVersion","saved-version");
            stubHistory(historyEnvelope(row,null),row);
            when(mapper.settled(eq(source),anyList(),eq(key))).thenReturn(List.of());
            var result=service.readHistory(List.of(7L));
            if(source==Source.ORDER_REFUND)assertThat(result.facts()).singleElement().satisfies(fact -> assertThat(fact.source()).isEqualTo(source));
            else {
                assertThat(result.facts()).isEmpty();
                assertThat(result.issues()).extracting(Issue::reason).containsExactly("MISSING_AUTHORITATIVE_SOURCE");
            }
        }
    }
    @Test void historicalHdpayRequiresRetainedIntentAndFinancialRootDespiteValidLedger() throws Exception {
        historyTransaction();var row=payment(Source.HDPAY,"intent-1",101,"10");row.put("sourceId","nx_hdpay_payin_order:1");
        row.put("successTimeField","nx_hdpay_payin_order.settled_at");row.put("sourceVersion","saved-version");
        stubHistory(historyEnvelope(row,null),row);
        var root=Map.<String,Object>of("id",1L,"customerId",7L,"businessId","intent-1","amount",BigDecimal.TEN,
            "ledgerBusinessId","intent-1","successAt",at);
        when(mapper.before(Source.HDPAY,7,"intent-1")).thenReturn(List.of(root));
        when(mapper.intent("intent-1")).thenReturn(List.of(intent("HDPAY","CREDITED","10")));
        assertThat(service.readHistory(List.of(7L)).facts()).hasSize(1);
        when(mapper.intent("intent-1")).thenReturn(List.of());
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("MISSING_INTENT_IDENTITY");
    }
    @Test void historicalCardRequiresActualSettlementAndHistoricalTrialRequiresClaimDeviceChain() throws Exception {
        historyTransaction();var card=payment(Source.CARD_TOPUP,"card-1",101,"10");card.put("sourceId","nx_payment_record:1");
        card.put("sourceVersion","saved-version");stubHistory(historyEnvelope(card,null),card);
        var cardRoot=new HashMap<String,Object>(Map.of("id",1L,"customerId",7L,"businessId","card-1","orderNo","card-order",
            "ledgerId",101L,"amount",BigDecimal.TEN,"currency","USDT","provider","PSP","providerPaymentId","provider-1","deleted",1));
        when(mapper.before(Source.CARD_TOPUP,7,"card-1")).thenReturn(List.of(cardRoot));
        when(mapper.cardSettlements("card-1")).thenReturn(List.of(Map.of("customerId",7L,"businessId","card-1",
            "orderNo","card-order","status","SETTLED","amount",BigDecimal.TEN,"provider","PSP","providerPaymentId","provider-1")));
        assertThat(service.readHistory(List.of(7L)).facts()).hasSize(1);
        cardRoot.put("amount",new BigDecimal("11"));
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("SETTLEMENT_MISMATCH");
        cardRoot.put("amount",BigDecimal.TEN);cardRoot.put("providerPaymentId","other-provider-payment");
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("SETTLEMENT_MISMATCH");
        cardRoot.put("providerPaymentId","provider-1");
        when(mapper.cardSettlements("card-1")).thenReturn(List.of());
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("MISSING_CARD_SETTLEMENT");
        var trial=payment(Source.TRIAL_CONVERT,"claim-1:CHARGE",201,"10");trial.put("sourceId","nx_order:1");
        trial.put("orderNo","trial-order");trial.put("orderType","TRIAL_CONVERT");trial.put("sourceVersion","saved-version");
        stubHistory(historyEnvelope(trial,null),trial);
        when(mapper.before(Source.TRIAL_CONVERT,7,"claim-1:CHARGE")).thenReturn(List.of(Map.of("customerId",7L,
            "businessId","claim-1:CHARGE","deviceId",4L,"status","REDEEMED","amount",BigDecimal.TEN,"successAt",at)));
        var device=Map.<String,Object>of("id",4L,"customerId",7L,"orderNo","trial-order");
        when(mapper.device(4)).thenReturn(List.of(device));
        var order=new HashMap<String,Object>(Map.of("id",1L,"customerId",7L,"businessId","trial-order","orderNo","trial-order",
            "orderType","TRIAL_CONVERT","amount",BigDecimal.TEN,"successAt",at.plusSeconds(1).withNano(100_000_000),"deleted",1));
        when(mapper.before(Source.WALLET_ORDER,7,"trial-order")).thenReturn(List.of(order));
        assertThat(service.readHistory(List.of(7L)).facts()).hasSize(1);
        trial.put("orderType","SINGLE");
        stubHistory(historyEnvelope(trial,null),trial);
        assertThat(service.readHistory(List.of(7L)).facts()).isEmpty();
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("SETTLEMENT_MISMATCH");
        trial.put("orderType","TRIAL_CONVERT");
        stubHistory(historyEnvelope(trial,null),trial);
        order.put("amount",new BigDecimal("11"));
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("SETTLEMENT_MISMATCH");
        order.put("amount",BigDecimal.TEN);order.put("successAt",at.plusSeconds(2));
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("SETTLEMENT_MISMATCH");
        order.put("successAt",at.plusSeconds(1).withNano(100_000_000));
        when(mapper.before(Source.WALLET_ORDER,7,"trial-order")).thenReturn(List.of());
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("MISSING_AUTHORITATIVE_SOURCE");
        when(mapper.before(Source.WALLET_ORDER,7,"trial-order")).thenReturn(List.of(order));
        when(mapper.device(4)).thenReturn(List.of());
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("MISSING_AUTHORITATIVE_SOURCE");
        when(mapper.device(4)).thenReturn(List.of(device,device));
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("BROKEN_SOURCE_LINK");
        when(mapper.device(4)).thenReturn(List.of(Map.of("id",4L,"customerId",8L,"orderNo","trial-order")));
        assertThat(service.readHistory(List.of(7L)).issues()).extracting(Issue::reason).containsExactly("BROKEN_SOURCE_LINK");
    }
    private Connection historyTransaction() throws Exception {
        var connection=mock(Connection.class);when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        TransactionSynchronizationManager.unbindResource(dataSource);
        TransactionSynchronizationManager.bindResource(dataSource,new ConnectionHolder(connection));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        when(history.readWithCapturedHistory(anyCollection(),any())).thenAnswer(i -> {
            SupportPaymentFactService.CapturedFinancialHistory captured=i.getArgument(1);
            return new Snapshot(captured.candidates().stream().map(SupportPaymentFactService.VerifiedCandidate::fact).toList(),
                captured.issues(),List.of(),DateTimeFormatConfig.BUSINESS_ZONE.getId(),java.time.Instant.EPOCH);
        });
        return connection;
    }
    private Map<String,Object> historyWallet() {
        var row=payment(Source.WALLET_ORDER,"order-1",201,"80");row.put("sourceId","nx_order:1");row.put("sourceVersion","saved-version");
        row.put("sourceConfirmationAt",at.plusSeconds(2));return row;
    }
    private Envelope historyEnvelope(Map<String,Object> row,String partition) throws Exception {
        var proof=sourceProof(row,partition);
        var saved=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(proof.get("sourceFactJson").toString());
        saved.put("sourceVersion","saved-version");
        var before=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(proof.get("beforeSourceJson").toString());
        before.putNull("sourceVersion");before.putArray("sourceIds");
        Fact f=SupportPaymentFactService.fact(row,Source.valueOf(row.get("source").toString()));
        return new Envelope(new Identity(f.factId(),f.customerId(),f.kind().name(),f.source().name(),f.ledgerId(),f.sourceBusinessId(),
            f.orderNo(),f.orderType(),f.originalFactId(),f.currency(),f.amount(),f.succeededAt(),DateTimeFormatConfig.BUSINESS_ZONE.getId(),
            f.successTimeField(),f.fractionalSecondDigits()),partition,at,"NEW_SUCCESS","support-payment-attribution-v1",
            json.writeValueAsString(saved),json.writeValueAsString(before),"NEW_SUCCESS","support-payment-attribution-v1");
    }
    private void stubHistory(Envelope envelope,Map<String,Object> row) {
        when(capturedHistory.readNewFinancialProofs(anyCollection())).thenReturn(List.of(envelope));
        when(mapper.historyLedgers(anyList(),anyList())).thenReturn(List.of(historyLedger(row)));
        if(Source.WALLET_ORDER.name().equals(row.get("source")))
        {
            when(mapper.payments("order-1")).thenReturn(List.of(confirmation(at.plusSeconds(2),"80")));
            when(mapper.before(Source.WALLET_ORDER,7,"order-1")).thenReturn(List.of(rawOrder("PAID",at.plusSeconds(1).withNano(100_000_000))));
        }
        when(mapper.settled(eq(Source.valueOf(row.get("source").toString())),anyList(),eq(row.get("businessId").toString()))).thenReturn(List.of(row));
    }
    private Map<String,Object> historyLedger(Map<String,Object> row) {
        var result=new HashMap<String,Object>();
        result.put("id",row.get("ledgerId"));result.put("customerId",row.get("ledgerCustomerId"));result.put("businessId",row.get("ledgerBusinessId"));
        result.put("ledgerType",row.get("ledgerType"));result.put("currency",row.get("ledgerCurrency"));result.put("amount",row.get("ledgerAmount"));
        result.put("direction",row.get("ledgerDirection"));result.put("status",row.get("ledgerStatus"));result.put("deleted",row.get("ledgerDeleted"));
        result.put("successAt",row.get("ledgerRecordedAt"));return result;
    }
    @Test void newWalletSuccessAcceptsActualCrossSecondAndZeroToSixPrecisionWithoutChangingHistory() {
        var before=wallet(false,false);
        var fact=read(before).orElseThrow();
        assertThat(before.oldSource()).isFalse();
        assertThat(fact.factId()).isEqualTo("PURCHASE:order-1");
        assertThat(fact.succeededAt()).isEqualTo(at.plusSeconds(1).withNano(100_000_000));
        assertThat(fact.ledgerRecordedAt()).isEqualTo(at);
        assertThat(fact.sourceConfirmationAt()).isEqualTo(at.plusSeconds(2));
        assertThat(fact.fractionalSecondDigits()).isEqualTo(6);
        assertThat(fact.historicalEnvironmentStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(SupportPaymentFactService.invalid(payment(Source.WALLET_ORDER,"order-1",201,"80"),Source.WALLET_ORDER))
            .isEqualTo("CONFLICTING_SUCCESS_TIME");
        verifyNoInteractions(history);
    }
    @Test void oldPaidAtEvenWithResetPendingCannotRelaxHistoricalSameSecond() {
        var before=wallet(true,false);
        assertThat(before.oldSource()).isTrue();
        assertThatThrownBy(() -> read(before)).hasMessage("CONFLICTING_SUCCESS_TIME");
    }
    @Test void existingLedgerEvenWithoutSuccessfulSourceIsOld() {
        when(mapper.ledgers("order-1")).thenAnswer(remember(Marker.LEDGER,List.of(Map.of("id",201L,"customerId",7L))));
        var before=wallet(false,false);
        assertThat(before.oldSource()).isTrue();
        assertThat(before.existingLedgerId()).isEqualTo(201L);
        assertThatThrownBy(() -> read(before)).hasMessage("CONFLICTING_SUCCESS_TIME");
    }
    @Test void oldCompleteWalletPaymentRetainsExistingFactMetadataAfterCurrentConfirmation() {
        when(mapper.before(Source.WALLET_ORDER,7,"order-1")).thenAnswer(rememberBefore(Source.WALLET_ORDER,7,"order-1",List.of(rawOrder("PAID",at))));
        when(mapper.ledgers("order-1")).thenAnswer(remember(Marker.LEDGER,List.of(Map.of("id",201L,"customerId",7L,"successAt",at))));
        var row=payment(Source.WALLET_ORDER,"order-1",201,"80");row.put("succeededAt",at);row.remove("sourceConfirmationAt");
        when(mapper.settled(eq(Source.WALLET_ORDER),anyList(),eq("order-1"))).thenReturn(List.of(row));
        when(mapper.payments("order-1")).thenAnswer(remember(Marker.PAYMENT,List.of(confirmation(at,"80"))));
        var before=service.beforeSource(7,Source.WALLET_ORDER,"order-1");
        assertThat(before.oldSource()).isTrue();
        assertThat(before.existingFactId()).isEqualTo("PURCHASE:order-1");
        assertThat(before.existingLedgerId()).isEqualTo(201L);
        assertThat(before.existingSuccessAt()).isEqualTo(at);
        assertThat(row).doesNotContainKey("sourceConfirmationAt");
    }
    @Test void forgedForeignServiceCrossResourceAndCompletedTokensAreRejected() {
        assertThatThrownBy(() -> service.readSettled(mock(BeforeSource.class))).hasMessage("INVALID_BEFORE_SOURCE_TRANSACTION");
        var before=wallet(false,false);
        var other=new SupportPaymentSourceService(mapper,history,dataSource,json,capturedHistory);
        assertThatThrownBy(() -> other.readSettled(before)).hasMessage("INVALID_BEFORE_SOURCE_TRANSACTION");
        TransactionSynchronizationManager.unbindResource(dataSource);
        TransactionSynchronizationManager.bindResource(dataSource,new Object());
        assertThatThrownBy(() -> service.readSettled(before)).hasMessage("INVALID_BEFORE_SOURCE_TRANSACTION");
        TransactionSynchronizationManager.unbindResource(dataSource);
        TransactionSynchronizationManager.bindResource(dataSource,resource);
        assertThat(read(before)).isPresent();
        for(var synchronization:TransactionSynchronizationManager.getSynchronizations())
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        assertThatThrownBy(() -> service.readSettled(before)).hasMessage("INVALID_BEFORE_SOURCE_TRANSACTION");
    }
    @Test void noActualTransactionReadOnlyAndMissingResourceCannotPrepare() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> service.beforeSource(7,Source.WALLET_ORDER,"order-1")).hasMessage("WRITABLE_TRANSACTION_REQUIRED");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> service.beforeSource(7,Source.WALLET_ORDER,"order-1")).hasMessage("WRITABLE_TRANSACTION_REQUIRED");
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.unbindResource(dataSource);
        assertThatThrownBy(() -> service.beforeSource(7,Source.WALLET_ORDER,"order-1")).hasMessage("TRANSACTION_DATASOURCE_REQUIRED");
    }
    @Test void sourceFailuresMissingSourceWrongCustomerAndWrongSourcePropagate() {
        var before=wallet(false,false);
        when(mapper.settled(eq(Source.WALLET_ORDER),anyList(),eq("order-1")))
            .thenThrow(new DataAccessResourceFailureException("source failed"));
        assertThatThrownBy(() -> read(before)).isInstanceOf(DataAccessResourceFailureException.class);
        when(mapper.settled(eq(Source.WALLET_ORDER),anyList(),eq("order-1"))).thenReturn(List.of());
        assertThatThrownBy(() -> read(before)).hasMessage("MISSING_SETTLED_SOURCE");
        var wrong=payment(Source.TRADE_IN,"order-1",201,"80");
        when(mapper.settled(eq(Source.WALLET_ORDER),anyList(),eq("order-1"))).thenReturn(List.of(wrong));
        assertThatThrownBy(() -> read(before)).hasMessage("SOURCE_IDENTITY_MISMATCH");
        wrong.put("source",Source.WALLET_ORDER.name());wrong.put("customerId",8L);
        assertThatThrownBy(() -> read(before)).hasMessage("SOURCE_IDENTITY_MISMATCH");
    }
    @Test void onlyExplicitSandboxOrZeroActualPurchaseMayReturnEmpty() {
        var before=wallet(false,true);
        assertThat(read(before)).isEmpty();
        var zero=payment(Source.WALLET_ORDER,"order-1",0,"0");
        when(mapper.settled(eq(Source.WALLET_ORDER),anyList(),eq("order-1"))).thenReturn(List.of(zero));
        assertThat(read(before)).isEmpty();
        zero.put("ledgerAmount",new BigDecimal("1"));
        assertThatThrownBy(() -> read(before)).hasMessage("MISSING_SETTLED_SOURCE");
    }
    @Test void cardProviderConfirmedIsNotWalletSettlementAndProcessingIsNotOld() {
        String key="card-1";
        when(mapper.before(Source.CARD_TOPUP,7,key)).thenAnswer(rememberBefore(Source.CARD_TOPUP,7,key,List.of(Map.of("id",1L,"customerId",7L,"businessId",key,"status","CONFIRMED"))));
        var receipt=new HashMap<String,Object>(Map.of("id",2L,"customerId",7L,"status","PROCESSING","orderNo","card-order",
            "provider","PSP","providerPaymentId","provider-1","amount",new BigDecimal("10")));
        when(mapper.cardSettlements(key)).thenAnswer(remember(Marker.CARD_SETTLEMENT,List.of(receipt)));
        var row=payment(Source.CARD_TOPUP,key,101,"10");row.put("sourceOrderNo","card-order");row.put("provider","PSP");row.put("providerPaymentId","provider-1");
        when(mapper.settled(eq(Source.CARD_TOPUP),anyList(),eq(key))).thenReturn(List.of(row));
        var before=service.beforeSource(7,Source.CARD_TOPUP,key);
        assertThat(before.oldSource()).isFalse();
        assertThatThrownBy(() -> read(before)).hasMessage("MISSING_CARD_SETTLEMENT");
        receipt.put("status","SETTLED");
        assertThat(read(before)).isPresent();
    }
    @Test void trialAliasBecomesCanonicalClaimAndMissingClaimCannotPretendEmptySuccess() {
        when(mapper.before(Source.TRIAL_CONVERT,7,"USER:7")).thenAnswer(rememberBefore(Source.TRIAL_CONVERT,7,"USER:7",List.of(Map.of("id",5L,"customerId",7L,"businessId","claim-1:CHARGE","status","ACTIVE"))));
        var before=service.beforeSource(7,Source.TRIAL_CONVERT,"USER:7");
        assertThat(before.stableBusinessKey()).isEqualTo("claim-1:CHARGE");
        assertThat(before.oldSource()).isFalse();
        assertThatThrownBy(() -> service.beforeSource(7,Source.TRIAL_CONVERT,"USER:8")).isInstanceOf(IllegalArgumentException.class);
        when(mapper.before(Source.TRIAL_CONVERT,7,"USER:7")).thenAnswer(rememberBefore(Source.TRIAL_CONVERT,7,"USER:7",List.of()));
        var missing=service.beforeSource(7,Source.TRIAL_CONVERT,"USER:7");
        assertThat(missing.oldSource()).isTrue();
        assertThatThrownBy(() -> service.readSettled(missing)).hasMessage("MISSING_TRIAL_CLAIM");
    }
    @Test void cregisNeedsExactProjectAndOriginalEventLedgerAndAmount() {
        String key="CR-23";
        when(mapper.cregisEvents(100,23)).thenAnswer(remember(Marker.CREGIS_EVENT,List.of()));
        when(mapper.before(Source.DEPOSIT_ORDER,7,key)).thenAnswer(rememberBefore(Source.DEPOSIT_ORDER,7,key,List.of(),List.of(Map.of("id",1L,"customerId",7L,"status","CREDITED"))));
        var row=payment(Source.DEPOSIT_ORDER,key,101,"10");
        when(mapper.settled(eq(Source.DEPOSIT_ORDER),anyList(),eq(key))).thenReturn(List.of(row));
        var before=service.beforeSource(7,Source.DEPOSIT_ORDER,key,"100");
        assertThat(before.sourcePartition()).isEqualTo("100");
        assertThatThrownBy(() -> read(before)).hasMessage("BROKEN_CREGIS_EVENT_LINK");
        when(mapper.cregisEvents(100,23)).thenAnswer(remember(Marker.CREGIS_EVENT,List.of(Map.of("id",3L,"customerId",7L,"projectId",100L,"cid",23L,
            "ledgerId",101L,"amount",new BigDecimal("10"),"status","CREDITED"))));
        assertThat(read(before)).isPresent();
        assertThatThrownBy(() -> service.beforeSource(7,Source.DEPOSIT_ORDER,key)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void orphanVietqrRequiresTrustedCurrentIntentAndThenActualCustomerAndIntentLink() {
        String key="D1-VIETQR-receipt-1",intent="intent-1";
        when(mapper.before(Source.VIETQR,7,key)).thenAnswer(rememberBefore(Source.VIETQR,7,key,
            List.of(Map.of("id",1L,"businessId",key,"status","OPEN","viewType","ORPHAN")),
            List.of(Map.of("id",1L,"customerId",7L,"businessId",key,"status","CREDITED","intentNo",intent))));
        when(mapper.intent(intent)).thenAnswer(remember(Marker.INTENT,List.of(intent("MANUAL","RECEIPT_REVIEW","0")),List.of(intent("MANUAL","CREDITED","10"))));
        var row=payment(Source.VIETQR,key,101,"10");row.put("duplicateKey",intent);
        when(mapper.settled(eq(Source.VIETQR),anyList(),eq(key))).thenReturn(List.of(row));
        var before=service.beforeSource(7,Source.VIETQR,key,intent);
        assertThat(before.oldSource()).isFalse();
        assertThat(read(before)).isPresent();
        when(mapper.ledgers(intent)).thenAnswer(remember(Marker.LEDGER,List.of(Map.of("id",102L,"customerId",7L))));
        assertThatThrownBy(() -> read(before)).hasMessage("DUPLICATE_PAYMENT_SOURCE");
        when(mapper.intent(intent)).thenAnswer(remember(Marker.INTENT,List.of(intent("HDPAY","CREDITED","10"))));
        assertThatThrownBy(() -> read(before)).hasMessage("BROKEN_INTENT_IDENTITY");
        verify(mapper,never()).settled(eq(Source.HDPAY),anyList(),anyString());
    }
    @Test void wrongOrphanIntentCustomerExistingBindingAndMissingIntentFailBeforeCapture() {
        String key="D1-VIETQR-receipt-1";
        when(mapper.before(Source.VIETQR,7,key)).thenAnswer(rememberBefore(Source.VIETQR,7,key,List.of(Map.of("id",1L,"customerId",8L,"status","OPEN","intentNo","intent-1"))));
        assertThatThrownBy(() -> service.beforeSource(7,Source.VIETQR,key,"intent-1")).hasMessage("SOURCE_CUSTOMER_MISMATCH");
        when(mapper.before(Source.VIETQR,7,key)).thenAnswer(rememberBefore(Source.VIETQR,7,key,List.of(Map.of("id",1L,"status","OPEN","viewType","ORPHAN"))));
        assertThatThrownBy(() -> service.beforeSource(7,Source.VIETQR,key,"intent-1")).hasMessage("MISSING_INTENT_IDENTITY");
        when(mapper.intent("intent-1")).thenAnswer(remember(Marker.INTENT,List.of(intent("HDPAY","AWAITING_PAYMENT","0"))));
        assertThatThrownBy(() -> service.beforeSource(7,Source.VIETQR,key,"intent-1")).hasMessage("BROKEN_INTENT_IDENTITY");
    }
    @Test void refundRetainsOriginalCustomerCurrencyChronologyAndCumulativeAmountProtection() {
        String key="E4-REFUND-order-1";
        when(mapper.before(Source.ORDER_REFUND,7,key)).thenAnswer(rememberBefore(Source.ORDER_REFUND,7,key,List.of(rawOrder("PAID",at)),List.of(rawOrder("REFUNDED",at))));
        when(mapper.before(Source.WALLET_ORDER,7,"order-1")).thenAnswer(rememberBefore(Source.WALLET_ORDER,7,"order-1",List.of(rawOrder("PAID",at))));
        var original=payment(Source.WALLET_ORDER,"order-1",201,"80");original.put("succeededAt",at);original.put("ledgerRecordedAt",at);
        when(mapper.settled(eq(Source.WALLET_ORDER),anyList(),eq("order-1"))).thenReturn(List.of(original));
        when(mapper.payments("order-1")).thenAnswer(remember(Marker.PAYMENT,List.of(confirmation(at,"80"))));
        var refund=payment(Source.ORDER_REFUND,key,301,"30");refund.put("orderNo","order-1");
        when(mapper.settled(eq(Source.ORDER_REFUND),anyList(),eq(key))).thenReturn(List.of(refund));
        when(mapper.refunds(anyList(),eq("order-1"))).thenReturn(List.of(refund));
        var before=service.beforeSource(7,Source.ORDER_REFUND,key);
        assertThat(before.oldSource()).isFalse();
        assertThat(read(before).orElseThrow().originalFactId()).isEqualTo("PURCHASE:order-1");
        var second=payment(Source.ORDER_REFUND,key,302,"60");second.put("orderNo","order-1");
        when(mapper.refunds(anyList(),eq("order-1"))).thenReturn(List.of(refund,second));
        assertThatThrownBy(() -> read(before)).hasMessage("REFUND_EXCEEDS_ORIGINAL_AMOUNT");
        // Keep the fresh refund tuple intact; move the original success later to test chronology.
        original.put("succeededAt",at.plusSeconds(1));original.put("ledgerRecordedAt",at.plusSeconds(1));
        when(mapper.before(Source.WALLET_ORDER,7,"order-1")).thenAnswer(rememberBefore(Source.WALLET_ORDER,7,"order-1",List.of(rawOrder("PAID",at.plusSeconds(1)))));
        when(mapper.payments("order-1")).thenAnswer(remember(Marker.PAYMENT,List.of(confirmation(at.plusSeconds(1),"80"))));
        when(mapper.refunds(anyList(),eq("order-1"))).thenReturn(List.of(refund));
        assertThatThrownBy(() -> read(before)).hasMessage("REFUND_PREDATES_ORIGINAL_PAYMENT");
        refund.put("customerId",8L);
        assertThatThrownBy(() -> read(before)).hasMessage("SOURCE_IDENTITY_MISMATCH");
    }
    @Test void historicalOldRefundOfCrossSecondPurchaseNeedsItsOriginalNewProofAndKeepsRefundLimits() throws Exception {
        var original=payment(Source.WALLET_ORDER,"order-1",201,"80");original.put("sourceConfirmationAt",at.plusSeconds(2));
        var refund=payment(Source.ORDER_REFUND,"E4-REFUND-order-1",301,"30");refund.put("orderNo","order-1");
        refund.put("succeededAt",at.plusSeconds(3));refund.put("ledgerRecordedAt",at.plusSeconds(3));
        var before=historicalRefundOfCrossSecond(original,refund);
        assertThat(before.oldSource()).isTrue();
        assertThatThrownBy(() -> read(before)).hasMessage("CONFLICTING_SUCCESS_TIME");
        var proof=sourceProof(original);
        when(mapper.originalSourceProof("PURCHASE:order-1")).thenReturn(proof);
        assertThat(read(before).orElseThrow().originalFactId()).isEqualTo("PURCHASE:order-1");
        proof.put("captureMode","OLD_SOURCE");
        assertThatThrownBy(() -> read(before)).hasMessage("CONFLICTING_SUCCESS_TIME");
        proof.put("captureMode","NEW_SUCCESS");
        var savedBefore=json.createObjectNode();savedBefore.put("oldSource",true);
        proof.put("beforeSourceJson",json.writeValueAsString(savedBefore));
        assertThatThrownBy(() -> read(before)).hasMessage("CONFLICTING_SUCCESS_TIME");
        proof.putAll(sourceProof(original));
        var second=payment(Source.ORDER_REFUND,"E4-REFUND-order-1",302,"60");second.put("orderNo","order-1");
        second.put("succeededAt",at.plusSeconds(3));second.put("ledgerRecordedAt",at.plusSeconds(3));
        when(mapper.refunds(anyList(),eq("order-1"))).thenReturn(List.of(refund,second));
        assertThatThrownBy(() -> read(before)).hasMessage("REFUND_EXCEEDS_ORIGINAL_AMOUNT");
        refund.put("succeededAt",at);refund.put("ledgerRecordedAt",at);
        when(mapper.refunds(anyList(),eq("order-1"))).thenReturn(List.of(refund));
        assertThatThrownBy(() -> read(before)).hasMessage("REFUND_PREDATES_ORIGINAL_PAYMENT");
    }
    @Test void historicalOldRefundRejectsForgedOriginalAmountCustomerLedgerAndTimes() throws Exception {
        var original=payment(Source.WALLET_ORDER,"order-1",201,"80");original.put("sourceConfirmationAt",at.plusSeconds(2));
        var refund=payment(Source.ORDER_REFUND,"E4-REFUND-order-1",301,"30");refund.put("orderNo","order-1");
        refund.put("succeededAt",at.plusSeconds(3));refund.put("ledgerRecordedAt",at.plusSeconds(3));
        var before=historicalRefundOfCrossSecond(original,refund);
        assertThat(before.oldSource()).isTrue();
        for(String field:List.of("amount","customerId","ledgerId","succeededAt","ledgerRecordedAt","sourceConfirmationAt")) {
            var proof=sourceProof(original);
            var saved=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(proof.get("sourceFactJson").toString());
            if(field.equals("amount"))saved.put(field,new BigDecimal("80.000001"));
            else if(field.equals("customerId") || field.equals("ledgerId"))saved.put(field,999L);
            else saved.put(field,"2026-10-08T12:01:00.000000");
            proof.put("sourceFactJson",json.writeValueAsString(saved));
            when(mapper.originalSourceProof("PURCHASE:order-1")).thenReturn(proof);
            assertThatThrownBy(() -> read(before)).hasMessage("CONFLICTING_SUCCESS_TIME");
        }
        var proof=sourceProof(original);
        when(mapper.originalSourceProof("PURCHASE:order-1")).thenReturn(proof);
        original.put("ledgerAmount",new BigDecimal("81"));
        assertThatThrownBy(() -> read(before)).hasMessage("SETTLEMENT_MISMATCH");
    }
    @Test void newCrossSecondFactCanBeReadByFreshOldPreparedOnlyWithPersistedOriginalNewProof() throws Exception {
        var fresh=wallet(false,false);
        Fact first=read(fresh).orElseThrow();
        var row=payment(Source.WALLET_ORDER,"order-1",201,"80");row.put("sourceConfirmationAt",first.sourceConfirmationAt());
        var proof=sourceProof(row);
        when(mapper.before(Source.WALLET_ORDER,7,"order-1")).thenAnswer(rememberBefore(Source.WALLET_ORDER,7,"order-1",List.of(rawOrder("PAID",first.succeededAt()))));
        when(mapper.settled(eq(Source.WALLET_ORDER),anyList(),eq("order-1"))).thenReturn(List.of(row));
        when(mapper.payments("order-1")).thenAnswer(remember(Marker.PAYMENT,List.of(confirmation(first.sourceConfirmationAt(),"80"))));
        when(mapper.ledgers("order-1")).thenAnswer(remember(Marker.LEDGER,List.of(Map.of("id",201L,"customerId",7L,"successAt",at))));
        var old=service.beforeSource(7,Source.WALLET_ORDER,"order-1");
        assertThat(old.oldSource()).isTrue();
        assertThatThrownBy(() -> service.readSettled(old)).hasMessage("CONFLICTING_SUCCESS_TIME");
        when(mapper.originalSourceProof(first.factId())).thenReturn(proof);
        assertThat(service.readSettled(old)).contains(first);
        assertThat(old.oldSource()).isTrue();
        proof.put("captureMode","OLD_SOURCE");
        assertThatThrownBy(() -> service.readSettled(old)).hasMessage("CONFLICTING_SUCCESS_TIME");
    }
    @Test void capturedDepositReplayUsesSameExactProjectPartitionAndRejectsDifferentPartitionProof() throws Exception {
        String key="CR-23";
        var row=payment(Source.DEPOSIT_ORDER,key,101,"10");row.put("succeededAt",at.plusSeconds(1));
        row.put("successTimeField","nx_deposit_order.credited_at");row.remove("sourceConfirmationAt");
        when(mapper.before(Source.DEPOSIT_ORDER,7,key)).thenAnswer(rememberBefore(Source.DEPOSIT_ORDER,7,key,List.of(Map.of("id",1L,"customerId",7L,"businessId",key,"status","CREDITED","ledgerId",101L,"successAt",at.plusSeconds(1)))));
        when(mapper.ledgers(key)).thenAnswer(remember(Marker.LEDGER,List.of(Map.of("id",101L,"customerId",7L,"successAt",at))));
        when(mapper.cregisEvents(100,23)).thenAnswer(remember(Marker.CREGIS_EVENT,List.of(Map.of("id",3L,"customerId",7L,"projectId",100L,"cid",23L,
            "ledgerId",101L,"amount",new BigDecimal("10"),"status","CREDITED"))));
        when(mapper.settled(eq(Source.DEPOSIT_ORDER),anyList(),eq(key))).thenReturn(List.of(row));
        var before=service.beforeSource(7,Source.DEPOSIT_ORDER,key,"100");
        assertThat(before.oldSource()).isTrue();
        var proof=sourceProof(row,"100");
        when(mapper.originalSourceProof("DEPOSIT:101")).thenReturn(proof);
        assertThat(read(before).orElseThrow().factId()).isEqualTo("DEPOSIT:101");
        proof.put("sourcePartition","101");
        assertThatThrownBy(() -> read(before)).hasMessage("CONFLICTING_SUCCESS_TIME");
        proof.putAll(sourceProof(row,"100"));
        var savedBefore=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(proof.get("beforeSourceJson").toString());
        savedBefore.put("sourcePartition","101");proof.put("beforeSourceJson",json.writeValueAsString(savedBefore));
        assertThatThrownBy(() -> read(before)).hasMessage("CONFLICTING_SUCCESS_TIME");
    }
    @Test void aMissingSnapshotIsNotFreshEvidenceAndLegacyReaderCannotAuthorizeNewSuccess() {
        var before=wallet(false,false);
        assertThatThrownBy(() -> service.readSettled(before)).hasMessage("FRESH_LEDGER_RECEIPT_REQUIRED");
        verify(mapper,never()).insertLedger(any(),anyMap());
        when(mapper.before(Source.WALLET_ORDER,7,"missing-order")).thenReturn(List.of());
        assertThatThrownBy(() -> service.beforeSource(7,Source.WALLET_ORDER,"missing-order"))
            .hasMessage("MISSING_SOURCE_IDENTITY");
    }
    @Test void actualCurrentPaidMarkerOverridesAnOldPendingPlanningSnapshot() {
        wallet(false,false);
        when(mapper.currentBefore(eq(Source.WALLET_ORDER),eq(7L),eq("order-1"),eq(1L)))
            .thenReturn(List.of(rawOrder("PENDING",at)));
        var before=service.beforeSource(7,Source.WALLET_ORDER,"order-1");
        assertThat(before.oldSource()).isTrue();
        assertThatThrownBy(() -> service.insertFreshPaymentLedger(before,BigDecimal.TEN,BigDecimal.TEN,"remark"))
            .hasMessage("FRESH_LEDGER_NOT_ALLOWED");
        verify(mapper,never()).insertLedger(any(),anyMap());
    }
    @Test void zeroInsertAndMissingGeneratedKeyNeverMintAReceipt() {
        var before=wallet(false,false);
        doReturn(0).when(mapper).insertLedger(any(),anyMap());
        assertThatThrownBy(() -> service.insertFreshPaymentLedger(before,BigDecimal.TEN,BigDecimal.TEN,"remark"))
            .hasMessage("FRESH_LEDGER_INSERT_REQUIRED");
        doReturn(1).when(mapper).insertLedger(any(),anyMap());
        assertThatThrownBy(() -> service.insertFreshPaymentLedger(before,BigDecimal.TEN,BigDecimal.TEN,"remark"))
            .hasMessage("FRESH_LEDGER_GENERATED_KEY_REQUIRED");
        assertThatThrownBy(() -> service.readSettled(before,() -> 201L)).hasMessage("INVALID_FRESH_LEDGER_RECEIPT");
    }
    @Test void duplicateAndOrdinaryStorageFailuresPropagateWithoutSuccessReceipt() {
        var before=wallet(false,false);
        for(var error:List.of(new org.springframework.dao.DuplicateKeyException("duplicate"),
                new DataAccessResourceFailureException("database unavailable"))) {
            doThrow(error).when(mapper).insertLedger(any(),anyMap());
            assertThatThrownBy(() -> service.insertFreshPaymentLedger(before,BigDecimal.TEN,BigDecimal.TEN,"remark"))
                .isSameAs(error);
            assertThatThrownBy(() -> service.readSettled(before,() -> 201L)).hasMessage("INVALID_FRESH_LEDGER_RECEIPT");
        }
    }
    @Test void receiptRejectsForgeryOtherBeforeServicePhysicalTransactionAndExpiredLifetime() {
        var before=wallet(false,false);
        var receipt=service.insertFreshPaymentLedger(before,new BigDecimal("80"),BigDecimal.TEN,"remark");
        assertThat(receipt.ledgerId()).isEqualTo(201L);
        assertThatThrownBy(() -> service.readSettled(before,() -> receipt.ledgerId())).hasMessage("INVALID_FRESH_LEDGER_RECEIPT");
        var otherBefore=service.beforeSource(7,Source.CARD_TOPUP,"other-card");
        assertThatThrownBy(() -> service.readSettled(otherBefore,receipt)).hasMessage("INVALID_FRESH_LEDGER_RECEIPT");
        var other=new SupportPaymentSourceService(mapper,history,dataSource,json,capturedHistory);
        assertThatThrownBy(() -> other.readSettled(before,receipt)).hasMessage("INVALID_BEFORE_SOURCE_TRANSACTION");
        TransactionSynchronizationManager.unbindResource(dataSource);TransactionSynchronizationManager.bindResource(dataSource,new Object());
        assertThatThrownBy(() -> service.readSettled(before,receipt)).hasMessage("INVALID_BEFORE_SOURCE_TRANSACTION");
        TransactionSynchronizationManager.unbindResource(dataSource);TransactionSynchronizationManager.bindResource(dataSource,resource);
        assertThat(service.readSettled(before,receipt)).isPresent();
        assertThatThrownBy(() -> service.insertFreshPaymentLedger(before,new BigDecimal("80"),BigDecimal.TEN,"remark"))
            .hasMessage("FRESH_LEDGER_NOT_ALLOWED");
        for(var synchronization:TransactionSynchronizationManager.getSynchronizations())
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        assertThatThrownBy(() -> service.readSettled(before,receipt)).hasMessage("INVALID_BEFORE_SOURCE_TRANSACTION");
    }
    @Test void receiptRechecksTheCompleteWrittenTupleAndActualProjectedLedger() {
        var before=wallet(false,false);
        var receipt=service.insertFreshPaymentLedger(before,new BigDecimal("80"),BigDecimal.TEN,"remark");
        var written=observed.get(Marker.LEDGER+":201");
        for(String key:List.of("id","customerId","businessId","ledgerType","currency","direction","status","deleted",
                "amount","balanceAfter","remark","successAt","updatedAt")) {
            var original=written.get(key);written.put(key,"changed");
            assertThatThrownBy(() -> service.readSettled(before,receipt)).hasMessage("FRESH_LEDGER_WRITE_MISMATCH");
            written.put(key,original);
        }
        var row=payment(Source.WALLET_ORDER,"order-1",202,"80");
        when(mapper.settled(eq(Source.WALLET_ORDER),anyList(),eq("order-1"))).thenReturn(List.of(row));
        assertThatThrownBy(() -> service.readSettled(before,receipt)).hasMessage("FRESH_LEDGER_SOURCE_MISMATCH");
    }
    @Test void missingNecessaryChainCannotBecomeSuccessDespiteTheFreshLedger() {
        var before=wallet(false,false);
        var receipt=service.insertFreshPaymentLedger(before,new BigDecimal("80"),BigDecimal.TEN,"remark");
        var row=payment(Source.WALLET_ORDER,"order-1",201,"80");row.remove("sourceRootId");
        when(mapper.settled(eq(Source.WALLET_ORDER),anyList(),eq("order-1"))).thenReturn(List.of(row));
        assertThatThrownBy(() -> service.readSettled(before,receipt)).hasMessage("MISSING_SETTLED_SOURCE");
    }
    @Test void afterCurrentVietqrCounterpartRejectsCommittedLedgerHiddenByOldPlanningView() {
        String key="D1-VIETQR-receipt-1",intent="intent-1";
        when(mapper.before(Source.VIETQR,7,key)).thenAnswer(rememberBefore(Source.VIETQR,7,key,
            List.of(Map.of("id",1L,"status","OPEN","viewType","ORPHAN")),
            List.of(Map.of("id",1L,"customerId",7L,"status","CREDITED","intentNo",intent))));
        when(mapper.intent(intent)).thenAnswer(remember(Marker.INTENT,
            List.of(intent("MANUAL","RECEIPT_REVIEW","0")),List.of(intent("MANUAL","CREDITED","10"))));
        when(mapper.ledgers(intent)).thenReturn(List.of());
        var row=payment(Source.VIETQR,key,101,"10");row.put("duplicateKey",intent);
        when(mapper.settled(eq(Source.VIETQR),anyList(),eq(key))).thenReturn(List.of(row));
        var before=service.beforeSource(7,Source.VIETQR,key,intent);
        verify(mapper,never()).settledVietqrCounterpart(anyString());
        when(mapper.settledVietqrCounterpart(intent)).thenReturn(List.of(Map.of("id",102L,"customerId",7L)));
        assertThatThrownBy(() -> read(before)).hasMessage("DUPLICATE_PAYMENT_SOURCE");
        when(mapper.settledVietqrCounterpart(intent)).thenReturn(List.of(Map.of("id",102L,"customerId",8L)));
        assertThatThrownBy(() -> read(before)).hasMessage("SOURCE_CUSTOMER_MISMATCH");
    }
    @SafeVarargs
    private final Answer<List<Map<String,Object>>> remember(Marker marker,List<Map<String,Object>>... results) {
        int[] next={0};return invocation -> {
            var rows=results[Math.min(next[0]++,results.length-1)];
            for(var row:rows) observed.put(marker+":"+row.get("id"),row);
            return rows;
        };
    }
    @SafeVarargs
    private final Answer<List<Map<String,Object>>> rememberBefore(Source source,long customer,String key,List<Map<String,Object>>... results) {
        int[] next={0};return invocation -> {
            var rows=results[Math.min(next[0]++,results.length-1)].stream().map(row -> {
                var copy=new HashMap<>(row);
                copy.putIfAbsent("businessId",source==Source.ORDER_REFUND?SupportPaymentSourceSql.rawKey(source,key):key);
                observed.put("ROOT:"+source+":"+copy.get("id"),copy);return (Map<String,Object>)copy;
            }).toList();return rows;
        };
    }
    private Optional<Fact> read(BeforeSource before) {
        if(before.oldSource()) return service.readSettled(before);
        FreshLedgerReceipt receipt=receipts.computeIfAbsent(before,b -> service.insertFreshPaymentLedger(b,
            new BigDecimal(b.source()==Source.WALLET_ORDER?"80":b.source()==Source.ORDER_REFUND?"30":"10"),BigDecimal.TEN,"original remark"));
        return service.readSettled(before,receipt);
    }
    private BeforeSource historicalRefundOfCrossSecond(Map<String,Object> original,Map<String,Object> refund) {
        String key="E4-REFUND-order-1";
        // These tests read an existing refund and validate its saved original payment proof.
        when(mapper.before(Source.ORDER_REFUND,7,key)).thenAnswer(rememberBefore(Source.ORDER_REFUND,7,key,List.of(rawOrder("REFUNDED",at))));
        when(mapper.before(Source.WALLET_ORDER,7,"order-1")).thenAnswer(rememberBefore(Source.WALLET_ORDER,7,"order-1",List.of(rawOrder("PAID",at))));
        when(mapper.settled(eq(Source.WALLET_ORDER),anyList(),eq("order-1"))).thenReturn(List.of(original));
        when(mapper.payments("order-1")).thenAnswer(remember(Marker.PAYMENT,List.of(confirmation(at.plusSeconds(2),"80"))));
        when(mapper.settled(eq(Source.ORDER_REFUND),anyList(),eq(key))).thenReturn(List.of(refund));
        when(mapper.refunds(anyList(),eq("order-1"))).thenReturn(List.of(refund));
        return service.beforeSource(7,Source.ORDER_REFUND,key);
    }
    private Map<String,Object> sourceProof(Map<String,Object> original) throws Exception {
        return sourceProof(original,null);
    }
    private Map<String,Object> sourceProof(Map<String,Object> original,String partition) throws Exception {
        Fact f=SupportPaymentFactService.fact(original,Source.valueOf(original.get("source").toString()));
        var format=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");
        var saved=new LinkedHashMap<String,Object>();
        saved.put("factId",f.factId());saved.put("kind",f.kind());saved.put("source",f.source());saved.put("sourceIds",f.sourceIds());
        saved.put("customerId",f.customerId());saved.put("ledgerId",f.ledgerId());saved.put("sourceBusinessId",f.sourceBusinessId());
        saved.put("orderNo",f.orderNo());saved.put("orderType",f.orderType());saved.put("originalFactId",f.originalFactId());
        saved.put("currency",f.currency());saved.put("amount",f.amount());saved.put("succeededAt",format.format(f.succeededAt()));
        saved.put("ledgerRecordedAt",format.format(f.ledgerRecordedAt()));saved.put("sourceConfirmationAt",f.sourceConfirmationAt()==null?null:format.format(f.sourceConfirmationAt()));
        saved.put("providerPaidAt",f.providerPaidAt()==null?null:format.format(f.providerPaidAt()));saved.put("successTimeField",f.successTimeField());saved.put("fractionalSecondDigits",f.fractionalSecondDigits());
        saved.put("historicalEnvironmentStatus",f.historicalEnvironmentStatus());saved.put("businessZone",DateTimeFormatConfig.BUSINESS_ZONE.getId());
        saved.put("succeededAtInstant",format.format(f.succeededAt().atZone(DateTimeFormatConfig.BUSINESS_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime())+"Z");
        var before=new LinkedHashMap<String,Object>();
        before.put("customerId",f.customerId());before.put("source",f.source());before.put("stableBusinessKey",f.sourceBusinessId());
        before.put("sourcePartition",partition);before.put("oldSource",false);before.put("existingLedgerId",null);before.put("existingFactId",null);
        before.put("existingSuccessAt",null);before.put("businessZone",DateTimeFormatConfig.BUSINESS_ZONE.getId());
        before.put("successTimeField",f.successTimeField());before.put("fractionalSecondDigits",f.fractionalSecondDigits());
        var result=new HashMap<String,Object>(Map.of("captureMode","NEW_SUCCESS","schemaVersion","support-payment-attribution-v1",
            "evidenceCaptureMode","NEW_SUCCESS","evidenceSchemaVersion","support-payment-attribution-v1",
            "sourceFactJson",json.writeValueAsString(saved),"beforeSourceJson",json.writeValueAsString(before)));
        if(partition!=null)result.put("sourcePartition",partition);
        return result;
    }
    private BeforeSource wallet(boolean old,boolean sandbox) {
        when(mapper.before(Source.WALLET_ORDER,7,"order-1"))
            .thenAnswer(rememberBefore(Source.WALLET_ORDER,7,"order-1",List.of(rawOrder("PENDING",old?at:null)),List.of(rawOrder("PAID",at.plusSeconds(1)))));
        var row=payment(Source.WALLET_ORDER,"order-1",201,"80");
        if(sandbox) row.put("excludedEnvironment",1);
        when(mapper.settled(eq(Source.WALLET_ORDER),anyList(),eq("order-1"))).thenReturn(List.of(row));
        when(mapper.payments("order-1")).thenAnswer(remember(Marker.PAYMENT,List.of(),List.of(confirmation(at.plusSeconds(2),"80"))));
        return service.beforeSource(7,Source.WALLET_ORDER,"order-1");
    }
    private Map<String,Object> rawOrder(String status,LocalDateTime paid) {
        var row=new HashMap<String,Object>(Map.of("id",1L,"customerId",7L,"businessId","order-1","orderNo","order-1",
            "orderType","SINGLE","paymentNo","payment-1","status",status));
        row.put("amount",new BigDecimal("80"));
        if(paid!=null) row.put("successAt",paid);
        return row;
    }
    private Map<String,Object> confirmation(LocalDateTime paid,String amount) {
        return Map.of("id",2L,"customerId",7L,"businessId","payment-1","orderNo","order-1","provider","NEXGRID_WALLET",
            "currency","USDT","amount",new BigDecimal(amount),"successAt",paid,"status","PAID");
    }
    private Map<String,Object> intent(String rail,String status,String amount) {
        return Map.of("id",1L,"customerId",7L,"businessId","intent-1","rail",rail,"target","WALLET_TOPUP",
            "status",status,"amount",new BigDecimal(amount));
    }
    private Map<String,Object> payment(Source source,String business,long ledger,String amount) {
        Kind kind=source==Source.ORDER_REFUND?Kind.DEVICE_PURCHASE_REFUND:source==Source.WALLET_ORDER || source==Source.TRADE_IN
            || source==Source.CAPACITY_KEEP || source==Source.TRIAL_CONVERT?Kind.DEVICE_PURCHASE:Kind.DEPOSIT;
        var row=new HashMap<String,Object>();
        row.put("sourceRootId",1L);row.put("deviceId",4L);row.put("claimId",5L);row.put("intentId",1L);
        row.put("source",source.name());row.put("kind",kind.name());row.put("sourceId",source+":"+ledger);
        row.put("customerId",7L);row.put("businessId",business);row.put("duplicateKey",business);
        row.put("ledgerCustomerId",7L);row.put("ledgerId",ledger);row.put("ledgerBusinessId",business);
        row.put("amount",new BigDecimal(amount));row.put("ledgerAmount",new BigDecimal(amount));row.put("currency","USDT");row.put("ledgerCurrency","USDT");
        row.put("sourceLinked",1);row.put("ledgerDeleted",0);row.put("ledgerStatus",source==Source.TRIAL_CONVERT?"POSTED":"SUCCESS");
        row.put("ledgerDirection",kind==Kind.DEVICE_PURCHASE?"OUT":"IN");
        row.put("ledgerType",switch(source){case CARD_TOPUP->"CARD_TOPUP";case DEPOSIT_ORDER->"CHAIN_TOPUP";case ORDER_REFUND->"ORDER_REFUND";
            case VIETQR,HDPAY->"VIETQR_DEPOSIT";case TRIAL_CONVERT->"TRIAL_CHARGE";case TRADE_IN->"TRADE_IN_PURCHASE";case CAPACITY_KEEP->"DEVICE_PURCHASE";default->"ORDER_PURCHASE";});
        row.put("succeededAt",kind==Kind.DEVICE_PURCHASE?at.plusSeconds(1).withNano(100_000_000):at);
        row.put("ledgerRecordedAt",at);row.put("sourceConfirmationAt",source==Source.WALLET_ORDER || source==Source.TRIAL_CONVERT?at:null);
        row.put("fractionalSecondDigits",kind==Kind.DEVICE_PURCHASE?6:0);
        row.put("successTimeField",kind==Kind.DEVICE_PURCHASE?"nx_order.paid_at":"nx_wallet_ledger.created_at");
        if(kind==Kind.DEVICE_PURCHASE) {row.put("orderNo",business);row.put("orderType","SINGLE");row.put("paymentNo","payment-1");}
        return row;
    }
}
