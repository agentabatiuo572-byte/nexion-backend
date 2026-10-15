package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade;
import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade.*;
import ffdd.opsconsole.finance.facade.*;
import ffdd.opsconsole.finance.mapper.*;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import static ffdd.opsconsole.finance.application.SupportPaymentBatchCoverageExpectations.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Frozen 74f per-item source checks vs current batched source checks, sharing the current fact service.
 * New c81 coverage has independent expectations; this is not a complete legacy-pipeline oracle. No DB claims. */
class SupportPaymentHistoryBatchTest {
    private static final LocalDateTime AT=LocalDateTime.of(2026,10,8,12,0);
    private static final String ZONE=DateTimeFormatConfig.BUSINESS_ZONE.getId();
    private final ObjectMapper json=new ObjectMapper();
    private final DataSource ds=mock(DataSource.class);
    private final Map<Source,List<Map<String,Object>>> projections=new EnumMap<>(Source.class),roots=new EnumMap<>(Source.class);
    private final Map<SupportPaymentSourceMapper.Marker,List<Map<String,Object>>> markers=new EnumMap<>(SupportPaymentSourceMapper.Marker.class);
    private final List<Map<String,Object>> ledgers=new ArrayList<>();
    private final List<Envelope> captures=new ArrayList<>();
    private final Map<String,Integer> calls=new HashMap<>();
    private final Set<Source> failedRoots=EnumSet.noneOf(Source.class),failedProjections=EnumSet.noneOf(Source.class);
    private final Set<SupportPaymentSourceMapper.Marker> failedMarkers=EnumSet.noneOf(SupportPaymentSourceMapper.Marker.class);
    private final SupportPaymentSourceMapper mapper=mock(SupportPaymentSourceMapper.class,inv->{
        String name=inv.getMethod().getName();calls.merge(name,1,Integer::sum);
        return switch(name) {
            case "historyNowUtc"->AT.plusDays(1);
            case "historyPaymentAudits","historyRefundOrigins"->List.of();
            case "historyLedgers"->ledgers;
            case "before"->rootRows(inv.getArgument(0),inv.getArgument(2));
            case "settled"->projectionRows(inv.getArgument(0),List.of(inv.<String>getArgument(2)));
            case "cregisEvents"->markerRows(SupportPaymentSourceMapper.Marker.CREGIS_EVENT,List.of(Map.of("projectId",inv.getArgument(0),"cid",inv.getArgument(1))));
            case "payments"->markerRows(SupportPaymentSourceMapper.Marker.PAYMENT,List.of(Map.of("key",inv.getArgument(0))));
            case "cardSettlements"->markerRows(SupportPaymentSourceMapper.Marker.CARD_SETTLEMENT,List.of(Map.of("key",inv.getArgument(0))));
            case "intent"->markerRows(SupportPaymentSourceMapper.Marker.INTENT,List.of(Map.of("key",inv.getArgument(0))));
            case "device"->markerRows(SupportPaymentSourceMapper.Marker.DEVICE,List.of(Map.of("id",inv.getArgument(0))));
            case "historyBeforeBatch"->{List<String> keys=inv.getArgument(2);var result=new ArrayList<Map<String,Object>>();for(String key:keys)result.addAll(rootRows(inv.getArgument(0),key));yield result;}
            case "historySettledBatch"->projectionRows(inv.getArgument(0),inv.getArgument(2));
            case "historyMarkerBatch"->markerRows(inv.getArgument(0),inv.getArgument(2));
            default->throw new AssertionError("Unexpected read: "+name);
        };
    });
    private final SupportPaymentFactMapper facts=mock(SupportPaymentFactMapper.class,inv->{
        String name=inv.getMethod().getName();calls.merge("fact."+name,1,Integer::sum);
        return switch(name) {
            case "deposits"->projections.getOrDefault(Source.DEPOSIT_ORDER,List.of());case "cards"->projections.getOrDefault(Source.CARD_TOPUP,List.of());
            case "vietqr"->projections.getOrDefault(Source.VIETQR,List.of());case "hdpay"->projections.getOrDefault(Source.HDPAY,List.of());
            case "orders"->java.util.stream.Stream.of(Source.WALLET_ORDER,Source.TRADE_IN,Source.CAPACITY_KEEP).flatMap(s->projections.getOrDefault(s,List.of()).stream()).toList();
            case "trials"->projections.getOrDefault(Source.TRIAL_CONVERT,List.of());case "refunds"->projections.getOrDefault(Source.ORDER_REFUND,List.of());
            case "freeTrials","unmatched"->List.of();default->throw new AssertionError(name);
        };
    });
    private final SupportPaymentCaptureHistoryFacade captured=mock(SupportPaymentCaptureHistoryFacade.class,inv->{
        String name=inv.getMethod().getName();calls.merge("capture."+name,1,Integer::sum);
        return switch(name){case "readBirths"->List.of(new BirthEvidence(7L,"support-payment-attribution-v1","AUTH_NEW_ACCOUNT_REGISTRATION",AT.minusDays(1),0,"PRODUCTION"));
            case "readNewFinancialProofs"->captures;default->throw new AssertionError(name);};
    });
    @BeforeEach void transaction() throws Exception {
        var connection=mock(Connection.class);when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);TransactionSynchronizationManager.bindResource(ds,new ConnectionHolder(connection));
    }
    @AfterEach void clear(){TransactionSynchronizationManager.unbindResourceIfPossible(ds);TransactionSynchronizationManager.clear();}
    private FinanceSupportPaymentFactsFacade service(boolean perItemReference) throws Exception {
        var history=new SupportPaymentFactService(facts,mock(ffdd.opsconsole.market.facade.SupportGenesisReadFacade.class));var fund=mock(SupportFundJournalFacade.class);var journal=mock(SupportFundJournalMapper.class);var earnings=mock(EarningsReleaseMapper.class);
        if(!perItemReference)return new SupportPaymentSourceService(mapper,history,ds,json,captured,fund,journal,earnings,mock(ffdd.opsconsole.market.facade.SupportGenesisReadFacade.class));
        return new FrozenPreBatchPaymentSourceService74f(mapper,history,ds,json,captured);
    }
    @Test void frozenPerItemReferencePreservesCommitted74fSourceAndStaysOutsideProduction() throws Exception {
        String source=java.nio.file.Files.readString(java.nio.file.Path.of("src/test/java/ffdd/opsconsole/finance/application/FrozenPreBatchPaymentSourceService74f.java"))
            .replace("\r\n","\n");
        String original=source.substring(source.indexOf("// END LEGACY PROVENANCE\n\n")+"// END LEGACY PROVENANCE\n\n".length())
            .replaceAll("(?s)\n    // BEGIN TEST-ONLY INTERFACE ADAPTERS.*?    // END TEST-ONLY INTERFACE ADAPTERS\n","")
            .replace("FrozenPreBatchPaymentSourceService74f","SupportPaymentSourceService")
            .replace("final class SupportPaymentSourceService","public class SupportPaymentSourceService")
            .replace("import com.fasterxml.jackson.databind.ObjectMapper;\n","import com.fasterxml.jackson.databind.ObjectMapper;\nimport ffdd.opsconsole.common.boundary.ApplicationService;\n")
            .replace("@RequiredArgsConstructor\n","@ApplicationService\n@RequiredArgsConstructor\n");
        assertEquals("04b36a9c4a5fb8ede5dbd142e174d3a78d26aaaa36524c96d756f9cc36672af1",
            java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertFalse(FrozenPreBatchPaymentSourceService74f.class.isAnnotationPresent(ffdd.opsconsole.common.boundary.ApplicationService.class));
        assertTrue(FrozenPreBatchPaymentSourceService74f.class.getProtectionDomain().getCodeSource().getLocation().toString().endsWith("/test-classes/"));
    }
    private Snapshot read(boolean perItemReference) throws Exception{return service(perItemReference).readHistory(List.of(7L));}
    private void equivalent(SupportPaymentBatchCoverageExpectations coverage) throws Exception {
        var expected=read(true);var actual=read(false);
        assertEquals(expected.facts(),actual.facts());
        assertEquals(expected.issues(),actual.issues().stream().filter(i->!ffdd.opsconsole.finance.facade.SupportPaymentFacts.genesis(i.source())).toList());
        assertEquals(expected.businessZone(),actual.businessZone());
        coverage.assertMatches(actual);
    }
    private static SupportPaymentBatchCoverageExpectations healthy(Source source) {
        return source==Source.ORDER_REFUND?empty().paid(Source.WALLET_ORDER).refundUnknown(Status.READY,REFUND_ORIGIN):empty().paid(source);
    }
    private static SupportPaymentBatchCoverageExpectations rejectedPayment(Source source,String reason,boolean ordinaryRetained) {
        var expected=empty().rejected(source,reason,ordinaryRetained);
        return ordinaryRetained?expected.firstUnknown(reason,PAYMENT_UNPROVEN):expected.firstUnknown(reason);
    }
    @Test void independentOracleRejectsEnvironmentRefundAndFirstHistoryPollution() throws Exception {
        add(Source.WALLET_ORDER,1);
        var expected=healthy(Source.WALLET_ORDER);var observed=read(false);expected.assertMatches(observed);
        var global=new ArrayList<>(observed.coverage());var wallet=global.get(4);
        global.set(4,new Coverage(wallet.source(),wallet.observedStatus(),wallet.historyStatus(),wallet.refundStatus(),
            Status.READY,wallet.supportedFrom(),wallet.reasons(),wallet.excludedFreeOrNonProductionRows(),wallet.adapterVersion()));
        assertThrows(AssertionError.class,()->expected.assertMatches(new Snapshot(observed.facts(),observed.issues(),global,
            observed.businessZone(),observed.evaluatedAt(),observed.firstHistory(),observed.customerCoverage())));
        var scoped=new ArrayList<>(observed.customerCoverage());var emptyTrade=scoped.get(5);
        scoped.set(5,new CustomerCoverage(7L,Source.TRADE_IN,emptyTrade.historyStatus(),Status.UNKNOWN,
            emptyTrade.historicalEnvironmentStatus(),emptyTrade.supportedFrom(),emptyTrade.reasons()));
        assertThrows(AssertionError.class,()->expected.assertMatches(new Snapshot(observed.facts(),observed.issues(),observed.coverage(),
            observed.businessZone(),observed.evaluatedAt(),observed.firstHistory(),scoped)));
        assertThrows(AssertionError.class,()->expected.assertMatches(new Snapshot(observed.facts(),observed.issues(),observed.coverage(),
            observed.businessZone(),observed.evaluatedAt(),List.of(new FirstHistory(7L,Status.UNKNOWN,List.of("NEW_ACCOUNT_BIRTH_NOT_PROVEN"))),observed.customerCoverage())));
    }
    @ParameterizedTest @EnumSource(value=Source.class,names={"FREE_TRIAL","UNMATCHED_LEDGER","GENESIS_PRIMARY","GENESIS_SECONDARY"},mode=EnumSource.Mode.EXCLUDE)
    void everyHealthySourceRetainsTheOriginalHistoryResult(Source source) throws Exception {add(source,1);assertTrue(read(false).issues().isEmpty(),read(false).issues().toString());assertEquals(source==Source.ORDER_REFUND?2:1,read(false).facts().size());equivalent(healthy(source));}
    @ParameterizedTest @EnumSource(value=Source.class,names={"FREE_TRIAL","UNMATCHED_LEDGER","GENESIS_PRIMARY","GENESIS_SECONDARY"},mode=EnumSource.Mode.EXCLUDE)
    void everySourceRetainsRootIdentityContradiction(Source source) throws Exception {
        add(source,1);roots.get(rootSource(source)).get(0).put("customerId",8L);assertFalse(read(false).issues().isEmpty());
        equivalent(source==Source.ORDER_REFUND?empty().rejected(Source.WALLET_ORDER,"SETTLEMENT_MISMATCH",false)
            .refundUnknown(Status.UNKNOWN,"SETTLEMENT_MISMATCH").firstUnknown("SETTLEMENT_MISMATCH")
            :rejectedPayment(source,"SETTLEMENT_MISMATCH",false));
    }
    @ParameterizedTest @EnumSource(value=Source.class,names={"FREE_TRIAL","UNMATCHED_LEDGER","GENESIS_PRIMARY","GENESIS_SECONDARY"},mode=EnumSource.Mode.EXCLUDE)
    void everySourceRetainsDuplicateRootFailure(Source source) throws Exception {
        add(source,1);var values=roots.get(rootSource(source));values.add(new HashMap<>(values.get(0)));assertFalse(read(false).issues().isEmpty());
        equivalent(source==Source.ORDER_REFUND?empty().rejected(Source.WALLET_ORDER,"CONFLICTING_CAPTURED_SOURCE_PROJECTION",false)
            .refundUnknown(Status.UNKNOWN,"CONFLICTING_CAPTURED_SOURCE_PROJECTION").firstUnknown("CONFLICTING_CAPTURED_SOURCE_PROJECTION")
            :rejectedPayment(source,"CONFLICTING_CAPTURED_SOURCE_PROJECTION",false));
    }
    @ParameterizedTest @EnumSource(value=Source.class,names={"FREE_TRIAL","UNMATCHED_LEDGER","GENESIS_PRIMARY","GENESIS_SECONDARY"},mode=EnumSource.Mode.EXCLUDE)
    void everySourceRetainsActualLedgerContradiction(Source source) throws Exception {
        add(source,1);ledgers.get(0).put("amount",new BigDecimal("9"));assertFalse(read(false).issues().isEmpty());
        equivalent(source==Source.ORDER_REFUND?empty().rejected(Source.WALLET_ORDER,"SETTLEMENT_MISMATCH",false)
            .refundUnknown(Status.UNKNOWN,"CAPTURED_PAYMENT_NOT_RECONCILED","UNPROVEN_ORIGINAL_PAYMENT").firstUnknown("SETTLEMENT_MISMATCH")
            :rejectedPayment(source,"SETTLEMENT_MISMATCH",false));
    }
    @Test void oneThousandCapturesMustUseBoundedPlainReadQueries() throws Exception {
        for(int i=1;i<=1000;i++)add(Source.WALLET_ORDER,i);calls.clear();var result=read(false);assertEquals(1000,result.facts().size());assertTrue(result.issues().isEmpty());
        int total=calls.values().stream().mapToInt(Integer::intValue).sum();assertEquals(18,total,"Queries="+total+" "+calls);
        System.out.println("BATCH_WALLET_1000_QUERIES="+total);
        for(String perFact:List.of("before","settled","payments","cregisEvents","cardSettlements","intent","device"))assertEquals(0,calls.getOrDefault(perFact,0),perFact);
        healthy(Source.WALLET_ORDER).assertMatches(result);
    }
    @Test void oneCaptureUsesTheSameQueryCountAsOneThousand() throws Exception {
        add(Source.WALLET_ORDER,1);calls.clear();var result=read(false);assertEquals(1,result.facts().size());assertEquals(18,calls.values().stream().mapToInt(Integer::intValue).sum());
        for(String perFact:List.of("before","settled","payments","cregisEvents","cardSettlements","intent","device"))assertEquals(0,calls.getOrDefault(perFact,0),perFact);
        healthy(Source.WALLET_ORDER).assertMatches(result);
    }
    @Test void allNineSourceFamiliesTogetherRemainBounded() throws Exception {
        int index=1;for(Source source:Source.values())if(source!=Source.FREE_TRIAL && source!=Source.UNMATCHED_LEDGER && !ffdd.opsconsole.finance.facade.SupportPaymentFacts.genesis(source))add(source,index++);
        calls.clear();var result=read(false);assertTrue(result.issues().isEmpty(),result.issues().toString());assertEquals(10,result.facts().size());
        int total=calls.values().stream().mapToInt(Integer::intValue).sum();assertEquals(35,total,calls.toString());
        for(String perFact:List.of("before","settled","payments","cregisEvents","cardSettlements","intent","device"))assertEquals(0,calls.getOrDefault(perFact,0),perFact);
        System.out.println("BATCH_ALL_NINE_QUERIES="+total);
        equivalent(empty().paid(Source.DEPOSIT_ORDER).paid(Source.CARD_TOPUP).paid(Source.VIETQR).paid(Source.HDPAY)
            .paid(Source.WALLET_ORDER).paid(Source.TRADE_IN).paid(Source.CAPACITY_KEEP).paid(Source.TRIAL_CONVERT)
            .refundUnknown(Status.READY,REFUND_ORIGIN));
    }
    @ParameterizedTest @EnumSource(value=Source.class,names={"FREE_TRIAL","UNMATCHED_LEDGER","GENESIS_PRIMARY","GENESIS_SECONDARY"},mode=EnumSource.Mode.EXCLUDE)
    void everyRootBatchFailureRetainsLegacyFailureAndOtherSourceResults(Source source) throws Exception {
        add(source,1);add(source==Source.CARD_TOPUP?Source.DEPOSIT_ORDER:Source.CARD_TOPUP,2);failedRoots.add(rootSource(source));
        assertTrue(read(false).issues().stream().anyMatch(i->i.reason().equals("SOURCE_READ_FAILED")));
        var other=source==Source.CARD_TOPUP?Source.DEPOSIT_ORDER:Source.CARD_TOPUP;
        equivalent(source==Source.ORDER_REFUND?empty().paid(other).rejected(Source.WALLET_ORDER,"SOURCE_READ_FAILED",true)
            .refundUnknown(Status.UNKNOWN,"SOURCE_READ_FAILED",PAYMENT_UNPROVEN,REFUND_ORIGIN).firstUnknown("SOURCE_READ_FAILED",PAYMENT_UNPROVEN)
            :rejectedPayment(source,"SOURCE_READ_FAILED",true).paid(other));
    }
    @ParameterizedTest @EnumSource(value=Source.class,names={"FREE_TRIAL","UNMATCHED_LEDGER","GENESIS_PRIMARY","GENESIS_SECONDARY"},mode=EnumSource.Mode.EXCLUDE)
    void everySettledBatchFailureRetainsLegacyFailure(Source source) throws Exception {
        add(source,1);failedProjections.add(source);assertTrue(read(false).issues().stream().anyMatch(i->i.reason().equals("SOURCE_READ_FAILED")));
        equivalent(source==Source.ORDER_REFUND?empty().paid(Source.WALLET_ORDER)
            .refundUnknown(Status.UNKNOWN,"SOURCE_READ_FAILED",PAYMENT_UNPROVEN,REFUND_ORIGIN)
            :rejectedPayment(source,"SOURCE_READ_FAILED",true));
    }
    @ParameterizedTest @EnumSource(value=Source.class,names={"DEPOSIT_ORDER","CARD_TOPUP","VIETQR","HDPAY","WALLET_ORDER","TRIAL_CONVERT"})
    void eachMarkerBatchFailureRetainsLegacyFailure(Source source) throws Exception {
        add(source,1);failedMarkers.add(markers.keySet().iterator().next());assertTrue(read(false).issues().stream().anyMatch(i->i.reason().equals("SOURCE_READ_FAILED")));
        equivalent(rejectedPayment(source,"SOURCE_READ_FAILED",true));
    }
    @Test void anEarlierLedgerContradictionStillTakesPriorityOverAPrefetchedSourceFailure() throws Exception {
        add(Source.WALLET_ORDER,1);ledgers.get(0).put("amount",BigDecimal.ONE);failedRoots.add(Source.WALLET_ORDER);failedProjections.add(Source.WALLET_ORDER);
        failedMarkers.add(SupportPaymentSourceMapper.Marker.PAYMENT);equivalent(rejectedPayment(Source.WALLET_ORDER,"SETTLEMENT_MISMATCH",false));assertFalse(read(false).issues().stream().anyMatch(i->i.reason().equals("SOURCE_READ_FAILED")));
    }
    @Test void missingWalletConfirmationCannotBeReplacedByTheSettledProjection() throws Exception {add(Source.WALLET_ORDER,1);markers.clear();assertFalse(read(false).issues().isEmpty());equivalent(rejectedPayment(Source.WALLET_ORDER,"MISSING_SOURCE_CONFIRMATION_TIME",true));}
    @Test void missingCregisEventCannotBeReplacedByTheSettledProjection() throws Exception {add(Source.DEPOSIT_ORDER,1);markers.clear();assertFalse(read(false).issues().isEmpty());equivalent(rejectedPayment(Source.DEPOSIT_ORDER,"MISSING_AUTHORITATIVE_SOURCE",true));}
    @Test void missingTrialDeviceCannotBeReplacedByTheCapturedFact() throws Exception {add(Source.TRIAL_CONVERT,1);markers.clear();assertFalse(read(false).issues().isEmpty());equivalent(rejectedPayment(Source.TRIAL_CONVERT,"MISSING_AUTHORITATIVE_SOURCE",true));}
    @ParameterizedTest @MethodSource("markerContradictions")
    void eachIndependentMarkerPredicateRetainsLegacyRejection(Source source,String field,Object invalid) throws Exception {
        add(source,1);markers.values().iterator().next().get(0).put(field,invalid);assertFalse(read(false).issues().isEmpty(),source+" "+field);
        String reason=switch(source) {
            case DEPOSIT_ORDER->Set.of("projectId","cid").contains(field)?"MISSING_AUTHORITATIVE_SOURCE":"BROKEN_CREGIS_EVENT_LINK";
            case CARD_TOPUP,WALLET_ORDER->"SETTLEMENT_MISMATCH";
            case VIETQR,HDPAY->"BROKEN_INTENT_IDENTITY";
            case TRIAL_CONVERT->"id".equals(field)?"MISSING_AUTHORITATIVE_SOURCE":"BROKEN_SOURCE_LINK";
            default->throw new AssertionError(source);
        };
        equivalent(rejectedPayment(source,reason,"MISSING_AUTHORITATIVE_SOURCE".equals(reason)));
    }
    private static java.util.stream.Stream<Arguments> markerContradictions(){
        var result=new ArrayList<Arguments>();
        for(Source source:List.of(Source.DEPOSIT_ORDER,Source.CARD_TOPUP,Source.VIETQR,Source.HDPAY,Source.WALLET_ORDER,Source.TRIAL_CONVERT)) {
            result.add(Arguments.of(source,"customerId",8L));
            if(source!=Source.TRIAL_CONVERT){result.add(Arguments.of(source,"amount",new BigDecimal("9")));result.add(Arguments.of(source,"status","PENDING"));}
            switch(source) {
                case DEPOSIT_ORDER->{result.add(Arguments.of(source,"projectId",101L));result.add(Arguments.of(source,"cid",2L));result.add(Arguments.of(source,"ledgerId",2L));}
                case CARD_TOPUP->{result.add(Arguments.of(source,"provider","OTHER"));result.add(Arguments.of(source,"providerPaymentId","OTHER"));result.add(Arguments.of(source,"orderNo","OTHER"));result.add(Arguments.of(source,"deleted",1));}
                case VIETQR,HDPAY->{result.add(Arguments.of(source,"rail","OTHER"));result.add(Arguments.of(source,"target","OTHER"));result.add(Arguments.of(source,"deleted",1));}
                case WALLET_ORDER->{result.add(Arguments.of(source,"provider","OTHER"));result.add(Arguments.of(source,"currency","NEX"));result.add(Arguments.of(source,"ledgerId",2L));result.add(Arguments.of(source,"successAt",AT.plusSeconds(1)));result.add(Arguments.of(source,"businessId","OTHER"));}
                case TRIAL_CONVERT->{result.add(Arguments.of(source,"id",2L));result.add(Arguments.of(source,"orderNo","OTHER"));}
                default->throw new AssertionError(source);
            }
        }
        return result.stream();
    }
    @ParameterizedTest @EnumSource(value=Source.class,names={"FREE_TRIAL","UNMATCHED_LEDGER","GENESIS_PRIMARY","GENESIS_SECONDARY"},mode=EnumSource.Mode.EXCLUDE)
    void absentRootRetainsOriginalSourceSpecificBehavior(Source source) throws Exception {
        add(source,1);roots.get(rootSource(source)).clear();
        equivalent(switch(source) {
            case CARD_TOPUP->rejectedPayment(source,"SETTLEMENT_MISMATCH",false);
            case VIETQR,HDPAY->rejectedPayment(source,"BROKEN_INTENT_IDENTITY",false);
            case ORDER_REFUND->rejectedPayment(Source.WALLET_ORDER,"MISSING_AUTHORITATIVE_SOURCE",true).refundUnknown(Status.READY,REFUND_ORIGIN);
            default->rejectedPayment(source,"MISSING_AUTHORITATIVE_SOURCE",true);
        });
    }
    @ParameterizedTest @EnumSource(value=Source.class,names={"FREE_TRIAL","UNMATCHED_LEDGER","GENESIS_PRIMARY","GENESIS_SECONDARY"},mode=EnumSource.Mode.EXCLUDE)
    void absentOrdinaryProjectionRetainsCapturedSourceChecks(Source source) throws Exception {add(source,1);projections.get(source).clear();equivalent(healthy(source));}
    @Test void duplicateMarkerCannotCollapseToAValidSingleRow() throws Exception {add(Source.CARD_TOPUP,1);var values=markers.values().iterator().next();values.add(new HashMap<>(values.get(0)));assertFalse(read(false).issues().isEmpty());equivalent(rejectedPayment(Source.CARD_TOPUP,"SETTLEMENT_MISMATCH",false));}
    @Test void immutableCapturedProofTypeFailureStillRejectsBeforeSourceReads() throws Exception {
        add(Source.WALLET_ORDER,1);var old=captures.get(0);var source=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(old.sourceFactJson());source.put("customerId",true);
        captures.set(0,new Envelope(old.identity(),old.sourcePartition(),old.captureDbUtc(),old.captureMode(),old.captureSchemaVersion(),source.toString(),old.beforeSourceJson(),old.evidenceCaptureMode(),old.evidenceSchemaVersion()));
        assertFalse(read(false).issues().isEmpty());
        for(String sourceRead:List.of("before","settled","payments","cregisEvents","cardSettlements","intent","device",
                "historyBeforeBatch","historySettledBatch","historyMarkerBatch"))assertEquals(0,calls.getOrDefault(sourceRead,0),sourceRead);
        equivalent(rejectedPayment(Source.WALLET_ORDER,"INVALID_PERSISTED_SOURCE_PROOF",true));
    }

    private List<Map<String,Object>> rootRows(Source source,String key){if(failedRoots.contains(rootSource(source)))throw new org.springframework.dao.DataAccessResourceFailureException("root");String raw=source==Source.ORDER_REFUND?key.substring("E4-REFUND-".length()):key;return roots.getOrDefault(rootSource(source),List.of()).stream().filter(r->Objects.equals(raw,r.get("businessId"))).toList();}
    private static Source rootSource(Source source){return switch(source){case TRADE_IN,CAPACITY_KEEP,ORDER_REFUND->Source.WALLET_ORDER;default->source;};}
    private List<Map<String,Object>> projectionRows(Source source,List<String> keys){if(failedProjections.contains(source))throw new org.springframework.dao.DataAccessResourceFailureException("settled");return projections.getOrDefault(source,List.of()).stream().filter(r->keys.contains(r.get("businessId"))).toList();}
    private List<Map<String,Object>> markerRows(SupportPaymentSourceMapper.Marker marker,List<Map<String,Object>> refs){
        if(failedMarkers.contains(marker))throw new org.springframework.dao.DataAccessResourceFailureException("marker");
        return markers.getOrDefault(marker,List.of()).stream().filter(r->refs.stream().anyMatch(ref->switch(marker){
            case CREGIS_EVENT->Objects.equals(ref.get("projectId"),r.get("projectId"))&&Objects.equals(ref.get("cid"),r.get("cid"));
            case DEVICE->Objects.equals(ref.get("id"),r.get("id"));case PAYMENT->Objects.equals(ref.get("key"),r.get("orderNo"));
            default->Objects.equals(ref.get("key"),r.get("businessId"));})).toList();
    }
    private void add(Source source,long index) throws Exception {
        if(source==Source.ORDER_REFUND)add(Source.WALLET_ORDER,index);
        long ledgerId=source==Source.ORDER_REFUND?1000000L+index:index;
        boolean purchase=Set.of(Source.WALLET_ORDER,Source.TRADE_IN,Source.CAPACITY_KEEP,Source.TRIAL_CONVERT).contains(source);
        Kind kind=purchase?Kind.DEVICE_PURCHASE:source==Source.ORDER_REFUND?Kind.DEVICE_PURCHASE_REFUND:Kind.DEPOSIT;
        String order="order-"+index,key=switch(source){case DEPOSIT_ORDER->"CR-"+index;case VIETQR->"D1-VIETQR-"+index;case HDPAY->"intent-"+index;case TRIAL_CONVERT->"claim-"+index+":CHARGE";case ORDER_REFUND->"E4-REFUND-"+order;case CARD_TOPUP->"card-"+index;default->order;};
        String prefix=switch(source){case DEPOSIT_ORDER->"nx_deposit_order:";case CARD_TOPUP->"nx_payment_record:";case VIETQR->"nx_vietqr_reconciliation:";case HDPAY->"nx_hdpay_payin_order:";case ORDER_REFUND->"nx_wallet_ledger:";default->"nx_order:";};
        var row=new LinkedHashMap<String,Object>();row.put("source",source.name());row.put("kind",kind.name());row.put("sourceId",prefix+(source==Source.ORDER_REFUND?ledgerId:index));row.put("customerId",7L);
        row.put("businessId",key);row.put("duplicateKey",key);row.put("ledgerId",ledgerId);row.put("ledgerCustomerId",7L);row.put("ledgerBusinessId",key);row.put("amount",BigDecimal.TEN);row.put("ledgerAmount",BigDecimal.TEN);
        row.put("currency","USDT");row.put("ledgerCurrency","USDT");row.put("sourceLinked",1);row.put("ledgerDeleted",0);row.put("ledgerDirection",purchase?"OUT":"IN");
        row.put("ledgerStatus",source==Source.TRIAL_CONVERT?"POSTED":"SUCCESS");row.put("ledgerType",SupportPaymentSourceSql.ledgerType(source));row.put("succeededAt",AT);row.put("ledgerRecordedAt",AT);
        row.put("sourceConfirmationAt",source==Source.WALLET_ORDER||source==Source.TRIAL_CONVERT?AT:null);row.put("providerPaidAt",source==Source.CARD_TOPUP||source==Source.VIETQR?AT:null);
        row.put("successTimeField",source==Source.DEPOSIT_ORDER?"nx_deposit_order.credited_at":source==Source.HDPAY?"nx_hdpay_payin_order.settled_at":purchase?"nx_order.paid_at":"nx_wallet_ledger.created_at");
        row.put("fractionalSecondDigits",purchase?6:0);row.put("sourceVersion",null);row.put("orderNo",kind!=Kind.DEPOSIT?order:null);row.put("orderType",kind==Kind.DEVICE_PURCHASE_REFUND||source==Source.WALLET_ORDER?"SINGLE":purchase?source.name():null);
        projections.computeIfAbsent(source,s->new ArrayList<>()).add(row);
        var root=new LinkedHashMap<String,Object>();root.put("id",index);root.put("customerId",7L);root.put("businessId",source==Source.ORDER_REFUND?order:key);root.put("amount",BigDecimal.TEN);root.put("currency","USDT");root.put("ledgerId",index);
        root.put("successAt",AT);root.put("providerPaidAt",AT);root.put("orderNo",order);root.put("orderType",row.get("orderType"));root.put("paymentNo","pay-"+index);root.put("provider","PSP");root.put("providerPaymentId","provider-"+index);
        root.put("ledgerBusinessId",key);root.put("intentNo","intent-"+index);root.put("deviceId",index);root.put("status",source==Source.TRIAL_CONVERT?"REDEEMED":"CREDITED");if(source!=Source.ORDER_REFUND)roots.computeIfAbsent(rootSource(source),s->new ArrayList<>()).add(root);
        if(source==Source.TRIAL_CONVERT){var original=new HashMap<>(root);original.put("businessId",order);roots.computeIfAbsent(Source.WALLET_ORDER,s->new ArrayList<>()).add(original);}
        var marker=new HashMap<String,Object>(root);marker.put("businessId",source==Source.VIETQR||source==Source.HDPAY?"intent-"+index:key);
        SupportPaymentSourceMapper.Marker markerType=switch(source){case DEPOSIT_ORDER->SupportPaymentSourceMapper.Marker.CREGIS_EVENT;case CARD_TOPUP->SupportPaymentSourceMapper.Marker.CARD_SETTLEMENT;case VIETQR,HDPAY->SupportPaymentSourceMapper.Marker.INTENT;case WALLET_ORDER->SupportPaymentSourceMapper.Marker.PAYMENT;case TRIAL_CONVERT->SupportPaymentSourceMapper.Marker.DEVICE;default->null;};
        marker.put("projectId",100L);marker.put("cid",index);marker.put("target","WALLET_TOPUP");marker.put("rail",source==Source.HDPAY?"HDPAY":"MANUAL");
        if(source==Source.CARD_TOPUP)marker.put("status","SETTLED");if(source==Source.WALLET_ORDER){marker.put("provider","NEXGRID_WALLET");marker.put("businessId","pay-"+index);marker.put("status","PAID");}
        if(markerType!=null)markers.computeIfAbsent(markerType,m->new ArrayList<>()).add(marker);
        var ledger=new HashMap<String,Object>();for(var pair:Map.of("id","ledgerId","customerId","ledgerCustomerId","businessId","ledgerBusinessId","ledgerType","ledgerType","currency","ledgerCurrency","amount","ledgerAmount","direction","ledgerDirection","status","ledgerStatus","deleted","ledgerDeleted","successAt","ledgerRecordedAt").entrySet())ledger.put(pair.getKey(),row.get(pair.getValue()));ledgers.add(ledger);
        captures.add(envelope(row,source==Source.DEPOSIT_ORDER?"100":source==Source.VIETQR?"intent-"+index:null));
    }
    private Envelope envelope(Map<String,Object> row,String partition) throws Exception {
        Fact f=SupportPaymentFactService.fact(row,Source.valueOf(row.get("source").toString()));var fmt=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");
        var saved=new LinkedHashMap<String,Object>();saved.put("factId",f.factId());saved.put("kind",f.kind());saved.put("source",f.source());saved.put("sourceIds",f.sourceIds());saved.put("customerId",f.customerId());saved.put("ledgerId",f.ledgerId());
        saved.put("sourceBusinessId",f.sourceBusinessId());saved.put("orderNo",f.orderNo());saved.put("orderType",f.orderType());saved.put("originalFactId",f.originalFactId());saved.put("currency",f.currency());saved.put("amount",f.amount());
        saved.put("succeededAt",fmt.format(f.succeededAt()));saved.put("ledgerRecordedAt",fmt.format(f.ledgerRecordedAt()));saved.put("sourceConfirmationAt",f.sourceConfirmationAt()==null?null:fmt.format(f.sourceConfirmationAt()));saved.put("providerPaidAt",f.providerPaidAt()==null?null:fmt.format(f.providerPaidAt()));
        saved.put("successTimeField",f.successTimeField());saved.put("fractionalSecondDigits",f.fractionalSecondDigits());saved.put("historicalEnvironmentStatus",Status.UNKNOWN);saved.put("sourceVersion",null);saved.put("businessZone",ZONE);saved.put("succeededAtInstant",fmt.format(f.succeededAt().atZone(DateTimeFormatConfig.BUSINESS_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime())+"Z");
        var before=new LinkedHashMap<String,Object>();before.put("customerId",7L);before.put("source",f.source());before.put("stableBusinessKey",f.sourceBusinessId());before.put("sourcePartition",partition);before.put("oldSource",false);before.put("existingLedgerId",null);before.put("existingFactId",null);before.put("existingSuccessAt",null);
        before.put("businessZone",ZONE);before.put("successTimeField",f.successTimeField());before.put("fractionalSecondDigits",f.fractionalSecondDigits());before.put("sourceVersion",null);before.put("sourceIds",List.of());
        return new Envelope(new Identity(f.factId(),7L,f.kind().name(),f.source().name(),f.ledgerId(),f.sourceBusinessId(),f.orderNo(),f.orderType(),f.originalFactId(),f.currency(),f.amount(),f.succeededAt(),ZONE,f.successTimeField(),f.fractionalSecondDigits()),partition,AT.plusSeconds(1),"NEW_SUCCESS","support-payment-attribution-v1",json.writeValueAsString(saved),json.writeValueAsString(before),"NEW_SUCCESS","support-payment-attribution-v1");
    }
}
