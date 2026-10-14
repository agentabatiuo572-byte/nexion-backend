package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.device.dto.DeviceOrderActionRequest;
import ffdd.opsconsole.finance.application.SupportPaymentSourceService;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** One explicitly selected retained-customer refund; never creates cash or adopts old objects for cleanup. */
class SupportNetContributionRefundRuntimeTest extends SupportNetContributionCommittedRuntimeTest {
    static final long CUSTOMER=6274;
    static final String ORDER="ORD-4FB5159F14424AB2A2946093F260EF2D";
    static final String CLONE_UUID="abe359c0-c621-11f1-97ea-a40c6626953d";
    static final String INCARNATION="53ccc3f1-5893-4206-af42-60b04ff1a235";
    static final String PRIOR_RUN="6415414c-d6af-4ed1-90bf-2c969cc33104";
    static final String PRIOR_SNAPSHOT="d08e4328dddc4680887244ea404d42b259e418ac654a7e9f9c77301ba0cafbcd";
    static final String PRIOR_DIR="D:/CodexData/test-environments/workflow-runs/support-analytics-20261007-r2/runtime/support-minimal-20261012/net-case-audit-identity";
    static final String PRIOR_SCENE=PRIOR_DIR+"/SupportNetContributionCommittedRuntimeTest-netRechargeHardwareSpendReplayThenSecondRechargeIsDeduplicated.json";
    static final String PRIOR_SHA="b4b3178ce6d29b6a29d79b3cc70ffd26cb5179a5b4a39562d88fe577906dd442";
    static final String CREATOR=PRIOR_DIR+"/object-ledger/00000002-ef98337d-61f1-4c77-9e43-fbb32c42c6b0.json";
    static final String CREATOR_SHA="ed71f22b1b8446e10fb4da47e2a6bd272902fd812379072673bb987ba231f9b6";
    static final String CREATE_INTENT=PRIOR_DIR+"/object-ledger/00000001-57eeea0c-baf4-44c9-aa09-78669ee14c6d.json";
    static final String CREATE_INTENT_SHA="92a64a05276372e3fb421dc87a0c61829b0e9675f550fa3f10868130c197c185";
    static final BigDecimal NET=new BigDecimal("849"), REFUND=new BigDecimal("649");
    @Autowired SupportPaymentSourceService source;
    JsonNode prior,creator;
    String priorNickname;
    final Set<String> refundKeys=new HashSet<>();

