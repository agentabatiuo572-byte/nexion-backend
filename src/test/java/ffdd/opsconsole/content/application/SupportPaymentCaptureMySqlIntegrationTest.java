package ffdd.opsconsole.content.application;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade.Prepared;
import ffdd.opsconsole.content.mapper.SupportPaymentAttributionMapper;
import ffdd.opsconsole.content.mapper.SupportPaymentHistoryBirthMapper;
import ffdd.opsconsole.finance.application.SupportPaymentFactService;
import ffdd.opsconsole.finance.application.SupportPaymentSourceService;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import ffdd.opsconsole.finance.mapper.E4OrderRefundMapper;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Opt-in auxiliary acceptance against the exclusively owned analytics MySQL instance.
 * This starts no application and uses production SQL and real Spring transaction proxies.
 * Financial writes below affect fresh fixture accounts only. Audit is a failure/count mock:
 * this test makes no assertion about a durable production audit record or workflow approval.
 */
@EnabledIfEnvironmentVariable(named="SUPPORT_CAPTURE_MYSQL_ENABLED", matches="true")
class SupportPaymentCaptureMySqlIntegrationTest {
    private static final String SERVER_UUID="3556ddae-c1a1-11f1-8853-a40c6626953d";
    private static final List<String> TABLES=List.of("nx_user","nx_user_wallet","nx_wallet_ledger",
        "nx_payment_record","nx_order","nx_wallet_bill","nx_deposit_order","nx_cregis_deposit_event",
        "nx_topup_card_settlement","nx_vietqr_intent","nx_vietqr_reconciliation","nx_hdpay_payin_order",
        "nx_user_device","nx_trial_claim","nx_support_payment_attribution","nx_support_payment_history_birth");
    private final ObjectMapper json=new ObjectMapper();
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcTemplate outside;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transaction;
    private FinanceSupportPaymentFactsFacade finance;
    private SupportPaymentAttributionFacade capture;
    private SupportPaymentHistoryBirthService birth;
    private AuditLogService audit;
    private SupportPaymentSourceMapper sourceMapper;
    private E4OrderRefundMapper refundMapper;

