package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** No Spring boot, database, credentials or business commands in these adversarial probes. */
class SupportNetContributionCommittedGuardTest {
    private final ObjectMapper json=new ObjectMapper();
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path syntheticDirectory;
    private static final String UUID="01234567-89ab-cdef-0123-456789abcdef";
    private static final String HASH="a".repeat(64);
    private static final String DIRECTORY="D:/CodexData/test-environments/workflow-runs/support-minimal-20261012/mysql-data";

    @Test void retainedEvidenceIgnoresNumericEncodingButRejectsAnyValueOrStructureChange() throws Exception {
        var runtime=new SupportNetContributionSettlementRuntimeTest();runtime.json=json;
        var actual=new LinkedHashMap<String,Object>();
        actual.put("amount",new BigDecimal("35.000000"));actual.put("cid",new java.math.BigInteger("2195862002132273322"));
        actual.put("rows",List.of(new BigDecimal("0.00000001"),new BigDecimal("1000.00")));actual.put("status","SENT");actual.put("completed_at",null);
        var expected=json.readTree("{\"amount\":35,\"cid\":2195862002132273322,\"rows\":[0.00000001,1000],\"status\":\"SENT\",\"completed_at\":null}");
        var canonical=runtime.principalTree(actual);assertThat(canonical).isEqualTo(runtime.principalTree(expected));
        for(var change:List.of(Map.entry("amount",(Object)new BigDecimal("35.000001")),Map.entry("cid",(Object)new java.math.BigInteger("2195862002132273323")),
                Map.entry("rows",(Object)List.of(new BigDecimal("1000"),new BigDecimal("0.00000001"))),Map.entry("status",(Object)"CONFIRMED"),Map.entry("amount",(Object)"35"))) {
            var changed=new LinkedHashMap<>(actual);changed.put(change.getKey(),change.getValue());assertThat(runtime.principalTree(changed)).isNotEqualTo(canonical);
        }
        actual.remove("completed_at");assertThat(runtime.principalTree(actual)).isNotEqualTo(canonical);
        assertThat(expected.path("amount").isIntegralNumber()).isTrue();
    }

    @Test void historicalRefundRowsUseReceiptPrecisionAndStillRejectChangedMoneyAndTime() {
        var runtime=new SupportNetContributionRefundRuntimeTest();
        var builder=org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json();
        new ffdd.opsconsole.shared.config.DateTimeFormatConfig().nexionDateTimeJacksonCustomizer().customize(builder);runtime.json=builder.build();
        var actual=new LinkedHashMap<String,Object>(Map.of("id",23L,"amount",new BigDecimal("649.000000"),"created_at",java.sql.Timestamp.valueOf("2026-10-12 12:32:58.638154")));
        var expected=runtime.json.valueToTree(List.of(actual));
        runtime.assertHistoricalRows(List.of(actual),expected);
        actual.put("amount",new BigDecimal("650"));assertThatThrownBy(()->runtime.assertHistoricalRows(List.of(actual),expected)).isInstanceOf(AssertionError.class);
        actual.put("amount",new BigDecimal("649"));actual.put("created_at",java.sql.Timestamp.valueOf("2026-10-12 12:32:59.638154"));
        assertThatThrownBy(()->runtime.assertHistoricalRows(List.of(actual),expected)).isInstanceOf(AssertionError.class);
    }

    @Test void netTestDisablesOnlyTheUnrelatedDevelopmentPayoutFixture() {
        var boot=SupportNetContributionCommittedRuntimeTest.class.getAnnotation(org.springframework.boot.test.context.SpringBootTest.class);
        assertThat(boot.properties()).containsExactly("nexion.finance.development-payout-address.enabled=false");
    }

    @Test void actualPrincipalMustBeExactAppOnExactClone() {
        var identity=new LinkedHashMap<String,Object>(Map.of("databaseName","cs_analytics_20261007","port",33338,
            "serverUuid",UUID,"currentUser","support_net_r55_app@127.0.0.1","dataDirectory",DIRECTORY));
        SupportNetContributionCommittedRuntimeTest.validateAppIdentity(identity,UUID,DIRECTORY);
        for(var change:List.of(Map.entry("databaseName",(Object)"other"),Map.entry("port",(Object)33337),
                Map.entry("serverUuid",(Object)SupportNetContributionCommittedRuntimeTest.ORIGINAL_UUID),
                Map.entry("currentUser",(Object)"cs_analytics_runner@127.0.0.1"),Map.entry("currentUser",(Object)"root@localhost"),
                Map.entry("port",(Object)new BigDecimal("33338.5")),
                Map.entry("dataDirectory",(Object)(DIRECTORY+"-other")))) {
            var bad=new LinkedHashMap<>(identity);bad.put(change.getKey(),change.getValue());
            assertThatThrownBy(()->SupportNetContributionCommittedRuntimeTest.validateAppIdentity(bad,UUID,DIRECTORY)).isInstanceOf(IllegalStateException.class);
        }
        for(String field:identity.keySet()) {var bad=new LinkedHashMap<>(identity);bad.remove(field);
            assertThatThrownBy(()->SupportNetContributionCommittedRuntimeTest.validateAppIdentity(bad,UUID,DIRECTORY)).isInstanceOf(IllegalStateException.class);}
    }

