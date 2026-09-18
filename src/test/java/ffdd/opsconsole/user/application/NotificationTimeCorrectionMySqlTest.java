package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.idempotency.*;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.mapper.*;
import ffdd.opsconsole.user.application.NotificationTimeCorrectionService.*;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses only a new owned schema on the expressly authorized loopback 18362 instance. */
@EnabledIfEnvironmentVariable(named="NEXION_C1_AUDIT_MYSQL",matches="true")
class NotificationTimeCorrectionMySqlTest {
    private static final String SCHEMA="bug3_correction_"+UUID.randomUUID().toString().replace("-","");
    private static final String SOURCE="a".repeat(32),BIZ="NOVA-welcome-"+SOURCE;
    private static final LocalDateTime OLD=LocalDateTime.of(2026,9,18,3,38,59), NEW=LocalDateTime.of(2026,9,18,11,38,58);
    private static final long TS=Instant.parse("2026-09-18T03:38:58.639Z").toEpochMilli();
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static DriverManagerDataSource source;
    private static JdbcTemplate jdbc;
    private static SqlSessionTemplate session;
    private NotificationTimeCorrectionService service;
    private NotificationTimeEvidenceService evidence;
    private NotificationTimeCorrectionMapper mapper;
    private AdminIdempotencyRecordMapper records,realRecords;
    private AuditLogService audit;
    private AdminIdempotencyService idempotency;

