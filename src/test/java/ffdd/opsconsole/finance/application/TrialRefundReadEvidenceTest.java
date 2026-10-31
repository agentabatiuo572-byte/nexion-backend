package ffdd.opsconsole.finance.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import ffdd.opsconsole.finance.mapper.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Narrow predicate negatives; raw/digest/CONSUME tests and real MySQL chain remain separate. */
class TrialRefundReadEvidenceTest {
    private final ObjectMapper json=new ObjectMapper();
    private final SupportFundJournalBindingService codec=new SupportFundJournalBindingService(mock(SupportFundJournalMapper.class),mock(DataSource.class),json);
    private final SupportFundReadService reader=new SupportFundReadService(mock(SupportFundReadMapper.class),null,null,codec,mock(DataSource.class));
    private final LocalDateTime at=LocalDateTime.of(2026,11,1,12,0);
    private final Map<String,Object> current=new LinkedHashMap<>(),order=new LinkedHashMap<>();
    private final ObjectNode source=json.createObjectNode();
    private final List<Fact> refunds=new ArrayList<>();
    private List<CustomerCoverage> coverage;

    TrialRefundReadEvidenceTest() {
        current.putAll(Map.of("customerId",7L,"claimNo","trial","claimId",44L,"orderId",45L,"deviceId",46L,"orderNo","order","orderType","TRIAL_CONVERT","claimStatus","REDEEMED","claimVersion",1));
        current.putAll(Map.of("paymentStatus","PAID","deviceStatus","ACTIVE","ownershipStatus","OWNED","deviceEnvironment","PRODUCTION","sourceChannel","TRIAL","userStatus","ACTIVE","userSandbox",0,"userDeleted",0,"claimDeleted",0,"orderDeleted",0));
        current.putAll(Map.of("deviceDeleted",0,"chargeUsdt",BigDecimal.TEN,"amountUsdt",BigDecimal.TEN,"remainderUsdt",BigDecimal.ZERO,"shadowNex",BigDecimal.ZERO,"paidAt",at.toString(),"settledAt",at.toString()));
        source.put("customerId",7).put("stableBusinessKey","trial:CHARGE").put("factId","PURCHASE:order").put("orderNo","order").put("currency","USDT").put("amount",10).put("succeededAt",at.toString());
        source.set("trialSource",json.valueToTree(current));source.putArray("rewards");source.putArray("rewardLedgers");
        current.put("paidAt",at);current.put("settledAt",at);
        current.put("paymentStatus","REFUNDED");current.put("deviceStatus","DEACTIVATED");current.put("ownershipStatus","REFUNDED");
        order.putAll(Map.of("customerId",7L,"orderId",45L,"orderNo","order","orderType","TRIAL_CONVERT","paymentStatus","REFUNDED","orderStatus","REFUNDED","isDeleted",0));
        refunds.add(refund(7,"PURCHASE:order","USDT",BigDecimal.TEN));
        coverage=List.of(new CustomerCoverage(7,Source.ORDER_REFUND,Status.READY,Status.READY,Status.READY,at,List.of()));
    }
    private Fact refund(long user,String original,String currency,BigDecimal amount) {
        return new Fact("ORDER_REFUND:90",Kind.DEVICE_PURCHASE_REFUND,Source.ORDER_REFUND,List.of("nx_wallet_ledger:90"),user,90,
                "E4-REFUND-order","order","TRIAL_CONVERT",original,currency,amount,at.plusSeconds(1),"nx_wallet_ledger.created_at",0,null,at.plusSeconds(1),at.plusSeconds(1),"1",Status.READY);
    }
    private Object compare() throws Exception {
        var history=new Snapshot(refunds,List.of(),List.of(),"Asia/Shanghai",Instant.now(),List.of(),coverage);
        var type=Class.forName(SupportFundReadService.class.getName()+"$TrialRefundInputs");
        var constructor=type.getDeclaredConstructor(Snapshot.class,List.class,Map.class);constructor.setAccessible(true);
        var input=constructor.newInstance(history,List.of(order),new HashMap<>());
        return ReflectionTestUtils.invokeMethod(reader,"trialRewards",source,List.of(current),List.of(),List.of(),new HashSet<String>(),70L,json.createObjectNode(),input);
    }
    @Test void independentlyProvenRefundAllowsOnlyTheThreeLifecycleFields() throws Exception {
        var original=source.deepCopy();assertEquals(List.of(),compare());assertEquals(original,source);
        assertEquals("REFUNDED",current.get("paymentStatus"));assertEquals("PAID",source.path("trialSource").path("paymentStatus").asText());
    }
    @Test void missingIndependentE4HistoryOrAdminAuthorityCannotGrantComparison() {
        coverage=List.of();assertThrows(RuntimeException.class,this::compare);
    }
    @ParameterizedTest @ValueSource(strings={"history","refund","environment","reason"})
    void incompleteIndependentCoverageCannotGrantComparison(String missing) {
        coverage=List.of(new CustomerCoverage(7,Source.ORDER_REFUND,missing.equals("history")?Status.UNKNOWN:Status.READY,
                missing.equals("refund")?Status.UNKNOWN:Status.READY,missing.equals("environment")?Status.UNKNOWN:Status.READY,
                at,missing.equals("reason")?List.of("E4_AUTHORITY_MISSING"):List.of()));
        assertThrows(RuntimeException.class,this::compare);
    }
    @ParameterizedTest @ValueSource(strings={"customerId","claimNo","claimId","claimVersion","deviceId","orderId","orderNo","orderType","amountUsdt","chargeUsdt","paidAt","settledAt","deviceEnvironment","sourceChannel","userSandbox","userDeleted","deviceDeleted"})
    void refundCannotHideAnyChangedIdentityAmountTimeOrEnvironment(String field) {
        Object old=current.get(field);current.put(field,old instanceof Number?new BigDecimal(old.toString()).add(BigDecimal.ONE):old+"-changed");
        assertThrows(RuntimeException.class,this::compare);
    }
    @ParameterizedTest @ValueSource(strings={"paymentStatus","deviceStatus","ownershipStatus"})
    void illegalCurrentLifecycleCombinationIsRejected(String field) {current.put(field,"RECYCLED");assertThrows(RuntimeException.class,this::compare);}
    @Test void bothOrderStatesMustBeRefunded() {order.put("orderStatus","COMPLETED");assertThrows(RuntimeException.class,this::compare);}
    @Test void duplicateRefundSourceIsRejected() {refunds.add(refunds.get(0));assertThrows(RuntimeException.class,this::compare);}
    @Test void wrongOriginalConsumeIsRejected() {refunds.set(0,refund(7,"PURCHASE:other","USDT",BigDecimal.TEN));assertThrows(RuntimeException.class,this::compare);}
    @Test void wrongRefundCustomerIsRejected() {refunds.set(0,refund(8,"PURCHASE:order","USDT",BigDecimal.TEN));assertThrows(RuntimeException.class,this::compare);}
    @Test void partialRefundCannotAuthorizeFullLifecycleProjection() {refunds.set(0,refund(7,"PURCHASE:order","USDT",BigDecimal.ONE));assertThrows(RuntimeException.class,this::compare);}
    @Test void wrongRefundCurrencyIsRejected() {refunds.set(0,refund(7,"PURCHASE:order","UVT",BigDecimal.TEN));assertThrows(RuntimeException.class,this::compare);}
    @Test void existingRefundTimePrecisionAllowsTheSameSecondAsMicrosecondPaidTime() throws Exception {
        current.put("paidAt",at.plusNanos(264384000));((ObjectNode)source.path("trialSource")).put("paidAt",at.plusNanos(264384000).toString());
        source.put("succeededAt",at.plusNanos(264384000).toString());var original=refunds.get(0);
        refunds.set(0,new Fact(original.factId(),original.kind(),original.source(),original.sourceIds(),original.customerId(),original.ledgerId(),original.sourceBusinessId(),original.orderNo(),original.orderType(),original.originalFactId(),original.currency(),original.amount(),at,original.successTimeField(),0,null,at,at,original.sourceVersion(),original.historicalEnvironmentStatus()));
        assertEquals(List.of(),compare());
    }
    @Test void refundFromAnEarlierSecondCannotGrantProjection() {
        var original=refunds.get(0);var earlier=at.minusSeconds(1);
        refunds.set(0,new Fact(original.factId(),original.kind(),original.source(),original.sourceIds(),original.customerId(),original.ledgerId(),original.sourceBusinessId(),original.orderNo(),original.orderType(),original.originalFactId(),original.currency(),original.amount(),earlier,original.successTimeField(),0,null,earlier,earlier,original.sourceVersion(),original.historicalEnvironmentStatus()));
        assertThrows(RuntimeException.class,this::compare);
    }
    @Test void environmentProofComesFromScopedCoverageRatherThanTheRawFactFlag() throws Exception {
        var fact=refunds.get(0);refunds.set(0,new Fact(fact.factId(),fact.kind(),fact.source(),fact.sourceIds(),fact.customerId(),fact.ledgerId(),fact.sourceBusinessId(),fact.orderNo(),fact.orderType(),fact.originalFactId(),fact.currency(),fact.amount(),fact.succeededAt(),fact.successTimeField(),fact.fractionalSecondDigits(),fact.providerPaidAt(),fact.ledgerRecordedAt(),fact.sourceConfirmationAt(),fact.sourceVersion(),Status.UNKNOWN));
        assertEquals(List.of(),compare());
    }
}