    ObjectNode context() {
        var context=json.createObjectNode().put("purpose","BUSINESS_PHASE").put("businessAuthorized",true);
        context.putObject("identity").put("runId","run").put("snapshotHash",HASH);
        context.putObject("objectBefore").put("path","D:/owned/net/object-before.json").put("sha256",HASH);
        return context;
    }
    @Test void snapshotReaderRequiresExactCurrentIdentityAndCompleteGrantHash() throws Exception {
        var identity=new LinkedHashMap<String,Object>(Map.of("databaseName","cs_analytics_20261007","port",33338,
            "serverUuid",UUID,"currentUser","support_net_schema_reader@127.0.0.1","dataDirectory",DIRECTORY));
        var grants=List.of("GRANT SHOW_ROUTINE ON *.* TO `support_net_schema_reader`@`127.0.0.1`","GRANT SELECT ON `cs_analytics_20261007`.* TO `support_net_schema_reader`@`127.0.0.1`");
        String hash=SupportNetContributionCommittedRuntimeTest.sha(json.writeValueAsBytes(grants.stream().sorted().toList()));
        SupportNetContributionCommittedRuntimeTest.validateReaderIdentity(identity,UUID,DIRECTORY,grants,hash);
        SupportNetContributionCommittedRuntimeTest.validateReaderIdentity(identity,UUID,DIRECTORY,List.of(grants.get(1),grants.get(0)),hash);
        for(var change:List.of(Map.entry("currentUser",(Object)"root@localhost"),Map.entry("currentUser",(Object)"support_net_r55_app@127.0.0.1"),
            Map.entry("port",(Object)33337),Map.entry("dataDirectory",(Object)(DIRECTORY+"-other")),Map.entry("serverUuid",(Object)SupportNetContributionCommittedRuntimeTest.ORIGINAL_UUID))) {
            var bad=new LinkedHashMap<>(identity);bad.put(change.getKey(),change.getValue());
            assertThatThrownBy(()->SupportNetContributionCommittedRuntimeTest.validateReaderIdentity(bad,UUID,DIRECTORY,grants,hash)).isInstanceOf(IllegalStateException.class);
        }
        for(var badGrants:List.of(List.<String>of(),List.of(grants.get(0)),List.of(grants.get(0),grants.get(1)+" WITH GRANT OPTION")))
            assertThatThrownBy(()->SupportNetContributionCommittedRuntimeTest.validateReaderIdentity(identity,UUID,DIRECTORY,badGrants,hash)).isInstanceOf(IllegalStateException.class);
    }
    ObjectNode proof() {
        var proof=json.createObjectNode().put("workflowRunId","run").put("snapshotHash",HASH);
        var schema=proof.putObject("schemaValidation");
        for(String field:List.of("contractSha256","functionDefinitionSha256","appGrantsSha256","readerGrantsSha256"))schema.put(field,HASH);
        return proof;
    }
    @Test void onlyCurrentContextProofAndIndependentBeforeAreRequired() {
        SupportNetContributionCommittedRuntimeTest.validateNetLineage(context(),proof(),"run",HASH,true);
        var capture=context();capture.remove("objectBefore");
        SupportNetContributionCommittedRuntimeTest.validateNetLineage(capture,proof(),"run",HASH,false);
        for(String field:List.of("purpose","businessAuthorized","objectBefore")) {var bad=context();bad.remove(field);
            assertThatThrownBy(()->SupportNetContributionCommittedRuntimeTest.validateNetLineage(bad,proof(),"run",HASH,true)).isInstanceOf(IllegalStateException.class);}
        assertThatThrownBy(()->SupportNetContributionCommittedRuntimeTest.validateNetLineage(context(),proof(),"other",HASH,true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SupportNetContributionCommittedRuntimeTest.validateNetLineage(context(),proof(),"run","b".repeat(64),true)).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"contractSha256","functionDefinitionSha256","appGrantsSha256","readerGrantsSha256"})
    void currentFullSchemaValidationCannotBeMissingOrBoolean(String field) {
        for(Object value:Arrays.asList(null,true,"invalid")) {
            var bad=proof();var schema=(ObjectNode)bad.path("schemaValidation");
            if(value==null)schema.remove(field);else schema.set(field,json.valueToTree(value));
            assertThatThrownBy(()->SupportNetContributionCommittedRuntimeTest.validateNetLineage(context(),bad,"run",HASH,true)).isInstanceOf(IllegalStateException.class);
        }
    }