    @BeforeEach
    void exclusivelyOwnedDatabaseAndRealTransactionProxies() throws Exception {
        var target=SupportRuntimeTarget.select(Map.of("SUPPORT_RUNTIME_TARGET","analytics-20261007"));
        String url=requiredEnvironment("NEXION_DB_URL"), username=requiredEnvironment("NEXION_DB_USERNAME");
        assertThat(url).startsWith(target.jdbcPrefix());
        assertThat(username).isEqualTo(target.username());
        dataSource=new DriverManagerDataSource(url,username,requiredEnvironment("NEXION_DB_PASSWORD"));
        jdbc=new JdbcTemplate(dataSource);
        // A distinct DataSource object ensures even an accidental in-transaction readback opens a new connection.
        outside=new JdbcTemplate(new DriverManagerDataSource(url,username,requiredEnvironment("NEXION_DB_PASSWORD")));
        Path proofPath=Path.of(requiredEnvironment("SUPPORT_CAPTURE_OWNERSHIP")).toAbsolutePath().normalize();
        byte[] proofBytes=Files.readAllBytes(proofPath);
        JsonNode proof=json.readTree(proofBytes);
        assertThat(proof.path("databaseIdentity").path("serverUuid").asText()).isEqualTo(SERVER_UUID);
        ObjectNode context=json.createObjectNode();
        context.put("schemaVersion",2).put("ownershipMode","EXCLUSIVE_ANALYTICS");
        context.set("resourceIdentity",proof.path("resourceIdentity").deepCopy());
        context.putObject("resourceOwnership").put("path",proofPath.toString())
            .put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(proofBytes)));
        // No run/step/snapshot is synthesized. The existing verifier compares server/account/grants/datadir.
        SupportExclusiveRuntimeOwnership.requireActual(context,target,jdbc);
        assertThat(jdbc.queryForObject("SELECT @@server_uuid",String.class)).isEqualTo(SERVER_UUID);
        applyStructureOnlyTwice();

        Configuration configuration=new Configuration(new Environment("support-capture-actual-mysql",
            new SpringManagedTransactionFactory(),dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SupportPaymentSourceMapper.class);
        configuration.addMapper(E4OrderRefundMapper.class);
        configuration.addMapper(SupportPaymentAttributionMapper.class);
        configuration.addMapper(SupportPaymentHistoryBirthMapper.class);
        var factory=new MybatisSqlSessionFactoryBuilder().build(configuration);
        var template=new SqlSessionTemplate(factory);
        assertThat(factory.getConfiguration().getEnvironment().getDataSource()).isSameAs(dataSource);
        manager=new DataSourceTransactionManager(dataSource);
        assertThat(manager.getDataSource()).isSameAs(dataSource);
        transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(30);
        audit=mock(AuditLogService.class);
        sourceMapper=template.getMapper(SupportPaymentSourceMapper.class);
        refundMapper=template.getMapper(E4OrderRefundMapper.class);
        finance=proxy(new SupportPaymentSourceService(sourceMapper,mock(SupportPaymentFactService.class),dataSource,json));
        capture=proxy(new SupportPaymentAttributionService(template.getMapper(SupportPaymentAttributionMapper.class),
            finance,audit,dataSource,json));
        birth=proxy(new SupportPaymentHistoryBirthService(template.getMapper(SupportPaymentHistoryBirthMapper.class),dataSource));
    }

    @ParameterizedTest
    @EnumSource(value=Source.class,names={"DEPOSIT_ORDER","CARD_TOPUP","VIETQR","HDPAY","WALLET_ORDER",
        "TRADE_IN","CAPACITY_KEEP","TRIAL_CONVERT","ORDER_REFUND"})
    void nineSourcesCaptureNewSuccessThenFreshOldPreparationReplaysWithoutChangingAnyRowBytes(Source source) {
        rollbackFixtures(accounts -> {
            Fixture fixture=pending(source,newAccount(accounts,0));
            int auditBefore=auditCalls();
            Object resource=physicalResource();
            assertThat(sourceMapper.settled(source,List.of(fixture.customer),fixture.key)).isEmpty();
            Prepared prepared=prepare(fixture);
            settle(fixture,prepared,true);
            capture.record(prepared);
            assertThat(physicalResource()).isSameAs(resource);
            Map<String,String> original=evidence(fixture);
            assertThat(original.get("capture_mode")).isEqualTo("NEW_SUCCESS");
            assertThat(original.get("source")).isEqualTo(source.name());
            JsonNode witness=tree(original.get("attribution_evidence_json")).path("beforeSource");
            assertThat(witness.path("oldSource").booleanValue()).isFalse();
            assertThat(witness.path("stableBusinessKey").asText()).isEqualTo(fixture.canonicalKey());
            assertThat(original.get("fractional_second_digits")).isEqualTo(orderSource(source)?"6":"0");
            if(source==Source.VIETQR) {
                assertThat(jdbc.queryForObject("SELECT intent_no FROM nx_vietqr_reconciliation WHERE reconciliation_no=?",
                    String.class,fixture.receipt)).isEqualTo(fixture.partition);
            }
            if(source==Source.CARD_TOPUP) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_topup_card_admission WHERE user_id=?",Long.class,
                    fixture.customer)).isZero(); // Actual CARD_SCOPE ledger fallback, without an admission fixture.
            }
            if(source==Source.ORDER_REFUND) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_bill WHERE user_id=? AND bill_no=? AND type='ORDER_REFUND' AND token='USDT' AND direction='IN' AND amount=10 AND deleted=0",
                    Long.class,fixture.customer,"E4-BILL-"+fixture.order)).isEqualTo(1);
            }
            Prepared old=prepare(fixture); // A fresh opaque token must now observe the successful source as OLD.
            capture.record(old);
            assertThat(evidence(fixture)).isEqualTo(original);
            assertThat(auditCalls()-auditBefore).isEqualTo(1);
        });
    }

    @Test
    void providerConfirmedCardStillRequiresActualSettledReceipt() {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.CARD_TOPUP,newAccount(accounts,0));
            Prepared prepared=prepare(f);
            settle(f,prepared,true);
            jdbc.update("UPDATE nx_topup_card_settlement SET status='PROCESSING' WHERE payment_no=?",f.key);
            assertThatThrownBy(() -> capture.record(prepared)).hasMessage("MISSING_CARD_SETTLEMENT");
            assertThat(countForCustomer("nx_support_payment_attribution",f.customer)).isZero();
            verifyNoInteractions(audit);
        });
    }

    @Test
    void separateSqlNowStatementsCrossSecondsAndRealZeroPrecisionRoundingPreserveTheRawFinancialTimes() {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true);
            assertThat(jdbc.queryForObject("SELECT datetime_precision FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_wallet_ledger' AND column_name='created_at'",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT datetime_precision FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_order' AND column_name='paid_at'",Integer.class)).isEqualTo(6);
            LocalDateTime highPrecision=f.base.withNano(900_000_000);
            String probeNo="PROBE-"+f.name;
            jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,created_at) VALUES(?,?,'ORDER_PURCHASE','USDT','OUT',1,0,'SUCCESS',?)",
                f.customer,probeNo,highPrecision);
            LocalDateTime rounded=databaseLocalDateTime("SELECT created_at FROM nx_wallet_ledger WHERE biz_no=? AND asset='USDT' AND direction='OUT'",probeNo);
            // Read the actual mode; neither truncation nor rounding is guessed from Java's withNano.
            String sqlMode=jdbc.queryForObject("SELECT @@session.sql_mode",String.class);
            assertThat(rounded).isEqualTo(sqlMode.contains("TIME_TRUNCATE_FRACTIONAL")?f.base:f.base.plusSeconds(1));
            LocalDateTime ledgerTime=databaseLocalDateTime("SELECT created_at FROM nx_wallet_ledger WHERE id=?",f.ledger);
            assertThat(jdbc.queryForObject("SELECT SLEEP(1.1)",Integer.class)).isZero();
            jdbc.update("UPDATE nx_order SET paid_at=NOW(6) WHERE order_no=?",f.order);
            jdbc.update("UPDATE nx_payment_record SET paid_at=NOW(6) WHERE payment_no=?",f.payment);
            LocalDateTime paidTime=databaseLocalDateTime("SELECT paid_at FROM nx_order WHERE order_no=?",f.order);
            LocalDateTime confirmationTime=databaseLocalDateTime("SELECT paid_at FROM nx_payment_record WHERE payment_no=?",f.payment);
            assertThat(paidTime.withNano(0)).isAfter(ledgerTime);
            capture.record(prepared);
            JsonNode financial=tree(evidence(f).get("source_fact_json"));
            var iso=java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");
            assertThat(financial.path("ledgerRecordedAt").asText()).isEqualTo(iso.format(ledgerTime));
            assertThat(financial.path("succeededAt").asText()).isEqualTo(iso.format(paidTime));
            assertThat(financial.path("sourceConfirmationAt").asText()).isEqualTo(iso.format(confirmationTime));
        });
    }

    @Test
    void creditedCregisEventUnderHeldWalletCannotBecomeNewAfterAStatusReset() {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.DEPOSIT_ORDER,newAccount(accounts,0));
            settle(f,prepare(f),false);
            // A legacy held balance plus reset event status still carries durable old credit markers.
            jdbc.update("UPDATE nx_user_wallet SET cregis_risk_held=10 WHERE user_id=?",f.customer);
            jdbc.update("UPDATE nx_cregis_deposit_event SET status='RISK_HOLD' WHERE project_id=? AND cid=?",
                Long.parseLong(f.partition),f.cid);
            var before=finance.beforeSource(f.customer,f.source,f.key,f.partition);
            assertThat(before.oldSource()).isTrue();
            assertThat(before.existingLedgerId()).isEqualTo(f.ledger);
            jdbc.update("UPDATE nx_cregis_deposit_event SET status='CREDITED' WHERE project_id=? AND cid=?",
                Long.parseLong(f.partition),f.cid);
            capture.record(prepare(f));
            assertOldUnknownWithoutPresentWitnesses(evidence(f));
        });
    }

    @Test
    void existingOldSuccessGetsOnlyUnknownOwnershipAndNoCurrentAdminOrGroupWitness() {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            settle(f,prepare(f),false);
            var before=finance.beforeSource(f.customer,f.source,f.key);
            assertThat(before.oldSource()).isTrue();
            assertThat(before.existingFactId()).isEqualTo("PURCHASE:"+f.order);
            capture.record(prepare(f));
            assertOldUnknownWithoutPresentWitnesses(evidence(f));
            verify(audit,times(1)).recordRequired(any(AuditLogWriteRequest.class));
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"MISSING","OLD","AMOUNT","CUSTOMER","TIME","PARTITION"})
    void crossSecondReplayRejectsMissingOldOrFinanciallyMismatchedPersistedProof(String corruption) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared first=prepare(f);settle(f,first,true);capture.record(first);
            corruptOwnProof(f,corruption);
            clearInvocations(audit);
            assertThatThrownBy(() -> capture.record(prepare(f))).hasMessage("CONFLICTING_SUCCESS_TIME");
            verifyNoInteractions(audit);
        });
    }

    @Test
    void capturedCregisProofCannotReplayAcrossTheTrustedProjectPartition() {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.DEPOSIT_ORDER,newAccount(accounts,0));
            Prepared first=prepare(f);settle(f,first,true);capture.record(first);
            Map<String,String> original=evidence(f);
            String otherProject=Long.toString(Long.parseLong(f.partition)+1);
            clearInvocations(audit);
            assertThatThrownBy(() -> capture.record(capture.prepare(f.customer,f.source,f.key,otherProject)))
                .hasMessage("CONFLICTING_SUCCESS_TIME");
            assertThat(evidence(f)).isEqualTo(original);
            verifyNoInteractions(audit);
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"SOURCE","CUSTOMER","VIETQR_INTENT"})
    void realCurrentReadsRejectSourceCustomerOrCanonicalReceiptBindingChangedAfterPreparation(String violation) {
        rollbackFixtures(accounts -> {
            Fixture f=pending("VIETQR_INTENT".equals(violation)?Source.VIETQR:Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true);
            String reason;
            if("SOURCE".equals(violation)) {
                jdbc.update("UPDATE nx_order SET order_type='TRADE_IN' WHERE order_no=?",f.order);
                reason="MISSING_SETTLED_SOURCE"; // The canonical root filter rejects the changed source type first.
            } else if("CUSTOMER".equals(violation)) {
                long other=newAccount(accounts,0);
                jdbc.update("UPDATE nx_order SET user_id=? WHERE order_no=?",other,f.order);
                reason="SOURCE_CUSTOMER_MISMATCH";
            } else {
                jdbc.update("UPDATE nx_vietqr_reconciliation SET intent_no=? WHERE reconciliation_no=?","INT-"+unique(),f.receipt);
                reason="SOURCE_INTENT_CHANGED";
            }
            assertThatThrownBy(() -> capture.record(prepared)).hasMessage(reason);
            assertThat(countForCustomer("nx_support_payment_attribution",f.customer)).isZero();
            verifyNoInteractions(audit);
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"MISSING","OLD","AMOUNT","CUSTOMER","TIME"})
    void laterRefundCannotBorrowUntrustedOriginalCrossSecondPurchaseProof(String corruption) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.ORDER_REFUND,newAccount(accounts,0));
            Prepared refund=prepare(f);
            corruptOwnProof(f.original,corruption);
            settle(f,refund,true);
            clearInvocations(audit);
            assertThatThrownBy(() -> capture.record(refund)).hasMessage("CONFLICTING_SUCCESS_TIME");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_payment_attribution WHERE source='ORDER_REFUND' AND customer_id=?",
                Long.class,f.customer)).isZero();
            verifyNoInteractions(audit);
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"CHRONOLOGY","SUM","CUSTOMER"})
    void validOriginalProofNeverRelaxesRefundChronologySumOrCustomer(String violation) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.ORDER_REFUND,newAccount(accounts,0));
            Prepared refund=prepare(f);settle(f,refund,true);
            String reason;
            if("CHRONOLOGY".equals(violation)) {
                jdbc.update("UPDATE nx_wallet_ledger SET created_at=? WHERE id=?",f.original.base.minusSeconds(1),f.ledger);
                reason="FRESH_LEDGER_WRITE_MISMATCH";
            } else if("SUM".equals(violation)) {
                jdbc.update("UPDATE nx_wallet_ledger SET amount=11 WHERE id=?",f.ledger);
                reason="FRESH_LEDGER_WRITE_MISMATCH";
            } else {
                long another=newAccount(accounts,0);
                jdbc.update("UPDATE nx_wallet_ledger SET user_id=? WHERE id=?",another,f.ledger);
                // A successful receipt cannot be rebound to another customer.
                reason="FRESH_LEDGER_WRITE_MISMATCH";
            }
            clearInvocations(audit);
            assertThatThrownBy(() -> capture.record(refund)).hasMessage(reason);
            verifyNoInteractions(audit);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void actualSameSecondMicrosecondPurchaseAndSecondRefundPreserveChronologyAtSourcePrecision(boolean previousWholeSecond) {
        rollbackFixtures(accounts -> {
            Fixture original=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared purchasePrepared=prepare(original);
            // This is an explicit clock-window experiment, solely in this rollback fixture.
            // Align to the start of the NEXT actual database second; neither ledger tuple is edited.
            assertThat(jdbc.queryForObject("SELECT SLEEP((1000000-MICROSECOND(NOW(6))+20000)/1000000.0)",Integer.class)).isZero();
            settle(original,purchasePrepared,true,false);
            capture.record(purchasePrepared);
            LocalDateTime purchaseAt=databaseLocalDateTime("SELECT paid_at FROM nx_order WHERE order_no=?",original.order);
            assertThat(purchaseAt.getNano()).as("Actual DATETIME(6) purchase retains its fractional second").isPositive();
            Map<String,String> purchaseEvidence=evidence(original);
            assertThat(purchaseEvidence.get("fractional_second_digits")).isEqualTo("6");
            Fixture refund=new Fixture(Source.ORDER_REFUND,original.customer,unique(),original.base);
            refund.original=original;refund.order=original.order;refund.key="E4-REFUND-"+original.order;
            if(previousWholeSecond) {
                // Deliberately malformed LEGACY refund: no new receipt is claimed or manufactured.
                // Its own valid old projection reaches checkRefund's whole-second chronology gate.
                LocalDateTime earlier=purchaseAt.withNano(0).minusSeconds(1);
                jdbc.update("UPDATE nx_user_wallet SET usdt_available=usdt_available+10 WHERE user_id=?",refund.customer);
                jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,created_at,updated_at) VALUES(?,?,'ORDER_REFUND','USDT','IN',10,500,'SUCCESS',?,?)",
                    refund.customer,refund.key,earlier,earlier);
                refund.ledger=jdbc.queryForObject("SELECT id FROM nx_wallet_ledger WHERE biz_no=? AND user_id=? AND asset='USDT' AND direction='IN'",Long.class,refund.key,refund.customer);
                assertThat(databaseLocalDateTime("SELECT created_at FROM nx_wallet_ledger WHERE id=?",refund.ledger)).isEqualTo(earlier);
                assertThat(refundMapper.insertBill(refund.customer,"E4-BILL-"+refund.order,new BigDecimal("10.000000"))).isEqualTo(1);
                jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",refund.order);
                jdbc.update("UPDATE nx_payment_record SET payment_status='REFUNDED' WHERE order_no=?",refund.order);
                assertThat(finance.beforeSource(refund.customer,refund.source,refund.key).oldSource()).isTrue();
                clearInvocations(audit);
                Prepared oldRefund=prepare(refund);
                assertThatThrownBy(() -> capture.record(oldRefund)).hasMessage("REFUND_PREDATES_ORIGINAL_PAYMENT");
                assertThat(countForCustomer("nx_support_payment_attribution",refund.customer)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_payment_attribution WHERE customer_id=? AND fact_id=?",Long.class,refund.customer,refund.factId())).isZero();
                assertThat(evidence(original)).isEqualTo(purchaseEvidence);
                verifyNoInteractions(audit);
            } else {
                Prepared refundPrepared=prepare(refund);
                settle(refund,refundPrepared,true,false); // Actual INSERT and source NOW, deliberately no SLEEP.
                LocalDateTime refundAt=databaseLocalDateTime("SELECT created_at FROM nx_wallet_ledger WHERE id=?",refund.ledger);
                assertThat(refundAt.getNano()).isZero();
                assertThat(refundAt).as("If the real execution crosses seconds, this experiment must fail rather than pretend it covered the boundary")
                    .isEqualTo(purchaseAt.withNano(0));
                assertThat(refundAt).as("Zero precision loses the already-paid microseconds within this same real second").isBefore(purchaseAt);
                capture.record(refundPrepared);
                Map<String,String> refundEvidence=evidence(refund);
                assertThat(refundEvidence.get("capture_mode")).isEqualTo("NEW_SUCCESS");
                assertThat(refundEvidence.get("fractional_second_digits")).isEqualTo("0");
                assertThat(evidence(original)).isEqualTo(purchaseEvidence);
                capture.record(prepare(refund));
                assertThat(evidence(refund)).isEqualTo(refundEvidence);
                verify(audit,times(2)).recordRequired(any(AuditLogWriteRequest.class));
            }
        });
    }

    @Test
    void sandboxAndZeroActualPurchaseAreExcludedWithoutAttributionOrAudit() {
        rollbackFixtures(accounts -> {
            Fixture sandbox=pending(Source.WALLET_ORDER,newAccount(accounts,1));
            Prepared sandboxPrepared=prepare(sandbox);settle(sandbox,sandboxPrepared,true);capture.record(sandboxPrepared);
            Fixture zero=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            jdbc.update("UPDATE nx_order SET amount_usdt=0 WHERE order_no=?",zero.order);
            Prepared zeroPrepared=prepare(zero);
            jdbc.update("UPDATE nx_order SET payment_status='PAID',order_status='PAID',paid_at=NOW(6) WHERE order_no=?",zero.order);
            capture.record(zeroPrepared);
            assertThat(countForCustomer("nx_support_payment_attribution",sandbox.customer)).isZero();
            assertThat(countForCustomer("nx_support_payment_attribution",zero.customer)).isZero();
            verifyNoInteractions(audit);
        });
    }

    @Test
    void requiredAuditFailureRollsBackNewAccountBirthWalletSourceLedgerAndAttributionTogether() {
        Map<String,Long> before=outsideCounts();
        List<Long> accounts=new ArrayList<>();
        doThrow(new IllegalStateException("capture-audit-failure")).when(audit).recordRequired(any(AuditLogWriteRequest.class));
        try {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                long customer=newAccount(accounts,0);birth.registerNewAccount(customer);
                Fixture f=pending(Source.WALLET_ORDER,customer);
                Prepared prepared=prepare(f);settle(f,prepared,true);
                assertThat(countForCustomer("nx_support_payment_history_birth",customer)).isEqualTo(1);
                assertThat(countForCustomer("nx_wallet_ledger",customer)).isEqualTo(1);
                capture.record(prepared);
            })).hasMessage("capture-audit-failure");
        } finally {
            assertRollbackReadback(before,accounts);
        }
        verify(audit,times(1)).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void actualRequiresNewRejectsOuterPreparedButOuterResumeSucceedsAndCompletionReuseFails() {
        var completed=new AtomicReference<Prepared>();
        var completedBefore=new AtomicReference<FinanceSupportPaymentFactsFacade.BeforeSource>();
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared outer=prepare(f);
            var sourceBefore=finance.beforeSource(f.customer,f.source,f.key);
            Object outerResource=physicalResource();
            TransactionTemplate inner=new TransactionTemplate(manager);
            inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            inner.setTimeout(10);
            inner.executeWithoutResult(status -> {
                try {
                    assertThat(physicalResource()).isNotSameAs(outerResource);
                    assertThatThrownBy(() -> capture.record(outer)).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
                    assertThatThrownBy(() -> finance.readSettled(sourceBefore)).hasMessage("INVALID_BEFORE_SOURCE_TRANSACTION");
                } finally { status.setRollbackOnly(); }
            });
            assertThat(physicalResource()).isSameAs(outerResource);
            settle(f,outer,true);capture.record(outer);
            assertThat(evidence(f).get("capture_mode")).isEqualTo("NEW_SUCCESS");
            completed.set(outer);completedBefore.set(sourceBefore);
        });
        transaction.executeWithoutResult(status -> {
            try {
                assertThatThrownBy(() -> capture.record(completed.get())).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
                assertThatThrownBy(() -> finance.readSettled(completedBefore.get())).hasMessage("INVALID_BEFORE_SOURCE_TRANSACTION");
            } finally { status.setRollbackOnly(); }
        });
    }

    @Test
    void committedBirthAndNewCaptureSurviveIndependentReadAndFreshTransactionOldReplayIsByteIdentical() {
        Map<String,Long> before=outsideCounts();
        List<Long> accounts=new ArrayList<>();
        AtomicReference<Fixture> fixture=new AtomicReference<>();
        try {
            transaction.executeWithoutResult(status -> {
                long customer=newAccount(accounts,0);birth.registerNewAccount(customer);
                Fixture f=pending(Source.WALLET_ORDER,customer);fixture.set(f);
                Prepared prepared=prepare(f);settle(f,prepared,true);capture.record(prepared);
            });
            Fixture f=fixture.get();
            Map<String,String> persisted=outsideEvidence(f);
            Map<String,String> originalBirth=outside.queryForObject("SELECT * FROM nx_support_payment_history_birth WHERE customer_id=?",
                (rs,n)->strings(rs),f.customer);
            assertThat(persisted.get("capture_mode")).isEqualTo("NEW_SUCCESS");
            assertThat(originalBirth.get("environment_status")).isEqualTo("PRODUCTION");
            assertThat(originalBirth.get("sandbox_at_birth")).isEqualTo("0");
            transaction.executeWithoutResult(status -> {
                capture.record(prepare(f));
                jdbc.update("UPDATE nx_user SET sandbox=1 WHERE id=?",f.customer);
                birth.registerNewAccount(f.customer);
            });
            assertThat(outsideEvidence(f)).isEqualTo(persisted);
            Map<String,String> replayedBirth=outside.queryForObject("SELECT * FROM nx_support_payment_history_birth WHERE customer_id=?",
                (rs,n)->strings(rs),f.customer);
            assertThat(replayedBirth).isEqualTo(originalBirth);
            verify(audit,times(1)).recordRequired(any(AuditLogWriteRequest.class));
        } finally {
            // The exact committed fresh fixture IDs are removed in dependency order.
            cleanupOwnAccounts(accounts);
            assertRollbackReadback(before,accounts);
        }
    }

    @ParameterizedTest
    @CsvSource({"WALLET_ORDER,WALLET_ORDER","VIETQR,HDPAY","CARD_TOPUP,VIETQR","HDPAY,CARD_TOPUP"})
    void twoDifferentCustomersCommitCompleteCapturesAfterConcurrentPreparationWithoutMissingLedgerGapDeadlock(
            Source firstSource,Source secondSource) throws Exception {
        Map<String,Long> before=outsideCounts();
        List<Long> accounts=new ArrayList<>();
        List<Fixture> fixtures=new ArrayList<>();
        ExecutorService workers=Executors.newFixedThreadPool(2);
        Throwable primaryFailure=null;
        try {
            // Commit only fresh accounts/wallets/pending source roots, before the money transactions start.
            // Neither namespace has a payment or ledger. No extra fence is installed to hide missing-key gaps.
            transaction.executeWithoutResult(status -> {
                for(int i=0;i<2;i++) {
                    long customer=newAccount(accounts,0);
                    birth.registerNewAccount(customer);
                    fixtures.add(pending(i==0?firstSource:secondSource,customer));
                }
            });
            assertThat(fixtures.get(0).customer).isNotEqualTo(fixtures.get(1).customer);
            assertThat(fixtures.get(0).key).isNotEqualTo(fixtures.get(1).key);
            CyclicBarrier bothPrepared=new CyclicBarrier(2);
            List<Future<Map<String,String>>> writes=new ArrayList<>();
            for(Fixture f:fixtures) {
                writes.add(workers.submit(() -> transaction.execute(status -> {
                    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    Object resource=physicalResource();
                    Prepared prepared=prepare(f);
                    awaitBothPrepared(bothPrepared);
                    // Production beforeSource SELECTs have already read both absent ledger keys under RR.
                    // Keep the full wallet/source/ledger write path and let a real deadlock fail this test.
                    // There is deliberately no retry, serialized write fence, or DuplicateKey-only surrogate.
                    settle(f,prepared,true);
                    capture.record(prepared);
                    assertThat(physicalResource()).isSameAs(resource);
                    return evidence(f);
                })));
            }
            workers.shutdown();
            List<Map<String,String>> committedRows=new ArrayList<>();
            for(Future<Map<String,String>> write:writes) committedRows.add(write.get(45,TimeUnit.SECONDS));
            for(int i=0;i<fixtures.size();i++) {
                Fixture f=fixtures.get(i);
                Map<String,String> committed=committedRows.get(i);
                // These reads use the distinct, unbound DataSource: success requires a real commit.
                assertThat(outsideEvidence(f)).isEqualTo(committed);
                assertThat(committed.get("capture_mode")).isEqualTo("NEW_SUCCESS");
                assertThat(committed.get("customer_id")).isEqualTo(Long.toString(f.customer));
                assertThat(committed.get("source_business_id")).isEqualTo(f.key);
                assertThat(committed.get("ledger_id")).isEqualTo(Long.toString(f.ledger));
                BigDecimal available=outside.queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",
                    BigDecimal.class,f.customer);
                assertThat(available).isEqualByComparingTo(orderSource(f.source)?"490":"510");
                String type=switch(f.source) {
                    case WALLET_ORDER->"ORDER_PURCHASE";case CARD_TOPUP->"CARD_TOPUP";
                    case VIETQR,HDPAY->"VIETQR_DEPOSIT";default->throw new AssertionError(f.source);
                };
                BigDecimal amount=outside.queryForObject("SELECT amount FROM nx_wallet_ledger WHERE id=? AND user_id=? AND biz_no=? AND direction=? AND asset='USDT' AND biz_type=? AND status='SUCCESS'",
                    BigDecimal.class,f.ledger,f.customer,f.canonicalKey(),orderSource(f.source)?"OUT":"IN",type);
                assertThat(amount).isEqualByComparingTo("10");
                assertCommittedSource(f);
                assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_payment_attribution WHERE customer_id=?",
                    Long.class,f.customer)).isEqualTo(1);
            }
            transaction.executeWithoutResult(status -> {
                for(Fixture f:fixtures) capture.record(prepare(f));
            });
            for(int i=0;i<fixtures.size();i++) assertThat(outsideEvidence(fixtures.get(i))).isEqualTo(committedRows.get(i));
            verify(audit,times(2)).recordRequired(any(AuditLogWriteRequest.class));
        } catch(Exception | Error failure) {
            primaryFailure=failure;
            throw failure;
        } finally {
            // A deadlock victim may roll back while the other payment commits. Wait for BOTH before cleanup.
            // Cleanup must never race a still-running money transaction or touch another account's rows.
            try {
                awaitCaptureWorkers(workers);
                cleanupOwnSourceFixtures(fixtures);
                cleanupOwnAccounts(accounts);
                assertRollbackReadback(before,accounts);
            } catch(Exception | Error cleanupFailure) {
                // Keep the actual transaction/deadlock failure as the primary evidence.
                // If workers cannot terminate, awaitCaptureWorkers throws before any fixture deletion.
                if(primaryFailure==null)throw cleanupFailure;
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void awaitBothPrepared(CyclicBarrier barrier) {
        try { barrier.await(15,TimeUnit.SECONDS); }
        catch(InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent capture preparation interrupted",failure);
        } catch(BrokenBarrierException | TimeoutException failure) {
            throw new IllegalStateException("Both money transactions must prepare before either writes",failure);
        }
    }

    private void assertCommittedSource(Fixture f) {
        long rows=switch(f.source) {
            case WALLET_ORDER -> outside.queryForObject("SELECT COUNT(*) FROM nx_order o JOIN nx_payment_record p ON p.payment_no=o.payment_no AND p.order_no=o.order_no AND p.user_id=o.user_id WHERE o.user_id=? AND o.order_no=? AND o.payment_status='PAID' AND p.payment_status='CONFIRMED' AND p.wallet_ledger_id=? AND o.amount_usdt=10 AND p.amount_usdt=10",
                Long.class,f.customer,f.order,f.ledger);
            case CARD_TOPUP -> outside.queryForObject("SELECT COUNT(*) FROM nx_topup_card_settlement s JOIN nx_payment_record p ON p.payment_no=s.payment_no AND p.user_id=s.user_id WHERE s.settlement_event_id=? AND s.user_id=? AND s.payment_no=? AND s.status='SETTLED' AND p.payment_status='CONFIRMED' AND p.wallet_ledger_id=? AND p.amount_usdt=10",
                Long.class,f.name,f.customer,f.key,f.ledger);
            case VIETQR -> outside.queryForObject("SELECT COUNT(*) FROM nx_vietqr_reconciliation r JOIN nx_vietqr_intent i ON i.intent_no=r.intent_no AND i.user_id=r.user_id WHERE r.reconciliation_no=? AND r.user_id=? AND r.intent_no=? AND r.status='CREDITED' AND r.view_type='MATCHED' AND r.credited_usdt=10 AND i.status='CREDITED' AND i.credited_usdt=10 AND i.payment_rail='MANUAL' AND i.settlement_target_type='WALLET_TOPUP'",
                Long.class,f.receipt,f.customer,f.partition);
            case HDPAY -> outside.queryForObject("SELECT COUNT(*) FROM nx_hdpay_payin_order h JOIN nx_vietqr_intent i ON i.intent_no=h.merchant_order_id WHERE h.merchant_order_id=? AND i.user_id=? AND h.settlement_status='CREDITED' AND h.settled_usdt=10 AND h.wallet_ledger_biz_no=? AND i.status='CREDITED' AND i.credited_usdt=10 AND i.payment_rail='HDPAY' AND i.settlement_target_type='WALLET_TOPUP'",
                Long.class,f.key,f.customer,f.key);
            default -> throw new AssertionError(f.source);
        };
        assertThat(rows).isEqualTo(1);
    }

    private void cleanupOwnSourceFixtures(List<Fixture> fixtures) {
        transaction.executeWithoutResult(status -> {
            for(Fixture f:fixtures) {
                assertThat(f.name).startsWith("SC");
                switch(f.source) {
                    case VIETQR -> {
                        jdbc.update("DELETE FROM nx_vietqr_reconciliation WHERE reconciliation_no=? AND (user_id IS NULL OR user_id=?) AND (intent_no IS NULL OR intent_no=?)",f.receipt,f.customer,f.partition);
                        jdbc.update("DELETE FROM nx_vietqr_intent WHERE intent_no=? AND user_id=?",f.partition,f.customer);
                    }
                    case HDPAY -> {
                        jdbc.update("DELETE FROM nx_hdpay_payin_order WHERE merchant_order_id=?",f.key);
                        jdbc.update("DELETE FROM nx_vietqr_intent WHERE intent_no=? AND user_id=?",f.key,f.customer);
                    }
                    case CARD_TOPUP -> jdbc.update("DELETE FROM nx_topup_card_settlement WHERE settlement_event_id=? AND payment_no=? AND user_id=?",f.name,f.key,f.customer);
                    case WALLET_ORDER -> { }
                    default -> throw new AssertionError(f.source);
                }
            }
        });
    }

    private static void awaitCaptureWorkers(ExecutorService workers) throws InterruptedException {
        workers.shutdown();
        if(!workers.awaitTermination(45,TimeUnit.SECONDS)) {
            workers.shutdownNow();
            if(!workers.awaitTermination(15,TimeUnit.SECONDS))
                throw new IllegalStateException("Capture transactions are still active; exact fixture cleanup cannot safely race them");
        }
    }

    private void applyStructureOnlyTwice() {
        long users=jdbc.queryForObject("SELECT COUNT(*) FROM nx_user",Long.class);
        boolean existed=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_support_payment_history_birth'",
            Long.class)>0;
        List<Map<String,Object>> births=existed?jdbc.queryForList("SELECT * FROM nx_support_payment_history_birth ORDER BY customer_id"):List.of();
        boolean billExisted=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_wallet_bill'",
            Long.class)>0;
        long bills=billExisted?jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_bill",Long.class):0;
        for(int attempt=0;attempt<2;attempt++) {
            ResourceDatabasePopulator ddl=new ResourceDatabasePopulator(
                new FileSystemResource("scripts/migrations/20261009_e4_wallet_bill_schema.sql"),
                new FileSystemResource("scripts/migrations/20261008_support_payment_attribution.sql"),
                new FileSystemResource("scripts/migrations/20261008_support_payment_history_birth.sql"));
            ddl.execute(dataSource);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user",Long.class)).isEqualTo(users);
            assertThat(jdbc.queryForList("SELECT * FROM nx_support_payment_history_birth ORDER BY customer_id")).isEqualTo(births);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_bill",Long.class)).isEqualTo(bills);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T service) {
        ProxyFactory factory=new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }

    private void rollbackFixtures(Consumer<List<Long>> body) {
        Map<String,Long> before=outsideCounts();
        List<Long> accounts=new ArrayList<>();
        try {
            transaction.executeWithoutResult(status -> {
                try { body.accept(accounts); }
                finally { status.setRollbackOnly(); }
            });
        } finally { assertRollbackReadback(before,accounts); }
    }

    private Map<String,Long> outsideCounts() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        Map<String,Long> result=new LinkedHashMap<>();
        for(String table:TABLES) result.put(table,outside.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));
        return result;
    }

    private void assertRollbackReadback(Map<String,Long> before,List<Long> accounts) {
        assertThat(outsideCounts()).isEqualTo(before);
        for(long customer:accounts) {
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_user WHERE id=?",Long.class,customer)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_payment_history_birth WHERE customer_id=?",Long.class,customer)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_payment_attribution WHERE customer_id=?",Long.class,customer)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_wallet_bill WHERE user_id=?",Long.class,customer)).isZero();
        }
    }

    private long newAccount(List<Long> accounts,int sandbox) {
        String identity=unique();
        jdbc.update("INSERT INTO nx_user(country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox) VALUES('0',?,'127.0.0.1','fixture-only','support-capture-fixture',?,'ACTIVE',?)",
            identity,identity,sandbox);
        long customer=jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,identity);
        accounts.add(customer);
        jdbc.update("INSERT INTO nx_user_wallet(user_id,usdt_available,cumulative_deposit_usdt) VALUES(?,500,0)",customer);
        return customer;
    }

    private Fixture pending(Source source,long customer) {
        Fixture f=new Fixture(source,customer,unique(),databaseLocalDateTime("SELECT NOW(6)").withNano(0));
        switch(source) {
            case DEPOSIT_ORDER -> {
                f.cid=positiveUnique();f.partition=Long.toString(positiveUnique());f.key="CR-"+f.cid;
                jdbc.update("INSERT INTO nx_cregis_deposit_event(user_id,project_id,cid,txid,log_index,address,gross_amount,fee_amount,net_amount,block_number,block_hash,confirmations,status) VALUES(?,?,?, ?,0,?,10,0,10,1,?,20,'RISK_HOLD')",
                    customer,Long.parseLong(f.partition),f.cid,f.name,f.name,f.name);
                jdbc.update("UPDATE nx_user_wallet SET cregis_risk_held=10 WHERE user_id=?",customer);
            }
            case CARD_TOPUP -> {
                f.key=f.name;f.order="CARD-"+f.name;
                payment(f,"CapturePSP","CONFIRMED",null,f.base);
                jdbc.update("INSERT INTO nx_topup_card_settlement(settlement_event_id,request_hash,admission_event_id,payment_no,order_no,user_id,provider,provider_payment_id,amount_usdt,fee_amount_usdt,fee_rate_pct,status) VALUES(?,REPEAT('a',64),?,?,?,?,'CapturePSP',?,10,0,0,'PROCESSING')",
                    f.name,"admission-"+f.name,f.key,f.order,customer,f.name);
            }
            case VIETQR,HDPAY -> {
                String intent="INT-"+f.name;f.receipt="REC-"+f.name;
                f.key=source==Source.VIETQR?"D1-VIETQR-"+f.receipt:intent;
                f.partition=source==Source.VIETQR?intent:null;
                jdbc.update("INSERT INTO nx_vietqr_intent(intent_no,user_id,create_idempotency_key,create_request_hash,requested_usdt,payable_vnd,credited_usdt,received_vnd,locked_fx_rate_vnd_per_usdt,fx_quote_version,bank_account_id,memo_code,status,expires_at,payment_rail,settlement_target_type) VALUES(?,?,?,REPEAT('a',64),10,250000,0,NULL,25000,1,1,?,'AWAITING_PAYMENT',?,?, 'WALLET_TOPUP')",
                    intent,customer,f.name,f.name,f.base.plusDays(1),source==Source.VIETQR?"MANUAL":"HDPAY");
                if(source==Source.VIETQR) jdbc.update("INSERT INTO nx_vietqr_reconciliation(reconciliation_no,view_type,status,locked_fx_rate_vnd_per_usdt) VALUES(?,'ORPHAN','OPEN',25000)",f.receipt);
                else jdbc.update("INSERT INTO nx_hdpay_payin_order(merchant_order_id,amount_vnd,submission_status,settlement_status,request_hash) VALUES(?,250000,'CREATED','UNSETTLED',REPEAT('a',64))",intent);
            }
            case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP -> {
                f.key=f.name;f.order=f.name;f.orderType=source==Source.WALLET_ORDER?"SINGLE":source.name();
                order(f,false,null);
            }
            case TRIAL_CONVERT -> {
                f.claim="CL-"+f.name;f.key="USER:"+customer;f.order="TC-"+f.name;f.orderType="TRIAL_CONVERT";
                jdbc.update("INSERT INTO nx_user_device(user_id,instance_no,name,device_type,source_channel,source_environment,run_id,status,is_deleted) VALUES(?,?,'capture-trial','BOX','TRIAL','PRODUCTION','','ACTIVE',0)",customer,f.name);
                f.device=jdbc.queryForObject("SELECT id FROM nx_user_device WHERE instance_no=?",Long.class,f.name);
                jdbc.update("INSERT INTO nx_trial_claim(user_id,claim_no,user_device_id,device_name,status,claimed_at,expires_at) VALUES(?,?,?,'capture-trial','ACTIVE',?,?)",
                    customer,f.claim,f.device,f.base.minusDays(1),f.base.plusDays(1));
            }
            case ORDER_REFUND -> {
                f.original=pending(Source.WALLET_ORDER,customer);
                Prepared original=prepare(f.original);settle(f.original,original,true);capture.record(original);
                f.order=f.original.order;f.key="E4-REFUND-"+f.order;f.base=databaseLocalDateTime("SELECT NOW(6)").withNano(0);
            }
            default -> throw new IllegalArgumentException("Not an eligible source");
        }
        return f;
    }

    private void settle(Fixture f,Prepared prepared,boolean crossSecond) {
        settle(f,prepared,crossSecond,crossSecond);
    }

    private void settle(Fixture f,Prepared prepared,boolean crossSecond,boolean waitForNextSecond) {
        // Normal cross-second fixtures deliberately separate the actual source statements.
        // The explicit same-second precision experiment opts out without editing either receipt tuple.
        if(f.source==Source.ORDER_REFUND && waitForNextSecond) databasePause();
        BigDecimal before=jdbc.queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",BigDecimal.class,f.customer);
        BigDecimal after=before.add(new BigDecimal(orderSource(f.source)?"-10.000000":"10.000000"));
        jdbc.update("UPDATE nx_user_wallet SET usdt_available=?,cumulative_deposit_usdt=cumulative_deposit_usdt+? WHERE user_id=?",
            after,f.source==Source.ORDER_REFUND || orderSource(f.source)?0:10,f.customer);
        assertThat(capture.insertLedger(prepared,new BigDecimal("10.000000"),after,"support capture fixture "+f.name)).isEqualTo(1);
        f.ledger=jdbc.queryForObject("SELECT id FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND asset='USDT' AND direction=?",
            Long.class,f.customer,f.canonicalKey(),orderSource(f.source)?"OUT":"IN");
        LocalDateTime ledgerAt=databaseLocalDateTime("SELECT created_at FROM nx_wallet_ledger WHERE id=?",f.ledger);
        if(waitForNextSecond) databasePause();
        // The positive path uses actual database time after the actual canonical INSERT.
        // The false path models a legacy success whose source timestamps equal its stored ledger second.
        LocalDateTime successAt=crossSecond?databaseLocalDateTime(orderSource(f.source)?"SELECT NOW(6)":"SELECT NOW()"):ledgerAt;
        switch(f.source) {
            case DEPOSIT_ORDER -> {
                successTimeWrite(f,crossSecond,"INSERT INTO nx_deposit_order(user_id,deposit_no,chain_name,chain_tx_hash,asset,amount,status,ledger_id,credited_at,created_at) VALUES(?,?,'BEP20',?,'USDT',10,'CREDITED',?,/*SOURCE_TIME*/?,?)",4,
                    f.customer,f.key,f.name,f.ledger,successAt,f.base);
                successTimeWrite(f,crossSecond,"UPDATE nx_cregis_deposit_event SET status='CREDITED',ledger_id=?,credited_at=/*SOURCE_TIME*/? WHERE project_id=? AND cid=?",1,
                    f.ledger,successAt,Long.parseLong(f.partition),f.cid);
                jdbc.update("UPDATE nx_user_wallet SET cregis_risk_held=0 WHERE user_id=?",f.customer);
            }
            case CARD_TOPUP -> {
                successTimeWrite(f,crossSecond,"UPDATE nx_payment_record SET wallet_ledger_id=?,paid_at=/*SOURCE_TIME*/? WHERE payment_no=?",1,f.ledger,successAt,f.key);
                jdbc.update("UPDATE nx_topup_card_settlement SET status='SETTLED' WHERE payment_no=?",f.key);
            }
            case VIETQR,HDPAY -> {
                String intent=f.source==Source.VIETQR?f.partition:f.key;
                jdbc.update("UPDATE nx_vietqr_intent SET status='CREDITED',credited_usdt=10,received_vnd=250000 WHERE intent_no=?",intent);
                if(f.source==Source.VIETQR) successTimeWrite(f,crossSecond,"UPDATE nx_vietqr_reconciliation SET intent_no=?,user_id=?,view_type='MATCHED',status='CREDITED',payable_vnd=250000,received_vnd=250000,credited_usdt=10,received_at=/*SOURCE_TIME*/? WHERE reconciliation_no=?",2,
                    intent,f.customer,successAt,f.receipt);
                else successTimeWrite(f,crossSecond,"UPDATE nx_hdpay_payin_order SET settlement_status='CREDITED',settled_usdt=10,wallet_ledger_biz_no=?,settled_at=/*SOURCE_TIME*/? WHERE merchant_order_id=?",1,intent,successAt,intent);
            }
            case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP -> {
                successTimeWrite(f,crossSecond,"UPDATE nx_order SET payment_status='PAID',order_status='PAID',paid_at=/*SOURCE_TIME*/? WHERE order_no=?",0,successAt,f.order);
                if(f.source==Source.WALLET_ORDER) {
                    f.payment="PAY-"+f.name;
                    payment(f,"NEXGRID_WALLET","CONFIRMED",f.ledger,crossSecond?databaseLocalDateTime("SELECT NOW(6)"):ledgerAt);
                    if(crossSecond)jdbc.update("UPDATE nx_payment_record SET paid_at=NOW(6) WHERE payment_no=?",f.payment);
                    jdbc.update("UPDATE nx_order SET payment_no=? WHERE order_no=?",f.payment,f.order);
                }
            }
            case TRIAL_CONVERT -> {
                order(f,true,successAt);
                if(crossSecond)jdbc.update("UPDATE nx_order SET paid_at=NOW(6) WHERE order_no=?",f.order);
                jdbc.update("UPDATE nx_user_device SET source_order_no=?,source_channel='ORDER' WHERE id=?",f.order,f.device);
                successTimeWrite(f,crossSecond,"UPDATE nx_trial_claim SET status='REDEEMED',settled_at=/*SOURCE_TIME*/?,settlement_amount_usdt=10 WHERE claim_no=?",0,
                    crossSecond?databaseLocalDateTime("SELECT NOW(6)"):ledgerAt,f.claim);
            }
            case ORDER_REFUND -> {
                // Exercise the existing E4 production INSERT, including UUID_SHORT and canonical bill identity.
                assertThat(refundMapper.insertBill(f.customer,"E4-BILL-"+f.order,new BigDecimal("10.000000"))).isEqualTo(1);
                jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",f.order);
                jdbc.update("UPDATE nx_payment_record SET payment_status='REFUNDED' WHERE order_no=?",f.order);
            }
            default -> throw new IllegalArgumentException("Not an eligible source");
        }
    }

    private void successTimeWrite(Fixture f,boolean actualNow,String sql,int timeParameter,Object... parameters) {
        if(actualNow) {
            List<Object> bound=new ArrayList<>(java.util.Arrays.asList(parameters));
            bound.remove(timeParameter);
            jdbc.update(sql.replace("/*SOURCE_TIME*/?",orderSource(f.source)?"NOW(6)":"NOW()"),bound.toArray());
        } else jdbc.update(sql.replace("/*SOURCE_TIME*/?","?"),parameters);
    }

    private void databasePause() { assertThat(jdbc.queryForObject("SELECT SLEEP(1.1)",Integer.class)).isZero(); }

    private void payment(Fixture f,String provider,String status,Long ledger,LocalDateTime paid) {
        String no=f.payment==null?f.key:f.payment;
        jdbc.update("INSERT INTO nx_payment_record(payment_no,order_no,user_id,provider,provider_payment_id,amount_usdt,currency,payment_status,wallet_ledger_id,paid_at) VALUES(?,?,?,?,?,10,'USDT',?,?,?)",
            no,f.order,f.customer,provider,f.name,status,ledger,paid);
    }

    private void order(Fixture f,boolean paid,LocalDateTime paidAt) {
        jdbc.update("INSERT INTO nx_order(user_id,order_no,product_id,order_type,amount_usdt,payment_status,order_status,paid_at,created_at) VALUES(?,?,42,?,10,?,?,?,?)",
            f.customer,f.order,f.orderType,paid?"PAID":"PENDING",paid?"PAID":"PENDING_PAYMENT",paidAt,f.base);
    }

    private LocalDateTime databaseLocalDateTime(String sql,Object... parameters) {
        // DATETIME is a wall-clock value. getTimestamp followed by toLocalDateTime would first
        // interpret it in the connection zone and then render it in the JVM zone (Shanghai/Tokyo).
        // Keep JDBC 4.2 LocalDateTime throughout fixture input and raw database readback instead.
        return jdbc.queryForObject(sql,(rs,n)->rs.getObject(1,LocalDateTime.class),parameters);
    }

    private Prepared prepare(Fixture f) { return capture.prepare(f.customer,f.source,f.key,f.partition); }
    private Map<String,String> evidence(Fixture f) { return row(jdbc,f); }
    private Map<String,String> outsideEvidence(Fixture f) { return row(outside,f); }
    private Map<String,String> row(JdbcTemplate reader,Fixture f) {
        return reader.queryForObject("SELECT a.*,HEX(CAST(source_fact_json AS BINARY)) source_json_bytes,HEX(CAST(attribution_evidence_json AS BINARY)) evidence_json_bytes FROM nx_support_payment_attribution a WHERE fact_id=?",
            (rs,n)->strings(rs),f.factId());
    }
    private static Map<String,String> strings(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String,String> result=new TreeMap<>();
        for(int i=1;i<=rs.getMetaData().getColumnCount();i++) result.put(rs.getMetaData().getColumnLabel(i),rs.getString(i));
        return result;
    }
    private void assertOldUnknownWithoutPresentWitnesses(Map<String,String> row) {
        assertThat(row.get("capture_mode")).isEqualTo("OLD_SOURCE");
        for(String layer:List.of("agent","group","owner")) {
            assertThat(row.get(layer+"_status")).isEqualTo("UNKNOWN");
            assertThat(row.get(layer.equals("group")?"group_id":layer+"_admin_id")).isNull();
        }
        JsonNode evidence=tree(row.get("attribution_evidence_json"));
        assertThat(evidence.path("beforeSource").path("oldSource").asBoolean()).isTrue();
        assertThat(evidence.has("admins") || evidence.has("groups") || evidence.has("members")
            || evidence.has("owners") || evidence.has("qualifications") || evidence.has("bindings") || evidence.has("routes")
            || evidence.has("bindingHistory") || evidence.has("assignmentHistory") || evidence.has("routeHistory")
            || evidence.has("memberHistory") || evidence.has("ownerHistory")).isFalse();
    }
    private void corruptOwnProof(Fixture f,String corruption) {
        switch(corruption) {
            case "MISSING" -> jdbc.update("DELETE FROM nx_support_payment_attribution WHERE fact_id=? AND customer_id=?",f.factId(),f.customer);
            case "OLD" -> jdbc.update("UPDATE nx_support_payment_attribution SET capture_mode='OLD_SOURCE',agent_status='UNKNOWN',group_status='UNKNOWN',owner_status='UNKNOWN',agent_admin_id=NULL,group_id=NULL,owner_admin_id=NULL,attribution_evidence_json=JSON_SET(attribution_evidence_json,'$.captureMode','OLD_SOURCE','$.beforeSource.oldSource',CAST('true' AS JSON)) WHERE fact_id=? AND customer_id=?",f.factId(),f.customer);
            case "AMOUNT" -> jdbc.update("UPDATE nx_support_payment_attribution SET source_fact_json=JSON_SET(source_fact_json,'$.amount',11) WHERE fact_id=? AND customer_id=?",f.factId(),f.customer);
            case "CUSTOMER" -> jdbc.update("UPDATE nx_support_payment_attribution SET source_fact_json=JSON_SET(source_fact_json,'$.customerId',?) WHERE fact_id=? AND customer_id=?",f.customer+1,f.factId(),f.customer);
            case "TIME" -> jdbc.update("UPDATE nx_support_payment_attribution SET source_fact_json=JSON_SET(source_fact_json,'$.sourceConfirmationAt','2000-01-01T00:00:00.000000') WHERE fact_id=? AND customer_id=?",f.factId(),f.customer);
            case "PARTITION" -> jdbc.update("UPDATE nx_support_payment_attribution SET source_partition='foreign-partition' WHERE fact_id=? AND customer_id=?",f.factId(),f.customer);
            default -> throw new IllegalArgumentException("Unknown corruption");
        }
    }
    private Object physicalResource() {
        Object resource=TransactionSynchronizationManager.getResource(dataSource);
        assertThat(resource).isInstanceOf(ConnectionHolder.class);
        Connection connection=((ConnectionHolder)resource).getConnection();
        try { assertThat(connection.getAutoCommit()).isFalse(); }
        catch(java.sql.SQLException failure) { throw new IllegalStateException(failure); }
        return resource;
    }
    private long countForCustomer(String table,long customer) {
        if(!TABLES.contains(table)) throw new IllegalArgumentException("Unowned table");
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+
            (table.startsWith("nx_support_payment_")?"customer_id":"user_id")+"=?",Long.class,customer);
    }
    private int auditCalls() { return org.mockito.Mockito.mockingDetails(audit).getInvocations().size(); }
    private JsonNode tree(String value) {
        try { return json.readTree(value); }
        catch(Exception failure) { throw new IllegalStateException("Invalid captured JSON",failure); }
    }
    private void cleanupOwnAccounts(List<Long> accounts) {
        transaction.executeWithoutResult(status -> {
            for(long customer:accounts) {
                var names=jdbc.queryForList("SELECT nickname FROM nx_user WHERE id=?",String.class,customer);
                if(names.isEmpty())continue; // An earlier transaction failure already rolled this exact fixture back.
                assertThat(names).containsExactly("support-capture-fixture");
                jdbc.update("DELETE FROM nx_support_payment_attribution WHERE customer_id=?",customer);
                jdbc.update("DELETE FROM nx_support_payment_history_birth WHERE customer_id=?",customer);
                jdbc.update("DELETE FROM nx_wallet_bill WHERE user_id=?",customer);
                jdbc.update("DELETE FROM nx_payment_record WHERE user_id=?",customer);
                jdbc.update("DELETE FROM nx_order WHERE user_id=?",customer);
                jdbc.update("DELETE FROM nx_wallet_ledger WHERE user_id=?",customer);
                jdbc.update("DELETE FROM nx_user_wallet WHERE user_id=?",customer);
                jdbc.update("DELETE FROM nx_user WHERE id=? AND nickname='support-capture-fixture'",customer);
            }
        });
    }
    private static String requiredEnvironment(String key) {
        String value=System.getenv(key);
        if(value==null || value.isBlank()) throw new IllegalStateException("Missing capture acceptance setting: "+key);
        return value;
    }
    private static String unique() { return "SC"+UUID.randomUUID().toString().replace("-","").substring(0,24); }
    private static long positiveUnique() { return (UUID.randomUUID().getMostSignificantBits()&Long.MAX_VALUE)%900_000_000_000L+100_000_000_000L; }
    private static boolean orderSource(Source source) {
        return source==Source.WALLET_ORDER || source==Source.TRADE_IN || source==Source.CAPACITY_KEEP || source==Source.TRIAL_CONVERT;
    }
    private static final class Fixture {
        final Source source;
        final long customer;
        final String name;
        LocalDateTime base;
        String key,partition,order,orderType,payment,claim,receipt;
        long cid,device,ledger;
        Fixture original;
        Fixture(Source source,long customer,String name,LocalDateTime base) {
            this.source=source;this.customer=customer;this.name=name;this.base=base;
        }
        String canonicalKey() { return source==Source.TRIAL_CONVERT?claim+":CHARGE":key; }
        String factId() { return orderSource(source)?"PURCHASE:"+order:source==Source.ORDER_REFUND?"ORDER_REFUND:"+ledger:"DEPOSIT:"+ledger; }
    }
}
