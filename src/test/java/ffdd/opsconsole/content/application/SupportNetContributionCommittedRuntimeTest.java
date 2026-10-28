package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import ffdd.opsconsole.auth.application.AuthNewAccountCreator;
import ffdd.opsconsole.commerce.application.AppOrderCommandService;
import ffdd.opsconsole.finance.application.TopupCardLifecycleService;
import ffdd.opsconsole.finance.dto.*;
import ffdd.opsconsole.finance.facade.SupportFundAuthBirthFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.user.facade.UserRegistrationWriteFacade.NewAccountCommand;
import io.minio.*;
import java.math.BigDecimal;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** Independently opt-in NET commands; retained financial facts are not a restoration or phase signoff. */
@EnabledIfEnvironmentVariable(named="CS_NET_CONTRIBUTION_COMMITTED_ENABLED",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties="nexion.finance.development-payout-address.enabled=false")
@Import({SupportIsolatedRuntime.class,SupportObjectEvidenceLedger.Configuration.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportNetContributionCommittedRuntimeTest extends SupportAnalyticsRuntimeFixture {
    static final String ORIGINAL_UUID="3556ddae-c1a1-11f1-8853-a40c6626953d";
    static final String APP_CURRENT_USER="support_net_r55_app@127.0.0.1";
    @Autowired AuthNewAccountCreator accounts;
    @Autowired EventOutboxService outbox;
    @Autowired PasswordEncoder passwords;
    @Autowired AppCanonicalBoundaryService checkout;
    @Autowired AppOrderCommandService orders;
    @Autowired TopupCardLifecycleService topups;
    @Autowired PlatformConfigFacade config;
    @Autowired SupportFundsReadFacade funds;
    @Autowired ffdd.opsconsole.finance.mapper.SupportFundReadMapper fundReader;
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    final List<Map<String,Object>> committedCommands=new ArrayList<>();
    boolean cloneGuardPassed;
    long lastReadConnectionId;

    @DynamicPropertySource static void boundary(DynamicPropertyRegistry registry) throws Exception {
        requireNetClone(); // Physical read-only fence before Spring startup or fixture writes.
        SupportEnhancementPreparationTest.isolatedBoundary(registry);
    }

    static void require(boolean condition,String reason) {if(!condition)throw new IllegalStateException(reason);}
    static boolean digest(JsonNode value) {return value!=null && value.isTextual() && value.textValue().matches("[0-9a-f]{64}");}
    static void validateNetLineage(JsonNode context,JsonNode proof,String run,String snapshot,boolean requireBefore) {
        require(context!=null && proof!=null,"Actual NET context and ownership proof required");
        require(run!=null && !run.isBlank() && snapshot!=null && snapshot.matches("[0-9a-f]{64}")
            && run.equals(context.path("identity").path("runId").asText()) && snapshot.equals(context.path("identity").path("snapshotHash").asText())
            && run.equals(proof.path("workflowRunId").asText()) && snapshot.equals(proof.path("snapshotHash").asText()),"Current NET run and snapshot required");
        require("BUSINESS_PHASE".equals(context.path("purpose").asText()) && context.path("businessAuthorized").isBoolean()
            && context.path("businessAuthorized").booleanValue(),"Current authorized business context required");
        for(String field:List.of("contractSha256","functionDefinitionSha256","appGrantsSha256","readerGrantsSha256"))
            require(digest(proof.path("schemaValidation").get(field)),"Current full schema/principal validation missing: "+field);
        if(requireBefore)require(context.path("objectBefore").path("path").isTextual() && !context.path("objectBefore").path("path").asText().isBlank()
            && digest(context.path("objectBefore").get("sha256")),"Independent NET before required");
    }
    static void validateAppIdentity(Map<String,Object> row,String cloneUuid,String expectedDirectory) {
        validateDatabaseIdentity(row,cloneUuid,expectedDirectory,APP_CURRENT_USER);
    }
    private static void validateDatabaseIdentity(Map<String,Object> row,String cloneUuid,String expectedDirectory,String expectedAccount) {
        require(row!=null && cloneUuid!=null && cloneUuid.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
            && !ORIGINAL_UUID.equalsIgnoreCase(cloneUuid),"New fixed clone UUID required");
        require("cs_analytics_20261007".equals(row.get("databaseName")) && number(row,"port")==33338
            && cloneUuid.equals(row.get("serverUuid")) && expectedAccount.equals(row.get("currentUser"))
            && row.get("dataDirectory") instanceof String directory
            && expectedDirectory!=null && expectedDirectory.replace('\\','/').startsWith("D:/CodexData/test-environments/workflow-runs/")
            && expectedDirectory.replace('\\','/').replaceAll("/+$","").equals(directory.replace('\\','/').replaceAll("/+$","")),"Actual restricted APP identity required");
    }
    static void validateReaderIdentity(Map<String,Object> row,String cloneUuid,String expectedDirectory,List<String> grants,String expectedGrantsSha256) throws Exception {
        validateDatabaseIdentity(row,cloneUuid,expectedDirectory,"support_net_schema_reader@127.0.0.1");
        require(grants!=null && !grants.isEmpty() && expectedGrantsSha256!=null && expectedGrantsSha256.matches("[0-9a-f]{64}")
            && sha(strictJson().writeValueAsBytes(grants.stream().sorted().toList())).equals(expectedGrantsSha256),"Actual snapshot reader grants changed");
    }
    static Connection openSnapshotConnection() throws SQLException {
        require("support_net_schema_reader".equals(required("NEXION_SUPPORT_NET_SCHEMA_READER_USERNAME")),"Fixed snapshot reader required");
        return DriverManager.getConnection(required("NEXION_DB_URL"),required("NEXION_SUPPORT_NET_SCHEMA_READER_USERNAME"),required("NEXION_SUPPORT_NET_SCHEMA_READER_PASSWORD"));
    }
    static long number(Map<String,Object> row,String field) {
        require(row!=null && row.get(field) instanceof Number,"Missing numeric raw field: "+field);
        try{return new BigDecimal(row.get(field).toString()).longValueExact();}catch(ArithmeticException failure){throw new IllegalStateException("Invalid integer raw field: "+field,failure);}
    }
    static BigDecimal decimal(Map<String,Object> row,String field) {
        require(row!=null && row.get(field) instanceof BigDecimal,"Missing decimal raw field: "+field);return (BigDecimal)row.get(field);
    }
    static boolean falseFlag(Map<String,Object> row,String field) {
        Object value=row.get(field);
        return Boolean.FALSE.equals(value) || (value instanceof Number && number(row,field)==0);
    }
    static void validateNewZeroWallet(Map<String,Object> row,long customer) {
        require(number(row,"id")>0 && number(row,"user_id")==customer && number(row,"version")==0 && number(row,"fund_revision")==0
            && falseFlag(row,"sandbox") && falseFlag(row,"is_deleted"),"New production zero-version wallet required");
        require(row.get("fund_incarnation") instanceof String incarnation && incarnation.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"),"AUTH-owned wallet incarnation required");
        for(String field:List.of("usdt_available","nex_available","pending_withdraw","cregis_risk_held"))
            require(decimal(row,field).signum()==0,"New account must have actual zero cash: "+field);
    }
    static ObjectMapper strictJson() {return new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);}
    static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
    static JsonNode reference(JsonNode ref,String exactPath) throws Exception {
        require(ref.path("path").asText().equals(exactPath) && digest(ref.get("sha256")),"Fixed material reference required");
        Path path=Path.of(exactPath).toAbsolutePath().normalize();
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && path.toRealPath().equals(path),"Plain material path required");
        require(Files.size(path)>0 && Files.size(path)<=64L*1024*1024,"Bounded material required");
        byte[] bytes=Files.readAllBytes(path);require(sha(bytes).equals(ref.path("sha256").asText()),"Material bytes changed");return strictJson().readTree(bytes);
    }
    static JsonNode requireNetClone() throws Exception {
        require("true".equals(System.getenv("CS_NET_CONTRIBUTION_COMMITTED_ENABLED")),"NET explicit opt-in required");
        var target=SupportRuntimeTarget.current();require(target.netCommitted(),"Fixed NET target required");target.requireEnvironment(System.getenv());
        var mapper=strictJson();
        Path contextPath=Path.of(required("CS_ENHANCE_ACTOR_CONTEXT"));byte[] contextBytes=Files.readAllBytes(contextPath);
        require(sha(contextBytes).equals(required("CS_ENHANCE_ACTOR_CONTEXT_SHA256")),"NET context bytes changed");
        var context=mapper.readTree(contextBytes);var proof=SupportExclusiveRuntimeOwnership.validate(context,target);
        for(var binding:Map.of("taskId","WORKFLOW_TASK_ID","stepId","WORKFLOW_STEP_ID","checkId","WORKFLOW_CHECK_ID","repo","WORKFLOW_REPO").entrySet())
            require(context.path("identity").path(binding.getKey()).asText().equals(required(binding.getValue())),"NET workflow identity changed");
        validateNetLineage(context,proof,required("WORKFLOW_RUN_ID"),required("WORKFLOW_SNAPSHOT_HASH"),true);
        String cloneUuid=proof.path("databaseIdentity").path("serverUuid").asText();
        var before=reference(context.path("objectBefore"),required("CS_ENHANCE_EVIDENCE_DIR")+"/object-before.json");
        require("R20_OBJECT_BEFORE".equals(before.path("event").asText()) && before.path("complete").isBoolean() && before.path("complete").booleanValue()
            && cloneUuid.equals(before.path("databaseIdentity").path("serverUuid").asText()),"Actual independent NET before required");
        try(var connection=openPhysicalConnection()) {
            connection.setReadOnly(true);var read=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            SupportExclusiveRuntimeOwnership.requireActual(context,target,read);
            validateAppIdentity(read.queryForMap("SELECT DATABASE() databaseName,@@port port,@@server_uuid serverUuid,@@datadir dataDirectory,CURRENT_USER() currentUser"),cloneUuid,proof.path("databaseIdentity").path("dataDirectory").asText());
            require(Boolean.TRUE.equals(read.queryForObject("SELECT nx_support_fund_schema_ready_v1()",Boolean.class)),"Current complete fund schema is not ready");
        }
        return context;
    }
    static Connection openPhysicalConnection() throws SQLException {return DriverManager.getConnection(required("NEXION_DB_URL"),required("NEXION_DB_USERNAME"),required("NEXION_DB_PASSWORD"));}

    @Override @BeforeEach void controlledActorsAndCurrentResource(TestInfo info) throws Exception {
        var context=requireNetClone();
        assertThat(applicationContext.getEnvironment().getProperty("nexion.finance.development-payout-address.enabled",Boolean.class)).isFalse();
        assertThat(applicationContext.containsBean("developmentPayoutAddressBootstrap")).isFalse();
        cloneGuardPassed=true;super.controlledActorsAndCurrentResource(info);
        var proof=SupportExclusiveRuntimeOwnership.validate(context,SupportRuntimeTarget.current());
        var springIdentity=jdbc.queryForMap("SELECT DATABASE() databaseName,@@port port,@@server_uuid serverUuid,CURRENT_USER() currentUser,@@datadir dataDirectory,CONNECTION_ID() connectionId");
        validateAppIdentity(springIdentity,proof.path("databaseIdentity").path("serverUuid").asText(),proof.path("databaseIdentity").path("dataDirectory").asText());
        assertThat(fundReader.schemaReady()).isTrue();
        nativeObservations.put("netLineage",Map.of("contextSha256",required("CS_ENHANCE_ACTOR_CONTEXT_SHA256"),"actualSpringPrincipal",springIdentity,
            "actualIndependentReadPrincipal",physical("SELECT DATABASE() databaseName,@@port port,@@server_uuid serverUuid,CURRENT_USER() currentUser,@@datadir dataDirectory,CONNECTION_ID() connectionId")));
    }

    @Test void netRechargeHardwareSpendReplayThenSecondRechargeIsDeduplicated() throws Exception {
        long customer=bornCustomer();bind(customer,agent);assertNet(customer,BigDecimal.ZERO,"AUTH_BIRTH");
        var product=product(customer,"stellarbox-s1","DEVICE");BigDecimal amount=decimal(product,"price_usdt").add(new BigDecimal("100"));
        deposit(customer,amount,"hardware-first");assertNet(customer,amount,"REAL_FIRST_RECHARGE");
        String order=createOrder(customer,product);String paymentKey=key();pay(customer,order,paymentKey);
        var wallet=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer);var witness=orderWitness(customer,order);
        assertNet(customer,amount,"HARDWARE_SPEND_NOT_COUNTED_TWICE");
        pay(customer,order,paymentKey);assertThat(physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer)).isEqualTo(wallet);
        assertThat(orderWitness(customer,order)).isEqualTo(witness);assertNet(customer,amount,"REAL_PAYMENT_REPLAY");
        BigDecimal later=new BigDecimal("100");deposit(customer,later,"hardware-second");assertNet(customer,amount.add(later),"SECOND_RECHARGE");
        checked("Real AUTH zero birth, card settlement, hardware payment/replay and second card settlement; actual APP cash, Reader commit/readback and own-customer HTTP NET. Withdrawals/earnings/refund and leaderboard NET remain pending separate acceptance.");
    }
    @Test void netRechargeCloudSharePaidConsumptionIsDeduplicated() throws Exception {
        long customer=bornCustomer();bind(customer,agent);assertNet(customer,BigDecimal.ZERO,"AUTH_BIRTH");
        var product=product(customer,"cloud-share","SHARE");BigDecimal amount=decimal(product,"price_usdt").add(new BigDecimal("100"));
        deposit(customer,amount,"share-first");assertNet(customer,amount,"REAL_RECHARGE");
        String order=createOrder(customer,product);pay(customer,order,key());var witness=orderWitness(customer,order);
        assertThat((List<?>)witness.get("device")).as("Actual paid Cloud Share source must exist").hasSize(1);
        assertNet(customer,amount,"NON_HARDWARE_PAID_SPEND_NOT_COUNTED_TWICE");
        checked("Real AUTH zero birth and card recharge -> existing paid Cloud Share order source; actual source Reader deduplicates recharge-funded non-hardware spending. No APP refund entry added; withdrawals/earnings/admin refund remain pending.");
    }

    long bornCustomer() throws Exception {
        requireNetClone();String ref=UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT);
        String phone="137"+String.format(Locale.ROOT,"%08d",new java.security.SecureRandom().nextInt(100_000_000));
        var intent=new LinkedHashMap<String,Object>();intent.put("event","PREPARED_BEFORE_AUTH_INSERT");intent.put("referralCode",ref);intent.put("sandbox",0);committedCommands.add(intent);saveIntent();
        long customer=objects.createCustomer(getClass().getSimpleName(),testcase,ref,()->{
            intent.put("transactionConnectionId",jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
            var handle=accounts.insertNew(new NewAccountCommand("86",phone,"127.0.0.1",passwords.encode(UUID.randomUUID().toString()),run,ref,null,null,"L1","V0","ACTIVE",0,"en"));
            registration.register(handle.customerId(),null);accounts.ensureWallet(handle);
            String eventId=outbox.publish("USER_REGISTRATION",String.valueOf(handle.customerId()),"auth.register_completed",Map.of("userId",handle.customerId()));
            var outcome=accounts.complete(handle,eventId);intent.put("registrationEventId",eventId);intent.put("birthOutcome",outcome.name());
            require(outcome==SupportFundAuthBirthFacade.Outcome.BOUND,"Real AUTH birth must bind NEW_ZERO in its own transaction");
            long lookup=jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,ref);
            // insertNewAccount only returns after actual mapper.insert==1 and positive generated ID.
            return new SupportObjectEvidenceLedger.CustomerInsert(handle.customerId(),1,lookup);
        });
        customers.add(customer);customerIdentities.put(customer,physical("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",customer).get(0));
        intent.put("readConnectionId",lastReadConnectionId);assertThat(lastReadConnectionId).isNotEqualTo(intent.get("transactionConnectionId"));
        var wallet=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer);intent.put("wallet",wallet);saveIntent();assertThat(wallet).hasSize(1);validateNewZeroWallet(wallet.get(0),customer);
        var birth=physical("SELECT * FROM nx_support_wallet_journal WHERE wallet_incarnation=?",wallet.get(0).get("fund_incarnation"));intent.put("journal",birth);saveIntent();
        assertThat(birth).singleElement().satisfies(row->{assertThat(row.get("observation")).isEqualTo("NEW_ZERO");assertThat(number(row,"revision")).isZero();assertThat(number(row,"new_version")).isZero();assertThat(number(row,"new_user_id")).isEqualTo(customer);});
        var event=physical("SELECT * FROM nx_event_outbox WHERE event_id=?",intent.get("registrationEventId"));intent.put("outbox",event);saveIntent();assertThat(event).hasSize(1);
        var operation=physical("SELECT * FROM nx_support_fund_operation WHERE op_key=?","AUTH_FUND_BIRTH:"+customer);intent.put("birthOperation",operation);saveIntent();
        assertThat(operation).singleElement().satisfies(row->{assertThat(row.get("business_type")).isEqualTo("AUTH_FUND_BIRTH");assertThat(row.get("source_environment")).isEqualTo("PRODUCTION");assertThat(row.get("source_key")).isEqualTo(intent.get("registrationEventId"));assertThat(row.get("ordered_events").toString()).isEqualTo("[]");});
        long operationId=number(operation.get(0),"id");var parties=physical("SELECT * FROM nx_support_fund_operation_party WHERE operation_id=?",operationId);var legs=physical("SELECT * FROM nx_support_fund_operation_leg WHERE operation_id=?",operationId);intent.put("party",parties);intent.put("leg",legs);saveIntent();
        assertThat(parties).singleElement().satisfies(row->assertThat(number(row,"customer_id")).isEqualTo(customer));assertThat(legs).singleElement().satisfies(row->{assertThat(row.get("wallet_incarnation")).isEqualTo(wallet.get(0).get("fund_incarnation"));assertThat(number(row,"revision")).isZero();});
        intent.put("event","AUTH_COMMITTED_NEW_ZERO_READBACK");saveIntent();return customer;
    }
    Map<String,Object> product(long customer,String sku,String type) {
        requireCustomer(customer);
        return jdbc.queryForList("SELECT id,product_no,product_type,price_usdt FROM nx_product WHERE product_no=? AND product_type=? AND is_deleted=0 AND status IN ('ACTIVE','ON_SALE') AND COALESCE(store_visible,1)=1 AND price_usdt>0 AND price_usdt<999900 ORDER BY price_usdt,id",sku,type).stream()
            .filter(row->{var eligibility=checkout.purchaseEligibility(customer,sku);return eligibility.getCode()==0 && eligibility.getData()!=null && Boolean.TRUE.equals(eligibility.getData().get("eligible"));})
            .findFirst().orElseThrow(()->new AssertionError("Existing actually eligible paid product required; do not modify its business gates"));
    }
    String createOrder(long customer,Map<String,Object> product) throws Exception {
        return createOrder(customer,product,1);
    }
    String createOrder(long customer,Map<String,Object> product,int quantity) throws Exception {
        assertThat(quantity).isBetween(1,2);
        String requestKey=key();var result=command(customer,"CREATE_ORDER",Map.of("productId",product.get("id"),"productNo",product.get("product_no"),"quantity",quantity,"idempotencyKey",requestKey),
            ()->checkout.createOrder(customer,null,number(product,"id"),String.valueOf(product.get("product_no")),quantity,null,requestKey));
        assertThat(result.getCode()).as(result.getMessage()).isZero();String order=String.valueOf(result.getData().get("orderNo"));
        assertThat(physical("SELECT * FROM nx_order WHERE user_id=? AND order_no=?",customer,order)).hasSize(1);return order;
    }
    void pay(long customer,String order,String requestKey) throws Exception {
        var before=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer);var result=command(customer,"PAY_ORDER",Map.of("orderNo",order,"idempotencyKey",requestKey),()->orders.pay(customer,order,requestKey));
        var witness=orderWitness(customer,order);nativeObservations.put("paid-"+order+"-"+committedCommands.size(),witness);saveIntent();
        assertThat(result.getCode()).as(result.getMessage()).isZero();assertThat(witness.get("payment")).as("Paid source readback").isInstanceOf(List.class);
        var ledger=physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND biz_type='ORDER_PURCHASE'",customer,order);
        assertThat(ledger).singleElement().satisfies(row->assertThat(row.get("status")).isEqualTo("SUCCESS"));
        var after=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer);
        if(!before.equals(after))assertThat(decimal(after.get(0),"usdt_available")).isEqualByComparingTo(decimal(before.get(0),"usdt_available").subtract(decimal(ledger.get(0),"amount")));
        assertThat(physical("SELECT * FROM nx_payment_record WHERE user_id=? AND order_no=?",customer,order)).singleElement().satisfies(row->{assertThat(row.get("payment_status")).isEqualTo("PAID");assertThat(row.get("provider")).isEqualTo("UVEL_WALLET");});
        assertThat(physical("SELECT * FROM nx_event_outbox WHERE aggregate_type='ORDER' AND aggregate_id=? AND event_name='checkout.completed'",order)).hasSize(1);
        var paidOrder=physical("SELECT quantity FROM nx_order WHERE user_id=? AND order_no=?",customer,order);assertThat(paidOrder).hasSize(1);
        assertThat(physical("SELECT * FROM nx_user_device WHERE user_id=? AND source_order_no=?",customer,order)).hasSize(Math.toIntExact(number(paidOrder.get(0),"quantity")));
    }
    void deposit(long customer,BigDecimal amount,String label) throws Exception {
        requireCustomer(customer);String stem=run+label;String provider=config.activeValue("finance.topup.psp.primary").orElse("Checkout.com");
        var admissionInput=new TopupCardAdmissionRequest(stem+"admit",stem+"topup",customer,provider,amount,"AUTHENTICATED","424242","127.0.0.1",stem+"device");
        var admission=command(customer,"CARD_ADMIT",admissionInput,()->topups.admit(admissionInput));assertThat(admission.decision()).isEqualTo("ALLOWED");
        var settlement=new TopupCardSettlementRequest(stem+"settle",stem+"admit",stem+"payment",stem+"topup",customer,provider,stem+"providerpayment",amount,admission.feeAmountUsdt(),admission.feeRatePct(),jdbc.queryForObject("SELECT NOW(6)",LocalDateTime.class));
        var before=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer);
        var result=command(customer,"CARD_SETTLE",settlement,()->topups.settle(settlement));
        var rows=new LinkedHashMap<String,Object>();rows.put("wallet",physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer));rows.put("payment",physical("SELECT * FROM nx_payment_record WHERE user_id=? AND payment_no=?",customer,stem+"payment"));rows.put("settlement",physical("SELECT * FROM nx_topup_card_settlement WHERE user_id=? AND settlement_event_id=?",customer,stem+"settle"));rows.put("outbox",physical("SELECT * FROM nx_event_outbox WHERE aggregate_type='WALLET' AND aggregate_id=? AND event_name='wallet.topup_confirmed'",stem+"payment"));
        nativeObservations.put("deposit-"+label,rows);saveIntent();assertThat(result.status()).isEqualTo("SETTLED");
        var after=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer);assertThat(decimal(after.get(0),"usdt_available")).isEqualByComparingTo(decimal(before.get(0),"usdt_available").add(amount));
        assertThat(number(after.get(0),"version")).isEqualTo(number(before.get(0),"version")+1);assertThat(number(after.get(0),"fund_revision")).isEqualTo(number(before.get(0),"fund_revision")+1);
        var payment=physical("SELECT * FROM nx_payment_record WHERE user_id=? AND payment_no=?",customer,stem+"payment");
        assertThat(payment).singleElement().satisfies(row->{assertThat(decimal(row,"amount_usdt")).isEqualByComparingTo(amount);assertThat(row.get("payment_status")).isEqualTo("CONFIRMED");});
        var ledger=physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND biz_type='CARD_TOPUP'",customer,stem+"payment");rows.put("ledger",ledger);saveIntent();
        assertThat(ledger).singleElement().satisfies(row->{assertThat(row.get("direction")).isEqualTo("IN");assertThat(row.get("status")).isEqualTo("SUCCESS");assertThat(decimal(row,"amount")).isEqualByComparingTo(amount);assertThat(number(row,"id")).isEqualTo(number(payment.get(0),"wallet_ledger_id"));});
        assertThat(physical("SELECT * FROM nx_event_outbox WHERE aggregate_type='WALLET' AND aggregate_id=? AND event_name='wallet.topup_confirmed'",stem+"payment")).hasSize(1);
        var replay=command(customer,"CARD_SETTLEMENT_REPLAY",settlement,()->topups.settle(settlement));assertThat(replay.replay()).isTrue();assertThat(physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer)).isEqualTo(after);
    }
    <T>T command(long customer,String action,Object request,Supplier<T> call) throws Exception {
        requireNetClone();requireCustomer(customer);assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var intent=new LinkedHashMap<String,Object>();intent.put("event","PREPARED_BEFORE_"+action);intent.put("customerId",customer);intent.put("customerCreator",customerIdentities.get(customer));intent.put("request",request);committedCommands.add(intent);saveIntent();
        var tx=new TransactionTemplate(transactions);tx.setTimeout(90);
        try {T result=tx.execute(status->{intent.put("transactionConnectionId",jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));return call.get();});
            intent.put("result",result);intent.put("walletAfter",physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer));intent.put("readConnectionId",lastReadConnectionId);
            assertThat(lastReadConnectionId).isNotEqualTo(intent.get("transactionConnectionId"));intent.put("event","COMMITTED_"+action);saveIntent();return result;
        }catch(Exception|AssertionError failure){intent.put("event","FAILED_OR_UNKNOWN_"+action);intent.put("failureClass",failure.getClass().getName());saveIntent();throw failure;}
    }
    Map<String,Object> orderWitness(long customer,String order) throws Exception {
        return Map.of("payment",physical("SELECT * FROM nx_payment_record WHERE user_id=? AND order_no=?",customer,order),"ledger",physical("SELECT * FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND biz_type='ORDER_PURCHASE'",customer,order),"device",physical("SELECT * FROM nx_user_device WHERE user_id=? AND source_order_no=?",customer,order),"outbox",physical("SELECT * FROM nx_event_outbox WHERE aggregate_type='ORDER' AND aggregate_id=? AND event_name='checkout.completed'",order));
    }
    void assertNet(long customer,BigDecimal expected,String checkpoint) throws Exception {
        var observation=new LinkedHashMap<String,Object>();observation.put("event",checkpoint);observation.put("customerId",customer);observation.put("expectedNetUSDT",expected);committedCommands.add(observation);saveIntent();
        var wallet=physical("SELECT * FROM nx_user_wallet WHERE user_id=?",customer);observation.put("wallet",wallet);
        String incarnation=String.valueOf(wallet.get(0).get("fund_incarnation"));observation.put("journal",physical("SELECT * FROM nx_support_wallet_journal WHERE wallet_incarnation=? ORDER BY revision",incarnation));
        observation.put("operations",physical("SELECT o.* FROM nx_support_fund_operation o JOIN nx_support_fund_operation_party p ON p.operation_id=o.id WHERE p.customer_id=? ORDER BY o.id",customer));
        observation.put("parties",physical("SELECT p.* FROM nx_support_fund_operation_party p WHERE p.operation_id IN (SELECT operation_id FROM nx_support_fund_operation_party WHERE customer_id=?) ORDER BY operation_id,customer_id",customer));
        observation.put("legs",physical("SELECT * FROM nx_support_fund_operation_leg WHERE wallet_incarnation=? ORDER BY revision",incarnation));saveIntent();
        SupportFundsReadFacade.NetSnapshot held=null;
        for(int i=0;i<2;i++) {
            var tx=new TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var read=Objects.requireNonNull(tx.execute(status->funds.readNetContributions(List.of(customer))));observation.put("actualReader-"+i,read);saveIntent();
            assertThat(read.customers()).singleElement().satisfies(row->{assertThat(row.customerId()).isEqualTo(customer);assertThat(row.state()).as("No missing source can become zero: %s",row.reasons()).isEqualTo(SupportFundsReadFacade.EvidenceState.READY);assertThat(row.reasons()).isEmpty();assertThat(row.usdt()).isEqualByComparingTo(expected);assertThat(row.nex()).isEqualByComparingTo(BigDecimal.ZERO);});
            if(held!=null)assertThat(read).isEqualTo(held);held=read;
        }
        var page=analytics(agent,"view=CUSTOMERS&basis=CURRENT_CUSTOMER_HISTORY&currency=USDT&keyword="+run);
        observation.put("httpAnalytics",page);saveIntent();
        assertThat(page.path("records").isArray()).isTrue();
        assertThat(page.path("records").size()).as("Only this actor's matching customer is visible").isEqualTo(1);
        var customerRow=page.path("records").get(0);
        assertThat(customerRow.path("customerId").asText()).isEqualTo(Long.toString(customer));
        var amounts=new ArrayList<JsonNode>();
        customerRow.path("lifetime").forEach(value->{if("USDT".equals(value.path("currency").asText()))amounts.add(value);});
        assertThat(amounts).singleElement().satisfies(value->{
            var net=value.path("net");
            assertThat(net.path("status").asText()).isEqualTo("AVAILABLE");
            for(String field:List.of("observed","confirmed")) {
                assertThat(net.path(field).isTextual()).as("Exact HTTP NET decimal: %s",field).isTrue();
                assertThat(new BigDecimal(net.path(field).textValue())).isEqualByComparingTo(expected);
            }
        });
    }
    List<Map<String,Object>> physical(String sql,Object... args) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var context=strictJson().readTree(Path.of(required("CS_ENHANCE_ACTOR_CONTEXT")).toFile());var proof=SupportExclusiveRuntimeOwnership.validate(context,SupportRuntimeTarget.current());
        try(var connection=openPhysicalConnection()) {
            connection.setReadOnly(true);var read=new JdbcTemplate(new SingleConnectionDataSource(connection,true));SupportExclusiveRuntimeOwnership.requireActual(context,SupportRuntimeTarget.current(),read);
            var identity=read.queryForMap("SELECT DATABASE() databaseName,@@port port,@@server_uuid serverUuid,@@datadir dataDirectory,CURRENT_USER() currentUser,CONNECTION_ID() connectionId");
            validateAppIdentity(identity,proof.path("databaseIdentity").path("serverUuid").asText(),proof.path("databaseIdentity").path("dataDirectory").asText());lastReadConnectionId=number(identity,"connectionId");return read.queryForList(sql,args);
        }
    }
    void saveIntent() throws Exception {Files.write(Path.of(required("CS_ENHANCE_EVIDENCE_DIR"),getClass().getSimpleName()+"-"+testcase+"-intents.json"),json.writerWithDefaultPrettyPrinter().writeValueAsBytes(committedCommands),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);}

    @Override @AfterEach void ownedCleanupAndSceneReadback() throws Exception {
        SecurityContextHolder.clearContext();if(!cloneGuardPassed)return;
        var result=new LinkedHashMap<String,Object>();result.put("workflowRunId",required("WORKFLOW_RUN_ID"));result.put("snapshotHash",required("WORKFLOW_SNAPSHOT_HASH"));result.put("netStage","NET_COMMITTED");result.put("suite",getClass().getSimpleName());result.put("testcase",testcase);
        result.put("status",assertionEvidence==null?"not-completed":"scenario-assertions-passed-financial-records-retained");result.put("assertions",assertionEvidence);result.put("financialRecordsRetained",true);result.put("cleanupComplete",false);result.put("cleanupPending",true);result.put("acceptanceCoverage","PARTIAL_NO_PHASE_SIGNOFF");result.put("phaseSigned",false);result.put("wholeTask","NOT_COMPLETE");
        result.put("requests",requests);result.put("committedCommands",committedCommands);result.put("nativeObservations",nativeObservations);result.put("ownedCustomerIds",customers);result.put("ownedActorIds",actors==null?Set.of():actors.ownedIds());result.put("ownedGroupIds",fixture==null?Set.of():fixture.createdGroupIds());result.put("objectEvidencePolicy","retain all ledger creator/intent/outcome and financial facts; do not call fixture cleanup");
        result.put("pendingCoverage",List.of("successful principal withdrawal gross including fee","actual earnings writer and earnings withdrawal","admin original wallet refund and restoration of original lot","product NET API readback","full App OTP/session/dayOne registration"));
        Files.write(Path.of(required("CS_ENHANCE_EVIDENCE_DIR"),getClass().getSimpleName()+"-"+testcase+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);
    }
}

