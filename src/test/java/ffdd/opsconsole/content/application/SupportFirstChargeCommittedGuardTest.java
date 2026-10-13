package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Pure guard probes only; no Spring context, SQL connection, financial command or phase signoff. */
class SupportFirstChargeCommittedGuardTest {
    @Test void originalSourceAndOtherDirectoriesAreRejected() {
        String directory=SupportFirstChargeCommittedRuntimeTest.CLONE_DIRECTORY;
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateCloneIdentity(SupportFirstChargeCommittedRuntimeTest.ORIGINAL_UUID,directory)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateCloneIdentity("01234567-89ab-cdef-0123-456789abcdef",directory+"-other")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateCloneIdentity("",directory)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateCloneIdentity("01234567-89ab-cdef-0123-456789abcdef","D:/CodexData/test-environments/workflow-runs/support-analytics-20261007-r2/runtime/support-committed-clone-r20-v1/mysql-data")).isInstanceOf(IllegalStateException.class);
        SupportFirstChargeCommittedRuntimeTest.validateCloneIdentity("01234567-89ab-cdef-0123-456789abcdef",directory.replace('/','\\')+"\\");
    }
    @Test void committedProductsAcceptBothConsumptionTypesAndRejectCaseMismatch() {
        var device=java.util.Map.<String,Object>of("product_no","stellarbox-s1","product_type","DEVICE");
        var share=java.util.Map.<String,Object>of("product_no","cloud-share","product_type","SHARE");
        SupportFirstChargeCommittedRuntimeTest.validateCommittedProduct(device,"stellarbox-s1","DEVICE");
        SupportFirstChargeCommittedRuntimeTest.validateCommittedProduct(share,"cloud-share","SHARE");
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateCommittedProduct(share,"stellarbox-s1","DEVICE")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateCommittedProduct(device,"cloud-share","SHARE")).isInstanceOf(IllegalStateException.class);
        for(var row:java.util.List.of(java.util.Map.<String,Object>of("product_no","stellarbox-s1","product_type","SHARE"),java.util.Map.<String,Object>of("product_no","stellarbox-pro","product_type","DEVICE"),java.util.Map.<String,Object>of("product_no","stellarbox-s1")))
            assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateCommittedProduct(row,"stellarbox-s1","DEVICE")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateCommittedProduct(null,"stellarbox-s1","DEVICE")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateCommittedProduct(device,"stellarbox-s1","SHARE")).isInstanceOf(IllegalStateException.class);
    }
    @Test void heldAccountAndVersionCannotBorrowOtherCustomerOrClaimUnknownContinuation() throws Exception {
        var mapper=new ObjectMapper();
        var good=mapper.readTree("{\"records\":[{\"customerId\":71}],\"versionState\":\"READY\",\"queryVersion\":\"saq-v1:"+"a".repeat(64)+"\"}");
        assertThat(SupportFirstChargeCommittedRuntimeTest.heldCustomer(good,71).path("customerId").asLong()).isEqualTo(71);
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.heldCustomer(good,72)).isInstanceOf(AssertionError.class);
        var bad=mapper.readTree("{\"records\":[{\"customerId\":71}],\"versionState\":\"UNKNOWN\",\"queryVersion\":null,\"canContinue\":true}");
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.heldCustomer(bad,71)).isInstanceOf(AssertionError.class);
        var stale=good.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)stale).put("queryVersion","stale");
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.heldCustomer(stale,71)).isInstanceOf(AssertionError.class);
    }
    @Test void proofBodyRejectsMissingOldWrongAndContextOnlyCurrentBindings() throws Exception {
        var mapper=new ObjectMapper();var context=mapper.readTree("{\"identity\":{\"runId\":\"CURRENT-RUN\",\"snapshotHash\":\"current-snapshot\"}}");
        var proof=mapper.readTree("{\"workflowRunId\":\"CURRENT-RUN\",\"snapshotHash\":\"current-snapshot\"}");
        var missingRun=proof.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)missingRun).remove("workflowRunId");
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateProofBinding(context,missingRun,"CURRENT-RUN","current-snapshot")).isInstanceOf(IllegalStateException.class);
        var missingSnapshot=proof.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)missingSnapshot).remove("snapshotHash");
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateProofBinding(context,missingSnapshot,"CURRENT-RUN","current-snapshot")).isInstanceOf(IllegalStateException.class);
        var old=mapper.readTree("{\"workflowRunId\":\"OLD-RUN\",\"snapshotHash\":\"old-snapshot\"}");
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateProofBinding(context,old,"CURRENT-RUN","current-snapshot")).isInstanceOf(IllegalStateException.class);
        var wrongRun=mapper.readTree("{\"workflowRunId\":\"OLD-RUN\",\"snapshotHash\":\"current-snapshot\"}");
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateProofBinding(context,wrongRun,"CURRENT-RUN","current-snapshot")).isInstanceOf(IllegalStateException.class);
        var wrongSnapshot=mapper.readTree("{\"workflowRunId\":\"CURRENT-RUN\",\"snapshotHash\":\"old-snapshot\"}");
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateProofBinding(context,wrongSnapshot,"CURRENT-RUN","current-snapshot")).isInstanceOf(IllegalStateException.class);
        var staleContext=mapper.readTree("{\"identity\":{\"runId\":\"OLD-RUN\",\"snapshotHash\":\"old-snapshot\"}}");
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateProofBinding(staleContext,old,"CURRENT-RUN","current-snapshot")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateProofBinding(context,mapper.readTree("{}"),"CURRENT-RUN","current-snapshot")).isInstanceOf(IllegalStateException.class);
    }
    @Test void proofBodyAcceptsBothExactCurrentBindings() throws Exception {
        var mapper=new ObjectMapper();
        for(String run:java.util.List.of("CURRENT-RUN","ANOTHER-CURRENT-RUN")) {
            var context=mapper.createObjectNode();context.putObject("identity").put("runId",run).put("snapshotHash","current-snapshot");
            var proof=mapper.createObjectNode().put("workflowRunId",run).put("snapshotHash","current-snapshot");
            SupportFirstChargeCommittedRuntimeTest.validateProofBinding(context,proof,run,"current-snapshot");
        }
    }
    @Test void liveSourceSavesRawCommittedEvidenceBeforeFinancialAndOwnerAssertions() throws Exception {
        var source=java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("cs.committed.candidate.source","src/test/java/ffdd/opsconsole/content/application/SupportFirstChargeCommittedRuntimeTest.java")));
        assertLiveEvidenceOrder(source);
        assertThatThrownBy(()->assertLiveEvidenceOrder(source.replace("rememberCommittedEvidence(customer,\"PURCHASE_PAID_BEFORE_ASSERTIONS\"","omittedCheckpoint(customer,\"PURCHASE_PAID_BEFORE_ASSERTIONS\""))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(()->assertLiveEvidenceOrder(source.replace("rememberCommittedEvidence(customer,\"CARD_SETTLED_BEFORE_ASSERTIONS\"","omittedCheckpoint(customer,\"CARD_SETTLED_BEFORE_ASSERTIONS\""))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(()->assertLiveEvidenceOrder(source.replace("nativeObservations.put(\"committedReadback-\"+committedCommands.size(),observation);saveIntent();","nativeObservations.put(\"committedReadback-\"+committedCommands.size(),observation);"))).isInstanceOf(AssertionError.class);
    }
    static void assertLiveEvidenceOrder(String source) {
        var purchase=source.substring(source.indexOf("    Map<String,Object> paymentWitness("),source.indexOf("    // Private raw observations"));
        var deposit=source.substring(source.indexOf("    void deposit("),source.indexOf("    Map<String,Object> paymentWitness("));
        for(var pair:java.util.List.of(new String[]{purchase,"rememberCommittedEvidence(customer,\"PURCHASE_PAID_BEFORE_ASSERTIONS\""},new String[]{deposit,"rememberCommittedEvidence(customer,\"CARD_SETTLED_BEFORE_ASSERTIONS\""})) {
            int checkpoint=pair[0].indexOf(pair[1]);assertThat(checkpoint).isNotNegative();
            assertThat(checkpoint).isLessThan(pair[0].indexOf("assertThat(payment)"));assertThat(checkpoint).isLessThan(pair[0].indexOf("assertOwner("));
        }
        var helper=source.substring(source.indexOf("    void rememberCommittedEvidence("),source.indexOf("    void assertOwner("));
        assertThat(helper).contains("nativeObservations.put(\"committedReadback-\"+committedCommands.size(),observation);saveIntent();");
        assertThat(helper.indexOf("saveIntent();")).isLessThan(helper.indexOf("source.readHistory("));
        assertThat(helper).contains("observation.put(\"actualSource\",source.readHistory(List.of(customer)));saveIntent();","observation.put(\"route\",physical(\"SELECT * FROM nx_support_customer_route_history WHERE customer_id=? ORDER BY id\",customer));saveIntent();");
    }
    @Test void finalizedAndUnknownCloneStateCannotReachDatabase() throws Exception {
        var mapper=new ObjectMapper();
        for(String state:java.util.List.of("{\"stage\":\"FINALIZED_SOURCE_RESTORED\",\"finalized\":true}","{\"stage\":\"CLONE_READY_PENDING_WORKFLOW_BINDING\",\"finalized\":true}","{\"stage\":\"CLONE_READY_PENDING_WORKFLOW_BINDING\"}","{}"))
            assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.validateCloneState(mapper.readTree(state))).isInstanceOf(IllegalStateException.class);
        SupportFirstChargeCommittedRuntimeTest.validateCloneState(mapper.readTree("{\"stage\":\"CLONE_READY_PENDING_WORKFLOW_BINDING\",\"finalized\":false}"));
    }
    @Test void monthUsesSourceInstantAndShanghaiHalfOpenBoundary() {
        assertThat(SupportFirstChargeCommittedRuntimeTest.paymentMonth(java.time.LocalDateTime.parse("2026-09-30T16:00:00"),"UTC")).isEqualTo(java.time.YearMonth.of(2026,10));
        assertThat(SupportFirstChargeCommittedRuntimeTest.paymentMonth(java.time.LocalDateTime.parse("2026-09-30T23:59:59.999999"),"Asia/Shanghai")).isEqualTo(java.time.YearMonth.of(2026,9));
        assertThat(SupportFirstChargeCommittedRuntimeTest.paymentMonth(java.time.LocalDateTime.parse("2026-10-01T00:00:00"),"Asia/Shanghai")).isEqualTo(java.time.YearMonth.of(2026,10));
    }
    @Test void positivePeriodCountRejectsZeroUnknownAndMissingValues() throws Exception {
        var mapper=new ObjectMapper();
        SupportFirstChargeCommittedRuntimeTest.assertPeriodCount(mapper.readTree("{\"observed\":1,\"confirmed\":1}"),1);
        SupportFirstChargeCommittedRuntimeTest.assertPeriodCount(mapper.readTree("{\"observed\":0,\"confirmed\":null,\"status\":\"UNKNOWN\"}"),0);
        for(String row:java.util.List.of("{\"observed\":0,\"confirmed\":0}","{\"observed\":1,\"confirmed\":null}","{\"current\":{\"firstConfirmed\":1}}","{}"))
            assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.assertPeriodCount(mapper.readTree(row),1)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.assertPeriodCount(mapper.readTree("{}"),0)).isInstanceOf(AssertionError.class);
    }
    @Test void zeroPeriodCountRejectsContradictionsAndUnprovenNull() throws Exception {
        var mapper=new ObjectMapper();
        SupportFirstChargeCommittedRuntimeTest.assertPeriodCount(mapper.readTree("{\"observed\":0,\"confirmed\":0,\"status\":\"READY\"}"),0);
        SupportFirstChargeCommittedRuntimeTest.assertPeriodCount(mapper.readTree("{\"observed\":0,\"confirmed\":null,\"status\":\"UNKNOWN\"}"),0);
        for(String row:java.util.List.of("{\"observed\":0,\"confirmed\":1}","{\"observed\":0,\"confirmed\":-1}","{\"observed\":0,\"confirmed\":0.0}","{\"observed\":0,\"confirmed\":\"0\"}","{\"observed\":0}","{\"observed\":0,\"confirmed\":null}","{\"observed\":0,\"confirmed\":null,\"status\":\"READY\"}","{\"observed\":0,\"confirmed\":null,\"status\":\"PARTIAL\"}","{\"observed\":0,\"confirmed\":null,\"status\":\"ERROR\"}","{\"observed\":\"0\",\"confirmed\":0}","{\"observed\":0.0,\"confirmed\":0}","{\"confirmed\":0}"))
            assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.assertPeriodCount(mapper.readTree(row),0)).as(row).isInstanceOf(AssertionError.class);
    }
    @Test void liveRefundCannotBypassHttpAuthorityOrCommittedReadback() throws Exception {
        var source=java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("cs.committed.candidate.source","src/test/java/ffdd/opsconsole/content/application/SupportFirstChargeCommittedRuntimeTest.java")));
        assertRefundHttpCoverage(source);
        assertThatThrownBy(()->assertRefundHttpCoverage(source.replace("refundActor(agent,false)","omittedActor(agent,false)"))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(()->assertRefundHttpCoverage(source.replace("rejected(response,403)","omittedDenial(response,403)"))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(()->assertRefundHttpCoverage(source.replace("request(\"POST\",path,actor,body,requestKey)","omittedHttp(\"POST\",path,actor,body,requestKey)"))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(()->assertRefundHttpCoverage(source.replace("assertThat(json.readTree(stored)).isEqualTo(response.body())","omittedResponse(stored)"))).isInstanceOf(AssertionError.class);
    }
    static void assertRefundHttpCoverage(String source) {
        var refund=source.substring(source.indexOf("@Test void committedWholeOrderWalletRefundThenDepositKeepsOriginalFirst"),source.indexOf("    long preparedCustomer()"));
        assertThat(refund).contains("refundActor(boss,true)","refundActor(agent,false)","refundHttp(customer,order,agentIdentity,deniedKey,request,false)","assertThat(refundWitness(customer,order)).isEqualTo(beforeDenied)","assertThat(refundWitness(customer,order)).isEqualTo(beforeReplay)","/api/admin/auth/me","device_e4_order_refund","PREPARED_BEFORE_REFUND_HTTP","request(\"POST\",path,actor,body,requestKey)","rejected(response,403)","assertThat(claims).isEmpty()","var data=ok(response)","assertThat(json.readTree(stored)).isEqualTo(response.body())","financialReadConnectionId","E4_ORDER_REFUNDED","order.refunded","admin.order_refunded");
        assertThat(refund).doesNotContain("deviceOrders.refundOrder(","new TransactionTemplate(","SimpleGrantedAuthority","transactionConnectionId");
        assertThat(refund.indexOf("committedCommands.add(receipt);saveIntent()")).isLessThan(refund.indexOf("request(\"POST\",path,actor,body,requestKey)"));
    }
    @Test void reserveMinimumIncludesRefundAndRoundsUpWithoutChangingThreshold() {
        assertThat(SupportFirstChargeCommittedRuntimeTest.refundReserveMinimum(new java.math.BigDecimal("101"),new java.math.BigDecimal("1000"),new java.math.BigDecimal("10"))).isEqualByComparingTo("1020.10");
        assertThat(SupportFirstChargeCommittedRuntimeTest.refundReserveMinimum(new java.math.BigDecimal("101"),java.math.BigDecimal.ZERO,new java.math.BigDecimal("0.001"))).isEqualByComparingTo("0.01");
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.refundReserveMinimum(java.math.BigDecimal.ZERO,java.math.BigDecimal.ONE,java.math.BigDecimal.ONE)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(()->SupportFirstChargeCommittedRuntimeTest.refundReserveMinimum(java.math.BigDecimal.ONE,java.math.BigDecimal.ONE,java.math.BigDecimal.ZERO)).isInstanceOf(AssertionError.class);
    }
    @Test void liveThreeCasesCannotLoseMonthlyOrCanonicalReserveCoverage() throws Exception {
        var source=java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("cs.committed.candidate.source","src/test/java/ffdd/opsconsole/content/application/SupportFirstChargeCommittedRuntimeTest.java")));
        assertMonthlyAndReserveCoverage(source);
        assertThatThrownBy(()->assertMonthlyAndReserveCoverage(source.replace("assertHttpMonth(customer,1,","omittedMonth(customer,1,"))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(()->assertMonthlyAndReserveCoverage(source.replace("treasury.ensureDevelopmentReserve(minimum,day)","omittedReserve(minimum,day)"))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(()->assertMonthlyAndReserveCoverage(source.replace("rankPublications.publish(","omittedPublication("))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(()->assertMonthlyAndReserveCoverage(source.replace("validateCloneState(mapper.readTree(","omittedCloneState(mapper.readTree("))).isInstanceOf(AssertionError.class);
    }
    static void assertMonthlyAndReserveCoverage(String source) {
        String[] cases={"committedPurchaseReplayThenDepositKeepsOriginalFirst","committedDepositThenPurchaseKeepsOriginalFirstAndOwner","committedWholeOrderWalletRefundThenDepositKeepsOriginalFirst"};
        for(int i=0;i<cases.length;i++) {
            int start=source.indexOf("@Test void "+cases[i]),end=i+1<cases.length?source.indexOf("@Test void "+cases[i+1]):source.indexOf("    long preparedCustomer()");
            assertThat(start).isNotNegative();assertThat(end).isGreaterThan(start);var body=source.substring(start,end);
            assertThat(body).contains("assertHttpMonth(customer,0,\"BEFORE_FIRST_PAYMENT\")","assertHttpMonth(customer,1,","assertHttpFirstTwice(customer,original)");
        }
        var refund=source.substring(source.indexOf("@Test void "+cases[2]),source.indexOf("    long preparedCustomer()"));
        assertThat(refund.indexOf("prepareRefundReserve(customer,order)")).isNotNegative().isLessThan(refund.indexOf("refundHttp(customer,order,bossIdentity,"));
        var month=source.substring(source.indexOf("    void assertHttpMonth("),source.indexOf("    static BigDecimal refundReserveMinimum("));
        assertThat(month).contains("basis=PERIOD_EVENT","&agentId=","&groupId=","rankSource.readForAuthorizedLeaderboard(","rankPublications.publish(","nx_support_leaderboard_publication","nx_support_leaderboard_latest","rank.path(\"viewVersion\")","ownedGroup.path(\"period\").path(\"firstCandidates\")");
        var reserve=source.substring(source.indexOf("    void prepareRefundReserve("),source.indexOf("    void assertHttpFirstTwice("));
        assertThat(reserve).contains("requireClone()","nexion.treasury.development-reserve.enabled","treasury.ensureDevelopmentReserve(minimum,day)","nx_admin_idempotency_record","nx_treasury_reserve_ledger","nx_audit_log","nx_event_outbox","coverageProjected()","reserve.add(amount)");
        assertThat(source.indexOf("validateCloneState(mapper.readTree(")).isNotNegative().isLessThan(source.indexOf("try(var connection=openPhysicalConnection())"));
    }
    public static void main(String[] ignored) throws Exception {
        var probe=new SupportFirstChargeCommittedGuardTest();probe.originalSourceAndOtherDirectoriesAreRejected();probe.heldAccountAndVersionCannotBorrowOtherCustomerOrClaimUnknownContinuation();
        probe.proofBodyRejectsMissingOldWrongAndContextOnlyCurrentBindings();probe.proofBodyAcceptsBothExactCurrentBindings();
        probe.liveSourceSavesRawCommittedEvidenceBeforeFinancialAndOwnerAssertions();
        probe.finalizedAndUnknownCloneStateCannotReachDatabase();probe.monthUsesSourceInstantAndShanghaiHalfOpenBoundary();probe.positivePeriodCountRejectsZeroUnknownAndMissingValues();
        probe.reserveMinimumIncludesRefundAndRoundsUpWithoutChangingThreshold();probe.liveThreeCasesCannotLoseMonthlyOrCanonicalReserveCoverage();
        probe.zeroPeriodCountRejectsContradictionsAndUnprovenNull();probe.liveRefundCannotBypassHttpAuthorityOrCommittedReadback();
        probe.committedProductsAcceptBothConsumptionTypesAndRejectCaseMismatch();
        System.out.println("PASS 13 pure guard methods: 12 preserved + DEVICE/SHARE case-match counterexamples; native NOT_EXECUTED; cleanup pending; no phase signoff");
    }
}
