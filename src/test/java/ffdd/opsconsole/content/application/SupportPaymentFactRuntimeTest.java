package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.NexionOpsConsoleApplication;
import ffdd.opsconsole.finance.application.SupportPaymentFactService;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Real source-table fixtures, always rolled back; does not call a payment gateway or write wallet balances. */
@EnabledIfEnvironmentVariable(named="CS_ANALYTICS_PAYMENT_FACTS_ENABLED",matches="true")
@SpringBootTest(classes=NexionOpsConsoleApplication.class,webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(SupportEnhancementPreparationTest.IsolatedConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportPaymentFactRuntimeTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<String,Object> CHECKS=Collections.synchronizedMap(new LinkedHashMap<>());
    private static final LocalDateTime SETTLED=LocalDateTime.of(2026,10,7,12,0,0);
    private static final LocalDateTime CREATED=SETTLED.minusDays(2);
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SupportPaymentFactService facts;
    private String prefix;
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) {SupportEnhancementPreparationTest.isolatedBoundary(r);}
    @BeforeEach void guard() throws Exception {
        var target=SupportRuntimeTarget.current();assertThat(target.analytics()).isTrue();target.requireEnvironment(System.getenv());
        var context=JSON.readTree(Path.of(required("CS_ENHANCE_ACTOR_CONTEXT")).toFile());
        SupportExclusiveRuntimeOwnership.requireActual(context,target,jdbc);
        assertThat(context.path("identity").path("runId").asText()).isEqualTo(required("WORKFLOW_RUN_ID"));
        assertThat(context.path("identity").path("snapshotHash").asText()).isEqualTo(required("WORKFLOW_SNAPSHOT_HASH"));
        prefix="PF"+UUID.randomUUID().toString().replace("-", "").substring(0,16);
    }
    @Test void depositRailsUseSuccessTimesAndLedgerIdentityWithoutProjectionMultiplication() {
        isolatedFixture("deposit-rails",users->{
            long customer=user(users);
            String chain=prefix+"chain";long chainLedger=ledger(customer,chain,"CHAIN_TOPUP","USDT","IN","SUCCESS","10.123456",SETTLED);
            jdbc.update("INSERT INTO nx_deposit_order(user_id,deposit_no,chain_name,chain_tx_hash,asset,amount,status,ledger_id,credited_at,created_at) VALUES(?,?,'BEP20',?,'USDT',?,'CREDITED',?,?,?)",
                customer,chain,prefix+"tx",new BigDecimal("10.123456"),chainLedger,SETTLED,CREATED);
            String card=prefix+"card";long cardLedger=ledger(customer,card,"CARD_TOPUP","USDT","IN","SUCCESS","100",SETTLED);
            payment(customer,card,prefix+"card-order","Card", "100","CHARGEBACK",cardLedger,CREATED);
            String recon=prefix+"r";ledger(customer,"D1-VIETQR-"+recon,"VIETQR_DEPOSIT","USDT","IN","SUCCESS","20",SETTLED);
            reconciliation(customer,recon,null,"20");
            String intent=prefix+"h";long hdLedger=ledger(customer,intent,"VIETQR_DEPOSIT","USDT","IN","SUCCESS","30",SETTLED);
            jdbc.update("INSERT INTO nx_vietqr_intent(intent_no,user_id,create_idempotency_key,create_request_hash,requested_usdt,payable_vnd,credited_usdt,received_vnd,locked_fx_rate_vnd_per_usdt,fx_quote_version,bank_account_id,memo_code,status,expires_at,payment_rail,settlement_target_type) VALUES(?,?,?,REPEAT('a',64),30,750000,30,750000,25000,1,1,?,'CREDITED',?,'HDPAY','WALLET_TOPUP')",
                intent,customer,prefix+"key",prefix+"memo",SETTLED.plusDays(1));
            jdbc.update("INSERT INTO nx_hdpay_payin_order(merchant_order_id,amount_vnd,submission_status,settlement_status,settled_usdt,wallet_ledger_biz_no,settled_at,request_hash) VALUES(?,750000,'CREATED','CREDITED',30,?,?,REPEAT('a',64))",intent,intent,SETTLED);
            reconciliation(customer,prefix+"mirror",intent,"30");
            var snapshot=facts.read(users);
            assertThat(snapshot.issues()).isEmpty();assertThat(snapshot.facts()).hasSize(4);
            assertThat(snapshot.facts()).allMatch(f -> f.kind()==Kind.DEPOSIT && f.succeededAt().equals(SETTLED));
            assertThat(snapshot.facts()).filteredOn(f -> f.ledgerId()==chainLedger).extracting(Fact::amount)
                .containsExactly(new BigDecimal("10.123456"));
            assertThat(snapshot.facts()).filteredOn(f -> f.ledgerId()==cardLedger).extracting(Fact::providerPaidAt).containsExactly(CREATED);
            assertThat(snapshot.facts()).filteredOn(f -> f.ledgerId()==hdLedger).hasSize(1);
            assertThat(facts.read(users).facts()).isEqualTo(snapshot.facts());
            assertThat(snapshot.coverage()).allMatch(c -> c.refundStatus()==Status.UNKNOWN && c.historyStatus()==Status.UNKNOWN
                && c.supportedFrom()==null && c.historicalEnvironmentStatus()==Status.UNKNOWN);
        });
    }
    @Test void devicePaymentsUseActualDebitIncludingPaidTrialWhileFreeAndPendingDoNotCount() {
        isolatedFixture("device-paid-free-trial",users->{
            long customer=user(users);String bundle=prefix+"bundle";
            order(customer,bundle,"BUNDLE","80",SETTLED);ledger(customer,bundle,"ORDER_PURCHASE","USDT","OUT","SUCCESS","80",SETTLED);
            payment(customer,prefix+"pay",bundle,"NEXGRID_WALLET","80","PAID",null,SETTLED);
            jdbc.update("UPDATE nx_order SET payment_no=? WHERE order_no=?",prefix+"pay",bundle);
            for(int i=0;i<3;i++) device(customer,bundle,"ORDER",0);
            for(int i=0;i<2;i++) jdbc.update("INSERT INTO nx_order_item(order_no,product_id,product_name,quantity,unit_price_usdt,line_amount_usdt) VALUES(?,42,'fixture',2,40,80)",bundle);
            String trade=prefix+"trade";order(customer,trade,"TRADE_IN","7",SETTLED);ledger(customer,trade,"TRADE_IN_PURCHASE","USDT","OUT","SUCCESS","7",SETTLED);
            String keep=prefix+"keep";order(customer,keep,"CAPACITY_KEEP","9",SETTLED);ledger(customer,keep,"DEVICE_PURCHASE","USDT","OUT","SUCCESS","9",SETTLED);
            long trialCustomer=user(users);String trial=prefix+"trial",claim=prefix+"claim";
            order(trialCustomer,trial,"TRIAL_CONVERT","25",SETTLED);long device=device(trialCustomer,trial,"TRIAL",1);
            trial(trialCustomer,claim,device,"REDEEMED","25");ledger(trialCustomer,claim+":CHARGE","TRIAL_CHARGE","USDT","OUT","POSTED","25",SETTLED);
            long free=user(users);trial(free,prefix+"free",null,"ACTIVE","0");
            String voucher=prefix+"voucher";order(customer,voucher,"SINGLE","0",SETTLED);
            String pending=prefix+"pending";order(customer,pending,"SINGLE","80",null);
            var snapshot=facts.read(users);
            assertThat(snapshot.issues()).isEmpty();assertThat(snapshot.facts()).hasSize(4);
            assertThat(snapshot.facts()).filteredOn(f -> f.orderNo().equals(bundle)).extracting(Fact::amount).containsExactly(new BigDecimal("80.000000"));
            assertThat(snapshot.facts()).filteredOn(f -> f.orderNo().equals(trial)).allSatisfy(f->{
                assertThat(f.source()).isEqualTo(Source.TRIAL_CONVERT);assertThat(f.amount()).isEqualByComparingTo("25");
                assertThat(f.sourceBusinessId()).isEqualTo(claim+":CHARGE");assertThat(f.succeededAt()).isEqualTo(SETTLED);});
            assertThat(snapshot.facts()).noneMatch(f -> voucher.equals(f.orderNo()) || pending.equals(f.orderNo()));
            assertThat(facts.read(users).facts()).isEqualTo(snapshot.facts());
        });
    }
    @Test void refundsKeepOriginalPaymentsAndBrokenOrOverAmountSourcesRemainUnknown() {
        isolatedFixture("refund-lineage-and-missing-evidence",users->{
            long customer=user(users);String order=prefix+"order";
            order(customer,order,"TRADE_IN","80",SETTLED);ledger(customer,order,"TRADE_IN_PURCHASE","USDT","OUT","SUCCESS","80",SETTLED);
            long refund=ledger(customer,"E4-REFUND-"+order,"ORDER_REFUND","USDT","IN","SUCCESS","80",SETTLED.plusDays(2));
            jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",order);
            String excess=prefix+"excess";order(customer,excess,"CAPACITY_KEEP","10",SETTLED);
            ledger(customer,excess,"DEVICE_PURCHASE","USDT","OUT","SUCCESS","10",SETTLED);
            ledger(customer,"E4-REFUND-"+excess,"ORDER_REFUND","USDT","IN","SUCCESS","11",SETTLED);
            ledger(customer,"E4-REFUND-"+prefix+"unknown","ORDER_REFUND","USDT","IN","SUCCESS","12",SETTLED);
            String bad=prefix+"bad";long badLedger=ledger(customer,bad,"CHAIN_TOPUP","USDT","IN","SUCCESS","13",SETTLED);
            jdbc.update("INSERT INTO nx_deposit_order(user_id,deposit_no,chain_name,chain_tx_hash,asset,amount,status,ledger_id,credited_at) VALUES(?,?,'BEP20',?,'USDT',13,'CREDITED',?,NULL)",customer,bad,prefix+"badtx",badLedger);
            ledger(customer,prefix+"orphan:CHARGE","TRIAL_CHARGE","USDT","OUT","POSTED","14",SETTLED);
            String drift=prefix+"drift";order(customer,drift,"SINGLE","16",SETTLED.plusDays(3));
            ledger(customer,drift,"ORDER_PURCHASE","USDT","OUT","SUCCESS","16",SETTLED);
            payment(customer,prefix+"driftpay",drift,"NEXGRID_WALLET","16","PAID",null,SETTLED);
            jdbc.update("UPDATE nx_order SET payment_no=? WHERE order_no=?",prefix+"driftpay",drift);
            String confirm=prefix+"confirm";order(customer,confirm,"SINGLE","17",SETTLED);
            ledger(customer,confirm,"ORDER_PURCHASE","USDT","OUT","SUCCESS","17",SETTLED);
            payment(customer,prefix+"confirmpay",confirm,"NEXGRID_WALLET","17","PAID",null,SETTLED.minusDays(2));
            jdbc.update("UPDATE nx_order SET payment_no=? WHERE order_no=?",prefix+"confirmpay",confirm);
            long trialCustomer=user(users);String trial=prefix+"drifttrial",claim=prefix+"driftclaim";
            order(trialCustomer,trial,"TRIAL_CONVERT","18",SETTLED);long device=device(trialCustomer,trial,"TRIAL",0);
            trial(trialCustomer,claim,device,"REDEEMED","18");
            ledger(trialCustomer,claim+":CHARGE","TRIAL_CHARGE","USDT","OUT","POSTED","18",SETTLED.plusDays(1));
            var snapshot=facts.read(users);
            assertThat(snapshot.facts()).hasSize(3);
            assertThat(snapshot.facts()).filteredOn(f -> f.ledgerId()==refund).allSatisfy(f->{
                assertThat(f.originalFactId()).isEqualTo("PURCHASE:"+order);assertThat(f.succeededAt()).isEqualTo(SETTLED.plusDays(2));});
            assertThat(snapshot.facts()).filteredOn(f -> ("PURCHASE:"+order).equals(f.factId())).hasSize(1);
            assertThat(snapshot.issues()).extracting(Issue::reason).contains("REFUND_EXCEEDS_ORIGINAL_AMOUNT","BROKEN_SOURCE_LINK",
                "MISSING_SUCCESS_TIME","MISSING_AUTHORITATIVE_SOURCE","CONFLICTING_SUCCESS_TIME");
            assertThat(snapshot.issues()).filteredOn(i -> i.reason().equals("CONFLICTING_SUCCESS_TIME")).hasSize(3);
            assertThat(facts.read(users).facts()).isEqualTo(snapshot.facts());
            assertThat(snapshot.coverage()).filteredOn(c -> c.source()==Source.DEPOSIT_ORDER)
                .allSatisfy(c -> assertThat(c.observedStatus()).isEqualTo(Status.UNKNOWN));
        });
    }
    private void isolatedFixture(String check,Consumer<List<Long>> action) {
        var users=new ArrayList<Long>();var before=tableCounts();
        new TransactionTemplate(transactions).executeWithoutResult(status->{try {action.accept(users);} finally {status.setRollbackOnly();}});
        assertThat(tableCounts()).as("all original source-table row counts after actual rollback").isEqualTo(before);
        for(long id:users) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user WHERE id=?",Long.class,id)).isZero();
        assertThat(facts.read(users).facts()).as("fresh read outside rolled-back fixture transaction").isEmpty();
        CHECKS.put(check,Map.of("status","pass","suite",getClass().getSimpleName(),"testcase",switch(check){
            case "deposit-rails"->"depositRailsUseSuccessTimesAndLedgerIdentityWithoutProjectionMultiplication";
            case "device-paid-free-trial"->"devicePaymentsUseActualDebitIncludingPaidTrialWhileFreeAndPendingDoNotCount";
            default->"refundsKeepOriginalPaymentsAndBrokenOrOverAmountSourcesRemainUnknown";},
            "evidence","Actual isolated MySQL source rows and production SELECT adapters reconciled; repeated read identical; fixture transaction rolled back, original source-table counts preserved, fresh read sees no fixture facts. Not gateway/payment settlement execution."));
    }
    private Map<String,Long> tableCounts() {
        var result=new TreeMap<String,Long>();for(String t:List.of("nx_user","nx_wallet_ledger","nx_deposit_order","nx_payment_record",
            "nx_order","nx_order_item","nx_trial_claim","nx_user_device","nx_vietqr_reconciliation","nx_vietqr_intent","nx_hdpay_payin_order"))
            result.put(t,jdbc.queryForObject("SELECT COUNT(*) FROM "+t,Long.class));return result;
    }
    private long user(List<Long> users) {
        String ref=UUID.randomUUID().toString().replace("-", "").substring(0,20);
        jdbc.update("INSERT INTO nx_user(country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox) VALUES('0',?,'127.0.0.1','fixture-only','payment-fact-fixture',?,'ACTIVE',0)",ref,ref);
        long id=jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,ref);users.add(id);return id;
    }
    private long ledger(long user,String business,String type,String currency,String direction,String status,String amount,LocalDateTime at) {
        jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,created_at) VALUES(?,?,?,?,?,?,0,?,?)",
            user,business,type,currency,direction,new BigDecimal(amount),status,at);
        return jdbc.queryForObject("SELECT id FROM nx_wallet_ledger WHERE biz_no=? AND asset=? AND direction=?",Long.class,business,currency,direction);
    }
    private void payment(long user,String payment,String order,String provider,String amount,String status,Long ledger,LocalDateTime at) {
        jdbc.update("INSERT INTO nx_payment_record(payment_no,order_no,user_id,provider,provider_payment_id,amount_usdt,currency,payment_status,wallet_ledger_id,paid_at) VALUES(?,?,?,?,?,?,'USDT',?,?,?)",
            payment,order,user,provider,payment,new BigDecimal(amount),status,ledger,at);
    }
    private void order(long user,String order,String type,String amount,LocalDateTime at) {
        jdbc.update("INSERT INTO nx_order(user_id,order_no,product_id,order_type,amount_usdt,payment_status,order_status,paid_at,created_at) VALUES(?,?,42,?,?,?, ?,?,?)",
            user,order,type,new BigDecimal(amount),at==null?"PENDING":"PAID",at==null?"PENDING_PAYMENT":"PAID",at,CREATED);
    }
    private long device(long user,String order,String channel,int deleted) {
        String instance=prefix+UUID.randomUUID().toString().substring(0,8);
        jdbc.update("INSERT INTO nx_user_device(user_id,source_order_no,instance_no,name,device_type,source_channel,source_environment,run_id,status,is_deleted) VALUES(?,?,?,'fixture','BOX',?,'PRODUCTION','','ACTIVE',?)",user,order,instance,channel,deleted);
        return jdbc.queryForObject("SELECT id FROM nx_user_device WHERE instance_no=?",Long.class,instance);
    }
    private void trial(long user,String claim,Long device,String status,String amount) {
        jdbc.update("INSERT INTO nx_trial_claim(user_id,claim_no,user_device_id,device_name,status,claimed_at,expires_at,settled_at,settlement_amount_usdt) VALUES(?,?,?,'fixture',?,?,?, ?,?)",
            user,claim,device,status,CREATED,SETTLED.plusDays(3),status.equals("REDEEMED")?SETTLED:null,new BigDecimal(amount));
    }
    private void reconciliation(long user,String no,String intent,String amount) {
        jdbc.update("INSERT INTO nx_vietqr_reconciliation(reconciliation_no,intent_no,user_id,view_type,status,payable_vnd,received_vnd,locked_fx_rate_vnd_per_usdt,credited_usdt,received_at) VALUES(?,?,?,'MATCHED','CREDITED',750000,750000,25000,?,?)",no,intent,user,new BigDecimal(amount),CREATED);
    }
    @AfterAll static void writeEvidence() throws Exception {
        var out=new LinkedHashMap<String,Object>();out.put("checkedAt",Instant.now().toString());out.put("checks",CHECKS);
        out.put("workflowRunId",required("WORKFLOW_RUN_ID"));out.put("snapshotHash",required("WORKFLOW_SNAPSHOT_HASH"));
        out.put("capability","runtime");out.put("fixtureMode","REAL_MYSQL_TRANSACTION_ROLLBACK");
        Files.write(Path.of(required("CS_ENHANCE_EVIDENCE_DIR")).resolve("payment-facts-runtime.json"),
            JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(out),StandardOpenOption.CREATE_NEW);
    }
    private static String required(String name) {String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing runtime identity: "+name);return value;}
}