    private static DriverManagerDataSource ds(String schema) {
        if(!schema.isEmpty()&&!schema.equals(SCHEMA))throw new IllegalArgumentException("UNOWNED_SCHEMA");
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:18362/"+schema+"?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "root",System.getenv("NEXION_C1_AUDIT_MYSQL_PASSWORD"));
    }
    @BeforeAll static void create() throws Exception {
        new JdbcTemplate(ds("")).execute("CREATE DATABASE "+SCHEMA+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        source=ds(SCHEMA);jdbc=new JdbcTemplate(source);
        String schema=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:List.of("nx_notification","nx_event_outbox","nx_admin_idempotency_record","nx_audit_log"))createTable(schema,table);
        createTable(Files.readString(Path.of("scripts/migrations/20260727_i2_nova_business_event_gate.sql")),"nx_nova_business_event_receipt");
        var cfg=new MybatisConfiguration(new Environment("bug3-correction",new SpringManagedTransactionFactory(),source));
        var global=new com.baomidou.mybatisplus.core.config.GlobalConfig();
        global.setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig());
        global.setMetaObjectHandler(new ffdd.opsconsole.shared.config.MybatisMetaObjectHandler(Clock.system(DateTimeFormatConfig.BUSINESS_ZONE)));
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(cfg,global);
        cfg.setMapUnderscoreToCamelCase(true);
        for(Class<?> mapper:List.of(NotificationTimeCorrectionMapper.class,NotificationTimeEvidenceMapper.class,AdminIdempotencyRecordMapper.class,AuditLogMapper.class))cfg.addMapper(mapper);
        session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(cfg));
    }
    private static void createTable(String text,String table){int start=text.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");int end=text.indexOf(';',text.indexOf(") ENGINE=",start));if(start<0||end<start)throw new IllegalStateException("MISSING_DDL");jdbc.execute(text.substring(start,end+1));}
    @AfterAll static void cleanup(){if(SCHEMA.matches("bug3_correction_[a-f0-9]{32}"))new JdbcTemplate(ds("")).execute("DROP DATABASE IF EXISTS "+SCHEMA);}
    @SuppressWarnings("unchecked") private static <T>T proxy(T target){var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));return(T)factory.getProxy();}
    private static void authenticate(String actor){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,"",List.of()));}
    @BeforeEach void seed() throws Exception {
        for(String table:List.of("nx_audit_log","nx_admin_idempotency_record","nx_nova_business_event_receipt","nx_event_outbox","nx_notification"))jdbc.update("DELETE FROM "+table);
        authenticate("1");
        jdbc.update("INSERT INTO nx_notification(id,user_id,biz_no,type,title,body,push_status,read_flag,read_at,created_at,updated_at,pushed_at) VALUES(99,7,?,'NOVA_WELCOME','fixture','fixture','READ',1,'2026-09-18 04:30:00',?,'2026-09-18 04:30:00','2026-09-18 03:38:59')",BIZ,OLD);
        insertEvent(SOURCE,"USER_REGISTRATION","7","auth.register_completed",TS-28496,Map.of());
        insertEvent("b".repeat(32),"NOVA_NOTIFICATION","99","nova.push_sent",TS,Map.of("notification_id",99,"channel","welcome"));
        insertEvent("c".repeat(32),"NOTIFICATION","99","notification.delivered",TS+1,Map.of("notification_id",99,"kind","nova_welcome","campaign_id",BIZ));
        jdbc.update("INSERT INTO nx_nova_business_event_receipt(channel_key,source_event_id,event_name,status,notification_count) VALUES('welcome',?,'auth.register_completed','DELIVERED',1)",SOURCE);
        var users=mock(UserOpsRepository.class);when(users.findUserIdByLookupKey("U7")).thenReturn(Optional.of(7L));when(users.findUserIdByLookupKey("U8")).thenReturn(Optional.of(8L));
        var roles=mock(AdminOperatorRoleResolver.class);when(roles.resolveCode()).thenReturn("SUPER_ADMIN");
        evidence=proxy(new NotificationTimeEvidenceService(users,session.getMapper(NotificationTimeEvidenceMapper.class),roles,JSON,Clock.fixed(Instant.parse("2026-09-18T04:30:00Z"),DateTimeFormatConfig.BUSINESS_ZONE)));
        var policy=mock(A2RuntimePolicy.class);when(policy.schemaVersion()).thenReturn("fixture-v1");when(policy.retentionMonths()).thenReturn(12);
        audit=spy(new AuditLogService(session.getMapper(AuditLogMapper.class),new AuditLogSanitizer(JSON),new ApplicationNameProperties(),new AuditProperties(),mock(AdminMapper.class),policy));
        realRecords=session.getMapper(AdminIdempotencyRecordMapper.class);records=mock(AdminIdempotencyRecordMapper.class,org.mockito.AdditionalAnswers.delegatesTo(realRecords));
        var expiry=proxy(new AdminIdempotencyExpiryTransitionExecutor(records));
        var executor=proxy(new AdminIdempotencyTransactionExecutor(records,JSON,expiry));
        idempotency=new AdminIdempotencyService(executor,Clock.system(DateTimeFormatConfig.BUSINESS_ZONE));
        mapper=mock(NotificationTimeCorrectionMapper.class,org.mockito.AdditionalAnswers.delegatesTo(session.getMapper(NotificationTimeCorrectionMapper.class)));
        service=new NotificationTimeCorrectionService(users,roles,evidence,mapper,idempotency,audit);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private CorrectionRequest request(){return new CorrectionRequest(OLD,evidence.preview("U7",99L).getData().facts(),"根据三项权威事实校正此条通知展示时间");}
    private String created(){return jdbc.queryForObject("SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') FROM nx_notification WHERE id=99",String.class);}
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private void assertNoSuccess(){assertThat(created()).isEqualTo("2026-09-18 03:38:59");assertThat(count("nx_audit_log")).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin_idempotency_record WHERE status='SUCCEEDED'",Long.class)).isZero();}
    private void assertFailed(){assertNoSuccess();assertThat(jdbc.queryForObject("SELECT status FROM nx_admin_idempotency_record",String.class)).isEqualTo("FAILED");}

    @Test void actualTransactionChangesOnlyCreatedAtAndSameKeyReplaysOnce() {
        var before=jdbc.queryForMap("SELECT read_flag,read_at,push_status,pushed_at,updated_at FROM nx_notification WHERE id=99");
        var request=request();var result=service.correct("U7",99L,"once",request);
        assertThat(result.correctedCreatedAt()).isEqualTo(NEW);assertThat(service.correct("U7",99L,"once",request)).isEqualTo(result);
        assertThat(created()).isEqualTo("2026-09-18 11:38:58");assertThat(jdbc.queryForMap("SELECT read_flag,read_at,push_status,pushed_at,updated_at FROM nx_notification WHERE id=99")).isEqualTo(before);
        assertThat(count("nx_event_outbox")).isEqualTo(3);assertThat(count("nx_nova_business_event_receipt")).isEqualTo(1);assertThat(count("nx_audit_log")).isEqualTo(1);
        String detail=jdbc.queryForObject("SELECT detail_json FROM nx_audit_log",String.class);assertThat(detail).contains("previousCreatedAt","correctedCreatedAt",SOURCE,"b".repeat(32),"c".repeat(32),"DISPLAY_TIME_FROM_AUTHORITATIVE_DELIVERY");
        authenticate("2");assertThatThrownBy(()->service.correct("U7",99L,"once",request)).hasMessageContaining("SNAPSHOT_CHANGED");assertThat(count("nx_audit_log")).isEqualTo(1);
    }
    @Test void requiredAuditFailureAfterActualInsertRollsBackThenSameKeyCanRecover() {
        var request=request();doAnswer(call->{call.callRealMethod();throw new IllegalStateException("AFTER_AUDIT_INSERT");}).when(audit).recordRequired(any());
        assertThatThrownBy(()->service.correct("U7",99L,"audit-retry",request)).hasMessage("AFTER_AUDIT_INSERT");assertFailed();
        doCallRealMethod().when(audit).recordRequired(any());assertThat(service.correct("U7",99L,"audit-retry",request).correctedCreatedAt()).isEqualTo(NEW);assertThat(count("nx_audit_log")).isEqualTo(1);
    }
    @Test void failureAfterSucceededReceiptWriteRollsBackBeforeFailedRecordingAndRetryDoesNotDeadlock() {
        var request=request();doAnswer(call->{realRecords.markSucceeded(call.getArgument(0),call.getArgument(1));throw new IllegalStateException("AFTER_SUCCESS_RECEIPT_WRITE");}).when(records).markSucceeded(anyLong(),anyString());
        assertTimeoutPreemptively(Duration.ofSeconds(8),()->{authenticate("1");assertThatThrownBy(()->service.correct("U7",99L,"receipt-retry",request)).hasMessage("AFTER_SUCCESS_RECEIPT_WRITE");SecurityContextHolder.clearContext();});assertFailed();
        doAnswer(call->realRecords.markSucceeded(call.getArgument(0),call.getArgument(1))).when(records).markSucceeded(anyLong(),anyString());
        assertThat(service.correct("U7",99L,"receipt-retry",request).correctedCreatedAt()).isEqualTo(NEW);assertThat(count("nx_audit_log")).isEqualTo(1);
    }
    @Test void zeroCasAndCrossUserNeverWriteOrCacheSuccess() {
        var request=request();doReturn(0).when(mapper).correctCreatedAt(any(),any());
        assertThatThrownBy(()->service.correct("U7",99L,"cas-zero",request)).hasMessageContaining("SNAPSHOT_CHANGED");assertFailed();
        assertThatThrownBy(()->service.correct("U8",99L,"cross-user",request)).hasMessageContaining("NOTIFICATION_NOT_FOUND");assertNoSuccess();
        new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{var real=session.getMapper(NotificationTimeCorrectionMapper.class);var row=real.lockNotification(7,99);assertThat(real.correctCreatedAt(new NotificationTimeEvidenceMapper.NotificationRow(row.notificationId(),8L,row.bizNo(),row.type(),row.pushStatus(),row.createdAt()),NEW)).isZero();return null;});
    }
    @Test void changedOrDuplicateEvidenceCannotBeCorrected() throws Exception {
        var request=request();insertEvent("d".repeat(32),"NOVA_NOTIFICATION","99","nova.push_sent",TS,Map.of("notification_id",99,"channel","welcome"));
        assertThatThrownBy(()->service.correct("U7",99L,"duplicate",request)).hasMessageContaining("EVIDENCE_CHANGED");assertFailed();
    }
    @Test void boundedAggregateLockRejectsPhantomWhileOtherNotificationRemainsWritable() throws Exception {
        var request=request();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{assertThat(DataSourceUtils.getConnection(source).getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);locked.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();call.callRealMethod();return null;}).when(audit).recordRequired(any());
        jdbc.update("INSERT INTO nx_event_outbox(event_id,aggregate_type,aggregate_id,event_type,event_name,payload) VALUES('unrelated','ZZZ','999','fixture','fixture','{}')");
        var workers=Executors.newFixedThreadPool(1);
        try {
            Future<CorrectionView> result=workers.submit(()->{authenticate("1");try{return service.correct("U7",99L,"range-lock",request);}finally{SecurityContextHolder.clearContext();}});
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            try(Connection connection=source.getConnection()) {
                connection.createStatement().execute("SET SESSION innodb_lock_wait_timeout=1");
                // Existing aggregate index locks must not become an entire outbox-table lock.
                connection.createStatement().executeUpdate("UPDATE nx_event_outbox SET payload='{\"fixture\":true}' WHERE event_id='unrelated'");
                assertThatThrownBy(()->connection.createStatement().executeUpdate("INSERT INTO nx_event_outbox(event_id,aggregate_type,aggregate_id,event_type,event_name,payload) VALUES('duplicate','NOVA_NOTIFICATION','99','fixture','nova.push_sent','{}')")).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("Lock wait timeout");
            } finally {release.countDown();}
            assertThat(result.get(5,TimeUnit.SECONDS).correctedCreatedAt()).isEqualTo(NEW);
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test void ordinaryExecutionKeepsReadCommitted() {
        assertThat(idempotency.execute("ORDINARY", "rc", "hash", String.class, () -> {
            try { assertThat(DataSourceUtils.getConnection(source).getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED); }
            catch(java.sql.SQLException error){throw new IllegalStateException(error);}
            return "ok";
        })).isEqualTo("ok");
    }
    @Test void evidenceDeletionWaitsForCorrectionCommit() throws Exception {
        var request=request();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{locked.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();call.callRealMethod();return null;}).when(audit).recordRequired(any());
        var workers=Executors.newFixedThreadPool(1);
        try {
            var result=workers.submit(()->{authenticate("1");try{return service.correct("U7",99L,"delete-lock",request);}finally{SecurityContextHolder.clearContext();}});
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            try(Connection connection=source.getConnection()) {
                connection.createStatement().execute("SET SESSION innodb_lock_wait_timeout=1");
                assertThatThrownBy(()->connection.createStatement().executeUpdate("DELETE FROM nx_event_outbox WHERE event_id='"+SOURCE+"'")).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("Lock wait timeout");
            } finally {release.countDown();}
            assertThat(result.get(5,TimeUnit.SECONDS).correctedCreatedAt()).isEqualTo(NEW);
            assertThat(jdbc.update("DELETE FROM nx_event_outbox WHERE event_id=?",SOURCE)).isEqualTo(1);
            assertThat(count("nx_audit_log")).isEqualTo(1);
        } finally {release.countDown();workers.shutdownNow();}
    }
    @Test void differentKeysContendingForSameNotificationProduceOnlyOneSuccess() throws Exception {
        var request=request();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var secondStarted=new CountDownLatch(1);
        doAnswer(call->{locked.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();call.callRealMethod();return null;}).when(audit).recordRequired(any());
        var workers=Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(()->{authenticate("1");try{return service.correct("U7",99L,"race-first",request);}finally{SecurityContextHolder.clearContext();}});
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var second=workers.submit(()->{authenticate("1");secondStarted.countDown();try{return catchThrowable(()->service.correct("U7",99L,"race-second",request));}finally{SecurityContextHolder.clearContext();}});
            assertThat(secondStarted.await(5,TimeUnit.SECONDS)).isTrue();release.countDown();
            assertThat(first.get(5,TimeUnit.SECONDS).correctedCreatedAt()).isEqualTo(NEW);
            assertThat(second.get(5,TimeUnit.SECONDS)).hasMessageContaining("SNAPSHOT_CHANGED");
            assertThat(count("nx_audit_log")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin_idempotency_record WHERE status='SUCCEEDED'",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin_idempotency_record WHERE status='FAILED'",Long.class)).isEqualTo(1);
        } finally {release.countDown();workers.shutdownNow();}
    }
    private static void insertEvent(String id,String aggregate,String aggregateId,String name,long ts,Map<String,Object> fields)throws Exception{
        var payload=new LinkedHashMap<String,Object>(fields);payload.put("event_id",id);payload.put("event_name",name);payload.put("ts",ts);payload.put("user_id",7);payload.put("is_server_authoritative",true);
        jdbc.update("INSERT INTO nx_event_outbox(event_id,aggregate_type,aggregate_id,event_type,event_name,is_server_authoritative,payload,event_ts) VALUES(?,?,?,?,?,1,?,?)",id,aggregate,aggregateId,name,name,JSON.writeValueAsString(payload),LocalDateTime.ofInstant(Instant.ofEpochMilli(ts),DateTimeFormatConfig.BUSINESS_ZONE));
    }
}