/** Independent NET before capture: no Spring initialization, business fixture or financial writes. */
@EnabledIfEnvironmentVariable(named="CS_NET_CONTRIBUTION_READONLY_CAPTURE",matches="true")
class SupportNetContributionBeforeCaptureTest {
    @Test void captureActualRestrictedNetResources() throws Exception {
        var target=SupportRuntimeTarget.current();SupportNetContributionCommittedRuntimeTest.require(target.netCommitted(),"NET capture target required");target.requireEnvironment(System.getenv());
        var json=SupportNetContributionCommittedRuntimeTest.strictJson();Path directory=Path.of(SupportAnalyticsRuntimeFixture.required("CS_ENHANCE_EVIDENCE_DIR"));
        var identity=new LinkedHashMap<String,String>();
        for(var entry:Map.of("taskId","WORKFLOW_TASK_ID","stepId","WORKFLOW_STEP_ID","checkId","WORKFLOW_CHECK_ID","runId","WORKFLOW_RUN_ID","repo","WORKFLOW_REPO","snapshotHash","WORKFLOW_SNAPSHOT_HASH").entrySet())identity.put(entry.getKey(),SupportAnalyticsRuntimeFixture.required(entry.getValue()));
        Path proofPath=Path.of(SupportAnalyticsRuntimeFixture.required("SUPPORT_RESOURCE_OWNERSHIP"));var proof=json.readTree(Files.readAllBytes(proofPath));
        Path suppliedPath=Path.of(SupportAnalyticsRuntimeFixture.required("CS_ENHANCE_ACTOR_CONTEXT"));
        SupportNetContributionCommittedRuntimeTest.require(SupportAnalyticsSnapshot.fileHash(suppliedPath).equals(SupportAnalyticsRuntimeFixture.required("CS_ENHANCE_ACTOR_CONTEXT_SHA256")),"Current capture context changed");
        JsonNode supplied=json.readTree(suppliedPath.toFile());
        SupportNetContributionCommittedRuntimeTest.require(proof.equals(SupportExclusiveRuntimeOwnership.validate(supplied,target)),"Capture ownership inputs disagree");
        for(var binding:identity.entrySet())SupportNetContributionCommittedRuntimeTest.require(supplied.path("identity").path(binding.getKey()).asText().equals(binding.getValue()),"Capture current workflow identity changed");
        var context=json.convertValue(supplied,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
        var contextNode=json.valueToTree(context);
        SupportNetContributionCommittedRuntimeTest.validateNetLineage(contextNode,proof,identity.get("runId"),identity.get("snapshotHash"),false);
        var tables=new LinkedHashMap<String,Object>();var rawTables=new LinkedHashMap<String,Object>();
        try(var connection=SupportNetContributionCommittedRuntimeTest.openPhysicalConnection()) {
            connection.setReadOnly(true);var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));SupportExclusiveRuntimeOwnership.requireActual(contextNode,target,jdbc);
            SupportNetContributionCommittedRuntimeTest.validateAppIdentity(jdbc.queryForMap("SELECT DATABASE() databaseName,@@port port,@@server_uuid serverUuid,CURRENT_USER() currentUser,@@datadir dataDirectory"),proof.path("databaseIdentity").path("serverUuid").asText(),proof.path("databaseIdentity").path("dataDirectory").asText());
            SupportNetContributionCommittedRuntimeTest.require(Boolean.TRUE.equals(jdbc.queryForObject("SELECT nx_support_fund_schema_ready_v1()",Boolean.class)),"Current complete fund schema is not ready");
            try(var readerConnection=SupportNetContributionCommittedRuntimeTest.openSnapshotConnection()) {
                readerConnection.setReadOnly(true);var reader=new JdbcTemplate(new SingleConnectionDataSource(readerConnection,true));
                String readerIdentitySql="SELECT DATABASE() databaseName,@@port port,@@server_uuid serverUuid,CURRENT_USER() currentUser,@@datadir dataDirectory";
                String cloneUuid=proof.path("databaseIdentity").path("serverUuid").asText(),dataDirectory=proof.path("databaseIdentity").path("dataDirectory").asText();
                String grantsSha256=proof.path("schemaValidation").path("readerGrantsSha256").asText();
                SupportNetContributionCommittedRuntimeTest.validateReaderIdentity(reader.queryForMap(readerIdentitySql),cloneUuid,dataDirectory,reader.queryForList("SHOW GRANTS",String.class),grantsSha256);
                for(String table:reader.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME",String.class)) {
                    SupportNetContributionCommittedRuntimeTest.require(table.matches("[A-Za-z0-9_]+"),"Known native table identifier required");
                    var captured=SupportAnalyticsSnapshot.capture(reader,table);tables.put(table,captured.summary());
                    if(SupportAnalyticsSnapshot.OBJECT_COLUMNS.containsKey(table))rawTables.put(table,captured.objectRows());
                }
                SupportNetContributionCommittedRuntimeTest.validateReaderIdentity(reader.queryForMap(readerIdentitySql),cloneUuid,dataDirectory,reader.queryForList("SHOW GRANTS",String.class),grantsSha256);
            }
            SupportExclusiveRuntimeOwnership.requireActual(contextNode,target,jdbc);
            SupportNetContributionCommittedRuntimeTest.validateAppIdentity(jdbc.queryForMap("SELECT DATABASE() databaseName,@@port port,@@server_uuid serverUuid,CURRENT_USER() currentUser,@@datadir dataDirectory"),proof.path("databaseIdentity").path("serverUuid").asText(),proof.path("databaseIdentity").path("dataDirectory").asText());
            SupportNetContributionCommittedRuntimeTest.require(Boolean.TRUE.equals(jdbc.queryForObject("SELECT nx_support_fund_schema_ready_v1()",Boolean.class)),"Current complete fund schema changed during capture");
        }
        var storage=MinioClient.builder().endpoint(target.storageEndpoint()).credentials(SupportAnalyticsRuntimeFixture.required("NEXION_MINIO_ACCESS_KEY"),SupportAnalyticsRuntimeFixture.required("NEXION_MINIO_SECRET_KEY")).build();
        var objects=new ArrayList<Map<String,Object>>();
        for(var result:storage.listObjects(ListObjectsArgs.builder().bucket(target.bucket()).recursive(true).build())) {
            var object=result.get();var digest=java.security.MessageDigest.getInstance("SHA-256");long size=0;
            try(var input=storage.getObject(GetObjectArgs.builder().bucket(target.bucket()).object(object.objectName()).build())) {byte[] buffer=new byte[65536];int read;while((read=input.read(buffer))!=-1){digest.update(buffer,0,read);size+=read;}}
            SupportNetContributionCommittedRuntimeTest.require(size==object.size(),"Object changed during NET before");objects.add(Map.of("bucket",target.bucket(),"key",object.objectName(),"size",size,"sha256",HexFormat.of().formatHex(digest.digest())));
        }
        objects.sort(Comparator.comparing(row->row.get("key").toString()));
        var shared=new LinkedHashMap<String,Object>();shared.put("schemaVersion",2);shared.put("netStage","NET_COMMITTED");shared.put("at",Instant.now().toString());shared.put("identity",identity);shared.put("databaseIdentity",proof.path("databaseIdentity"));shared.put("tables",tables);
        Path sharedPath=directory.resolve("shared-before.json");writeNew(sharedPath,json,shared);
        var before=new LinkedHashMap<String,Object>();before.put("schemaVersion",1);before.put("event","R20_OBJECT_BEFORE");before.put("netStage","NET_COMMITTED");before.put("complete",true);before.put("at",Instant.now().toString());for(String field:List.of("candidate","windowId","identity","resourceIdentity"))before.put(field,context.get(field));before.put("databaseIdentity",proof.path("databaseIdentity"));before.put("objects",objects);
        var objectTables=new LinkedHashMap<String,Object>();
        for(String table:List.of("nx_admin","nx_user","nx_support_admin_avatar_asset","nx_support_attachment","nx_support_attachment_command","nx_support_bulk_job","nx_admin_account_state")){SupportNetContributionCommittedRuntimeTest.require(rawTables.containsKey(table),"Required APP-observed NET before table missing: "+table);objectTables.put(table,rawTables.get(table));}
        before.put("tables",objectTables);Path beforePath=directory.resolve("object-before.json");writeNew(beforePath,json,before);
        context.put("rootSharedBefore",ref(sharedPath));context.put("phaseSharedBefore",ref(sharedPath));context.put("objectBefore",ref(beforePath));
        SupportNetContributionCommittedRuntimeTest.validateNetLineage(json.valueToTree(context),proof,identity.get("runId"),identity.get("snapshotHash"),true);writeNew(directory.resolve("actor-context.json"),json,context);
    }
    static Map<String,Object> ref(Path path) throws Exception {return Map.of("path",path.toAbsolutePath().normalize().toString().replace('\\','/'),"bytes",Files.size(path),"sha256",SupportAnalyticsSnapshot.fileHash(path));}
    static void writeNew(Path path,ObjectMapper json,Object value) throws Exception {try(var output=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)){json.writeValue(output,value);}}
}