    static Map<String,Object> wallet() {
        var row=new LinkedHashMap<String,Object>();row.put("id",1L);row.put("user_id",2L);row.put("version",0L);row.put("fund_revision",0L);
        row.put("fund_incarnation",UUID);row.put("sandbox",0);row.put("is_deleted",0);
        for(String bucket:List.of("usdt_available","nex_available","pending_withdraw","cregis_risk_held"))row.put(bucket,BigDecimal.ZERO);
        return row;
    }
    @Test void committedCommandAssertionFailureIsPersistedAndRethrown() throws Exception {
        // Exercise the actual command helper; only resource fences and physical I/O are isolated.
        var evidence=syntheticDirectory.resolve("synthetic-intents.json");
        var committed=new java.util.concurrent.atomic.AtomicBoolean();
        var saves=new java.util.concurrent.atomic.AtomicInteger();
        var runtime=new SupportNetContributionCommittedRuntimeTest() {
            @Override void requireCustomer(long customer) {assertThat(customer).isEqualTo(7L);}
            @Override List<Map<String,Object>> physical(String sql,Object... args) {
                assertThat(committed).isTrue();
                lastReadConnectionId=17L; // Deliberately the transaction ID: actual post-commit assertion must fail.
                return List.of(Map.of("user_id",7L));
            }
            @Override void saveIntent() throws Exception {
                saves.incrementAndGet();
                java.nio.file.Files.write(evidence,json.writeValueAsBytes(committedCommands));
            }
        };
        runtime.json=json;
        runtime.jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.mockito.Mockito.when(runtime.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class)).thenReturn(17L);
        runtime.transactions=org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status=new org.springframework.transaction.support.SimpleTransactionStatus();
        org.mockito.Mockito.when(runtime.transactions.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(status);
        org.mockito.Mockito.doAnswer(call->{committed.set(true);return null;}).when(runtime.transactions).commit(status);
        Throwable failure;
        try(var fences=org.mockito.Mockito.mockStatic(SupportNetContributionCommittedRuntimeTest.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            fences.when(SupportNetContributionCommittedRuntimeTest::requireNetClone).thenReturn(json.createObjectNode());
            failure=catchThrowable(()->runtime.command(7L,"SYNTHETIC_COMMIT",Map.of("synthetic",true),()->"synthetic-result"));
        }
        assertThat(failure).isInstanceOf(AssertionError.class);
        assertThat(committed).isTrue();
        org.mockito.Mockito.verify(runtime.transactions).commit(status);
        org.mockito.Mockito.verify(runtime.transactions,org.mockito.Mockito.never()).rollback(org.mockito.ArgumentMatchers.any());
        assertThat(saves.get()).isEqualTo(2);
        var persisted=json.readTree(java.nio.file.Files.readAllBytes(evidence));
        assertThat(persisted.size()).isEqualTo(1);
        assertThat(persisted.get(0).path("event").asText()).isEqualTo("FAILED_OR_UNKNOWN_SYNTHETIC_COMMIT");
        assertThat(persisted.get(0).path("failureClass").asText()).isEqualTo(failure.getClass().getName());
        assertThat(persisted.get(0).path("result").asText()).isEqualTo("synthetic-result");
        assertThat(persisted.get(0).path("walletAfter").get(0).path("user_id").longValue()).isEqualTo(7L);
        assertThat(persisted.get(0).path("transactionConnectionId").longValue()).isEqualTo(17L);
        assertThat(persisted.get(0).path("readConnectionId").longValue()).isEqualTo(17L);
    }
    @Test void nativeBooleanFlagsPreserveTheSameZeroWalletBoundary() {
        var nativeFlags=new LinkedHashMap<>(wallet());
        nativeFlags.put("sandbox",false);nativeFlags.put("is_deleted",false);
        SupportNetContributionCommittedRuntimeTest.validateNewZeroWallet(nativeFlags,2);
        for(String field:List.of("sandbox","is_deleted"))for(Object value:new Object[]{true,"false","0",new BigDecimal("0.5"),null}) {
            var bad=new LinkedHashMap<>(nativeFlags);bad.put(field,value);
            assertThatThrownBy(()->SupportNetContributionCommittedRuntimeTest.validateNewZeroWallet(bad,2)).isInstanceOf(IllegalStateException.class);
        }
        nativeFlags.put("version",false);
        assertThatThrownBy(()->SupportNetContributionCommittedRuntimeTest.validateNewZeroWallet(nativeFlags,2)).isInstanceOf(IllegalStateException.class);
    }
    @Test void authenticatedBirthRejectsMissingZeroCashVersionAndWrongIdentity() {
        SupportNetContributionCommittedRuntimeTest.validateNewZeroWallet(wallet(),2);
        for(String field:wallet().keySet()) {var bad=new LinkedHashMap<>(wallet());bad.remove(field);
            assertThatThrownBy(()->SupportNetContributionCommittedRuntimeTest.validateNewZeroWallet(bad,2)).isInstanceOf(IllegalStateException.class);}
        for(String field:List.of("version","fund_revision","sandbox","is_deleted","usdt_available","nex_available","pending_withdraw","cregis_risk_held")) {
            var bad=new LinkedHashMap<>(wallet());bad.put(field,BigDecimal.ONE);
            assertThatThrownBy(()->SupportNetContributionCommittedRuntimeTest.validateNewZeroWallet(bad,2)).isInstanceOf(IllegalStateException.class);
        }
        var raw=new LinkedHashMap<>(wallet());raw.put("usdt_available","0");
        assertThatThrownBy(()->SupportNetContributionCommittedRuntimeTest.validateNewZeroWallet(raw,2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SupportNetContributionCommittedRuntimeTest.validateNewZeroWallet(wallet(),3)).isInstanceOf(IllegalStateException.class);
        var missingNonce=new LinkedHashMap<>(wallet());missingNonce.put("fund_incarnation","");
        assertThatThrownBy(()->SupportNetContributionCommittedRuntimeTest.validateNewZeroWallet(missingNonce,2)).isInstanceOf(IllegalStateException.class);
    }

    @Test void runtimeSourceCannotReplaceAuthCashSeamsOrRestoreFinancialFacts() throws Exception {
        var source=java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("cs.net.candidate.source",
            "src/test/java/ffdd/opsconsole/content/application/SupportNetContributionCommittedRuntimeTest.java")));
        assertActualBusinessSeams(source);
        for(String actual:List.of("objects.createCustomer(","accounts.insertNew(","accounts.ensureWallet(","accounts.complete(","outbox.publish(","topups.admit(","topups.settle(","checkout.createOrder(","orders.pay(","funds.readNetContributions(","connection.setReadOnly(true)"))
            assertThatThrownBy(()->assertActualBusinessSeams(source.replace(actual,"removedSeam("))).as(actual).isInstanceOf(AssertionError.class);
        for(String forbidden:List.of("INSERT INTO nx_user_wallet","INSERT INTO nx_user ","UPDATE nx_withdrawal_order SET status='CONFIRMED'","objects.cleanup(","fixture.cleanup(","prepareRefundReserve(","completeDevelopmentSimulation("))
            assertThatThrownBy(()->assertActualBusinessSeams(source+"\n"+forbidden)).as(forbidden).isInstanceOf(AssertionError.class);
    }
    static void assertActualBusinessSeams(String source) {
        assertThat(source).contains("objects.createCustomer(","accounts.insertNew(","accounts.ensureWallet(","accounts.complete(","outbox.publish(",
            "topups.admit(","topups.settle(","checkout.createOrder(","orders.pay(","funds.readNetContributions(","connection.setReadOnly(true)",
            "NET_COMMITTED","netRechargeHardwareSpendReplayThenSecondRechargeIsDeduplicated","netRechargeCloudSharePaidConsumptionIsDeduplicated",
            "@Override @AfterEach","financialRecordsRetained","NOT_COMPLETE");
        assertThat(source).doesNotContain("INSERT INTO nx_user_wallet","INSERT INTO nx_user ","UPDATE nx_withdrawal_order SET status='CONFIRMED'",
            "objects.cleanup(","fixture.cleanup(","prepareRefundReserve(","completeDevelopmentSimulation(","wallet(customer,");
        var birth=source.substring(source.indexOf("long bornCustomer()"),source.indexOf("Map<String,Object> product("));
        assertThat(birth.indexOf("saveIntent();")).isLessThan(birth.indexOf("objects.createCustomer("));
        assertThat(birth.indexOf("accounts.insertNew(")).isLessThan(birth.indexOf("accounts.ensureWallet("));
        assertThat(birth.indexOf("outbox.publish(")).isLessThan(birth.indexOf("accounts.complete("));
    }
}
