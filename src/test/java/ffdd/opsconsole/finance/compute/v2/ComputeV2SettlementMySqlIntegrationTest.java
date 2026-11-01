package ffdd.opsconsole.finance.compute.v2;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.application.SupportDeviceReadService;
import ffdd.opsconsole.device.application.ComputeV2ReadService;
import ffdd.opsconsole.device.application.AppTaskAssignmentService;
import ffdd.opsconsole.device.application.ComputeV2DeviceLifecycleService;
import ffdd.opsconsole.device.compute.v2.ComputeV2Runtime;
import ffdd.opsconsole.device.dto.AppTaskAssignmentsResponse;
import ffdd.opsconsole.device.dto.AppTaskDeviceState;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event.*;
import ffdd.opsconsole.device.mapper.SupportDeviceReadMapper;
import ffdd.opsconsole.device.mapper.ComputeV2ReadMapper;
import ffdd.opsconsole.finance.application.SupportFundDeviceEvidence;
import ffdd.opsconsole.finance.application.SupportFundJournalBindingService;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Event;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import ffdd.opsconsole.finance.mapper.ComputeV2SettlementMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.env.MockEnvironment;

/** Real SQL, Spring transactions, MyBatis binder and original journal triggers; owned disposable schemas only. */
@EnabledIfEnvironmentVariable(named="COMPUTE_V2_MYSQL_IT",matches="true")
public class ComputeV2SettlementMySqlIntegrationTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final String incarnation="44444444-4444-4444-4444-444444444444";
    private final Instant completed=Instant.parse("2026-10-31T23:59:59.123456Z");
    private Connection admin;
    private String database;
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private SupportFundJournalMapper journal;
    private ComputeV2SettlementMapper settlementMapper;
    private ComputeV2ReadMapper readMapper;
    private SupportDeviceReadService sources;
    private EventOutboxService outbox;
    private ComputeV2SettlementService sink;

    @BeforeEach public void fixture() throws Exception {
        String original=System.getenv().getOrDefault("NEXION_TEST_DB_URL","jdbc:mysql://127.0.0.1:33342/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        assertThat(original).matches("^jdbc:mysql://(127\\.0\\.0\\.1|localhost|\\[::1\\])(?::[0-9]{1,5})?/[^?]+(?:\\?.*)?$");
        String user=System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME","root");
        String password=System.getenv().getOrDefault("NEXION_TEST_DB_PASSWORD","");
        database="compute_v2_"+UUID.randomUUID().toString().replace("-","");
        admin=DriverManager.getConnection(original,user,password);
        admin.createStatement().execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        int slash=original.indexOf('/',"jdbc:mysql://".length()),query=original.indexOf('?',slash);
        source=new DriverManagerDataSource(original.substring(0,slash+1)+database+(query<0?"":original.substring(query)),user,password);
        jdbc=new JdbcTemplate(source); manager=new DataSourceTransactionManager(source);
        schema();
        var configuration=new Configuration(new Environment("compute-v2-isolated",new SpringManagedTransactionFactory(),source));
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.addMapper(SupportFundJournalMapper.class); configuration.addMapper(SupportDeviceReadMapper.class);
        configuration.addMapper(ComputeV2SettlementMapper.class);configuration.addMapper(ComputeV2ReadMapper.class);
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        journal=session.getMapper(SupportFundJournalMapper.class);
        settlementMapper=session.getMapper(ComputeV2SettlementMapper.class);readMapper=session.getMapper(ComputeV2ReadMapper.class);
        var binder=new SupportFundJournalBindingService(journal,source,json);
        sources=new SupportDeviceReadService(session.getMapper(SupportDeviceReadMapper.class));
        outbox=mock(EventOutboxService.class);
        sink=new ComputeV2SettlementService(settlementMapper,manager,binder,outbox,json);
    }
    @AfterEach public void cleanup() throws Exception {
        admin.createStatement().execute("DROP DATABASE "+database); admin.close();
    }

    @Test void twoCreditsAndZeroChildShareOneActualWalletRevisionAndSurviveBothReplayKeys() throws Exception {
        var events=List.of(event(1,300001,0,completed),event(2,200002,0,completed),event(3,0,0,completed));
        var result=persist("minute-1",events);
        assertThat(result.eventIds()).hasSize(3); assertThat(result.allocationIds()).hasSize(3);
        assertWallet("0.500003","0",1);
        assertCount("nx_compute_task",3);assertCount("nx_compute_receipt",3);assertCount("nx_earning_event",3);
        assertCount("nx_support_wallet_journal",1);assertCount("nx_support_fund_operation_leg",1);
        assertThat(jdbc.queryForList("SELECT DISTINCT balance_after FROM nx_wallet_ledger")).hasSize(1);
        assertThat(sink.findCommitted("minute-1").orElseThrow().replayed()).isTrue();
        assertThat(persist("minute-1",events).replayed()).isTrue();
        persist("duplicate-other-batch",events);assertWallet("0.500003","0",1);
        assertCount("nx_compute_v2_allocation",3);assertCount("nx_compute_v2_checkpoint",2);
        verify(outbox,times(6)).publishUserEvent(anyString(),anyString(),anyString(),anyLong(),anyString(),anyInt(),anyString(),any());
        verifyEvidence();
        verifyReadContract(Set.of("USDT"));
        assertThatThrownBy(()->persist("changed-event",List.of(event(1,300002,0,completed))))
                .hasMessage("COMPUTE_V2_EVENT_CONTENT_CHANGED");
        assertThatThrownBy(()->persist("minute-1",List.of(events.get(0))))
                .hasMessage("COMPUTE_V2_BATCH_CONTENT_CHANGED");
    }

    @Test void allZeroCreatesTruthfulZeroLegOperationAndAllocationWithoutTouchingWallet() throws Exception {
        persist("zero",List.of(event(1,0,0,completed)));
        assertWallet("0","0",0);assertCount("nx_support_wallet_journal",0);
        assertCount("nx_support_fund_operation_leg",0);assertCount("nx_support_fund_operation",1);
        assertThat(jdbc.queryForObject("SELECT amount_micros FROM nx_compute_v2_allocation",Long.class)).isZero();
        verifyEvidence();
        verifyReadContract(Set.of("USDT"));
    }

    @Test void twoHundredIsTheExactLimitAndTheTwoHundredFirstIsRejectedBeforeAnyWrite() {
        var events=new ArrayList<ComputeV2Event>();
        for(int i=1;i<=200;i++)events.add(event(i,1,0,completed));
        persist("limit",events);assertWallet("0.000200","0",1);assertCount("nx_compute_v2_allocation",200);
        events.add(event(201,1,0,completed));
        assertThatThrownBy(()->persist("too-many",events)).isInstanceOf(IllegalArgumentException.class);
        assertCount("nx_compute_v2_checkpoint",1);
    }

    @Test void normalDeactivationKeepsConfirmedMoneyButFrozenAccountAndChangedWalletIdentityBlock() {
        jdbc.update("UPDATE nx_user_device SET status='DEACTIVATED',deactivated_at=NOW(),pending_deactivate=0 WHERE id=11");
        persist("deactivated",List.of(event(1,1,0,completed)));assertWallet("0.000001","0",1);
        jdbc.update("UPDATE nx_user SET status='FROZEN' WHERE id=7");
        assertThatThrownBy(()->persist("frozen",List.of(event(2,1,0,completed)))).hasMessage("COMPUTE_V2_ACCOUNT_NOT_ACTIVE");
        assertThat(persist("deactivated",List.of(event(1,1,0,completed))).replayed()).isTrue();
        jdbc.update("UPDATE nx_user SET status='ACTIVE' WHERE id=7");
        var original=event(2,1,0,completed);var t=original.task();
        var wrong=new TaskSnapshot(t.requestId(),t.taskConfigId(),t.taskName(),t.taskClass(),t.modelName(),t.clientName(),t.configVersion(),
                t.expectedSeconds(),t.minSeconds(),t.maxSeconds(),t.requiredSeconds(),0,t.issuedAt(),t.startedAt(),t.leaseExpiresAt(),t.proofExpiresAt(),
                t.rewardUsdtMicros(),t.rawQuoteMicros(),t.e3Multiplier(),t.walletId(),UUID.randomUUID().toString(),t.device(),t.inputSha256(),t.modelSha256(),t.inputArtifactRef());
        var changed=new ComputeV2Event(2,original.eventId(),original.kind(),original.deploymentScope(),"PRODUCTION","",7,11,"DEVICE-11",original.taskNo(),original.generation(),original.occurredAt(),wrong,original.evidence(),null);
        assertThatThrownBy(()->persist("identity",List.of(changed))).hasMessage("COMPUTE_V2_WALLET_IDENTITY_CHANGED");
        assertWallet("0.000001","0",1);assertCount("nx_compute_v2_checkpoint",1);
    }

    @Test void uvtIsUniquePerDeviceUtcDayAndPostingTimeRemainsSeparateFromCompletion() throws Exception {
        persist("cross-day",List.of(event(1,2,3000000,completed),event(2,3,4000000,completed.plusSeconds(1))));
        assertWallet("0.000005","7",2);assertCount("nx_compute_v2_settlement",2);
        assertThat(jdbc.queryForList("SELECT daily_entitlement_key FROM nx_compute_v2_allocation WHERE asset='UVT' ORDER BY daily_entitlement_key"))
                .extracting(r->r.get("daily_entitlement_key")).containsExactly("CLOUD_SHARE_DAILY:11:2026-10-31","CLOUD_SHARE_DAILY:11:2026-11-01");
        assertThat(jdbc.queryForObject("SELECT completed_at_utc<>posted_at_utc FROM nx_compute_v2_allocation LIMIT 1",Boolean.class)).isTrue();
        assertThatThrownBy(()->persist("daily-replay",List.of(event(3,8,4000000,completed.plusSeconds(2))))).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertWallet("0.000005","7",2);assertCount("nx_compute_receipt",2);assertCount("nx_compute_v2_checkpoint",1);
        verifyEvidence();
        verifyReadContract(Set.of("USDT","NEX"));
    }

    @Test void anExistingLegacyDailyLedgerPreventsBothV2CurrencyCreditsAtomically() {
        jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status) VALUES(7,'CLOUD_SHARE_DAILY:11:2026-10-31','COMPUTE_TASK_REWARD','UVT','IN',3,3,'SUCCESS')");
        assertThatThrownBy(()->persist("legacy-daily",List.of(event(1,1,3000000,completed)))).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertWallet("0","0",0);assertCount("nx_compute_v2_checkpoint",0);assertCount("nx_compute_receipt",0);
    }

    @ParameterizedTest @ValueSource(strings={"nx_compute_task","nx_compute_receipt","nx_compute_v2_settlement","nx_compute_v2_allocation","nx_wallet_ledger","nx_earning_event","nx_support_fund_operation","nx_support_fund_operation_leg","nx_compute_v2_event","nx_compute_v2_checkpoint"})
    void everyWriteFailureRollsBackWalletJournalAndAllOtherFinancialEvidence(String table) {
        jdbc.execute("CREATE TRIGGER compute_v2_test_failure BEFORE INSERT ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='INJECTED_WRITE_FAILURE'");
        assertThatThrownBy(()->persist("fault",List.of(event(1,100,0,completed)))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertWallet("0","0",0);
        for(String name:List.of("nx_compute_task","nx_compute_receipt","nx_compute_v2_settlement","nx_compute_v2_allocation","nx_wallet_ledger","nx_earning_event","nx_support_wallet_journal","nx_support_fund_operation","nx_support_fund_operation_leg","nx_compute_v2_event","nx_compute_v2_checkpoint"))assertCount(name,0);
    }

    @Test void concurrentCrossBatchReplayCommitsMoneyOnlyOnce() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);var e=event(1,999999,0,completed);
        try {
            var a=pool.submit(()->{start.await();return persist("concurrent-a",List.of(e));});
            var b=pool.submit(()->{start.await();return persist("concurrent-b",List.of(e));});
            start.countDown();assertThat(a.get(20,TimeUnit.SECONDS).eventIds()).containsExactly(e.eventId());
            assertThat(b.get(20,TimeUnit.SECONDS).eventIds()).containsExactly(e.eventId());
            assertWallet("0.999999","0",1);assertCount("nx_compute_v2_checkpoint",2);assertCount("nx_compute_v2_allocation",1);
        }finally{pool.shutdownNow();}
    }

    @Test void aLostAckAfterTheRealPhysicalCommitResolvesOnlyTheOriginalCheckpoint() {
        var lost=new AtomicBoolean();
        DataSource lossy=new DelegatingDataSource(source){
            @Override public Connection getConnection() throws SQLException {
                Connection physical=super.getConnection();
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                    try {
                        Object result=method.invoke(physical,args);
                        if(method.getName().equals("commit") && lost.compareAndSet(false,true))throw new SQLException("INJECTED_ACK_LOSS_AFTER_REAL_COMMIT");
                        return result;
                    }catch(InvocationTargetException ex){throw ex.getCause();}
                });
            }
        };
        var tx=new DataSourceTransactionManager(lossy);
        var configuration=new Configuration(new Environment("compute-v2-ack-loss",new SpringManagedTransactionFactory(),lossy));
        configuration.addMapper(SupportFundJournalMapper.class);configuration.addMapper(ComputeV2SettlementMapper.class);
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        var mapper=session.getMapper(SupportFundJournalMapper.class);
        var acknowledgementSink=new ComputeV2SettlementService(session.getMapper(ComputeV2SettlementMapper.class),tx,new SupportFundJournalBindingService(mapper,lossy,json),outbox,json);
        var batch=new ffdd.opsconsole.device.compute.v2.ComputeV2Persistence.SealedBatch("ack-loss",Instant.parse("2026-11-01T00:00:00Z"),Instant.parse("2026-11-01T00:01:00Z"),List.of(event(1,300001,0,completed)));
        assertThatThrownBy(()->acknowledgementSink.persistBatch(batch)).isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertWallet("0.300001","0",1);assertCount("nx_compute_v2_checkpoint",1);
        var unacknowledged=mock(ComputeV2Runtime.class);
        when(unacknowledged.pendingVerifiedSnapshot(7L)).thenReturn(new ComputeV2Runtime.PendingVerifiedSnapshot(
                new ComputeV2Runtime.PendingSummary(300001,0,1,0),batch.events()));
        when(unacknowledged.view(7L,11L)).thenReturn(Optional.empty());
        var legacy=mock(AppTaskAssignmentService.class);
        when(legacy.assignments(7L)).thenReturn(ApiResult.ok(new AppTaskAssignmentsResponse(LocalDateTime.now(),
                List.of(new AppTaskDeviceState(11L,"DEVICE-11","SERVER",null,null,List.of())),"server","PRODUCTION","",true)));
        var environment=new MockEnvironment().withProperty("nexion.compute-v2.enabled","true")
                .withProperty("nexion.compute-v2.deployment-scope","TEST").withProperty("nexion.compute-v2.device-ids","11");
        var read=new ComputeV2ReadService(legacy,Optional.of(unacknowledged),environment,readMapper,json);
        assertThat(read.assignments(7L).get("pendingEarned")).isEqualTo(List.of(
                Map.of("asset","USDT","amount","0.000000","taskCount",0L),Map.of("asset","NEX","amount","0.000000","taskCount",0L)));
        assertThat(sink.findCommitted("ack-loss").orElseThrow().eventIds()).containsExactly(batch.events().get(0).eventId());
        assertThat(acknowledgementSink.persistBatch(batch).replayed()).isTrue();assertWallet("0.300001","0",1);
        assertCount("nx_support_fund_operation",1);assertCount("nx_compute_v2_allocation",1);
    }

    @Test void immutableCheckpointLifecycleSettlementAndAllocationRejectUpdateAndDelete() {
        persist("immutable",List.of(event(1,1,0,completed)));
        for(String table:List.of("nx_compute_v2_checkpoint","nx_compute_v2_event","nx_compute_v2_settlement","nx_compute_v2_allocation")){
            assertThatThrownBy(()->jdbc.execute("UPDATE "+table+" SET user_id=user_id")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.execute("DELETE FROM "+table)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertCount(table,1);
        }
    }

    @Test void outboxFailureCannotLeaveCreditedWalletOrPostedReferralSources() {
        doThrow(new IllegalStateException("INJECTED_OUTBOX_FAILURE")).when(outbox).publishUserEvent(anyString(),anyString(),anyString(),anyLong(),anyString(),anyInt(),anyString(),any());
        assertThatThrownBy(()->persist("outbox-fault",List.of(event(1,100,0,completed)))).hasMessage("INJECTED_OUTBOX_FAILURE");
        assertWallet("0","0",0);assertCount("nx_earning_event",0);assertCount("nx_compute_receipt",0);
        assertCount("nx_support_fund_operation",0);assertCount("nx_compute_v2_checkpoint",0);
    }

    @Test void realCanonicalOutboxCommitsBothAssetsAndZeroChildWithoutDuplicatingReplay() throws Exception {
        useCanonicalOutbox();
        var events=List.of(event(1,100,3000000,completed),event(2,0,0,completed));
        persist("canonical-events",events);assertWallet("0.000100","3",2);
        assertCount("nx_event_outbox",4);assertCount("nx_compute_v2_allocation",3);
        for(var row:jdbc.queryForList("SELECT event_name,schema_revision,schema_registered,payload FROM nx_event_outbox")) {
            assertThat(row.get("event_name")).isIn("task.completed","earnings.credited");
            assertThat(((Number)row.get("schema_revision")).intValue()).isEqualTo(316);
            assertThat(((Number)row.get("schema_registered")).intValue()).isEqualTo(1);
            var payload=json.readTree(row.get("payload").toString());
            assertThat(payload.path("user_id").longValue()).isEqualTo(7);
            assertThat(payload.path("task_id").asText()).isEqualTo("TASK-EM");
            assertThat(payload.path("device_id").longValue()).isEqualTo(11);
            assertThat(payload.path("receipt_no").asText()).isEqualTo("CTR-"+payload.path("task_no").asText().substring(payload.path("task_no").asText().length()-32));
            assertThat(payload.path("amount_usdt").decimalValue()).isEqualByComparingTo(
                    payload.path("task_no").asText().equals(events.get(0).taskNo())?"0.000100":"0");
            for(String removed:List.of("compute_engine","completed_at","wallet_posted_at"))assertThat(payload.has(removed)).isFalse();
        }
        var retained=json.readTree(jdbc.queryForObject("SELECT payload FROM nx_compute_v2_event WHERE event_id=?",String.class,events.get(0).eventId()));
        assertThat(retained.path("evidence").path("completedAt").asText()).isEqualTo(completed.toString());
        assertThat(retained.path("task").path("rewardUsdtMicros").longValue()).isEqualTo(100);
        persist("canonical-events",events);persist("canonical-events-other-checkpoint",events);
        assertWallet("0.000100","3",2);assertCount("nx_event_outbox",4);verifyEvidence();
    }

    @Test void realCanonicalOutboxStillRejectsTheOriginalUnregisteredExtensions() throws Exception {
        useCanonicalOutbox();
        Map<String,Object> old=new LinkedHashMap<>(Map.of("task_id","TASK-EM","task_no","TASK-fixture",
                "device_id",11L,"receipt_no","CTR-fixture","amount_usdt",new BigDecimal("0.000100"),
                "compute_engine","V2","completed_at",completed.toString(),"wallet_posted_at",completed.plusSeconds(60).toString()));
        assertThatThrownBy(()->outbox.publishUserEvent("COMPUTE_TASK","TASK-fixture","task.completed",7L,"P2",1,"2026-W37",old))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class).hasMessage("A4_SCHEMA_PROPERTY_NOT_REGISTERED");
        assertWallet("0","0",0);assertCount("nx_event_outbox",0);assertCount("nx_support_fund_operation",0);
    }

    @Test void secondRealBusinessEventRejectionRollsBackBothAssetsAndRetriesTheSameImmutableBatch() throws Exception {
        useCanonicalOutbox();
        jdbc.update("UPDATE nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id SET p.property_type='string' WHERE s.event_name='earnings.credited' AND p.property_name='amount_usdt'");
        var events=List.of(event(1,100,3000000,completed));
        assertThatThrownBy(()->persist("real-outbox-retry",events)).isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessage("A4_SCHEMA_PROPERTY_TYPE_MISMATCH");
        assertWallet("0","0",0);
        for(String table:List.of("nx_event_outbox","nx_compute_task","nx_compute_receipt","nx_compute_v2_settlement","nx_compute_v2_allocation",
                "nx_wallet_ledger","nx_earning_event","nx_support_wallet_journal","nx_support_fund_operation","nx_support_fund_operation_leg",
                "nx_compute_v2_event","nx_compute_v2_checkpoint"))assertCount(table,0);
        jdbc.update("UPDATE nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id SET p.property_type='number' WHERE s.event_name='earnings.credited' AND p.property_name='amount_usdt'");
        persist("real-outbox-retry",events);assertWallet("0.000100","3",2);assertCount("nx_event_outbox",2);verifyEvidence();
    }

    private void useCanonicalOutbox() throws Exception {
        String schema=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:List.of("nx_event_outbox","nx_event_schema_revision","nx_event_schema_registry","nx_event_schema_property"))createTable(schema,table);
        createTable(Files.readString(Path.of("scripts/migrations/20260810_ab_pending_closure.sql")),"nx_admin_event_lifecycle");
        try(Connection connection=source.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource("scripts/migrations/20260831_business_event_schema_closure.sql"));
        }
        var config=new Configuration(new Environment("compute-v2-real-outbox",new SpringManagedTransactionFactory(),source));
        config.addMapper(EventOutboxMapper.class);
        var mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(EventOutboxMapper.class);
        outbox=new EventOutboxService(mapper,json,new ffdd.opsconsole.shared.outbox.OutboxProperties(),mock(ffdd.opsconsole.platform.application.A4RuntimePolicyService.class));
        sink=new ComputeV2SettlementService(settlementMapper,manager,new SupportFundJournalBindingService(journal,source,json),outbox,json);
    }

    @Test void orphanTaskEvidenceBlocksWithoutDeletingTheUnownedOriginal() {
        var event=event(1,100,0,completed);
        jdbc.update("INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_type,client_name,status) VALUES(?,7,11,'EM','orphan','COMPLETED')",event.taskNo());
        assertThatThrownBy(()->persist("orphan",List.of(event))).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertWallet("0","0",0);assertCount("nx_compute_task",1);assertCount("nx_compute_receipt",0);assertCount("nx_compute_v2_checkpoint",0);
    }

    @Test void startedAndFencedLifecycleSnapshotsRecoverActualPermitUntilExplicitExit() {
        var verified=event(1,1,0,completed);var t=verified.task();
        var issued=new TaskSnapshot(t.requestId(),t.taskConfigId(),t.taskName(),t.taskClass(),t.modelName(),t.clientName(),1,60,60,60,60,0,
                t.issuedAt(),null,t.leaseExpiresAt(),t.proofExpiresAt(),1,1,BigDecimal.ONE,1,incarnation,t.device(),t.inputSha256(),t.modelSha256(),t.inputArtifactRef());
        var events=List.of(lifecycle(verified,Kind.ISSUED,issued),lifecycle(verified,Kind.STARTED,t),lifecycle(verified,Kind.FENCED,t));
        persist("lifecycle",events);assertWallet("0","0",0);assertCount("nx_compute_task",0);
        assertThat(sink.recover("TEST")).extracting(ComputeV2Event::kind).containsExactlyInAnyOrder(Kind.ISSUED,Kind.STARTED,Kind.FENCED);
        persist("exited",List.of(lifecycle(verified,Kind.EXITED,t)));
        assertThat(sink.recover("TEST")).extracting(ComputeV2Event::kind).contains(Kind.EXITED);
    }

    @Test void coldRequestLookupReturnsOriginalIssuanceAndSqlUniquenessBindsAccountDeviceAndKind() {
        var complete=event(1,300001,0,completed);var t=complete.task();
        var frozen=new TaskSnapshot(t.requestId(),t.taskConfigId(),t.taskName(),t.taskClass(),t.modelName(),t.clientName(),t.configVersion(),
                t.expectedSeconds(),t.minSeconds(),t.maxSeconds(),t.requiredSeconds(),t.executionTimeoutSec(),t.taskLockMinutes(),
                t.issuedAt(),null,t.leaseExpiresAt(),t.proofExpiresAt(),t.rewardUsdtMicros(),t.rawQuoteMicros(),t.e3Multiplier(),
                t.walletId(),t.walletIncarnation(),t.device(),t.inputSha256(),t.modelSha256(),t.inputArtifactRef());
        var issued=lifecycle(complete,Kind.ISSUED,frozen);var started=lifecycle(complete,Kind.STARTED,t);
        assertThat(sink.findIssued(7,11,t.requestId())).isEmpty();
        assertThat(sink.findLifecycle(issued.eventId())).isEmpty();
        persist("lookup-original",List.of(issued,started));
        assertThat(sink.findIssued(7,11,t.requestId())).contains(issued);
        assertThat(sink.findLifecycle(started.eventId())).contains(started);
        assertThat(sink.findIssued(8,11,t.requestId())).isEmpty();
        assertThat(sink.findIssued(7,12,t.requestId())).isEmpty();
        assertThat(sink.findIssued(7,11,t.requestId().toUpperCase(Locale.ROOT))).isEmpty();
        assertThat(sink.findIssued(7,11,"missing' OR 1=1 --")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT request_id FROM nx_compute_v2_event WHERE event_id=?",String.class,issued.eventId())).isEqualTo(t.requestId());
        assertThat(jdbc.queryForObject("SELECT EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_compute_v2_event' AND COLUMN_NAME='request_id'",String.class)).contains("STORED GENERATED");
        assertThat(jdbc.queryForObject("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_compute_v2_event' AND INDEX_NAME='uk_compute_v2_request_kind' AND NON_UNIQUE=0",String.class))
                .isEqualTo("user_id,user_device_id,request_id,kind");
        var duplicate=new ComputeV2Event(2,ComputeV2Event.eventId("OTHER-TASK",2,Kind.ISSUED),Kind.ISSUED,"TEST","PRODUCTION","",
                7,11,"DEVICE-11","OTHER-TASK",2,completed,frozen,null,"duplicate request");
        assertThatThrownBy(()->persist("lookup-duplicate",List.of(duplicate))).hasMessageContaining("uk_compute_v2_request_kind");
        assertCount("nx_compute_v2_event",2);assertCount("nx_compute_v2_checkpoint",1);assertWallet("0","0",0);
        verifyNoInteractions(outbox);
    }

    @Test void queuedDeactivationCommitsPendingBeforeCancellationAndFinalizesThroughRealCanonicalSql() {
        var runtime=mock(ComputeV2Runtime.class);var event=event(1,1,0,completed);var original=event.task();
        var queued=new TaskSnapshot(original.requestId(),original.taskConfigId(),original.taskName(),original.taskClass(),original.modelName(),original.clientName(),original.configVersion(),60,60,60,60,300,0,
                original.issuedAt(),null,original.leaseExpiresAt(),original.proofExpiresAt(),1,1,BigDecimal.ONE,1,incarnation,original.device(),original.inputSha256(),original.modelSha256(),original.inputArtifactRef());
        var issued=lifecycle(event,Kind.ISSUED,queued);
        var current=new AtomicReference<>(new ComputeV2Runtime.RuntimeView(issued,"QUEUED",null,null,0,0));
        when(runtime.isReady()).thenReturn(true);when(runtime.viewDevice(11)).thenAnswer(i->Optional.of(current.get()));
        var service=lifecycleService(runtime);
        when(runtime.cancelQueued(7,11,1,"USER_DEACTIVATION_PENDING")).thenAnswer(i->{
            // An independent physical connection proves the owner transaction committed before MQ cancellation.
            try(var connection=source.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT pending_deactivate FROM nx_user_device WHERE id=11")){
                assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(1);
            }
            current.set(new ComputeV2Runtime.RuntimeView(issued,"FAILED",null,null,0,0));return true;
        });
        new TransactionTemplate(manager).execute(status->{assertThat(service.deferDeactivation(7,11,5L)).isTrue();return null;});
        assertThat(jdbc.queryForMap("SELECT status,pending_deactivate,row_version FROM nx_user_device WHERE id=11"))
                .containsEntry("status","DEACTIVATED").containsEntry("pending_deactivate",0).containsEntry("row_version",6L);
        assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime WHERE user_device_id=11",String.class)).isEqualTo("OFFLINE");
        assertWallet("0","0",0);assertCount("nx_compute_task",0);
    }

    @Test void exitedV2NeverFinalizesOverRealUnresolvedV1JournalEvidence() {
        var runtime=mock(ComputeV2Runtime.class);var event=event(1,1,0,completed);
        when(runtime.isReady()).thenReturn(true);when(runtime.viewDevice(11)).thenReturn(Optional.of(new ComputeV2Runtime.RuntimeView(event,"COMPLETED",null,null,0,0)));
        when(runtime.hasExited(event.taskNo(),1)).thenReturn(true);
        var service=lifecycleService(runtime);
        jdbc.update("UPDATE nx_user_device SET pending_deactivate=1 WHERE id=11");
        jdbc.update("INSERT INTO nx_test_cloud_request VALUES(7,11,'UNKNOWN')");
        var original=jdbc.queryForList("SELECT * FROM nx_test_cloud_request");
        assertThat(service.finalizeExited(7,11,event.taskNo(),1)).isFalse();
        assertThat(jdbc.queryForList("SELECT * FROM nx_test_cloud_request")).isEqualTo(original);
        assertThat(jdbc.queryForMap("SELECT status,pending_deactivate,row_version FROM nx_user_device WHERE id=11"))
                .containsEntry("status","ACTIVE").containsEntry("pending_deactivate",1).containsEntry("row_version",5L);
        assertWallet("0","0",0);
    }

    private ComputeV2DeviceLifecycleService lifecycleService(ComputeV2Runtime runtime) {
        jdbc.execute("ALTER TABLE nx_user_device ADD ownership_status VARCHAR(32) NOT NULL DEFAULT 'OWNED',ADD row_version BIGINT NOT NULL DEFAULT 5,ADD device_type VARCHAR(32) NOT NULL DEFAULT 'SERVER',ADD activated_at DATETIME DEFAULT '2026-01-01',ADD updated_at DATETIME DEFAULT CURRENT_TIMESTAMP");
        jdbc.execute("CREATE TABLE nx_user_device_runtime(user_device_id BIGINT,online_status VARCHAR(32),paused_reason VARCHAR(64),active_task_no VARCHAR(96),updated_at DATETIME,is_deleted INT)");
        jdbc.execute("INSERT INTO nx_user_device_runtime VALUES(11,'ONLINE',NULL,NULL,NOW(),0)");
        jdbc.execute("CREATE TABLE nx_test_cloud_request(owner_id BIGINT,device_id BIGINT,state VARCHAR(32))");
        var config=new Configuration(new Environment("compute-v2-canonical-lifecycle",new SpringManagedTransactionFactory(),source));
        config.addMapper(CanonicalStateMapper.class);
        var mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(CanonicalStateMapper.class);
        return new ComputeV2DeviceLifecycleService(Optional.of(runtime),mapper,manager,mock(AuditLogService.class),outbox,
                new MockEnvironment().withProperty("nexion.compute-v2.device-ids","11").withProperty("nexion.compute-v2.deployment-scope","TEST"));
    }

    private ComputeV2Event lifecycle(ComputeV2Event e,Kind kind,TaskSnapshot snapshot) {
        return new ComputeV2Event(2,ComputeV2Event.eventId(e.taskNo(),e.generation(),kind),kind,"TEST","PRODUCTION","",7,11,"DEVICE-11",e.taskNo(),e.generation(),completed,snapshot,null,"test");
    }
    public ComputeV2SettlementService sink() { return sink; }
    public JdbcTemplate jdbc() { return jdbc; }
    private ffdd.opsconsole.device.compute.v2.ComputeV2Persistence.PersistenceResult persist(String id,List<ComputeV2Event> events) {
        Instant start=Instant.parse("2026-11-01T00:00:00Z");
        return sink.persistBatch(new ffdd.opsconsole.device.compute.v2.ComputeV2Persistence.SealedBatch(id,start,start.plusSeconds(60),events));
    }
    public ComputeV2Event completion(int id,long usdt,long uvt,Instant at) { return event(id,usdt,uvt,at); }
    private ComputeV2Event event(int id,long usdt,long uvt,Instant at) {
        String task=String.format("CTA-V2-%032d",id),hash="a".repeat(64);
        var t=new TaskSnapshot("request-"+id,"TASK-EM","BGE","EM","BGE-M3","UVEL TEST BGE-M3",1,60,60,60,60,0,
                at.minusSeconds(61),at.minusSeconds(60),at.plusSeconds(60),at.plusSeconds(60),usdt,usdt,BigDecimal.ONE,1,incarnation,
                new DeviceSnapshot("Device11","SERVER","stellarbox-s1","GPU",1,BigDecimal.ONE,8),hash,"b".repeat(64),"input/ref");
        var p=new Verified("c".repeat(64),hash,"d".repeat(64),at,at,"result/ref",12,uvt,uvt>0?"CLOUD_SHARE_DAILY:11:"+at.atZone(ZoneOffset.UTC).toLocalDate():null,at.atZone(ZoneOffset.UTC).toLocalDate());
        return new ComputeV2Event(2,ComputeV2Event.eventId(task,id,Kind.VERIFIED),Kind.VERIFIED,"TEST","PRODUCTION","",7,11,"DEVICE-11",task,id,at,t,p,null);
    }
    private void assertWallet(String usdt,String uvt,long revision) {
        var w=jdbc.queryForMap("SELECT usdt_available,nex_available,version,fund_revision FROM nx_user_wallet WHERE id=1");
        assertThat((BigDecimal)w.get("usdt_available")).isEqualByComparingTo(usdt);assertThat((BigDecimal)w.get("nex_available")).isEqualByComparingTo(uvt);
        assertThat(w.get("version")).isEqualTo(revision);assertThat(w.get("fund_revision")).isEqualTo(revision);
    }
    private void assertCount(String table,int expected) { assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).as(table).isEqualTo(expected); }
    private void verifyEvidence() throws Exception {
        new TransactionTemplate(manager).execute(status->{
            var original=sources.readFundEarnings(List.of(7L));
            var raw=journal.journal(incarnation,-1,10000);var ops=jdbc.queryForList("SELECT id,op_key operationKey,business_type businessType,phase,source_table sourceTable,source_key sourceKey,source_environment sourceEnvironment,ordered_events orderedEventsJson,source_snapshot sourceSnapshotJson,source_proof sourceProofJson FROM nx_support_fund_operation ORDER BY id");
            var parties=new ArrayList<Map<String,Object>>();var legs=new ArrayList<Map<String,Object>>();
            for(var op:ops){long id=((Number)op.get("id")).longValue();parties.addAll(journal.parties(id));legs.addAll(journal.legs(id));}
            var input=new SupportFundDeviceEvidence.Inputs(original,raw,ops,parties,legs,sources.timePrecisions());
            var reader=new ComputeV2FundEvidence(settlementMapper,json);var verified=new ArrayList<ComputeV2FundEvidence.Verified>();
            for(var op:ops)try{
                verified.add(reader.verify(op,json.readTree(op.get("sourceSnapshotJson").toString()),json.readTree(op.get("sourceProofJson").toString()),json.readValue(op.get("orderedEventsJson").toString(),new TypeReference<List<Event>>(){}),input));
            }catch(Exception e){throw new AssertionError("Independent batch owning-source evidence",e);}
            var legacy=reader.legacySources(Set.of(7L),input,verified);assertThat(legacy.receipts()).isEmpty();assertThat(legacy.tasks()).isEmpty();assertThat(legacy.ledgers()).isEmpty();assertThat(legacy.earningEvents()).isEmpty();
            return null;
        });
    }
    @SuppressWarnings("unchecked")
    private void verifyReadContract(Set<String> expectedAssets) {
        var reader=new ComputeV2ReadService(mock(AppTaskAssignmentService.class),Optional.empty(),
                new MockEnvironment().withProperty("nexion.compute-v2.deployment-scope","TEST"),readMapper,json);
        var records=(List<Map<String,Object>>)reader.settlements(7L,null,20).get("records");
        assertThat(records).extracting(r->r.get("asset")).containsExactlyInAnyOrderElementsOf(expectedAssets);
        for(var record:records){
            String batchNo=record.get("batchNo").toString();
            assertThat(batchNo).matches("[a-f0-9]{64}:(USDT|UVT)");
            var detail=reader.settlement(7L,batchNo);var items=(List<Map<String,Object>>)detail.get("items");
            assertThat(items).hasSize(Math.toIntExact(((Number)record.get("taskCount")).longValue()));
            BigDecimal sum=items.stream().map(i->new BigDecimal(i.get("amount").toString())).reduce(BigDecimal.ZERO,BigDecimal::add);
            assertThat(sum).isEqualByComparingTo(record.get("amount").toString());
            assertThat(detail.get("amount")).isEqualTo(record.get("amount"));
            assertThatThrownBy(()->reader.settlement(8L,batchNo)).isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class);
        }
        assertThat((List<?>)reader.settlements(8L,null,20).get("records")).isEmpty();
    }
    private void schema() throws Exception {
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(32),sandbox INT,is_deleted INT,created_at DATETIME)");
        jdbc.execute("INSERT INTO nx_user VALUES(7,'ACTIVE',0,0,'2026-01-01')");
        jdbc.execute("CREATE TABLE nx_user_device(id BIGINT PRIMARY KEY,user_id BIGINT,instance_no VARCHAR(96),source_environment VARCHAR(16),run_id VARCHAR(64),is_deleted INT,status VARCHAR(32),deactivated_at DATETIME,pending_deactivate INT)");
        jdbc.execute("INSERT INTO nx_user_device VALUES(11,7,'DEVICE-11','PRODUCTION','',0,'ACTIVE',NULL,0)");
        jdbc.execute("CREATE TABLE nx_user_wallet(id BIGINT PRIMARY KEY,user_id BIGINT UNIQUE,usdt_available DECIMAL(18,6) NOT NULL DEFAULT 0,nex_available DECIMAL(18,6) NOT NULL DEFAULT 0,pending_withdraw DECIMAL(18,6) NOT NULL DEFAULT 0,cregis_risk_held DECIMAL(18,6) NOT NULL DEFAULT 0,lifetime_earned DECIMAL(18,6) NOT NULL DEFAULT 0,version BIGINT NOT NULL DEFAULT 0,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,sandbox INT NOT NULL DEFAULT 0,is_deleted INT NOT NULL DEFAULT 0,fund_incarnation CHAR(36) NOT NULL,fund_revision BIGINT NOT NULL DEFAULT 0)");
        jdbc.update("INSERT INTO nx_user_wallet(id,user_id,fund_incarnation) VALUES(1,7,?)",incarnation);
        jdbc.execute("CREATE TABLE nx_config_item(config_key VARCHAR(128),config_value VARCHAR(128),status INT,is_deleted INT)");
        String schema=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:List.of("nx_compute_task","nx_compute_receipt","nx_wallet_ledger","nx_earning_event"))createTable(schema,table);
        String journalSql=Files.readString(Path.of("scripts/migrations/20261011_support_fund_journal.sql"));
        createTable(journalSql,"nx_support_wallet_journal");
        for(String name:List.of("nx_support_wallet_journal_bu_v1","nx_support_wallet_journal_au_v1")){
            var matcher=Pattern.compile("CREATE TRIGGER IF NOT EXISTS "+name+"\\b[\\s\\S]+?END\\$\\$").matcher(journalSql);
            assertThat(matcher.find()).isTrue();jdbc.execute(matcher.group().replace("END$$","END").replace(" FOLLOWS nx_wallet_earnings_debit_counter_v1",""));
        }
        String operationSql=Files.readString(Path.of("scripts/migrations/20261011_support_fund_operations.sql"));
        createTable(operationSql,"nx_support_fund_operation");createTable(operationSql,"nx_support_fund_operation_leg");
        createTable(Files.readString(Path.of("scripts/migrations/20261011_support_fund_operation_party.sql")),"nx_support_fund_operation_party");
        String v2=Files.readString(Path.of("scripts/migrations/20261101_compute_v2_settlement.sql")).replaceAll("(?m)^--.*$","");
        for(String statement:v2.split(";"))if(!statement.isBlank())jdbc.execute(statement);
    }
    private void createTable(String script,String table) {
        var match=Pattern.compile("CREATE TABLE IF NOT EXISTS "+table+"\\s*\\([\\s\\S]+?\\) ENGINE=InnoDB[^;]*;").matcher(script);
        assertThat(match.find()).as(table).isTrue();jdbc.execute(match.group());
    }
}
