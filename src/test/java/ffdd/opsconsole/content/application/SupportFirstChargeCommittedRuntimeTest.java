package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.commerce.application.AppOrderCommandService;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats;
import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.dto.DeviceOrderActionRequest;
import ffdd.opsconsole.finance.application.SupportPaymentSourceService;
import ffdd.opsconsole.finance.application.TopupCardLifecycleService;
import ffdd.opsconsole.finance.dto.TopupCardAdmissionRequest;
import ffdd.opsconsole.finance.dto.TopupCardSettlementRequest;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService;
import ffdd.opsconsole.treasury.application.OpsTreasuryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.core.env.Environment;

/** Test-only committed commands on the single disposable clone; never a shared-row restoration certificate. */
@EnabledIfEnvironmentVariable(named="CS_ANALYTICS_COMMITTED_ENABLED",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({SupportIsolatedRuntime.class,SupportObjectEvidenceLedger.Configuration.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportFirstChargeCommittedRuntimeTest extends SupportAnalyticsRuntimeFixture {
    static final String ORIGINAL_UUID="3556ddae-c1a1-11f1-8853-a40c6626953d";
    static final String CLONE_DIRECTORY="D:/CodexData/test-environments/workflow-runs/support-analytics-20261007-r2/runtime/support-committed-clone-r21-fresh-v1/mysql-data";
    @Autowired AppCanonicalBoundaryService checkout;
    @Autowired AppOrderCommandService orders;
    @Autowired TopupCardLifecycleService topups;
    @Autowired PlatformConfigFacade config;
    @Autowired SupportPaymentSourceService source;
    @Autowired SupportAnalyticsService statistics;
    @Autowired OpsDeviceService deviceOrders;
    @Autowired OpsTreasuryService treasury;
    @Autowired SupportLeaderboardSourceService rankSource;
    @Autowired SupportLeaderboardPublicationService rankPublications;
    @Autowired Environment environment;
    final List<Map<String,Object>> committedCommands=new ArrayList<>();
    final Set<String> preparedKeys=new HashSet<>();
    boolean cloneGuardPassed;

    @DynamicPropertySource static void boundary(DynamicPropertyRegistry registry) throws Exception {
        // The read-only identity fence runs before Spring boot and its fixture beans can write.
        requireClone();
        SupportEnhancementPreparationTest.isolatedBoundary(registry);
    }

    static void validateCloneIdentity(String uuid,String directory) {
        if(uuid==null || !uuid.matches("[0-9a-fA-F-]{36}") || ORIGINAL_UUID.equalsIgnoreCase(uuid)
                || directory==null || !CLONE_DIRECTORY.equals(directory.replace('\\','/').replaceAll("/+$","")))
            throw new IllegalStateException("Dedicated committed clone identity required");
    }

    static void validateProofBinding(JsonNode context,JsonNode proof,String expectedRun,String expectedSnapshot) {
        if(context==null || proof==null || expectedRun==null || expectedRun.isBlank()
                || expectedSnapshot==null || expectedSnapshot.isBlank()
                || !proof.path("workflowRunId").isTextual() || !proof.path("snapshotHash").isTextual()
                || !expectedRun.equals(proof.path("workflowRunId").asText())
                || !expectedSnapshot.equals(proof.path("snapshotHash").asText())
                || !expectedRun.equals(context.path("identity").path("runId").asText())
                || !expectedSnapshot.equals(context.path("identity").path("snapshotHash").asText()))
            throw new IllegalStateException("Ownership proof must bind the current run and snapshot");
    }

    static void validateCloneState(JsonNode state) {
        if(state==null || !"CLONE_READY_PENDING_WORKFLOW_BINDING".equals(state.path("stage").asText())
                || !state.path("finalized").isBoolean() || state.path("finalized").asBoolean())
            throw new IllegalStateException("Fresh unfinalized committed clone required");
    }

    static JsonNode requireClone() throws Exception {
        if(!"true".equals(System.getenv("CS_ANALYTICS_COMMITTED_ENABLED")))
            throw new IllegalStateException("Committed suite explicit opt-in required");
        var target=SupportRuntimeTarget.current();
        if(!target.freshCommitted())throw new IllegalStateException("Fixed fresh committed target required");
        target.requireEnvironment(System.getenv());
        var mapper=new ObjectMapper();
        validateCloneState(mapper.readTree(Path.of(CLONE_DIRECTORY).getParent().resolve("state.json").toFile()));
        var context=mapper.readTree(Path.of(required("CS_ENHANCE_ACTOR_CONTEXT")).toFile());
        if(!required("WORKFLOW_RUN_ID").equals(context.path("identity").path("runId").asText())
                || !required("WORKFLOW_SNAPSHOT_HASH").equals(context.path("identity").path("snapshotHash").asText()))
            throw new IllegalStateException("Current clone context identity required");
        var proof=SupportExclusiveRuntimeOwnership.validate(context,target);
        validateProofBinding(context,proof,required("WORKFLOW_RUN_ID"),required("WORKFLOW_SNAPSHOT_HASH"));
        var expected=proof.path("databaseIdentity");
        validateCloneIdentity(expected.path("serverUuid").asText(),expected.path("dataDirectory").asText());
        try(var connection=openPhysicalConnection()) {
            connection.setReadOnly(true);
            var read=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            SupportExclusiveRuntimeOwnership.requireActual(context,target,read);
            var actual=read.queryForMap("SELECT @@server_uuid AS uuid,@@datadir AS directory");
            validateCloneIdentity(String.valueOf(actual.get("uuid")),String.valueOf(actual.get("directory")));
        }
        return context;
    }

    static Connection openPhysicalConnection() throws SQLException {
        return DriverManager.getConnection(required("NEXION_DB_URL"),required("NEXION_DB_USERNAME"),required("NEXION_DB_PASSWORD"));
    }

    @Override @BeforeEach void controlledActorsAndCurrentResource(TestInfo info) throws Exception {
        requireClone();cloneGuardPassed=true;
        super.controlledActorsAndCurrentResource(info);
        nativeObservations.put("cloneGuard",Map.of("originalUuidRejected",ORIGINAL_UUID,"exactCloneDirectory",CLONE_DIRECTORY,
                "contextSha256",sha256(Files.readString(Path.of(required("CS_ENHANCE_ACTOR_CONTEXT"))))));
    }

    @Test void committedPurchaseReplayThenDepositKeepsOriginalFirst() throws Exception {
        long customer=preparedCustomer();
        String order=createOrder(customer,"stellarbox-s1","DEVICE");var pending=physical("SELECT * FROM nx_order WHERE order_no=? AND user_id=?",order,customer);
        assertThat(pending).singleElement().satisfies(row->assertThat(row.get("payment_status")).isEqualTo("PENDING"));
        assertThat(first(customer).state()).isEqualTo(SupportAnalyticsStats.FirstState.NONE);
        assertHttpMonth(customer,0,"BEFORE_FIRST_PAYMENT");
        var beforePayWallet=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer).get(0);
        String payKey=key();pay(customer,order,payKey,false);
        var paid=paymentWitness(customer,order);var original=first(customer);var originalSource=source.readHistory(List.of(customer)).facts().get(0);
        var afterPayWallet=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer).get(0);
        assertThat(new BigDecimal(afterPayWallet.get("usdt_available").toString())).isEqualByComparingTo(new BigDecimal(beforePayWallet.get("usdt_available").toString()).subtract(originalSource.amount()));
        assertThat(((Number)afterPayWallet.get("version")).longValue()).isEqualTo(((Number)beforePayWallet.get("version")).longValue()+1);
        assertThat(original.state()).isEqualTo(SupportAnalyticsStats.FirstState.CONFIRMED);
        assertThat(original.observedCandidate().kind()).isEqualTo("DEVICE_PURCHASE");
        assertHttpFirstTwice(customer,original);assertHttpMonth(customer,1,"PURCHASE_FIRST");var heldWallet=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer);
        pay(customer,order,payKey,false);assertHttpMonth(customer,1,"SAME_KEY_PURCHASE_REPLAY");pay(customer,order,key(),true);
        assertThat(physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer)).isEqualTo(heldWallet);
        assertThat(paymentWitness(customer,order)).isEqualTo(paid);
        assertThat(first(customer)).isEqualTo(original);assertHttpFirstTwice(customer,original);
        assertHttpMonth(customer,1,"PURCHASE_REPLAYED");
        awaitLaterSecond(customer,originalSource.succeededAt());deposit(customer,"afterpurchase");
        assertThat(first(customer)).isEqualTo(original);assertHttpFirstTwice(customer,original);
        assertHttpMonth(customer,1,"LATER_DEPOSIT");
        assertThat(source.readHistory(List.of(customer)).facts()).hasSize(2).contains(originalSource);
        assertThat(physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? AND kind='DEVICE_PURCHASE'",customer))
                .isEqualTo(paid.get("capture"));
        nativeObservations.put("purchaseFirst",Map.of("original",original,"originalSource",originalSource,"purchaseWitness",paid,
                "walletHeldAcrossBothReplays",heldWallet,"afterSources",source.readHistory(List.of(customer))));
        checked("Committed genuine create/pay, distinct physical SQL read, actual HTTP twice, same-key cache and new-key paid-order replay without second debit/device, later committed deposit retains exact original first/source/owner. Partial only; clone finalization pending.");
    }

    @Test void committedDepositThenPurchaseKeepsOriginalFirstAndOwner() throws Exception {
        long customer=preparedCustomer();assertHttpMonth(customer,0,"BEFORE_FIRST_PAYMENT");deposit(customer,"beforepurchase");var original=first(customer);
        var capture=physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? ORDER BY fact_id",customer);
        assertThat(original.observedCandidate().kind()).isEqualTo("DEPOSIT");assertHttpFirstTwice(customer,original);
        assertHttpMonth(customer,1,"DEPOSIT_FIRST");
        awaitLaterSecond(customer,source.readHistory(List.of(customer)).facts().get(0).succeededAt());String order=createOrder(customer,"cloud-share","SHARE");pay(customer,order,key(),false);
        paymentWitness(customer,order);assertThat(first(customer)).isEqualTo(original);assertHttpFirstTwice(customer,original);
        assertHttpMonth(customer,1,"LATER_PURCHASE");
        assertThat(physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? ORDER BY fact_id",customer)).hasSize(2).containsAll(capture);
        assertThat(source.readHistory(List.of(customer)).facts()).hasSize(2);
        nativeObservations.put("depositFirst",Map.of("original",original,"originalCapture",capture,"afterSources",source.readHistory(List.of(customer))));
        checked("Independent born customer, real card admit/settle commit then wallet purchase commit, physical payment/ledger/device/capture and actual HTTP twice preserve original deposit-first owner. Partial only; clone finalization pending.");
    }

    @Test void committedWholeOrderWalletRefundThenDepositKeepsOriginalFirst() throws Exception {
        long customer=preparedCustomer();assertHttpMonth(customer,0,"BEFORE_FIRST_PAYMENT");String order=createOrder(customer,"stellarbox-s1","DEVICE");pay(customer,order,key(),false);
        var original=first(customer);var purchase=source.readHistory(List.of(customer)).facts().get(0);paymentWitness(customer,order);
        assertHttpFirstTwice(customer,original);assertHttpMonth(customer,1,"PURCHASE_BEFORE_REFUND");
        prepareRefundReserve(customer,order);
        nativeObservations.put("originalRefundPolicyPreview",as(boss,()->deviceOrders.order(order)));
        String refundKey=key();var request=new DeviceOrderActionRequest("refunded","WALLET","Committed original wallet order refund",run);
        var bossIdentity=refundActor(boss,true);var agentIdentity=refundActor(agent,false);
        var beforeDenied=refundWitness(customer,order);String deniedKey=key();
        refundHttp(customer,order,agentIdentity,deniedKey,request,false);
        assertThat(refundWitness(customer,order)).isEqualTo(beforeDenied);
        refundHttp(customer,order,bossIdentity,refundKey,request,true);
        var beforeReplay=refundWitness(customer,order);
        refundHttp(customer,order,bossIdentity,refundKey,request,true);
        assertThat(refundWitness(customer,order)).isEqualTo(beforeReplay);
        var refunded=source.readHistory(List.of(customer));assertThat(refunded.facts()).hasSize(2).contains(purchase);
        assertThat(refunded.facts()).filteredOn(f->f.kind()==SupportPaymentFacts.Kind.DEVICE_PURCHASE_REFUND).singleElement()
                .satisfies(f->{assertThat(f.originalFactId()).isEqualTo(purchase.factId());assertThat(f.amount()).isEqualByComparingTo(purchase.amount());});
        var refundLedger=physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND biz_type='ORDER_REFUND'",customer,"E4-REFUND-"+order);
        assertThat(refundLedger).hasSize(1);assertThat(first(customer)).isEqualTo(original);assertHttpFirstTwice(customer,original);
        assertHttpMonth(customer,1,"WHOLE_ORDER_REFUND");
        awaitLaterSecond(customer,purchase.succeededAt());deposit(customer,"afterrefund");
        assertThat(first(customer)).isEqualTo(original);assertHttpFirstTwice(customer,original);assertThat(source.readHistory(List.of(customer)).facts()).hasSize(3).contains(purchase);
        assertHttpMonth(customer,1,"DEPOSIT_AFTER_REFUND");
        nativeObservations.put("wholeOrderRefund",Map.of("original",original,"originalSource",purchase,"refundSources",refunded,"refundLedger",refundLedger));
        checked("Owned JWT identities and live refund authority checked; support HTTP 403 leaves financial rows unchanged without claim; boss whole-order WALLET refund and same-key replay pass original E4 HTTP permission/reason/A2/treasury guards, preserve linked source and original first. Clone finalization pending.");
    }

    JsonNode refundActor(long actor,boolean allowed) throws Exception {
        assertThat(actors.ownedIds()).contains(actor);
        var row=physical("SELECT id,username FROM nx_admin WHERE id=?",actor);assertThat(row).hasSize(1);
        var identity=ok(request("GET","/api/admin/auth/me",actor,null,null));
        assertThat(identity.path("adminId").asLong()).isEqualTo(actor);
        assertThat(identity.path("username").asText()).isEqualTo(row.get(0).get("username"));
        assertThat(identity.path("authorities").isArray()).isTrue();
        var authorities=new ArrayList<String>();identity.path("authorities").forEach(value->authorities.add(value.asText()));
        assertThat(authorities.contains("device_e4_order_refund")).isEqualTo(allowed);
        nativeObservations.put("refundHttpIdentity-"+actor,identity);saveIntent();return identity;
    }

    void refundHttp(long customer,String order,JsonNode identity,String requestKey,DeviceOrderActionRequest body,boolean allowed) throws Exception {
        requireClone();requireCustomer(customer);assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        long actor=identity.path("adminId").asLong();assertThat(actors.ownedIds()).contains(actor);
        assertThat(physical("SELECT * FROM nx_order WHERE order_no=? AND user_id=?",order,customer)).hasSize(1);
        String scope="E4_ORDER_REFUND",path="/api/admin/devices/orders/"+order+"/refund";
        String username=identity.path("username").asText();
        var trusted=new DeviceOrderActionRequest(body.terminalState(),body.refundChannel(),body.reason().trim(),username);
        String requestHash=sha256(scope+"\n"+order.trim()+"\n"+trusted);
        if(preparedKeys.add(scope+"\n"+requestKey))assertThat(physical("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",scope,requestKey)).isEmpty();
        var receipt=new LinkedHashMap<String,Object>();receipt.put("event","PREPARED_BEFORE_REFUND_HTTP");receipt.put("customerCreator",customerIdentities.get(customer));receipt.put("scope",scope);receipt.put("key",requestKey);receipt.put("requestHash",requestHash);receipt.put("method","POST");receipt.put("path",path);receipt.put("actor",identity);receipt.put("request",body);receipt.put("trustedRequest",trusted);
        committedCommands.add(receipt);saveIntent();
        var response=request("POST",path,actor,body,requestKey);receipt.put("httpStatus",response.status());receipt.put("httpBody",response.body());saveIntent();
        var claims=physical("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",scope,requestKey);
        receipt.put("claimRows",claims);receipt.put("readConnectionId",lastReadConnectionId);receipt.put("event","REFUND_HTTP_READBACK");saveIntent();
        receipt.put("financialReadback",refundWitness(customer,order));receipt.put("financialReadConnectionId",lastReadConnectionId);saveIntent();
        if(!allowed) {rejected(response,403);assertThat(claims).isEmpty();return;}
        var data=ok(response);assertThat(data.path("state").asText()).isEqualTo("refunded");
        assertThat(claims).hasSize(1);var claim=claims.get(0);
        assertThat(claim.get("request_hash")).isEqualTo(requestHash);assertThat(claim.get("status")).isEqualTo("SUCCEEDED");assertThat(claim.get("response_json")).isNotNull();
        String stored=claim.get("response_json").toString();receipt.put("storedResponseHash",sha256(stored));saveIntent();
        assertThat(json.readTree(stored)).isEqualTo(response.body());
        var audit=physical("SELECT * FROM nx_audit_log WHERE action='E4_ORDER_REFUNDED' AND resource_id=?",order);receipt.put("audit",audit);assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("result")).isEqualTo("SUCCESS");assertThat(audit.get(0).get("actor_username")).isEqualTo(username);
        JsonNode detail=json.readTree(audit.get(0).get("detail_json").toString());
        assertThat(detail.path("idempotencyKey").asText()).isEqualTo(requestKey);assertThat(detail.path("reason").asText()).isEqualTo(body.reason().trim());assertThat(detail.path("refundChannel").asText()).isEqualTo("WALLET");
        var events=physical("SELECT * FROM nx_event_outbox WHERE aggregate_type='E4_ORDER' AND aggregate_id=? AND event_name IN ('order.refunded','admin.order_refunded') ORDER BY event_name,id",order);receipt.put("outbox",events);saveIntent();
        assertThat(events).hasSize(2);assertThat(events.stream().map(row->row.get("event_name"))).containsExactly("admin.order_refunded","order.refunded");
        for(var event:events) {var payload=json.readTree(event.get("payload").toString());assertThat(payload.path("operator").asText()).isEqualTo(username);assertThat(payload.path("reason").asText()).isEqualTo(body.reason().trim());assertThat(payload.path("refundChannel").asText()).isEqualTo("WALLET");}
    }

    Map<String,Object> refundWitness(long customer,String order) throws Exception {
        var rows=new LinkedHashMap<String,Object>();rows.put("order",physical("SELECT * FROM nx_order WHERE user_id=? AND order_no=?",customer,order));
        rows.put("wallet",physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer));
        rows.put("payment",physical("SELECT * FROM nx_payment_record WHERE user_id=? AND order_no=?",customer,order));
        rows.put("purchaseLedger",physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND biz_type='ORDER_PURCHASE'",customer,order));
        rows.put("device",physical("SELECT * FROM nx_user_device WHERE user_id=? AND source_order_no=?",customer,order));
        rows.put("checkoutOutbox",physical("SELECT * FROM nx_event_outbox WHERE aggregate_type='ORDER' AND aggregate_id=? AND event_name='checkout.completed'",order));
        rows.put("refundLedger",physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND biz_type='ORDER_REFUND'",customer,"E4-REFUND-"+order));
        rows.put("refundBill",physical("SELECT * FROM nx_wallet_bill WHERE user_id=? AND bill_no=?",customer,"E4-BILL-"+order));
        rows.put("capture",physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? ORDER BY fact_id",customer));
        rows.put("history",physical("SELECT * FROM nx_order_state_history WHERE order_no=? ORDER BY id",order));
        rows.put("audit",physical("SELECT * FROM nx_audit_log WHERE action='E4_ORDER_REFUNDED' AND resource_id=? ORDER BY id",order));
        rows.put("outbox",physical("SELECT * FROM nx_event_outbox WHERE aggregate_type='E4_ORDER' AND aggregate_id=? AND event_name IN ('order.refunded','admin.order_refunded') ORDER BY event_name,id",order));
        rows.put("source",source.readHistory(List.of(customer)).facts());rows.put("first",first(customer));
        nativeObservations.put("refundWitness-"+committedCommands.size(),rows);saveIntent();return rows;
    }

    long preparedCustomer() throws Exception {
        long customer=customer(true);bind(customer,agent);wallet(customer,"1000000","0");
        assertThat(physical("SELECT * FROM nx_support_payment_history_birth WHERE customer_id=?",customer)).hasSize(1);
        return customer;
    }

    String createOrder(long customer,String expectedSku,String expectedType) throws Exception {
        requireCustomer(customer);
        validateCommittedProduct(Map.of("product_no",expectedSku,"product_type",expectedType),expectedSku,expectedType);
        var candidates=jdbc.queryForList("SELECT id,product_no,product_type,price_usdt FROM nx_product WHERE product_no=? AND product_type=? AND is_deleted=0 AND status IN ('ACTIVE','ON_SALE') AND COALESCE(store_visible,1)=1 AND price_usdt>0 AND price_usdt<1000000 ORDER BY price_usdt,id",expectedSku,expectedType);
        var chosen=candidates.stream().filter(p->{var eligible=checkout.purchaseEligibility(customer,String.valueOf(p.get("product_no")));return eligible.getCode()==0 && eligible.getData()!=null && Boolean.TRUE.equals(eligible.getData().get("eligible"));}).findFirst().orElseThrow(()->new AssertionError("Actual eligible released SKU required; do not change buyer gates"));
        validateCommittedProduct(chosen,expectedSku,expectedType);
        long product=((Number)chosen.get("id")).longValue();String sku=String.valueOf(chosen.get("product_no"));
        var payload=new LinkedHashMap<String,Object>();payload.put("clientOrderId",null);payload.put("productId",product);payload.put("productNo",sku);payload.put("quantity",1);payload.put("voucherId",null);
        String requestKey=key();var result=commit(customer,"APP:ORDER_CREATE:USER:"+customer,requestKey,sha256(payload.toString()),payload,
                ()->checkout.createOrder(customer,null,product,sku,1,null,requestKey));
        assertThat(result.getCode()).as(result.getMessage()).isZero();String order=String.valueOf(result.getData().get("orderNo"));assertThat(order).startsWith("ORD-");
        assertThat(physical("SELECT * FROM nx_order_item WHERE order_no=?",order)).singleElement().satisfies(row->{
            assertThat(row.get("product_no")).isEqualTo(expectedSku);
            assertThat(((Number)row.get("product_id")).longValue()).isEqualTo(product);
        });
        var productReadback=physical("SELECT product_no,product_type FROM nx_product WHERE id=?",product);
        assertThat(productReadback).singleElement().satisfies(row->validateCommittedProduct(row,expectedSku,expectedType));
        nativeObservations.put("committedProduct",Map.of("sku",expectedSku,"type",expectedType,"productId",product,"orderNo",order));saveIntent();
        return order;
    }
    static void validateCommittedProduct(Map<String,Object> product,String expectedSku,String expectedType) {
        boolean covered=("stellarbox-s1".equals(expectedSku) && "DEVICE".equals(expectedType))
                || ("cloud-share".equals(expectedSku) && "SHARE".equals(expectedType));
        if(!covered || product==null || !expectedSku.equals(product.get("product_no")) || !expectedType.equals(product.get("product_type")))
            throw new IllegalStateException("Actual product must match this committed consumption case");
    }
    void pay(long customer,String order,String requestKey,boolean alreadyPaid) throws Exception {
        var result=commit(customer,"APP:ORDER_PAYMENT:USER:"+customer,requestKey,sha256(customer+"|"+order),Map.of("orderNo",order,"customerId",customer),()->orders.pay(customer,order,requestKey));
        assertThat(result.getCode()).as(result.getMessage()).isZero();assertThat(result.getData()).containsEntry("orderNo",order).containsEntry("idempotent",alreadyPaid);
    }

    <T>T commit(long customer,String scope,String requestKey,String requestHash,Object payload,Supplier<T> command) throws Exception {
        requireClone();requireCustomer(customer);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        if(scope.startsWith("APP:"))assertThat(scope).endsWith(":USER:"+customer);
        String identity=scope+"\n"+requestKey;
        if(preparedKeys.add(identity))assertThat(physical("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",scope,requestKey)).isEmpty();
        if(payload instanceof Map<?,?> map && map.containsKey("orderNo"))assertThat(physical("SELECT * FROM nx_order WHERE order_no=? AND user_id=?",map.get("orderNo"),customer)).hasSize(1);
        var receipt=new LinkedHashMap<String,Object>();receipt.put("event","PREPARED_BEFORE_CALL");receipt.put("customerCreator",customerIdentities.get(customer));receipt.put("scope",scope);receipt.put("key",requestKey);receipt.put("requestHash",requestHash);receipt.put("request",payload);
        committedCommands.add(receipt);saveIntent();
        var transaction=new TransactionTemplate(transactions);transaction.setTimeout(90);
        T result=transaction.execute(status->{receipt.put("transactionConnectionId",jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));return command.get();});
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var rows=physical("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",scope,requestKey);assertThat(rows).hasSize(1);
        var row=rows.get(0);assertThat(row.get("request_hash")).isEqualTo(requestHash);assertThat(row.get("status")).isEqualTo("SUCCEEDED");assertThat(row.get("response_json")).isNotNull();
        receipt.put("event","COMMITTED_READBACK");receipt.put("claimRow",row);receipt.put("readConnectionId",lastReadConnectionId);
        assertThat(lastReadConnectionId).isNotEqualTo(receipt.get("transactionConnectionId"));saveIntent();return result;
    }

    void deposit(long customer,String suffix) throws Exception {
        requireClone();requireCustomer(customer);String stem=run+suffix;String provider=config.activeValue("finance.topup.psp.primary").orElse("Checkout.com");
        assertThat(physical("SELECT * FROM nx_topup_card_admission WHERE admission_event_id=? OR order_no=?",stem+"admit",stem+"topup")).isEmpty();
        assertThat(physical("SELECT * FROM nx_topup_card_settlement WHERE settlement_event_id=?",stem+"settle")).isEmpty();
        var admissionRequest=new TopupCardAdmissionRequest(stem+"admit",stem+"topup",customer,provider,new BigDecimal("100"),"AUTHENTICATED","424242","127.0.0.1",stem+"device");
        var intent=new LinkedHashMap<String,Object>();intent.put("event","PREPARED_BEFORE_CARD_ADMIT");intent.put("customerCreator",customerIdentities.get(customer));intent.put("admissionRequest",admissionRequest);intent.put("admissionIntentHash",sha256(json.writeValueAsString(admissionRequest)));committedCommands.add(intent);saveIntent();
        var tx=new TransactionTemplate(transactions);var admission=tx.execute(status->topups.admit(admissionRequest));assertThat(admission.decision()).isEqualTo("ALLOWED");
        var admissionRows=physical("SELECT * FROM nx_topup_card_admission WHERE user_id=? AND admission_event_id=?",customer,stem+"admit");assertThat(admissionRows).singleElement().satisfies(row->{assertThat(row.get("decision")).isEqualTo("ALLOWED");assertThat(String.valueOf(row.get("request_hash"))).matches("[0-9a-f]{64}");});intent.put("actualAdmissionRows",admissionRows);
        var settlementRequest=new TopupCardSettlementRequest(stem+"settle",stem+"admit",stem+"payment",stem+"topup",customer,provider,stem+"providerpayment",new BigDecimal("100"),admission.feeAmountUsdt(),admission.feeRatePct(),jdbc.queryForObject("SELECT NOW(6)",LocalDateTime.class));
        intent.put("event","PREPARED_BEFORE_CARD_SETTLE");intent.put("settlementRequest",settlementRequest);intent.put("settlementIntentHash",sha256(json.writeValueAsString(settlementRequest)));saveIntent();
        tx.executeWithoutResult(status->{intent.put("transactionConnectionId",jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));topups.settle(settlementRequest);});
        var committedCapture=physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? AND kind='DEPOSIT'",customer);
        rememberCommittedEvidence(customer,"CARD_SETTLED_BEFORE_ASSERTIONS",Map.of(
                "payment",physical("SELECT * FROM nx_payment_record WHERE user_id=? AND payment_no=?",customer,stem+"payment"),
                "ledger",physical("SELECT * FROM nx_wallet_ledger WHERE id=? AND user_id=?",committedCapture.isEmpty()?null:committedCapture.get(0).get("ledger_id"),customer),
                "wallet",physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer),
                "settlement",physical("SELECT * FROM nx_topup_card_settlement WHERE user_id=? AND settlement_event_id=?",customer,stem+"settle"),
                "outbox",physical("SELECT * FROM nx_event_outbox WHERE aggregate_type='WALLET' AND aggregate_id=? AND event_name='wallet.topup_confirmed'",stem+"payment"),"capture",committedCapture));
        var payment=physical("SELECT * FROM nx_payment_record WHERE user_id=? AND payment_no=?",customer,stem+"payment");assertThat(payment).hasSize(1);intent.put("readConnectionId",lastReadConnectionId);assertThat(lastReadConnectionId).isNotEqualTo(intent.get("transactionConnectionId"));
        assertHttpMonth(customer,1,"CARD_SETTLED_BEFORE_REPLAY");
        var beforeReplay=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer);intent.put("event","PREPARED_BEFORE_CARD_REPLAY");saveIntent();
        var replay=tx.execute(status->topups.settle(settlementRequest));assertThat(replay.replay()).isTrue();assertThat(physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer)).isEqualTo(beforeReplay);
        assertThat(physical("SELECT * FROM nx_payment_record WHERE user_id=? AND payment_no=?",customer,stem+"payment")).isEqualTo(payment);
        var capture=physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? AND kind='DEPOSIT'",customer);assertThat(capture).hasSize(1);assertOwner(capture.get(0));
        var ledger=physical("SELECT * FROM nx_wallet_ledger WHERE id=? AND user_id=?",capture.get(0).get("ledger_id"),customer);assertThat(ledger).singleElement().satisfies(row->assertThat(row.get("status")).isEqualTo("SUCCESS"));
        var settlementRows=physical("SELECT * FROM nx_topup_card_settlement WHERE user_id=? AND settlement_event_id=?",customer,stem+"settle");assertThat(settlementRows).singleElement().satisfies(row->{assertThat(row.get("status")).isEqualTo("SETTLED");assertThat(String.valueOf(row.get("request_hash"))).matches("[0-9a-f]{64}");});
        var outbox=physical("SELECT * FROM nx_event_outbox WHERE aggregate_type='WALLET' AND aggregate_id=? AND event_name='wallet.topup_confirmed'",stem+"payment");assertThat(outbox).hasSize(1);
        assertThat(source.readHistory(List.of(customer)).facts()).filteredOn(f->f.kind()==SupportPaymentFacts.Kind.DEPOSIT).singleElement().satisfies(f->assertThat(f.amount()).isEqualByComparingTo("100"));
        intent.put("event","COMMITTED_CARD_READBACK");intent.put("paymentRows",payment);intent.put("captureRows",capture);intent.put("walletRows",beforeReplay);intent.put("settlementRows",settlementRows);intent.put("ledgerRows",ledger);intent.put("outboxRows",outbox);saveIntent();
        assertHttpMonth(customer,1,"CARD_SETTLEMENT_REPLAY");
    }

    Map<String,Object> paymentWitness(long customer,String order) throws Exception {
        var payment=physical("SELECT * FROM nx_payment_record WHERE user_id=? AND order_no=?",customer,order);
        var ledger=physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND biz_type='ORDER_PURCHASE'",customer,order);
        var device=physical("SELECT * FROM nx_user_device WHERE user_id=? AND source_order_no=?",customer,order);
        var event=physical("SELECT * FROM nx_event_outbox WHERE aggregate_type='ORDER' AND aggregate_id=? AND event_name='checkout.completed'",order);
        var capture=physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? AND order_no=? AND kind='DEVICE_PURCHASE'",customer,order);
        rememberCommittedEvidence(customer,"PURCHASE_PAID_BEFORE_ASSERTIONS",Map.of("payment",payment,"ledger",ledger,"device",device,"outbox",event,"capture",capture));
        assertThat(payment).singleElement().satisfies(row->{assertThat(row.get("payment_status")).isEqualTo("PAID");assertThat(row.get("provider")).isEqualTo("UVEL_WALLET");});
        assertThat(ledger).singleElement().satisfies(row->assertThat(row.get("status")).isEqualTo("SUCCESS"));
        assertThat(device).hasSize(1);assertThat(event).hasSize(1);assertThat(capture).hasSize(1);assertOwner(capture.get(0));
        return Map.of("payment",payment,"ledger",ledger,"device",device,"outbox",event,"capture",capture);
    }
    // Private raw observations only: empty/mismatched rows are saved without deriving a successful attribution.
    void rememberCommittedEvidence(long customer,String event,Map<String,Object> financialRows) throws Exception {
        var observation=new LinkedHashMap<String,Object>();observation.put("event",event);observation.put("customerCreator",customerIdentities.get(customer));observation.put("financialRows",financialRows);
        committedCommands.add(observation);nativeObservations.put("committedReadback-"+committedCommands.size(),observation);saveIntent();
        observation.put("actualSource",source.readHistory(List.of(customer)));saveIntent();
        observation.put("birth",physical("SELECT * FROM nx_support_payment_history_birth WHERE customer_id=?",customer));
        observation.put("binding",physical("SELECT * FROM nx_support_agent_user_assignment WHERE user_id=? ORDER BY id",customer));
        observation.put("group",physical("SELECT * FROM nx_support_group WHERE id=?",group));
        observation.put("membership",physical("SELECT * FROM nx_support_group_member_history WHERE agent_admin_id=? ORDER BY id",agent));
        observation.put("owner",physical("SELECT * FROM nx_support_group_owner_history WHERE group_id=? ORDER BY id",group));
        var qualifications=new LinkedHashMap<Long,Object>();if(actors!=null)for(long actor:actors.ownedIds())qualifications.put(actor,physical("SELECT * FROM nx_support_account_qualification_history WHERE admin_id=? ORDER BY id",actor));
        observation.put("qualification",qualifications);
        observation.put("queue",physical("SELECT * FROM nx_support_binding_pool WHERE customer_id=?",customer));
        observation.put("route",physical("SELECT * FROM nx_support_customer_route_history WHERE customer_id=? ORDER BY id",customer));saveIntent();
    }
    void assertOwner(Map<String,Object> capture) {
        assertThat(((Number)capture.get("agent_admin_id")).longValue()).isEqualTo(agent);assertThat(((Number)capture.get("group_id")).longValue()).isEqualTo(group);
        assertThat(capture.get("capture_mode")).isEqualTo("NEW_SUCCESS");assertThat(capture.get("source_fact_json")).isNotNull();assertThat(capture.get("attribution_evidence_json")).isNotNull();assertThat(capture.get("capture_db_utc")).isNotNull();
    }
    SupportAnalyticsStats.FirstSelection first(long customer) {
        var query=new SupportAnalyticsStats.Query(null,null,null,SupportAnalyticsStats.Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai","USDT");
        return as(agent,()->statistics.summarize(query)).currentCustomers().stream().filter(row->row.customerId()==customer).findFirst().orElseThrow().first();
    }

    static YearMonth paymentMonth(LocalDateTime at,String sourceZone) {
        return YearMonth.from(at.atZone(ZoneId.of(sourceZone)).withZoneSameInstant(SupportLeaderboard.BUSINESS_ZONE));
    }
    static void assertPeriodCount(JsonNode count,long expected) {
        assertThat(count.path("observed").isIntegralNumber()).isTrue();assertThat(count.path("observed").asLong()).isEqualTo(expected);
        var confirmed=count.path("confirmed");
        if(expected==0 && confirmed.isNull())assertThat(count.path("status").asText()).isEqualTo("UNKNOWN");
        else {assertThat(confirmed.isIntegralNumber()).isTrue();assertThat(confirmed.asLong()).isEqualTo(expected);}
    }

    void assertHttpMonth(long customer,long expected,String checkpoint) throws Exception {
        requireClone();requireCustomer(customer);assertThat(fixture.createdGroupIds()).contains(group);assertThat(actors.ownedIds()).contains(agent,manager);
        var observation=new LinkedHashMap<String,Object>();observation.put("event",checkpoint);observation.put("customerCreator",customerIdentities.get(customer));
        observation.put("capture",physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? ORDER BY fact_id",customer));
        observation.put("birth",physical("SELECT * FROM nx_support_payment_history_birth WHERE customer_id=?",customer));
        observation.put("bindings",physical("SELECT * FROM nx_support_agent_user_assignment WHERE agent_admin_id=? AND is_deleted=0 AND ends_at IS NULL",agent));
        var nowRows=physical("SELECT UTC_TIMESTAMP(6) AS nowUtc");observation.put("physicalConnectionId",lastReadConnectionId);
        committedCommands.add(observation);nativeObservations.put("monthly-"+committedCommands.size(),observation);saveIntent();
        var actualSource=source.readHistory(List.of(customer));observation.put("actualSource",actualSource);saveIntent();
        assertThat(actualSource.firstHistory()).singleElement().satisfies(h->assertThat(h.status()).isEqualTo(SupportPaymentFacts.Status.READY));
        var candidate=SupportAnalyticsService.selectFirstFacts(actualSource.facts()).get(customer);
        if(expected==0) {assertThat(actualSource.facts()).isEmpty();assertThat(candidate).isNull();}
        else assertThat(candidate).isNotNull();
        var bindings=physical("SELECT user_id FROM nx_support_agent_user_assignment WHERE agent_admin_id=? AND is_deleted=0 AND ends_at IS NULL",agent);
        assertThat(bindings).singleElement().satisfies(row->assertThat(((Number)row.get("user_id")).longValue()).isEqualTo(customer));
        YearMonth month=candidate==null?paymentMonth((LocalDateTime)nowRows.get(0).get("nowUtc"),"UTC"):paymentMonth(candidate.succeededAt(),actualSource.businessZone());
        Instant from=month.atDay(1).atStartOfDay(SupportLeaderboard.BUSINESS_ZONE).toInstant(),to=month.plusMonths(1).atDay(1).atStartOfDay(SupportLeaderboard.BUSINESS_ZONE).toInstant();
        String range="view=FINANCE&basis=PERIOD_EVENT&currency=USDT&businessZone=Asia/Shanghai&from="+from+"&to="+to;
        observation.put("month",month.toString());observation.put("from",from);observation.put("to",to);
        // Publish this owned stream through the normal service; automatic refresh is a separate acceptance.
        var read=new TransactionTemplate(transactions);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var snapshot=read.execute(status->{
            observation.put("rankReadConnectionId",jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
            var context=new SupportLeaderboard.Context(SupportLeaderboard.Board.firstPayment,month,month,"USDT",SupportLeaderboard.Scope.ownGroup,Set.of(group),SupportLeaderboardPolicy.DEFINITION,from);
            var ranked=rankSource.readForAuthorizedLeaderboard(context);
            observation.put("rankSource",Map.of("context",ranked.context(),"sourceVersion",ranked.sourceVersion(),"coverage",ranked.candidateCoverage(),"ownedCandidates",ranked.candidates().stream().filter(c->c.agentId()==agent).toList()));
            var calculated=SupportLeaderboard.calculate(ranked.context(),ranked.sourceVersion(),ranked.candidateCoverage(),ranked.candidates());
            return SupportLeaderboard.withMovement(calculated,rankPublications.previousBusinessDay(ranked.context()));
        });saveIntent();
        var publication=rankPublications.publish(Objects.requireNonNull(snapshot));String stream=SupportLeaderboardPublicationService.streamKey(snapshot.context());
        var rows=physical("SELECT * FROM nx_support_leaderboard_publication WHERE id=? AND stream_key=?",publication.publicationId(),stream);
        observation.put("publication",rows);observation.put("publicationReadConnectionId",lastReadConnectionId);
        observation.put("pointer",physical("SELECT * FROM nx_support_leaderboard_latest WHERE stream_key=?",stream));saveIntent();
        assertThat(observation.get("publicationReadConnectionId")).isNotEqualTo(observation.get("rankReadConnectionId"));
        assertThat(rows).singleElement().satisfies(row->assertThat(row.get("view_version")).isEqualTo(snapshot.viewVersion()));
        var pointer=physical("SELECT publication_id FROM nx_support_leaderboard_latest WHERE stream_key=?",stream);
        assertThat(pointer).singleElement().satisfies(row->assertThat(((Number)row.get("publication_id")).longValue()).isEqualTo(publication.publicationId()));
        for(int i=0;i<2;i++) {
            var agentPeriod=analytics(agent,range+"&agentId="+agent);observation.put("agentHttp"+i,agentPeriod);saveIntent();
            assertPeriodCount(agentPeriod.path("scopeSummary").path("financial").path("firstCandidates"),expected);
            var groupPeriod=analytics(manager,range+"&groupId="+group);observation.put("groupHttp"+i,groupPeriod);saveIntent();
            assertPeriodCount(groupPeriod.path("scopeSummary").path("financial").path("firstCandidates"),expected);
            JsonNode ownedGroup=null;for(var item:groupPeriod.path("scopeSummary").path("groups"))if(item.path("groupId").asLong()==group)ownedGroup=item;
            assertThat(ownedGroup).isNotNull();assertPeriodCount(ownedGroup.path("period").path("firstCandidates"),expected);
            var rank=ok(request("GET","/api/admin/content/support-workbench/leaderboard?board=firstPayment&currency=USDT&scope=ownGroup&groupId="+group+"&month="+month,agent,null,null));
            observation.put("rankHttp"+i,rank);saveIntent();assertThat(rank.path("viewVersion").asText()).isEqualTo(snapshot.viewVersion());assertThat(rank.path("rankMonth").asText()).isEqualTo(month.toString());
            JsonNode owned=null;for(var item:rank.path("rows"))if(item.path("agentId").asLong()==agent)owned=item;
            assertThat(owned).isNotNull();var value=owned.path("firstPayment").path("value");
            if(expected>0) {assertThat(value.isIntegralNumber()).isTrue();assertThat(value.asLong()).isEqualTo(expected);}
            else if(!value.isNull()) {assertThat(value.isIntegralNumber()).isTrue();assertThat(value.asLong()).isZero();}
        }
    }

    static BigDecimal refundReserveMinimum(BigDecimal healthy,BigDecimal liabilities,BigDecimal refund) {
        assertThat(healthy).isPositive();assertThat(liabilities).isNotNegative();assertThat(refund).isPositive();
        return healthy.multiply(liabilities.add(refund)).divide(new BigDecimal("100"),2,RoundingMode.CEILING);
    }
    static BigDecimal decimal(Object value) {assertThat(value).isNotNull();return new BigDecimal(value.toString());}
    @SuppressWarnings("unchecked") Map<String,Object> coverageSnapshot() {
        var result=treasury.dualLedger();assertThat(result.getCode()).isZero();assertThat(result.getData().get("snapshot")).isInstanceOf(Map.class);
        return (Map<String,Object>)result.getData().get("snapshot");
    }
    void prepareRefundReserve(long customer,String order) throws Exception {
        requireClone();requireCustomer(customer);assertThat(environment.getActiveProfiles()).contains("dev").doesNotContain("prod");
        assertThat(environment.getProperty("nexion.treasury.development-reserve.enabled",Boolean.class,false)).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        var preview=as(boss,()->deviceOrders.order(order));assertThat(preview.getCode()).isZero();assertThat(preview.getData().userId()).isEqualTo(customer);
        var before=coverageSnapshot();BigDecimal reserve=decimal(before.get("reserveUsd")),liabilities=decimal(before.get("liabilitiesUsd")),healthy=decimal(before.get("healthyPct")),redline=decimal(before.get("redlinePct"));
        assertThat(before.get("valuationReliable")).isEqualTo(true);assertThat(redline).isPositive();assertThat(healthy).isGreaterThan(redline);
        BigDecimal minimum=refundReserveMinimum(healthy,liabilities,preview.getData().order().amount()),amount=minimum.subtract(reserve).setScale(2,RoundingMode.CEILING);assertThat(amount).isPositive();
        LocalDate day=((LocalDateTime)physical("SELECT UTC_TIMESTAMP(6) AS nowUtc").get(0).get("nowUtc")).toInstant(ZoneOffset.UTC).atZone(SupportLeaderboard.BUSINESS_ZONE).toLocalDate();
        String suffix=java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(day)+":"+amount.movePointRight(2).toPlainString(),requestKey="development:treasury-reserve:"+suffix;
        String voucher="DEV-LOCAL-TREASURY-"+java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(day)+"-"+amount.movePointRight(2).toPlainString();
        String scope="D3_RESERVE_INJECTION";
        assertThat(physical("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",scope,requestKey)).isEmpty();
        assertThat(physical("SELECT * FROM nx_treasury_reserve_ledger WHERE voucher_no=?",voucher)).isEmpty();
        var intent=new LinkedHashMap<String,Object>();intent.put("event","PREPARED_BEFORE_DEVELOPMENT_RESERVE");intent.put("before",before);intent.put("preview",preview);intent.put("minimum",minimum);intent.put("businessDay",day);intent.put("scope",scope);intent.put("key",requestKey);intent.put("voucher",voucher);
        committedCommands.add(intent);nativeObservations.put("developmentReserve",intent);saveIntent();requireClone();
        var tx=new TransactionTemplate(transactions);var result=tx.execute(status->{intent.put("transactionConnectionId",jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));return treasury.ensureDevelopmentReserve(minimum,day);});
        var claim=physical("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",scope,requestKey);intent.put("claim",claim);intent.put("readConnectionId",lastReadConnectionId);
        var ledger=physical("SELECT * FROM nx_treasury_reserve_ledger WHERE voucher_no=?",voucher);intent.put("ledger",ledger);
        var audit=physical("SELECT * FROM nx_audit_log WHERE action='D3_TREASURY_RESERVE_INJECTION' AND resource_id=?",voucher);intent.put("audit",audit);
        var event=physical("SELECT * FROM nx_event_outbox WHERE aggregate_type='TREASURY_RESERVE' AND aggregate_id=? AND event_name='admin.treasury_reserve_injected'",voucher);intent.put("outbox",event);saveIntent();
        var after=coverageSnapshot();intent.put("after",after);saveIntent();
        assertThat(result).isNotNull();assertThat(result.getCode()).isZero();assertThat(result.getData()).containsEntry("injected",true);assertThat(decimal(result.getData().get("amount"))).isEqualByComparingTo(amount);
        assertThat(intent.get("readConnectionId")).isNotEqualTo(intent.get("transactionConnectionId"));
        assertThat(claim).singleElement().satisfies(row->{assertThat(row.get("status")).isEqualTo("SUCCEEDED");assertThat(row.get("response_json")).isNotNull();assertThat(String.valueOf(row.get("request_hash"))).matches("[0-9a-f]{64}");});
        assertThat(ledger).singleElement().satisfies(row->{assertThat(row.get("status")).isEqualTo("CONFIRMED");assertThat(row.get("direction")).isEqualTo("IN");assertThat(row.get("idempotency_key")).isEqualTo(requestKey);assertThat(decimal(row.get("amount_usd"))).isEqualByComparingTo(amount);});
        assertThat(audit).singleElement().satisfies(row->{assertThat(row.get("result")).isEqualTo("SUCCESS");assertThat(row.get("actor_username")).isEqualTo("development-system");});assertThat(event).hasSize(1);
        JsonNode auditDetail=json.readTree(String.valueOf(audit.get(0).get("detail_json"))),eventPayload=json.readTree(String.valueOf(event.get(0).get("payload")));
        assertThat(auditDetail.path("idempotencyKey").asText()).isEqualTo(requestKey);assertThat(decimal(auditDetail.path("amount").asText())).isEqualByComparingTo(amount);
        assertThat(eventPayload.path("voucherNo").asText()).isEqualTo(voucher);assertThat(decimal(eventPayload.path("amountUsdt").asText())).isEqualByComparingTo(amount);
        assertThat(decimal(after.get("reserveUsd"))).isEqualByComparingTo(reserve.add(amount));assertThat(decimal(after.get("liabilitiesUsd"))).isEqualByComparingTo(liabilities);
        assertThat(decimal(after.get("redlinePct"))).isEqualByComparingTo(redline);assertThat(decimal(after.get("healthyPct"))).isEqualByComparingTo(healthy);
        var ready=as(boss,()->deviceOrders.order(order));intent.put("refundPreviewAfter",ready);saveIntent();assertThat(ready.getCode()).isZero();assertThat(ready.getData().refundAllowed()).isTrue();
        assertThat(ready.getData().coverageProjected()).isGreaterThanOrEqualTo(redline);assertThat(ready.getData().refundChannels()).containsExactly("WALLET");
    }
    void assertHttpFirstTwice(long customer,SupportAnalyticsStats.FirstSelection expected) throws Exception {
        var snapshot=source.readHistory(List.of(customer));
        var rawFirst=snapshot.facts().stream().filter(f->f.kind().name().equals(expected.observedCandidate().kind())).min(Comparator.comparing(SupportPaymentFacts.Fact::succeededAt)).orElseThrow();
        // Raw facts follow their snapshot zone; FirstCandidate follows the requested business zone.
        var expectedInstant=rawFirst.succeededAt().atZone(java.time.ZoneId.of(snapshot.businessZone())).toInstant();
        assertThat(expected.observedCandidate().succeededAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant()).isEqualTo(expectedInstant);
        JsonNode held=null;
        for(int i=0;i<2;i++) {
            var response=analytics(agent,"view=CUSTOMERS&basis=CURRENT_CUSTOMER_HISTORY&currency=USDT&keyword="+run);
            var row=heldCustomer(response,customer);
            assertThat(row.path("first").path("state").asText()).isEqualTo("CONFIRMED");assertThat(row.path("first").path("kind").asText()).isEqualTo(expected.observedCandidate().kind());
            assertThat(new BigDecimal(row.path("first").path("amount").asText())).isEqualByComparingTo(expected.observedCandidate().amount());
            assertThat(row.path("first").path("currency").asText()).isEqualTo(expected.observedCandidate().currency());
            assertThat(row.path("first").path("succeededAt").asText()).isEqualTo(expectedInstant.toString());
            assertThat(row.path("owner").path("agentId").asLong()).isEqualTo(agent);assertThat(row.path("owner").path("groupId").asLong()).isEqualTo(group);
            if(held!=null)assertThat(row.path("first")).isEqualTo(held);held=row.path("first").deepCopy();
        }
    }
    static JsonNode heldCustomer(JsonNode response,long customer) {
        assertThat(response.path("records")).hasSize(1);var row=response.path("records").get(0);assertThat(row.path("customerId").asLong()).isEqualTo(customer);
        if(response.path("versionState").asText().equals("READY"))assertThat(response.path("queryVersion").asText()).matches("saq-v1:[0-9a-f]{64}");
        else {assertThat(response.path("versionState").asText()).isEqualTo("UNKNOWN");assertThat(response.path("queryVersion").isNull()).isTrue();assertThat(response.path("canContinue").asBoolean()).isFalse();}
        return row;
    }
    long lastReadConnectionId;
    List<Map<String,Object>> physical(String sql,Object... args) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        try(var connection=openPhysicalConnection()) {
            connection.setReadOnly(true);var read=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            var actual=read.queryForMap("SELECT @@server_uuid AS uuid,@@datadir AS directory,CONNECTION_ID() AS connectionId");validateCloneIdentity(String.valueOf(actual.get("uuid")),String.valueOf(actual.get("directory")));
            lastReadConnectionId=((Number)actual.get("connectionId")).longValue();return read.queryForList(sql,args);
        }
    }
    void awaitLaterSecond(long customer,LocalDateTime first) {
        var sourceZone=java.time.ZoneId.of(source.readHistory(List.of(customer)).businessZone());
        var firstInstant=first.atZone(sourceZone).toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(4).toNanos();
        while(!jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)",LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).isAfter(firstInstant)) {
            assertThat(System.nanoTime()).isLessThan(deadline);try{Thread.sleep(20);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
        }
    }
    void saveIntent() throws Exception {
        Files.write(Path.of(required("CS_ENHANCE_EVIDENCE_DIR")).resolve(getClass().getSimpleName()+"-"+testcase+"-intents.json"),json.writerWithDefaultPrettyPrinter().writeValueAsBytes(committedCommands),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
    }
    static String sha256(String value) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }

    // Original fixture cleanup is deliberately overridden only for this strict disposable-clone suite.
    @Override @AfterEach void ownedCleanupAndSceneReadback() throws Exception {
        SecurityContextHolder.clearContext();if(!cloneGuardPassed)return;
        var histories=new LinkedHashMap<Long,Object>();
        for(long customer:customers)histories.put(customer,Map.of("attribution",physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? ORDER BY fact_id",customer),
                "birth",physical("SELECT * FROM nx_support_payment_history_birth WHERE customer_id=?",customer),
                "assignment",physical("SELECT * FROM nx_support_agent_user_assignment WHERE user_id=? ORDER BY id",customer),
                "route",physical("SELECT * FROM nx_support_customer_route_history WHERE customer_id=? ORDER BY id",customer),"actualSource",source.readHistory(List.of(customer))));
        nativeObservations.put("completeOwnedCustomerHistories",histories);
        var qualifications=new LinkedHashMap<Long,Object>();if(actors!=null)for(long actor:actors.ownedIds())qualifications.put(actor,Map.of("qualification",physical("SELECT * FROM nx_support_account_qualification_history WHERE admin_id=? ORDER BY id",actor),
                "membership",physical("SELECT * FROM nx_support_group_member_history WHERE agent_admin_id=? ORDER BY id",actor)));
        nativeObservations.put("completeOwnedActorQualificationHistories",qualifications);
        if(group>0)nativeObservations.put("ownedGroupOwnerHistory",physical("SELECT * FROM nx_support_group_owner_history WHERE group_id=? ORDER BY id",group));
        var result=new LinkedHashMap<String,Object>();result.put("workflowRunId",required("WORKFLOW_RUN_ID"));result.put("snapshotHash",required("WORKFLOW_SNAPSHOT_HASH"));result.put("capability","runtime");result.put("suite",getClass().getSimpleName());result.put("testcase",testcase);
        result.put("status",assertionEvidence==null?"not-completed":"committed-assertions-passed-cleanup-pending");result.put("cleanupPolicy","committed-disposable");result.put("cleanupComplete",false);result.put("cleanupPending",true);result.put("acceptanceCoverage","PARTIAL_NO_PHASE_SIGNOFF");result.put("assertions",assertionEvidence);
        result.put("requests",requests);result.put("committedCommands",committedCommands);result.put("nativeObservations",nativeObservations);result.put("exactFactReceipts",exactFacts==null?List.of():exactFacts.receipts());result.put("ownedCustomerIds",customers);result.put("ownedActorIds",actors==null?Set.of():actors.ownedIds());result.put("httpBase",SupportRuntimeTarget.current().httpBase());result.put("cloneDirectory",CLONE_DIRECTORY);
        result.put("cloneIdentity",physical("SELECT DATABASE() AS databaseName,@@server_uuid AS serverUuid,@@datadir AS dataDirectory,CURRENT_USER() AS currentUser"));
        Files.write(Path.of(required("CS_ENHANCE_EVIDENCE_DIR")).resolve(getClass().getSimpleName()+"-"+testcase+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);
    }
}
