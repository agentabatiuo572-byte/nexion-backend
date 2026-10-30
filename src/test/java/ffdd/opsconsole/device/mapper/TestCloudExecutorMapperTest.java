package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.util.Map;
import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.Test;

class TestCloudExecutorMapperTest {
    @Test void completionPrechecksUseOrdinarySelectsAndFinalChecksRetainTheSameScopedLockingSql() {
        var configuration=configuration();
        configuration.addMapper(AppTaskAssignmentMapper.class);
        for(var pair:Map.of("execution","lock","binding","lockBinding","fleetBinding","liveFleetBinding",
                "activeTaskNos","lockActiveTaskNos","admission","legacyAdmission").entrySet()) {
            assertReadAndLockPair(configuration,TestCloudExecutorMapper.class,pair.getKey(),pair.getValue());
        }
        for(var pair:Map.of("ownedDevice","lockOwnedDevice","assignment","lockAssignment",
                "testWorkerRuntime","lockTestWorkerRuntime","paidCloudShareDailyNex","lockPaidCloudShareDailyNex").entrySet()) {
            assertReadAndLockPair(configuration,AppTaskAssignmentMapper.class,pair.getKey(),pair.getValue());
        }
    }
    private void assertReadAndLockPair(MybatisConfiguration configuration,Class<?> mapper,String read,String lock) {
        var ordinary=configuration.getMappedStatement(mapper.getName()+"."+read);
        var locked=configuration.getMappedStatement(mapper.getName()+"."+lock);
        assertThat(ordinary.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
        String sql=ordinary.getBoundSql(Map.of()).getSql().replaceAll("\\s+"," ").trim();
        assertThat(sql).as(read).doesNotContain("FOR UPDATE","FOR SHARE");
        assertThat(locked.getBoundSql(Map.of()).getSql().replaceAll("\\s+"," ").trim()).as(lock)
                .startsWith(sql+" FOR UPDATE");
    }
    private MybatisConfiguration configuration() {
        var configuration=new MybatisConfiguration();
        configuration.addMapper(TestCloudExecutorMapper.class);
        new MybatisSqlSessionFactoryBuilder().build(configuration);
        return configuration;
    }
    @Test void baseMapperRegistrationPreservesExplicitScopedExecutionStatements() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(TestCloudExecutorMapper.class);
        new MybatisSqlSessionFactoryBuilder().build(configuration);
        String namespace = TestCloudExecutorMapper.class.getName() + ".";
        var insert = configuration.getMappedStatement(namespace + "insert");
        assertThat(insert.getSqlCommandType()).isEqualTo(SqlCommandType.INSERT);
        var bound = insert.getBoundSql(Map.of());
        assertThat(bound.getSql()).contains("INSERT INTO nx_test_cloud_execution");
        assertThat(bound.getParameterMappings()).allSatisfy(parameter ->
                assertThat(parameter.getProperty()).startsWith("e."));
        assertThat(configuration.getMappedStatement(namespace + "eligibleBinding").getBoundSql(Map.of()).getSql())
                .contains("user_id=?", "instance_no=?", "source_environment='PRODUCTION'", "run_id=''");
        // There is no aggregate primary key for inherited by-ID CRUD statements.
        assertThat(configuration.hasStatement(namespace + "selectById")).isFalse();
        String fleet=configuration.getMappedStatement(namespace+"fleetCandidates").getBoundSql(Map.of()).getSql();
        assertThat(fleet).contains("o.payment_status='PAID'","o.subtotal_usdt>0","o.amount_usdt>=0","d.activated_at>=?",
                "NOT IN ('PHONE','MOBILE')","d.pending_deactivate=0","ORDER BY d.id LIMIT ?",
                "r.agent_version<>CONCAT('test-cloud:fleet-',d.id)","nx_test_cloud_execution");
        String live=configuration.getMappedStatement(namespace+"liveFleetBinding").getBoundSql(Map.of()).getSql();
        assertThat(live).contains("FOR UPDATE OF o,d","d.user_id=?","d.instance_no=?");
        assertThat(fleet).contains("d.source_channel='TRADE_IN'", "nx_tradein_application", "nx_trade_in_order",
                "a.target_device_id=d.id", "a.target_order_no=o.order_no", "source.instance_no=a.source_instance_no",
                "a.tradein_discount_usdt+a.wallet_debit_usdt=a.target_price_usdt", "a.target_price_usdt=d.price_usdt_snapshot",
                "o.activation_status IN ('ACTIVATED','INVENTORY')", "source.ownership_status", "'CPR-'", "'DEV-CPR-'")
                .doesNotContain("JOIN nx_audit_log");
        assertThat(configuration.getMappedStatement(namespace+"fleetCandidates").getBoundSql(Map.of())
                .getParameterMappings()).anySatisfy(parameter -> assertThat(parameter.getProperty()).isEqualTo("tradeinCutoff"));
        assertThat(configuration.getMappedStatement(namespace+"register").getSqlCommandType()).isEqualTo(SqlCommandType.INSERT);
        assertThat(configuration.getMappedStatement(namespace+"historicalReceipt").getBoundSql(Map.of()).getSql())
                .contains("t.user_id=?","t.user_device_id=?").doesNotContain("u.status","ownership_status");
    }
    @Test void claimedRecoveryFinancialReadDetectsReceiptLedgerAndEarningEvidenceWithoutWriting() {
        var statement=configuration().getMappedStatement(TestCloudExecutorMapper.class.getName()+".settlementEvidence");
        assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
        assertThat(statement.getBoundSql(Map.of()).getSql()).contains("nx_compute_receipt r WHERE r.task_no=?",
                "nx_wallet_ledger l WHERE l.biz_no=?", "CONCAT('CTR-',RIGHT(?,32))",
                "nx_earning_event e WHERE e.event_no=CONCAT('EARN-',?)", "e.receipt_no=")
                .doesNotContain("INSERT", "UPDATE", "DELETE");
    }
    @Test void legacyDateExceptionsRequireTheSameReviewedIdentityWithoutRelaxingPaidDeliveryRelationships() {
        var configuration=configuration();
        String namespace=TestCloudExecutorMapper.class.getName()+".";
        for(String statement:new String[]{"fleetCandidates","fleetCandidate","liveFleetBinding"}) {
            var bound=configuration.getMappedStatement(namespace+statement).getBoundSql(Map.of());
            assertThat(bound.getSql()).as(statement).contains("nx_test_cloud_legacy_admission",
                    "la.owner_id=d.user_id", "la.device_id=d.id", "la.instance_no=d.instance_no",
                    "la.source_order_no=d.source_order_no", "la.source_channel=d.source_channel",
                    "la.deployment_scope='TEST'", "la.enabled=1",
                    "o.payment_status='PAID'", "o.order_status='COMPLETED'", "o.subtotal_usdt>0", "o.amount_usdt>=0",
                    "owned.source_order_no=o.order_no", "SUM(oi.quantity)", "a.target_device_id=d.id",
                    "a.target_order_no=o.order_no", "c.user_id=a.user_id", "source.instance_no=a.source_instance_no",
                    "a.tradein_discount_usdt+a.wallet_debit_usdt=a.target_price_usdt",
                    "a.target_price_usdt=d.price_usdt_snapshot", "a.wallet_debit_usdt=o.amount_usdt");
            assertThat(bound.getParameterMappings()).as(statement)
                    .anySatisfy(parameter->assertThat(parameter.getProperty()).isEqualTo("legacyEnabled"));
        }
    }
    @Test void legacyCancellationAdmissionLocksTheExactTaskConfigurationAndCurrentSourceIdentity() {
        var bound=configuration().getMappedStatement(TestCloudExecutorMapper.class.getName()+".legacyAdmission")
                .getBoundSql(Map.of());
        assertThat(bound.getSql()).contains("la.old_task_no oldTaskNo", "la.reason", "la.evidence_sha256 evidenceSha256",
                "d.user_id=la.owner_id", "d.instance_no=la.instance_no", "d.source_order_no=la.source_order_no",
                "d.source_channel=la.source_channel", "la.owner_id=?", "la.device_id=?", "la.instance_no=?",
                "la.old_task_no=?", "la.task_config_id=?", "la.deployment_scope='TEST'", "la.enabled=1", "FOR UPDATE");
        assertThat(bound.getParameterMappings()).extracting(parameter->parameter.getProperty())
                .containsExactly("ownerId","deviceId","instanceNo","taskNo","taskNo","config");
    }
    @Test void bothLegacyDiscoveryAndCancellationRejectAnyExecutionSettlementOrForeignWorkerEvidence() {
        var configuration=configuration();
        String namespace=TestCloudExecutorMapper.class.getName()+".";
        for(String statement:new String[]{"fleetCandidates","liveFleetBinding","cancelUnexecuted"}) {
            String sql=configuration.getMappedStatement(namespace+statement).getBoundSql(Map.of()).getSql();
            assertThat(sql).as(statement).contains("t.model_name='BGE-M3'", "t.client_name='UVEL App'",
                    "t.proof_consumed_at IS NULL", "t.completed_at IS NULL", "COALESCE(t.last_error,'')=''",
                    "nx_test_cloud_execution e WHERE e.task_no=t.task_no", "nx_compute_receipt r WHERE r.task_no=t.task_no",
                    "nx_wallet_ledger l WHERE l.biz_no=t.task_no", "l.biz_no=CONCAT('CTR-',RIGHT(t.task_no,32))",
                    "nx_earning_event ee WHERE ee.event_no=CONCAT('EARN-',t.task_no)",
                    "ee.receipt_no=CONCAT('CTR-',RIGHT(t.task_no,32))", "TASK_ASSIGNMENT_CLAIMED",
                    "COALESCE(r.agent_version,'')<>''", "COALESCE(r.client_name,'') NOT IN ('','UVEL App')");
        }
        String live=configuration.getMappedStatement(namespace+"liveFleetBinding").getBoundSql(Map.of()).getSql();
        assertThat(live).contains("la.old_task_no=t.task_no", "la.task_config_id=t.task_config_id");
        String cancel=configuration.getMappedStatement(namespace+"cancelUnexecuted").getBoundSql(Map.of()).getSql();
        assertThat(cancel).contains("t.status='CANCELLED'", "t.user_id=?", "t.user_device_id=?", "t.task_no=?", "t.task_config_id=?")
                .doesNotContain("INSERT", "status='COMPLETED'");
        String active=configuration.getMappedStatement(namespace+"lockActiveTaskNos").getBoundSql(Map.of()).getSql();
        assertThat(active).contains("SELECT task_no", "user_id=?", "user_device_id=?", "source_environment='PRODUCTION'",
                "is_deleted=0", "status IN ('CLAIMED','RUNNING')", "FOR UPDATE").doesNotContain("LIMIT");
    }
}
