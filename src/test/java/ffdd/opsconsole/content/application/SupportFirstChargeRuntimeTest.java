package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import ffdd.opsconsole.finance.application.TopupCardLifecycleService;
import ffdd.opsconsole.finance.application.SupportPaymentSourceService;
import ffdd.opsconsole.finance.dto.TopupCardAdmissionRequest;
import ffdd.opsconsole.finance.dto.TopupCardSettlementRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.commerce.application.AppOrderCommandService;
import ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.dto.DeviceOrderActionRequest;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real admission/settlement beans, audit and SQL; whole outer transaction rolls back. No callback HTTP claim. */
@EnabledIfEnvironmentVariable(named="CS_ANALYTICS_FIRST_ENABLED",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({SupportIsolatedRuntime.class,SupportObjectEvidenceLedger.Configuration.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportFirstChargeRuntimeTest extends SupportAnalyticsRuntimeFixture {
    @DynamicPropertySource static void boundary(DynamicPropertyRegistry r){SupportEnhancementPreparationTest.isolatedBoundary(r);}
    @Autowired TopupCardLifecycleService topups;
    @Autowired SupportPaymentSourceService financialSource;
    @Autowired PlatformConfigFacade config;
    @Autowired AppCanonicalBoundaryService checkout;
    @Autowired AppOrderCommandService orderCommands;
    @Autowired OpsDeviceService deviceOrders;
    @Autowired SupportAnalyticsService statistics;

    @Test void genuinePurchaseThenDepositPreservesFirstReplayAndHalfOpenEventBoundary()throws Exception {
        long customer=customer(true);bind(customer,agent);wallet(customer,"1000000","0");
        rollbackFinancialCommands(receipts->{
            String order=createActualOrder(customer,receipts);
            assertNoQualifyingFirst(customer,agent);
            payActualOrder(customer,order,receipts);
            var first=firstFor(customer,agent);assertThat(first.state()).isEqualTo(SupportAnalyticsStats.FirstState.CONFIRMED);
            assertThat(first.observedCandidate().kind()).isEqualTo("DEVICE_PURCHASE");
            var originalPurchase=financialSource.readHistory(List.of(customer)).facts().stream().filter(f->f.kind()==SupportPaymentFacts.Kind.DEVICE_PURCHASE).findFirst().orElseThrow();
            assertThat(originalPurchase.orderNo()).isEqualTo(order);
            assertPeriodBoundary(originalPurchase,customer);
            awaitLaterDatabaseSecond(originalPurchase.succeededAt());
            settleActualCard(customer,"afterpurchase");
            var facts=financialSource.readHistory(List.of(customer));assertThat(facts.facts()).hasSize(2);
            assertThat(facts.facts()).filteredOn(f->f.kind()==SupportPaymentFacts.Kind.DEPOSIT).singleElement().satisfies(f->assertThat(f.amount()).isEqualByComparingTo("100"));
            assertThat(firstFor(customer,agent)).isEqualTo(first);assertSingleFirstCustomer(customer,agent);
            assertThat(facts.facts()).filteredOn(f->f.kind()==SupportPaymentFacts.Kind.DEVICE_PURCHASE).singleElement().isEqualTo(originalPurchase);
            assertThat(currentFor(customer,agent).metrics().lifetime()).filteredOn(v->v.currency().equals("USDT")).singleElement().satisfies(v->{
                assertThat(v.deposits().observedAmount()).isEqualByComparingTo("100");
                assertThat(v.purchases().observedAmount()).isEqualByComparingTo(originalPurchase.amount());
            });
            nativeObservations.put("purchaseThenDepositFirstAndSources",Map.of("originalFirst",first,"originalPurchase",originalPurchase,"after",facts));
        });
        checked("Real canonical checkout and wallet payment precede actual card settlement; repeated new-key paid-order command has one debit/payment/device; card same-request replay is idempotent; exact original purchase first survives with distinct observed deposit100, and adjacent half-open actual-event windows do not double count. Entire financial transaction rolls back; exact independent claims cleaned; no committed financial/gateway/month-boundary or net-refund certificate.");
    }

    @Test void genuineDepositThenPurchaseNeverReplacesOriginalFirstOrOwnership()throws Exception {
        long customer=customer(true);bind(customer,agent);wallet(customer,"1000000","0");
        rollbackFinancialCommands(receipts->{
            settleActualCard(customer,"beforepurchase");var first=firstFor(customer,agent);
            assertThat(first.state()).isEqualTo(SupportAnalyticsStats.FirstState.CONFIRMED);assertThat(first.observedCandidate().kind()).isEqualTo("DEPOSIT");
            var original=jdbc.queryForList("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? ORDER BY fact_id",customer);assertThat(original).hasSize(1);
            String order=createActualOrder(customer,receipts);awaitLaterDatabaseSecond(first.observedCandidate().succeededAt());payActualOrder(customer,order,receipts);
            assertThat(firstFor(customer,agent)).isEqualTo(first);assertSingleFirstCustomer(customer,agent);
            var all=jdbc.queryForList("SELECT * FROM nx_support_payment_attribution WHERE customer_id=? ORDER BY fact_id",customer);assertThat(all).hasSize(2);assertThat(all).containsAll(original);
            var facts=financialSource.readHistory(List.of(customer));assertThat(facts.facts()).hasSize(2);
            assertThat(facts.facts()).filteredOn(f->f.kind()==SupportPaymentFacts.Kind.DEPOSIT).singleElement().satisfies(f->assertThat(f.amount()).isEqualByComparingTo("100"));
            assertThat(facts.facts()).filteredOn(f->f.kind()==SupportPaymentFacts.Kind.DEVICE_PURCHASE).singleElement().satisfies(f->assertThat(f.orderNo()).isEqualTo(order));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_payment_attribution WHERE customer_id=? AND agent_admin_id=? AND group_id=?",Long.class,customer,agent,group)).isEqualTo(2);
            nativeObservations.put("depositThenPurchaseOriginalFirst",Map.of("first",first,"beforeAttribution",original,"afterAttribution",all,"sources",facts));
        });
        checked("Real deposit-first then actual canonical checkout/wallet purchase stays exactly the original first kind/time/amount/currency/attribution; original persisted attribution row unchanged and both events keep the same actual owner/group. No owner transfer/concurrent ordering claim.");
    }

    @Test void ownedGiftAndFreeTrialSourceRowsDoNotQualifyBeforeRealPaidCheckout()throws Exception {
        long customer=customer(true);bind(customer,agent);wallet(customer,"1000000","0");
        rollbackFinancialCommands(receipts->{
            // Negative input fixtures, not fabricated successful payments or production gift/claim command results.
            String gift=run+"gift";assertThat(jdbc.update("INSERT INTO nx_user_device(user_id,instance_no,name,device_type,status,ownership_status,source_channel,source_environment,run_id,price_usdt_snapshot) VALUES(?,?,?,'PHONE','ACTIVE','OWNED','GIFT','PRODUCTION','',0)",customer,gift,gift)).isEqualTo(1);
            String claim=run+"free";assertThat(jdbc.update("INSERT INTO nx_trial_claim(user_id,claim_no,device_name,status,claimed_at,expires_at,settlement_amount_usdt) VALUES(?,?,?,'ACTIVE',NOW(),DATE_ADD(NOW(),INTERVAL 3 DAY),0)",customer,claim,claim)).isEqualTo(1);
            var empty=financialSource.readHistory(List.of(customer));assertThat(empty.facts()).isEmpty();
            assertThat(empty.coverage()).filteredOn(c->c.source()==SupportPaymentFacts.Source.FREE_TRIAL).singleElement().satisfies(c->assertThat(c.excludedFreeOrNonProductionRows()).isEqualTo(1));
            assertNoQualifyingFirst(customer,agent);
            String pending=createActualOrder(customer,receipts);assertNoQualifyingFirst(customer,agent);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=?",Long.class,customer)).isZero();
            payActualOrder(customer,pending,receipts);var first=firstFor(customer,agent);
            assertThat(first.state()).isEqualTo(SupportAnalyticsStats.FirstState.CONFIRMED);assertThat(first.observedCandidate().kind()).isEqualTo("DEVICE_PURCHASE");
            assertThat(financialSource.readHistory(List.of(customer)).facts()).hasSize(1);
            nativeObservations.put("negativeOwnedSourceInputs",Map.of("gift",jdbc.queryForMap("SELECT * FROM nx_user_device WHERE instance_no=? AND user_id=?",gift,customer),"freeTrial",jdbc.queryForMap("SELECT * FROM nx_trial_claim WHERE claim_no=? AND user_id=?",claim,customer),"beforePayment",empty,"afterFirst",first));
        });
        checked("Receipt-owned transactional gift device and free zero trial source inputs plus an actual production-created unpaid order produce no first; real later paid checkout alone qualifies. Negative source fixtures do not claim executing a gift grant/free-trial lifecycle, zero-paid voucher settlement, or trial conversion command.");
    }

    @Test void originalPurchaseRefundAndLaterDepositKeepTheOriginalLifetimeFirst()throws Exception {
        long customer=customer(true);bind(customer,agent);wallet(customer,"1000000","0");
        rollbackFinancialCommands(receipts->{
            String order=createActualOrder(customer,receipts);payActualOrder(customer,order,receipts);
            var first=firstFor(customer,agent);var purchase=financialSource.readHistory(List.of(customer)).facts().get(0);
            String refundKey="fst13-"+UUID.randomUUID();var request=new DeviceOrderActionRequest("refunded","WALLET","Native original purchase refund proof",run);
            String hash=sha256("E4_ORDER_REFUND\n"+order+"\n"+request);
            var proof=Map.of("orderNo",order,"request",request,"productionCaller","OpsDeviceService.refundOrder");
            var refunded=as(boss,()->receipts.invoke(customer,"E4_ORDER_REFUND",refundKey,hash,proof,()->deviceOrders.refundOrder(order,refundKey,request)));
            assertThat(refunded.getCode()).as("Original E4 A2/treasury/refund guards remain authoritative; do not override policy to make native green: %s",refunded.getMessage()).isZero();
            var afterRefund=financialSource.readHistory(List.of(customer));assertThat(afterRefund.facts()).hasSize(2);
            assertThat(afterRefund.facts()).filteredOn(f->f.kind()==SupportPaymentFacts.Kind.DEVICE_PURCHASE).singleElement().isEqualTo(purchase);
            assertThat(afterRefund.facts()).filteredOn(f->f.kind()==SupportPaymentFacts.Kind.DEVICE_PURCHASE_REFUND).singleElement().satisfies(f->{assertThat(f.originalFactId()).isEqualTo(purchase.factId());assertThat(f.amount()).isEqualByComparingTo(purchase.amount());});
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND biz_type='ORDER_REFUND'",Long.class,customer,"E4-REFUND-"+order)).isEqualTo(1);
            assertThat(firstFor(customer,agent)).isEqualTo(first);assertSingleFirstCustomer(customer,agent);awaitLaterDatabaseSecond(first.observedCandidate().succeededAt());settleActualCard(customer,"afterrefund");
            assertThat(firstFor(customer,agent)).isEqualTo(first);assertSingleFirstCustomer(customer,agent);
            assertThat(financialSource.readHistory(List.of(customer)).facts()).hasSize(3);
            nativeObservations.put("actualOriginalPurchaseRefund",Map.of("originalFirst",first,"beforePurchase",purchase,"refundResult",refunded,"afterRefund",afterRefund));
        });
        checked("Actual E4 original wallet purchase refund keeps the original source fact/lifetime first, produce one linked purchase-refund ledger, and later real deposit cannot create another first. No cached same-key wallet/refund replay, deposit-refund, partial-refund/cross-month or complete refund-coverage/net-rank claim; original treasury/A2 checks not bypassed.");
    }

    @Test void realUnassignedPaymentAndMissingLegacyBirthKeepDifferentUnknownDimensions()throws Exception {
        long born=customer(true),legacy=customer(false);wallet(born,"1000000","0");wallet(legacy,"1000000","0");
        rollbackFinancialCommands(receipts->{
            String known=createActualOrder(born,receipts);payActualOrder(born,known,receipts);
            String old=createActualOrder(legacy,receipts);payActualOrder(legacy,old,receipts);
            var first=firstFor(born,boss);assertThat(first.state()).isEqualTo(SupportAnalyticsStats.FirstState.CONFIRMED);
            assertThat(first.observedCandidate().attribution().agent()).isEqualTo(SupportAnalyticsStats.AttributionStatus.UNASSIGNED);
            assertThat(first.observedCandidate().attribution().group()).isEqualTo(SupportAnalyticsStats.AttributionStatus.UNASSIGNED);
            assertThat(firstFor(legacy,boss).state()).isEqualTo(SupportAnalyticsStats.FirstState.UNKNOWN);
            var source=financialSource.readHistory(List.of(born,legacy));assertThat(source.facts()).hasSize(2);
            assertThat(source.firstHistory()).filteredOn(h->h.customerId()==born).singleElement().satisfies(h->assertThat(h.status()).isEqualTo(SupportPaymentFacts.Status.READY));
            assertThat(source.firstHistory()).filteredOn(h->h.customerId()==legacy).singleElement().satisfies(h->{assertThat(h.status()).isEqualTo(SupportPaymentFacts.Status.UNKNOWN);assertThat(h.reasons()).contains("NEW_ACCOUNT_BIRTH_NOT_PROVEN");});
            assertThat(jdbc.queryForList("SELECT * FROM nx_support_payment_history_birth WHERE customer_id=?",legacy)).isEmpty();
            assertThat(jdbc.queryForMap("SELECT agent_status,agent_admin_id,group_status,group_id FROM nx_support_payment_attribution WHERE customer_id=?",born)).containsEntry("agent_status","UNASSIGNED").containsEntry("agent_admin_id",null).containsEntry("group_status","UNASSIGNED").containsEntry("group_id",null);
            nativeObservations.put("provenUnassignedVersusLegacyUnknown",Map.of("bornFirst",first,"legacyFirst",firstFor(legacy,boss),"source",source));
        });
        checked("Both real payments are observed; only authentic new-registration birth permits CONFIRMED first. Proven event-time UNASSIGNED owner remains distinct from legacy history UNKNOWN; no old-customer birth/backfill or artificial owner-UNKNOWN certificate. Missing historical owner evidence remains a separate untested branch.");
    }

    @FunctionalInterface interface FinancialAction {void run(SupportFirstChargeCommandReceipt receipts);}
    void rollbackFinancialCommands(FinancialAction action)throws Exception {
        var before=allTableHashes();var receipts=new SupportFirstChargeCommandReceipt(jdbc,json,customerIdentities,transactions);var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(90);
        nativeObservations.put("beforeNewFinancialAllInstalledTableHashes",before);Throwable failure=null;
        try{tx.executeWithoutResult(status->{try{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();action.run(receipts);}finally{status.setRollbackOnly();}});}
        catch(RuntimeException|Error ex){failure=ex;throw ex;}
        finally {
            var checks=new ArrayList<Runnable>();checks.add(receipts::cleanupAfterRollback);checks.add(()->{try{var after=allTableHashes();nativeObservations.put("afterNewFinancialAllInstalledTableHashes",after);assertThat(after).isEqualTo(before);}catch(Exception ex){throw new IllegalStateException(ex);}});
            try{SupportObjectEvidenceLedger.cleanupIndependently(checks.toArray(Runnable[]::new));}
            catch(RuntimeException|Error cleanup){if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}
            finally{nativeObservations.put("exactFinancialCommandReceipts",receipts.receipts());}
        }
    }
    String createActualOrder(long customer,SupportFirstChargeCommandReceipt receipts) {
        requireCustomer(customer);var candidates=jdbc.queryForList("SELECT id,product_no,price_usdt FROM nx_product WHERE is_deleted=0 AND status IN ('ACTIVE','ON_SALE') AND COALESCE(store_visible,1)=1 AND price_usdt>0 AND price_usdt<1000000 ORDER BY price_usdt,id");
        var chosen=candidates.stream().filter(p->{var e=checkout.purchaseEligibility(customer,String.valueOf(p.get("product_no")));return e.getCode()==0 && e.getData()!=null && Boolean.TRUE.equals(e.getData().get("eligible"));}).findFirst().orElseThrow(()->new AssertionError("Installed released storefront SKU satisfying original buyer gates required; no source/policy fabrication"));
        long product=((Number)chosen.get("id")).longValue();String sku=String.valueOf(chosen.get("product_no"));String key="fst13-"+UUID.randomUUID();var payload=new LinkedHashMap<String,Object>();payload.put("clientOrderId",null);payload.put("productId",product);payload.put("productNo",sku);payload.put("quantity",1);payload.put("voucherId",null);
        var result=receipts.invoke(customer,"APP:ORDER_CREATE:USER:"+customer,key,sha256(payload.toString()),payload,()->checkout.createOrder(customer,null,product,sku,1,null,key));
        assertThat(result.getCode()).as("Real canonical checkout preconditions must hold, never seed a paid order: %s",result.getMessage()).isZero();String order=String.valueOf(result.getData().get("orderNo"));assertThat(order).startsWith("ORD-");
        var identity=jdbc.queryForMap("SELECT * FROM nx_order WHERE order_no=? AND user_id=?",order,customer);assertThat(identity.get("payment_status")).isEqualTo("PENDING");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_order_item WHERE order_no=?",Long.class,order)).isEqualTo(1);
        nativeObservations.put("canonicalCreatedOrder-"+order,identity);return order;
    }
    void payActualOrder(long customer,String order,SupportFirstChargeCommandReceipt receipts) {
        String key="fst13-"+UUID.randomUUID();String hash=sha256(customer+"|"+order);var proof=Map.of("orderNo",order,"customerId",customer,"productionCaller","AppOrderCommandService.pay");
        var result=receipts.invoke(customer,"APP:ORDER_PAYMENT:USER:"+customer,key,hash,proof,()->orderCommands.pay(customer,order,key));assertThat(result.getCode()).as(result.getMessage()).isZero();
        assertThat(result.getData()).containsEntry("idempotent",false);var walletBeforeRepeat=jdbc.queryForMap("SELECT * FROM nx_user_wallet WHERE user_id=?",customer);
        // Same order, new key exercises the authoritative already-paid branch; same-key cache success is not visible until commit.
        String repeatKey="fst13-"+UUID.randomUUID();var repeated=receipts.invoke(customer,"APP:ORDER_PAYMENT:USER:"+customer,repeatKey,hash,proof,()->orderCommands.pay(customer,order,repeatKey));
        assertThat(repeated.getCode()).isZero();assertThat(repeated.getData()).containsEntry("idempotent",true).containsEntry("orderNo",order);
        assertThat(jdbc.queryForMap("SELECT * FROM nx_user_wallet WHERE user_id=?",customer)).isEqualTo(walletBeforeRepeat);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND biz_type='ORDER_PURCHASE'",Long.class,customer,order)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_payment_record WHERE user_id=? AND order_no=?",Long.class,customer,order)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device WHERE user_id=? AND source_order_no=?",Long.class,customer,order)).isEqualTo(1);
    }
    void settleActualCard(long customer,String suffix) {
        String provider=config.activeValue("finance.topup.psp.primary").orElse("Checkout.com");String stem=run+suffix;
        var admission=topups.admit(new TopupCardAdmissionRequest(stem+"admit",stem+"topup",customer,provider,new BigDecimal("100"),"AUTHENTICATED","424242","127.0.0.1",stem+"device"));assertThat(admission.decision()).isEqualTo("ALLOWED");
        var request=new TopupCardSettlementRequest(stem+"settle",stem+"admit",stem+"payment",stem+"topup",customer,provider,stem+"providerpayment",new BigDecimal("100"),admission.feeAmountUsdt(),admission.feeRatePct(),jdbc.queryForObject("SELECT NOW(6)",LocalDateTime.class));topups.settle(request);topups.settle(request);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_payment_record WHERE user_id=? AND payment_no=?",Long.class,customer,stem+"payment")).isEqualTo(1);
    }
    SupportAnalyticsStats.Customer currentFor(long customer,long actor) {
        var query=new SupportAnalyticsStats.Query(null,null,null,SupportAnalyticsStats.Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai","USDT");
        return as(actor,()->statistics.summarize(query)).currentCustomers().stream().filter(c->c.customerId()==customer).findFirst().orElseThrow();
    }
    void assertSingleFirstCustomer(long customer,long actor) {
        var query=new SupportAnalyticsStats.Query(null,null,null,SupportAnalyticsStats.Basis.CURRENT_CUSTOMER_HISTORY,null,null,"Asia/Shanghai","USDT");var stats=as(actor,()->statistics.summarize(query));
        assertThat(stats.currentCustomers()).extracting(SupportAnalyticsStats.Customer::customerId).containsExactly(customer);
        assertThat(stats.financialSummary().firstCandidates().observedValue()).isEqualTo(1);assertThat(stats.financialSummary().firstCandidates().confirmedValue()).isEqualTo(1);
    }
    SupportAnalyticsStats.FirstSelection firstFor(long customer,long actor){return currentFor(customer,actor).first();}
    void assertNoQualifyingFirst(long customer,long actor){assertThat(financialSource.readHistory(List.of(customer)).facts()).isEmpty();assertThat(firstFor(customer,actor).state()).isEqualTo(SupportAnalyticsStats.FirstState.NONE);}
    void assertPeriodBoundary(SupportPaymentFacts.Fact purchase,long customer) {
        var at=purchase.succeededAt();var previous=new SupportAnalyticsStats.Query(null,null,null,SupportAnalyticsStats.Basis.PERIOD_EVENT,at.minusSeconds(1),at,"Asia/Shanghai","USDT");var next=new SupportAnalyticsStats.Query(null,null,null,SupportAnalyticsStats.Basis.PERIOD_EVENT,at,at.plusSeconds(1),"Asia/Shanghai","USDT");
        var before=as(agent,()->statistics.summarize(previous));var after=as(agent,()->statistics.summarize(next));
        assertThat(before.financialSummary().firstCandidates().confirmedValue()).isEqualTo(0);assertThat(after.financialSummary().firstCandidates().confirmedValue()).isEqualTo(1);
        assertThat(before.financialSummary().currencies()).filteredOn(c->c.currency().equals("USDT")).singleElement().satisfies(c->assertThat(c.purchases().observedAmount()).isEqualByComparingTo("0"));
        assertThat(after.financialSummary().currencies()).filteredOn(c->c.currency().equals("USDT")).singleElement().satisfies(c->assertThat(c.purchases().observedAmount()).isEqualByComparingTo(purchase.amount()));
        var utc=at.atZone(java.time.ZoneId.of("Asia/Shanghai")).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();var sameInstant=new SupportAnalyticsStats.Query(null,null,null,SupportAnalyticsStats.Basis.PERIOD_EVENT,utc,utc.plusSeconds(1),"UTC","USDT");
        assertThat(as(agent,()->statistics.summarize(sameInstant)).financialSummary()).isEqualTo(after.financialSummary());
        nativeObservations.put("realEventHalfOpenBoundary",Map.of("customerId",customer,"actualSucceededAt",at,"preceding",before.financialSummary(),"following",after.financialSummary(),"equivalentUtcWindow",sameInstant));
    }
    void awaitLaterDatabaseSecond(LocalDateTime first) {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(4).toNanos();
        while(!jdbc.queryForObject("SELECT NOW(6)",LocalDateTime.class).withNano(0).isAfter(first.withNano(0))) {
            assertThat(System.nanoTime()).as("Actual DB second must advance, no fabricated future event time").isLessThan(deadline);
            try{Thread.sleep(20);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
        }
    }
    static String sha256(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}

    @Test void actualSpringCardSettlementKeepsSingleFirstAndRealAuditInsideFullRollback()throws Exception {
        long customer=customer(true);bind(customer,agent);wallet(customer,"0","0");
        rollbackAllTables(()->{
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_topup_fee_buffer_account WHERE id=1",Long.class)).as("Use installed fee buffer; never manufacture one").isEqualTo(1);
            String payment=run+"payment";
            Runnable deposit=()->{
                String provider=config.activeValue("finance.topup.psp.primary").orElse("Checkout.com");
                var admission=topups.admit(new TopupCardAdmissionRequest(run+"admit",run+"topup",customer,provider,new BigDecimal("100"),"AUTHENTICATED","424242","127.0.0.1",run+"device"));
                assertThat(admission.decision()).isEqualTo("ALLOWED");
                var request=new TopupCardSettlementRequest(run+"settle",run+"admit",payment,run+"topup",customer,provider,run+"providerpayment",new BigDecimal("100"),admission.feeAmountUsdt(),admission.feeRatePct(),jdbc.queryForObject("SELECT NOW(6)",LocalDateTime.class));
                topups.settle(request);topups.settle(request);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_payment_record WHERE payment_no=? AND user_id=?",Long.class,payment,customer)).isEqualTo(1);
            };
            deposit.run();
            var facts=financialSource.readHistory(List.of(customer));
            nativeObservations.put("productionFinancialSourceSnapshot",facts);
            assertThat(facts.facts()).hasSize(1);assertThat(facts.firstHistory()).singleElement().satisfies(f->assertThat(f.status().name()).isEqualTo("READY"));
            assertThat(facts.facts()).filteredOn(f->f.kind().name().equals("DEPOSIT")).singleElement().satisfies(f->assertThat(f.amount()).isEqualByComparingTo("100"));
            assertThat(facts.facts()).noneMatch(f->f.kind().name().equals("DEVICE_PURCHASE"));
            var response=as(agent,()->privateQuery.query(Map.of("keyword",List.of(run),"currency",List.of("USDT"))));
            nativeObservations.put("productionPrivateQueryResponse",response);
            var tree=json.valueToTree(response);assertThat(tree.path("records")).hasSize(1);
            assertThat(tree.path("records").get(0).path("first").path("state").asText()).isEqualTo("CONFIRMED");
            assertThat(tree.path("records").get(0).path("first").path("kind").asText()).isEqualTo("DEPOSIT");
            assertThat(tree.path("selectedCurrent").path("firstConfirmed").path("confirmed").asLong(-1)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_payment_attribution WHERE customer_id=?",Long.class,customer)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE user_id=? AND action='SUPPORT_PAYMENT_ATTRIBUTION_CAPTURED'",Long.class,customer)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE user_id=? AND action='D1_CARD_TOPUP_SETTLED'",Long.class,customer)).isEqualTo(1);
            nativeObservations.put("actualAuditReadback",jdbc.queryForList("SELECT id,action,resource_id,result FROM nx_audit_log WHERE user_id=? ORDER BY id",customer));
        });
        checked("Actual production Spring card admission/settlement and real audit; one deposit-first100, replay unchanged; independent all-table row hashes restored after rollback. No wallet-purchase command, gateway callback, committed financial proof or UI signoff");
    }

    @Test void registrationFacadeCreatesOnlyNewBoundaryAndMissingLegacyHistoryStaysUnknown()throws Exception {
        long fresh=customer(true),legacy=customer(false);bind(fresh,agent);bind(legacy,agent);
        assertThat(fresh("SELECT birth_origin,capture_protocol FROM nx_support_payment_history_birth WHERE customer_id=?",fresh)).hasSize(1);
        assertThat(fresh("SELECT * FROM nx_support_payment_history_birth WHERE customer_id=?",legacy)).isEmpty();
        var rows=analytics(agent,"keyword="+run).path("records");assertThat(rows).hasSize(2);
        for(var row:rows)assertThat(row.path("first").path("state").asText()).isEqualTo(row.path("customerId").asLong()==fresh?"NONE":"UNKNOWN");
        checked("Production registration facade boundary for newly created receipt-owned customer; customer without boundary remains UNKNOWN over real HTTP. No OTP registration claim and no old-customer certificate");
    }

    void rollbackAllTables(Runnable action)throws Exception {
        var before=allTableHashes();nativeObservations.put("beforeRollbackAllInstalledTableHashes",before);var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(90);
        try{tx.executeWithoutResult(status->{try{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);action.run();}finally{status.setRollbackOnly();}});}
        finally{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();var after=allTableHashes();nativeObservations.put("afterRollbackAllInstalledTableHashes",after);assertThat(after).as("Every installed table, independent physical JDBC connection after rollback").isEqualTo(before);}
    }
    Map<String,String> allTableHashes()throws Exception {
        var out=new TreeMap<String,String>();
        try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try(var tables=c.prepareStatement("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME");var rs=tables.executeQuery()) {
            var names=new ArrayList<String>();while(rs.next())names.add(rs.getString(1));assertThat(names).as("Full installed inventory includes the 377-table baseline and additive migrations, never a narrowed subset").hasSizeGreaterThanOrEqualTo(377);
            for(String table:names){assertThat(table).matches("[A-Za-z0-9_]+");var rows=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM `"+table+"`")){var m=r.getMetaData();while(r.next()){var values=new ArrayList<Object>();for(int i=1;i<=m.getColumnCount();i++){Object v=r.getObject(i);values.add(v instanceof byte[] b?Base64.getEncoder().encodeToString(b):v==null?null:v.toString());}rows.add(json.writeValueAsString(values));}}Collections.sort(rows);byte[] bytes=json.writeValueAsBytes(rows);out.put(table,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));}
            }finally{c.rollback();}
        }return out;
    }
}