    @Test void retainedRechargeFundedS1WalletRefundPreservesOriginalFirstAndNetThenReplays() throws Exception {
        verifyRetainedOrigin();
        var history=source.readHistory(List.of(CUSTOMER));
        assertThat(history.firstHistory()).singleElement().satisfies(h->assertThat(h.status()).isEqualTo(SupportPaymentFacts.Status.READY));
        var originalFirst=SupportAnalyticsService.selectFirstFacts(history.facts()).get(CUSTOMER);
        assertThat(originalFirst).isNotNull();assertThat(originalFirst.kind()).isEqualTo(SupportPaymentFacts.Kind.DEPOSIT);
        assertThat(originalFirst.amount()).isEqualByComparingTo("749");
        var originalCapture=physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? ORDER BY fact_id",CUSTOMER);
        assertThat(originalCapture).hasSize(3);
        for(var capture:originalCapture){
            assertThat(capture.get("agent_status")).isEqualTo("KNOWN");assertThat(number(capture,"agent_admin_id")).isEqualTo(5400);
            assertThat(number(capture,"group_id")).isEqualTo(687);assertThat(capture.get("capture_mode")).isEqualTo("NEW_SUCCESS");
        }
        var purchase=history.facts().stream().filter(f->("PURCHASE:"+ORDER).equals(f.factId())).findFirst().orElseThrow();
        assertThat(purchase.amount()).isEqualByComparingTo(REFUND);
        var originalConsume=physical("SELECT * FROM nx_support_fund_operation WHERE id=23");
        assertThat(originalConsume).hasSize(1);
        assertThat(json.readTree(originalConsume.get(0).get("ordered_events").toString()).get(0).path("id").asText()).isEqualTo("CONSUME:PURCHASE:"+ORDER);
        assertNet(CUSTOMER,NET,"RETAINED_ORIGINAL_NET");
        JsonNode originalHttpFirst=retainedCustomerPage().path("records").get(0).path("first").deepCopy();
        assertThat(originalHttpFirst).isEqualTo(checkpoint(prior,"SECOND_RECHARGE").path("httpAnalytics").path("records").get(0).path("first"));
        var supporter=refundActor(agent,false);var supervisor=refundActor(boss,true);
        registerAuthorizedTestReserve(supervisor);
        var preview=ok(request("GET","/api/admin/devices/orders/"+ORDER,boss,null,null));
        nativeObservations.put("existingRefundPolicyPreview",preview);saveIntent();
        // The owner-authorized test reserve still leaves the existing treasury/A2/state guards in force.
        var body=new DeviceOrderActionRequest("refunded","WALLET","Retained recharge-funded S1 wallet refund",run);
        var before=refundWitness();refundHttp(supporter,key(),body,false);
        assertThat(refundWitness()).as("Denied support refund leaves every observed financial/source row unchanged").isEqualTo(before);
        String refundKey=key();refundHttp(supervisor,refundKey,body,true);
        var after=refundWitness();
        nativeObservations.put("retainedRefundBefore",before);nativeObservations.put("retainedRefundAfter",after);saveIntent();
        var beforeWallet=rows(before,"wallet").get(0);var afterWallet=rows(after,"wallet").get(0);
        assertThat(decimal(afterWallet,"usdt_available")).isEqualByComparingTo(decimal(beforeWallet,"usdt_available").add(REFUND));
        assertThat(decimal(afterWallet,"nex_available")).isEqualByComparingTo(decimal(beforeWallet,"nex_available"));
        for(String field:List.of("pending_withdraw","cregis_risk_held"))assertThat(decimal(afterWallet,field)).isEqualByComparingTo(decimal(beforeWallet,field));
        assertThat(number(afterWallet,"version")).isEqualTo(number(beforeWallet,"version")+1);
        assertThat(number(afterWallet,"fund_revision")).isEqualTo(number(beforeWallet,"fund_revision")+1);
        assertThat(afterWallet.get("fund_incarnation")).isEqualTo(INCARNATION);
        assertThat(rows(after,"refundLedger")).singleElement().satisfies(r->{
            assertThat(r.get("status")).isEqualTo("SUCCESS");assertThat(r.get("asset")).isEqualTo("USDT");
            assertThat(r.get("direction")).isEqualTo("IN");assertThat(decimal(r,"amount")).isEqualByComparingTo(REFUND);
        });
        assertThat(rows(after,"refundBill")).singleElement().satisfies(r->{
            assertThat(r.get("type")).isEqualTo("ORDER_REFUND");assertThat(r.get("token")).isEqualTo("USDT");
            assertThat(r.get("direction")).isEqualTo("IN");assertThat(decimal(r,"amount")).isEqualByComparingTo(REFUND);
        });
        assertThat(rows(after,"payment")).singleElement().satisfies(r->assertThat(r.get("payment_status")).isEqualTo("REFUNDED"));
        assertThat(rows(after,"purchaseLedger")).isEqualTo(rows(before,"purchaseLedger"));
        var refunded=source.readHistory(List.of(CUSTOMER));
        assertThat(refunded.facts()).hasSize(4).containsAll(history.facts());
        var refund=refunded.facts().stream().filter(f->f.kind()==SupportPaymentFacts.Kind.DEVICE_PURCHASE_REFUND).findFirst().orElseThrow();
        assertThat(refund.originalFactId()).isEqualTo(purchase.factId());assertThat(refund.customerId()).isEqualTo(CUSTOMER);
        assertThat(refund.currency()).isEqualTo(purchase.currency());assertThat(refund.amount()).isEqualByComparingTo(purchase.amount());
        assertThat(refund.ledgerId()).isEqualTo(number(rows(after,"refundLedger").get(0),"id"));
        assertThat(SupportAnalyticsService.selectFirstFacts(refunded.facts()).get(CUSTOMER)).isEqualTo(originalFirst);
        assertThat(physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? ORDER BY fact_id",CUSTOMER)).hasSize(4).containsAll(originalCapture);
        assertThat(physical("SELECT * FROM nx_support_fund_operation WHERE id=23")).isEqualTo(originalConsume);
        var refundOps=rows(after,"operations").stream().filter(r->!rows(before,"operations").contains(r)).toList();
        assertThat(refundOps).singleElement().satisfies(r->{
            try {
                var snapshot=json.readTree(r.get("source_snapshot").toString());var proof=json.readTree(r.get("source_proof").toString());
                assertThat(snapshot.path("source").asText()).isEqualTo("ORDER_REFUND");assertThat(snapshot.path("factId").asText()).isEqualTo(refund.factId());
                assertThat(snapshot.path("originalPurchase").path("factId").asText()).isEqualTo(purchase.factId());
                assertThat(proof.path("originalConsume").asText()).isEqualTo("CONSUME:"+purchase.factId());
                assertThat(proof.path("originalFundEvidence").asText()).isEqualTo("VERIFIED");
                var events=json.readTree(r.get("ordered_events").toString());assertThat(events.size()).isEqualTo(1);
                assertThat(events.get(0).path("action").asText()).isEqualTo("REFUND");
                assertThat(events.get(0).path("reference").asText()).isEqualTo("CONSUME:"+purchase.factId());
            }catch(java.io.IOException failure){throw new AssertionError("Actual refund receipt JSON unreadable",failure);}
        });
        long refundOperation=number(refundOps.get(0),"id");
        assertThat(rows(after,"parties").stream().filter(r->number(r,"operation_id")==refundOperation).toList()).singleElement()
            .satisfies(r->assertThat(number(r,"customer_id")).isEqualTo(CUSTOMER));
        assertThat(rows(after,"legs").stream().filter(r->number(r,"operation_id")==refundOperation).toList()).singleElement()
            .satisfies(r->{assertThat(r.get("wallet_incarnation")).isEqualTo(INCARNATION);assertThat(number(r,"revision")).isEqualTo(number(afterWallet,"fund_revision"));});
        assertThat(rows(after,"journal")).hasSize(rows(before,"journal").size()+1).containsAll(rows(before,"journal"));
        assertNet(CUSTOMER,NET,"REFUND_RESTORES_PREVIOUSLY_COUNTED_RECHARGE_SOURCE");
        assertThat(retainedCustomerPage().path("records").get(0).path("first")).isEqualTo(originalHttpFirst);
        assertThat(retainedCustomerPage().path("records").get(0).path("owner")).isEqualTo(checkpoint(prior,"SECOND_RECHARGE").path("httpAnalytics").path("records").get(0).path("owner"));
        refundHttp(supervisor,refundKey,body,true);assertThat(refundWitness()).as("Same key returns original receipt without a second credit").isEqualTo(after);
        assertNet(CUSTOMER,NET,"REFUND_SAME_KEY_REPLAY");
        assertThat(retainedCustomerPage().path("records").get(0).path("first")).isEqualTo(originalHttpFirst);
        checked("Retained AUTH creator and original committed receipts verified; support HTTP 403 changes no financial rows; boss WALLET full refund and same-key replay preserve deposit-first source/attribution and NET 849. Financial facts retained. Earnings-funded refund reduction remains separate, not covered.");
    }

    void registerAuthorizedTestReserve(JsonNode supervisor) throws Exception {
        requireNetClone();
        String voucher="TEST-CSNET-6274-20261013-RESERVE", injectionKey="csnet:6274:20261013:reserve";
        BigDecimal amount=new BigDecimal("314389.92");
        var permissions=new ArrayList<String>();supervisor.path("authorities").forEach(v->permissions.add(v.asText()));
        assertThat(permissions).contains("finance_d3_read","finance_d3_injection_create");
        assertThat(physical("SELECT * FROM nx_treasury_reserve_ledger WHERE voucher_no=?",voucher)).isEmpty();
        var before=ok(request("GET","/api/admin/treasury/dual-ledger",boss,null,null)).path("snapshot");
        assertThat(before.path("valuationReliable").asBoolean()).isTrue();
        BigDecimal reserve=new BigDecimal(before.path("reserveUsd").asText()), liabilities=new BigDecimal(before.path("liabilitiesUsd").asText());
        BigDecimal redline=new BigDecimal(before.path("redlinePct").asText());
        assertThat(redline).isEqualByComparingTo("100");
        assertThat(liabilities.add(REFUND).subtract(reserve)).isPositive().isLessThanOrEqualTo(amount);
        var body=Map.of("amount",amount,"voucherNo",voucher,"reason","Owner-authorized isolated 33338 test reserve; no actual transfer; original database unchanged","operator",supervisor.path("username").asText());
        var intent=new LinkedHashMap<String,Object>();intent.put("event","AUTHORIZED_TEST_RESERVE_PREPARED");
        intent.put("authorization","2026-10-13 owner approved only isolated 33338, maximum 314389.92 USDT");
        intent.put("before",before);intent.put("request",body);intent.put("idempotencyKey",injectionKey);committedCommands.add(intent);saveIntent();
        var result=ok(request("POST","/api/admin/treasury/reserve-injection",boss,body,injectionKey));
        intent.put("response",result);saveIntent();
        assertThat(new BigDecimal(result.path("injection").path("amount").asText())).isEqualByComparingTo(amount);
        var rows=physical("SELECT * FROM nx_treasury_reserve_ledger WHERE voucher_no=?",voucher);
        assertThat(rows).singleElement().satisfies(r->{assertThat(decimal(r,"amount_usd")).isEqualByComparingTo(amount);assertThat(r.get("direction")).isEqualTo("IN");});
        assertThat(physical("SELECT * FROM nx_audit_log WHERE resource_id=? AND action='D3_TREASURY_RESERVE_INJECTION'",voucher)).hasSize(1);
        assertThat(physical("SELECT * FROM nx_event_outbox WHERE aggregate_type='TREASURY_RESERVE' AND aggregate_id=? AND event_type='admin.treasury_reserve_injected'",voucher)).hasSize(1);
        var after=ok(request("GET","/api/admin/treasury/dual-ledger",boss,null,null)).path("snapshot");
        assertThat(new BigDecimal(after.path("reserveUsd").asText())).isEqualByComparingTo(reserve.add(amount));
        assertThat(after.path("liabilitiesUsd")).isEqualTo(before.path("liabilitiesUsd"));assertThat(after.path("redlinePct")).isEqualTo(before.path("redlinePct"));
        intent.put("event","AUTHORIZED_TEST_RESERVE_COMMITTED_AND_READ_BACK");intent.put("after",after);intent.put("ledger",rows);saveIntent();
    }

    void verifyRetainedOrigin() throws Exception {
        requireNetClone();prior=readFixed(PRIOR_SCENE,PRIOR_SHA);creator=readFixed(CREATOR,CREATOR_SHA);
        assertThat(prior.path("workflowRunId").asText()).isEqualTo(PRIOR_RUN);assertThat(prior.path("snapshotHash").asText()).isEqualTo(PRIOR_SNAPSHOT);
        assertThat(prior.path("status").asText()).isEqualTo("scenario-assertions-passed-financial-records-retained");
        assertThat(prior.path("ownedCustomerIds").size()).isEqualTo(1);assertThat(prior.path("ownedCustomerIds").get(0).asLong()).isEqualTo(CUSTOMER);
        var oldDb=prior.path("nativeObservations").path("netLineage").path("actualSpringPrincipal");
        assertThat(oldDb.path("serverUuid").asText()).isEqualTo(CLONE_UUID);assertThat(oldDb.path("port").asInt()).isEqualTo(33338);
        var live=physical("SELECT DATABASE() databaseName,@@port port,@@server_uuid serverUuid,@@datadir dataDirectory,CURRENT_USER() currentUser").get(0);
        assertThat(live.get("serverUuid")).isEqualTo(CLONE_UUID);assertThat(number(live,"port")).isEqualTo(33338);
        assertThat(live.get("dataDirectory").toString().replace('\\','/')).isEqualTo(oldDb.path("dataDirectory").asText().replace('\\','/'));
        var intent=readFixed(CREATE_INTENT,CREATE_INTENT_SHA);var payload=creator.path("payload");
        assertThat(creator.path("eventType").asText()).isEqualTo("CUSTOMER_CREATED");
        assertThat(creator.path("identity").path("runId").asText()).isEqualTo(PRIOR_RUN);
        assertThat(creator.path("identity").path("snapshotHash").asText()).isEqualTo(PRIOR_SNAPSHOT);
        assertThat(creator.path("requestId")).isEqualTo(intent.path("requestId"));assertThat(intent.path("eventType").asText()).isEqualTo("CUSTOMER_CREATE_INTENT");
        assertThat(intent.path("payload").path("beforeAbsent").asBoolean()).isTrue();
        assertThat(intent.path("payload").path("databaseIdentity").path("serverUuid").asText()).isEqualTo(CLONE_UUID);
        assertThat(payload.path("generatedId").asLong()).isEqualTo(CUSTOMER);assertThat(payload.path("referralLookupId").asLong()).isEqualTo(CUSTOMER);
        assertThat(payload.path("affectedRows").asInt()).isEqualTo(1);assertThat(payload.path("transactionOutcome").asText()).isEqualTo("COMMIT_RETURNED");
        assertThat(payload.path("intentRef").path("sha256").asText()).isEqualTo(CREATE_INTENT_SHA);
        assertThat(intent.path("payload").path("referralSha256")).isEqualTo(payload.path("referralSha256"));
        var user=physical("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",CUSTOMER).get(0);
        assertThat(number(user,"id")).isEqualTo(CUSTOMER);priorNickname=String.valueOf(user.get("nickname"));
        assertThat(priorNickname).isEqualTo("an12_0b19d4c396");
        assertThat(sha(user.get("referral_code").toString().getBytes(StandardCharsets.UTF_8))).isEqualTo(payload.path("referralSha256").asText());
        assertThat(user.get("created_at").toString().replace('T',' ')).isEqualTo(payload.path("row").path("created_at").asText().replace('T',' '));
        var finalCheckpoint=checkpoint(prior,"SECOND_RECHARGE");
        assertHistoricalRows(physical("SELECT * FROM nx_user_wallet WHERE user_id=?",CUSTOMER),finalCheckpoint.path("wallet"));
        assertHistoricalRows(physical("SELECT * FROM nx_support_wallet_journal WHERE wallet_incarnation=? ORDER BY revision",INCARNATION),finalCheckpoint.path("journal"));
        assertHistoricalRows(operations(),finalCheckpoint.path("operations"));
        var paid=prior.path("nativeObservations").path("paid-"+ORDER+"-10");
        assertHistoricalRows(physical("SELECT * FROM nx_payment_record WHERE user_id=? AND order_no=?",CUSTOMER,ORDER),paid.path("payment"));
        assertHistoricalRows(physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND biz_type='ORDER_PURCHASE'",CUSTOMER,ORDER),paid.path("ledger"));
        assertThat(physical("SELECT * FROM nx_order_item WHERE order_no=?",ORDER)).singleElement().satisfies(r->assertThat(r.get("product_no")).isEqualTo("stellarbox-s1"));
        assertThat(physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND biz_type='ORDER_REFUND'",CUSTOMER,"E4-REFUND-"+ORDER)).isEmpty();
        nativeObservations.put("retainedOrigin",Map.of("scenePath",PRIOR_SCENE,"sceneSha256",PRIOR_SHA,"creatorPath",CREATOR,"creatorSha256",CREATOR_SHA,"creator",creator,"actualIdentity",live,"customerIdentity",user));saveIntent();
    }

    // Preserve the parent's Reader twice, its exact customer-ID check and every NET decimal assertion.
    // The historical customer is deliberately not placed in this run's owned-customer/cleanup set.
    @Override JsonNode analytics(long actor,String query) throws Exception {
        String inherited="view=CUSTOMERS&basis=CURRENT_CUSTOMER_HISTORY&currency=USDT&keyword="+run;
        require(priorNickname!=null && actor==agent && inherited.equals(query),"Only the retained NET assertion query may be translated");
        return retainedCustomerPage();
    }
    JsonNode retainedCustomerPage() throws Exception {
        var page=ok(request("GET","/api/admin/content/support-workbench/analytics?view=CUSTOMERS&basis=CURRENT_CUSTOMER_HISTORY&currency=USDT&keyword="+encoded(priorNickname),boss,null,null));
        assertThat(page.path("records").size()).isEqualTo(1);assertThat(page.path("records").get(0).path("customerId").asText()).isEqualTo(Long.toString(CUSTOMER));return page;
    }
    JsonNode refundActor(long actor,boolean allowed) throws Exception {
        assertThat(actors.ownedIds()).contains(actor);var identity=ok(request("GET","/api/admin/auth/me",actor,null,null));
        assertThat(identity.path("adminId").asLong()).isEqualTo(actor);
        assertThat(identity.path("username").asText()).isEqualTo(physical("SELECT username FROM nx_admin WHERE id=?",actor).get(0).get("username"));
        assertThat(identity.path("authorities").isArray()).isTrue();var authorities=new ArrayList<String>();identity.path("authorities").forEach(v->authorities.add(v.asText()));
        assertThat(authorities.contains("device_e4_order_refund")).isEqualTo(allowed);return identity;
    }
    void refundHttp(JsonNode identity,String requestKey,DeviceOrderActionRequest body,boolean allowed) throws Exception {
        requireNetClone();assertThat(prior).isNotNull();long actor=identity.path("adminId").asLong();assertThat(actors.ownedIds()).contains(actor);
        require(actor==(allowed?boss:agent),"Only this run's verified refund actors are permitted");
        require("refunded".equals(body.terminalState()) && "WALLET".equals(body.refundChannel()),"Only the original WALLET refund request is permitted");
        assertThat(readFixed(PRIOR_SCENE,PRIOR_SHA)).isEqualTo(prior);assertThat(readFixed(CREATOR,CREATOR_SHA)).isEqualTo(creator);
        readFixed(CREATE_INTENT,CREATE_INTENT_SHA);
        var retained=physical("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",CUSTOMER);assertThat(retained).hasSize(1);
        assertThat(retained.get(0).get("nickname")).isEqualTo(priorNickname);
        assertThat(sha(retained.get(0).get("referral_code").toString().getBytes(StandardCharsets.UTF_8))).isEqualTo(creator.path("payload").path("referralSha256").asText());
        assertThat(retained.get(0).get("created_at").toString().replace('T',' ')).isEqualTo(creator.path("payload").path("row").path("created_at").asText().replace('T',' '));
        assertThat(physical("SELECT * FROM nx_order WHERE order_no=? AND user_id=?",ORDER,CUSTOMER)).hasSize(1);
        var originalReceipt=json.createArrayNode();checkpoint(prior,"SECOND_RECHARGE").path("operations").forEach(r->{if(r.path("id").asLong()==23)originalReceipt.add(r);});
        assertHistoricalRows(physical("SELECT * FROM nx_support_fund_operation WHERE id=23"),originalReceipt);
        String username=identity.path("username").asText();String scope="E4_ORDER_REFUND";
        var trusted=new DeviceOrderActionRequest(body.terminalState(),body.refundChannel(),body.reason().trim(),username);
        String requestHash=sha((scope+"\n"+ORDER+"\n"+trusted).getBytes(StandardCharsets.UTF_8));
        if(refundKeys.add(requestKey))assertThat(physical("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",scope,requestKey)).isEmpty();
        var receipt=new LinkedHashMap<String,Object>();receipt.put("event","PREPARED_BEFORE_RETAINED_REFUND_HTTP");receipt.put("creatorSha256",CREATOR_SHA);receipt.put("priorSceneSha256",PRIOR_SHA);
        receipt.put("customerId",CUSTOMER);receipt.put("orderNo",ORDER);receipt.put("key",requestKey);receipt.put("requestHash",requestHash);receipt.put("actor",identity);receipt.put("request",body);committedCommands.add(receipt);saveIntent();
        var response=request("POST","/api/admin/devices/orders/"+ORDER+"/refund",actor,body,requestKey);receipt.put("httpStatus",response.status());receipt.put("body",response.body());saveIntent();
        var claims=physical("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",scope,requestKey);receipt.put("claim",claims);receipt.put("independentReadConnectionId",lastReadConnectionId);receipt.put("readback",refundWitness());saveIntent();
        if(!allowed){rejected(response,403);assertThat(claims).isEmpty();return;}
        assertThat(ok(response).path("state").asText()).isEqualTo("refunded");
        assertThat(claims).singleElement().satisfies(r->{assertThat(r.get("status")).isEqualTo("SUCCEEDED");assertThat(r.get("request_hash")).isEqualTo(requestHash);});
        assertThat(json.readTree(claims.get(0).get("response_json").toString())).isEqualTo(response.body());
        var audits=physical("SELECT * FROM nx_audit_log WHERE action='E4_ORDER_REFUNDED' AND resource_id=?",ORDER);assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("actor_username")).isEqualTo(username);assertThat(audits.get(0).get("result")).isEqualTo("SUCCESS");
        var detail=json.readTree(audits.get(0).get("detail_json").toString());assertThat(detail.path("idempotencyKey").asText()).isEqualTo(requestKey);
        assertThat(detail.path("reason").asText()).isEqualTo(body.reason());assertThat(detail.path("refundChannel").asText()).isEqualTo("WALLET");
        var events=physical("SELECT * FROM nx_event_outbox WHERE aggregate_type='E4_ORDER' AND aggregate_id=? AND event_name IN ('order.refunded','admin.order_refunded') ORDER BY event_name,id",ORDER);
        assertThat(events).hasSize(2);assertThat(events.stream().map(r->r.get("event_name"))).containsExactly("admin.order_refunded","order.refunded");
        for(var event:events){var payload=json.readTree(event.get("payload").toString());assertThat(payload.path("operator").asText()).isEqualTo(username);assertThat(payload.path("reason").asText()).isEqualTo(body.reason());assertThat(payload.path("refundChannel").asText()).isEqualTo("WALLET");}
    }
    Map<String,Object> refundWitness() throws Exception {
        var r=new LinkedHashMap<String,Object>();
        r.put("order",physical("SELECT * FROM nx_order WHERE user_id=? AND order_no=?",CUSTOMER,ORDER));r.put("wallet",physical("SELECT * FROM nx_user_wallet WHERE user_id=?",CUSTOMER));
        r.put("payment",physical("SELECT * FROM nx_payment_record WHERE user_id=? ORDER BY id",CUSTOMER).stream().filter(v->ORDER.equals(v.get("order_no"))).toList());
        r.put("allCustomerPayments",physical("SELECT * FROM nx_payment_record WHERE user_id=? ORDER BY id",CUSTOMER));r.put("allCustomerLedgers",physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? ORDER BY id",CUSTOMER));
        r.put("allCustomerBills",physical("SELECT * FROM nx_wallet_bill WHERE user_id=? ORDER BY id",CUSTOMER));r.put("purchaseLedger",physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND biz_type='ORDER_PURCHASE'",CUSTOMER,ORDER));
        r.put("refundLedger",physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND biz_type='ORDER_REFUND'",CUSTOMER,"E4-REFUND-"+ORDER));r.put("refundBill",physical("SELECT * FROM nx_wallet_bill WHERE user_id=? AND bill_no=?",CUSTOMER,"E4-BILL-"+ORDER));
        r.put("capture",physical("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? ORDER BY fact_id",CUSTOMER));r.put("history",physical("SELECT * FROM nx_order_state_history WHERE order_no=? ORDER BY id",ORDER));
        r.put("device",physical("SELECT * FROM nx_user_device WHERE user_id=? AND source_order_no=?",CUSTOMER,ORDER));r.put("audit",physical("SELECT * FROM nx_audit_log WHERE action='E4_ORDER_REFUNDED' AND resource_id=? ORDER BY id",ORDER));
        r.put("outbox",physical("SELECT * FROM nx_event_outbox WHERE aggregate_type IN ('ORDER','E4_ORDER') AND aggregate_id=? ORDER BY id",ORDER));
        r.put("operations",operations());r.put("journal",physical("SELECT * FROM nx_support_wallet_journal WHERE wallet_incarnation=? ORDER BY revision",INCARNATION));
        r.put("legs",physical("SELECT * FROM nx_support_fund_operation_leg WHERE wallet_incarnation=? ORDER BY revision",INCARNATION));
        r.put("parties",physical("SELECT * FROM nx_support_fund_operation_party WHERE operation_id IN (SELECT operation_id FROM nx_support_fund_operation_party WHERE customer_id=?) ORDER BY operation_id,customer_id",CUSTOMER));
        var history=source.readHistory(List.of(CUSTOMER));r.put("sourceFacts",history.facts());r.put("firstHistory",history.firstHistory());return r;
    }
    List<Map<String,Object>> operations() throws Exception {return physical("SELECT o.* FROM nx_support_fund_operation o JOIN nx_support_fund_operation_party p ON p.operation_id=o.id WHERE p.customer_id=? ORDER BY o.id",CUSTOMER);}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Map<String,Object> witness,String field){return (List<Map<String,Object>>)witness.get(field);}
    static JsonNode checkpoint(JsonNode scene,String event){var matches=new ArrayList<JsonNode>();scene.path("committedCommands").forEach(c->{if(event.equals(c.path("event").asText()))matches.add(c);});require(matches.size()==1,"Exactly one prior successful checkpoint required");return matches.get(0);}
    static JsonNode readFixed(String path,String digest) throws Exception {
        Path fixed=Path.of(path).toAbsolutePath().normalize();require(Files.isRegularFile(fixed,LinkOption.NOFOLLOW_LINKS)&&fixed.toRealPath().equals(fixed),"Plain fixed prior evidence required");
        require(Files.size(fixed)>0 && Files.size(fixed)<=64L*1024*1024,"Bounded prior evidence required");byte[] bytes=Files.readAllBytes(fixed);require(sha(bytes).equals(digest),"Prior evidence bytes changed");return strictJson().readTree(bytes);
    }
    void assertHistoricalRows(List<Map<String,Object>> actual,JsonNode expected) {
        assertThat(expected.isArray()).isTrue();assertThat(actual.size()).isEqualTo(expected.size());
        for(int i=0;i<actual.size();i++){
            var row=actual.get(i);var priorRow=expected.get(i);assertThat(row.keySet()).containsExactlyInAnyOrderElementsOf(iteratorFields(priorRow));
            for(var cell:row.entrySet()){
                var old=priorRow.get(cell.getKey());var value=cell.getValue();
                if(value==null)assertThat(old.isNull()).isTrue();
                else if(value instanceof Number)assertThat(new BigDecimal(value.toString())).isEqualByComparingTo(old.decimalValue());
                else if(value instanceof Boolean)assertThat((Boolean)value).isEqualTo(old.booleanValue());
                // Compare using the receipt's serializer, including its original timestamp precision.
                else assertThat(json.<JsonNode>valueToTree(value)).isEqualTo(old);
            }
        }
    }
    static Set<String> iteratorFields(JsonNode node){var keys=new LinkedHashSet<String>();node.fieldNames().forEachRemaining(keys::add);return keys;}
}
