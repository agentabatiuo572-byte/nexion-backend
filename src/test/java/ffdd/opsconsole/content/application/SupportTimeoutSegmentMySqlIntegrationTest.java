package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import ffdd.opsconsole.content.domain.ConversationIdleCandidate;
import ffdd.opsconsole.content.infrastructure.ConversationEntity;
import ffdd.opsconsole.content.infrastructure.ConversationMessageEntity;
import ffdd.opsconsole.content.infrastructure.MybatisConversationRepository;
import ffdd.opsconsole.content.mapper.ConversationMapper;
import ffdd.opsconsole.content.mapper.ConversationMessageMapper;
import ffdd.opsconsole.content.mapper.ConversationTimeoutPolicyMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.config.MybatisMetaObjectHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** No bootstrap/DDL: fixture-only transactions against the existing exclusive analytics instance. */
@EnabledIfEnvironmentVariable(named="SUPPORT_CAPTURE_MYSQL_ENABLED",matches="true")
class SupportTimeoutSegmentMySqlIntegrationTest {
    private static final String SERVER_UUID="3556ddae-c1a1-11f1-8853-a40c6626953d";
    private static final String MARKER="support-timeout-fixture";
    private static final List<String> OWNED_CONVERSATION_TABLES=List.of("nx_conversation_message_receipt",
        "nx_conversation_transfer","nx_support_reply_cursor","nx_conversation_message",
        "nx_conversation_timeout_event","nx_conversation_timeout_segment","nx_conversation");
    private final List<Customer> customers=new ArrayList<>();
    private final Map<String,Long> conversations=new LinkedHashMap<>();
    private final SqlProbe probe=new SqlProbe();
    private HikariDataSource dataSource,outsideDataSource;
    private JdbcTemplate jdbc,outside;
    private TransactionTemplate transaction,outsideWriter;
    private ConversationMapper headers;
    private ConversationTimeoutPolicyMapper timeouts;
    private MybatisConversationRepository repository;
    private SupportOwnershipService unusedOwnership;
    private AuditLogService audit;
    private ApplicationEventPublisher events;
    private Clock clock;
    private LocalDateTime now;
    private Map<String,Long> beforeCounts;
    private Map<String,Object> beforeGlobal;

    @BeforeEach void existingExclusiveResourceAndRealMapperTransactions() throws Exception {
        var target=SupportRuntimeTarget.select(Map.of("SUPPORT_RUNTIME_TARGET","analytics-20261007"));
        String url=required("NEXION_DB_URL"),username=required("NEXION_DB_USERNAME"),password=required("NEXION_DB_PASSWORD");
        assertThat(url).startsWith(target.jdbcPrefix());assertThat(username).isEqualTo(target.username());
        dataSource=pool(url,username,password,3);outsideDataSource=pool(url,username,password,2);
        jdbc=new JdbcTemplate(dataSource);outside=new JdbcTemplate(outsideDataSource);
        var json=new ObjectMapper();Path proofPath=Path.of(required("SUPPORT_CAPTURE_OWNERSHIP")).toAbsolutePath().normalize();
        byte[] proofBytes=Files.readAllBytes(proofPath);var proof=json.readTree(proofBytes);
        assertThat(proof.path("databaseIdentity").path("serverUuid").asText()).isEqualTo(SERVER_UUID);
        var context=json.createObjectNode();context.put("schemaVersion",2).put("ownershipMode","EXCLUSIVE_ANALYTICS");
        context.set("resourceIdentity",proof.path("resourceIdentity").deepCopy());
        context.putObject("resourceOwnership").put("path",proofPath.toString())
            .put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(proofBytes)));
        SupportExclusiveRuntimeOwnership.requireActual(context,target,jdbc);
        SupportExclusiveRuntimeOwnership.requireActual(context,target,outside);
        assertThat(jdbc.queryForObject("SELECT @@server_uuid",String.class)).isEqualTo(SERVER_UUID);
        now=jdbc.queryForObject("SELECT NOW()",LocalDateTime.class).truncatedTo(ChronoUnit.SECONDS);
        clock=Clock.fixed(now.toInstant(ZoneOffset.UTC),ZoneOffset.UTC);
        var configuration=new MybatisConfiguration(new Environment("support-timeout-actual-mysql",
            new SpringManagedTransactionFactory(),dataSource));
        configuration.setMapUnderscoreToCamelCase(true);configuration.addInterceptor(probe);
        var global=new GlobalConfig();global.setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(new MybatisMetaObjectHandler(clock));GlobalConfigUtils.setGlobalConfig(configuration,global);
        configuration.addMapper(ConversationMapper.class);configuration.addMapper(ConversationMessageMapper.class);
        configuration.addMapper(ConversationTimeoutPolicyMapper.class);
        var template=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        headers=template.getMapper(ConversationMapper.class);timeouts=template.getMapper(ConversationTimeoutPolicyMapper.class);
        unusedOwnership=mock(SupportOwnershipService.class);
        repository=new MybatisConversationRepository(headers,template.getMapper(ConversationMessageMapper.class),unusedOwnership);
        audit=mock(AuditLogService.class);events=mock(ApplicationEventPublisher.class);
        transaction=rr(dataSource);outsideWriter=rr(outsideDataSource);
        outsideWriter.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        probe.dataSource=dataSource;probe.jdbc=jdbc;probe.owned=conversations;
        // Require the approved installed structure; neither missing tables nor GLOBAL are repaired here.
        for(String table:OWNED_CONVERSATION_TABLES)outside.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);
        beforeGlobal=outside.queryForMap("SELECT * FROM nx_conversation_timeout_policy WHERE policy_key='GLOBAL'");
        assertThat(timeouts.selectPolicy()).isNotNull();
        beforeCounts=outsideCounts();
    }

    @Test void segmentSnapshotThresholdDoesNotFollowLatestGlobalAndMissingSnapshotNeverWarnsOrCloses() {
        transaction.executeWithoutResult(tx->{
            tx.setRollbackOnly();
            long customer=customer();var initial=timeouts.selectPolicyForUpdate();assertThat(initial).isNotNull();
            assertThat(timeouts.updatePolicy(1,120,initial.version(),MARKER,"Fixture rollback policy",now)).isEqualTo(1);
            String old=conversation(customer,10,null,null),legacy=conversation(customer,130,null,null);
            assertThat(headers.insertTimeoutSnapshot(old)).isEqualTo(1);
            var captured=jdbc.queryForMap("SELECT policy_version,warn_minutes,close_minutes FROM nx_conversation_timeout_segment WHERE conversation_no=?",old);
            assertThat(captured).containsEntry("policy_version",initial.version()+1).containsEntry("warn_minutes",1).containsEntry("close_minutes",120);
            assertThat(timeouts.updatePolicy(1,2,initial.version()+1,MARKER,"Fixture rollback shorter policy",now)).isEqualTo(1);
            String fresh=conversation(customer,10,null,null);assertThat(headers.insertTimeoutSnapshot(fresh)).isEqualTo(1);
            assertThat(timeouts.selectDueCloseCandidates(now,100)).extracting(ConversationIdleCandidate::conversationNo)
                .contains(fresh).doesNotContain(old,legacy);
            assertThat(timeouts.selectDueWarningCandidates(now,100)).extracting(ConversationIdleCandidate::conversationNo)
                .contains(old).doesNotContain(legacy);
            assertThat(timeouts.lockCandidate(legacy)).isNull();
            assertThat(scheduler().sweep()).isEqualTo(new ConversationIdleTimeoutScheduler.SweepResult(1,1));
            assertThat(status(old)).isEqualTo("OPEN");assertThat(status(fresh)).isEqualTo("CLOSED");assertThat(status(legacy)).isEqualTo("OPEN");
            assertThat(jdbc.queryForMap("SELECT policy_version,warn_minutes,close_minutes FROM nx_conversation_timeout_segment WHERE conversation_no=?",old)).isEqualTo(captured);
            assertThat(eventCount(old,"WARN")).isEqualTo(1);assertThat(eventCount(old,"CLOSE")).isZero();
            assertThat(eventCount(legacy,"WARN")).isZero();assertThat(eventCount(legacy,"CLOSE")).isZero();
            assertThat(jdbc.queryForObject("SELECT policy_version FROM nx_conversation_timeout_event WHERE conversation_no=? AND event_type='WARN'",Long.class,old)).isEqualTo(initial.version()+1);
        });
    }

    @Test void pendingCustomerReplyProtectsBothWarningAndCloseAndHistoricalMissingSnapshot() {
        transaction.executeWithoutResult(tx->{
            tx.setRollbackOnly();long customer=customer();
            String warning=conversation(customer,10,1,120),closing=conversation(customer,10,1,5),legacy=conversation(customer,130,null,null);
            userMessage(jdbc,warning,now.minusMinutes(10));userMessage(jdbc,closing,now.minusMinutes(10));
            assertThat(timeouts.pendingRepliesCurrent(warning)).hasSize(1);assertThat(timeouts.pendingRepliesCurrent(closing)).hasSize(1);
            assertThat(timeouts.selectDueWarningCandidates(now,100)).isEmpty();assertThat(timeouts.selectDueCloseCandidates(now,100)).isEmpty();
            assertThat(scheduler().sweep()).isEqualTo(new ConversationIdleTimeoutScheduler.SweepResult(0,0));
            for(String no:List.of(warning,closing,legacy)) {
                assertThat(status(no)).isEqualTo("OPEN");assertThat(version(jdbc,no)).isZero();
                assertThat(eventCount(no,"WARN")).isZero();assertThat(eventCount(no,"CLOSE")).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no=? AND sender_type='system'",Long.class,no)).isZero();
            }
            verifyNoInteractions(audit,events);
        });
    }

    @Test void currentPendingReadAndHeaderCasSeeSameTimestampReplyCommittedAfterPinnedRepeatableRead() {
        String[] fixture=new String[1];transaction.executeWithoutResult(tx->fixture[0]=conversation(customer(),10,1,5));String no=fixture[0];
        transaction.executeWithoutResult(tx->{
            long readerConnection=connectionId(jdbc);assertThat(jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ");
            var candidate=timeouts.selectDueCloseCandidates(now,100).stream().filter(row->no.equals(row.conversationNo())).findFirst().orElseThrow();
            assertThat(ordinaryUserCount(jdbc,no)).isZero();assertThat(version(jdbc,no)).isZero();
            outsideWriter.executeWithoutResult(writer->{
                assertThat(connectionId(outside)).isNotEqualTo(readerConnection);
                assertThat(outside.update("UPDATE nx_conversation SET version=version+1 WHERE conversation_no=? AND user_id=?",no,conversations.get(no))).isEqualTo(1);
                userMessage(outside,no,candidate.lastActivityAt());
                assertThat(version(outside,no)).isEqualTo(1L);assertThat(ordinaryUserCount(outside,no)).isEqualTo(1);
            });
            assertThat(ordinaryUserCount(jdbc,no)).isZero();assertThat(version(jdbc,no)).isZero();
            var current=timeouts.lockCandidate(no);assertThat(current.version()).isEqualTo(candidate.version()+1);
            assertThat(current.lastActivityAt()).isEqualTo(candidate.lastActivityAt());
            assertThat(timeouts.pendingRepliesCurrent(no)).hasSize(1);
            assertThat(timeouts.closeIfStillIdle(no,candidate.lastActivityAt(),candidate.version(),"Must not close",now)).isZero();
        });
        assertThat(outside.queryForObject("SELECT status FROM nx_conversation WHERE conversation_no=?",String.class,no)).isEqualTo("OPEN");
        assertThat(version(outside,no)).isEqualTo(1L);assertThat(ordinaryUserCount(outside,no)).isEqualTo(1);
        assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_conversation_timeout_event WHERE conversation_no=?",Long.class,no)).isZero();
    }

    @Test void twoRealSchedulersPinSameCandidateButCloseVersionEventAndMessageOnlyOnce() throws Exception {
        String[] fixture=new String[1];transaction.executeWithoutResult(tx->fixture[0]=conversation(customer(),10,1,5));String no=fixture[0];
        probe.racingNo=no;probe.closeReads=new CountDownLatch(2);var workers=Executors.newFixedThreadPool(2);
        try {
            var first=scheduler();var second=scheduler();
            var one=workers.submit(()->transaction.execute(tx->first.sweep()));
            var two=workers.submit(()->transaction.execute(tx->second.sweep()));
            var a=one.get(25,TimeUnit.SECONDS);var b=two.get(25,TimeUnit.SECONDS);
            assertThat(a.warned()+b.warned()).isZero();assertThat(a.closed()+b.closed()).isEqualTo(1);
            assertThat(probe.raceConnections).hasSize(2);
            assertThat(outside.queryForObject("SELECT status FROM nx_conversation WHERE conversation_no=?",String.class,no)).isEqualTo("CLOSED");
            assertThat(version(outside,no)).isEqualTo(1L);
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_conversation_timeout_event WHERE conversation_no=? AND event_type='CLOSE'",Long.class,no)).isEqualTo(1);
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no=? AND sender_type='system'",Long.class,no)).isEqualTo(1);
            verify(audit,times(1)).recordRequired(any());verify(events,times(1)).publishEvent(any(Object.class));
        } finally {
            workers.shutdownNow();assertThat(workers.awaitTermination(15,TimeUnit.SECONDS)).isTrue();probe.racingNo=null;
        }
    }

    @Test void bothRepositoryTerminalCreatesRollBackSnapshotHeaderAndRealInsertedMessageOnFailure() {
        long[] fixture=new long[1];transaction.executeWithoutResult(tx->fixture[0]=customer());long customer=fixture[0];
        for(boolean userEntry:List.of(true,false)) {
            String no=number(customer);probe.failAfterMessageNo=no;probe.failureReached=false;
            assertThatThrownBy(()->transaction.executeWithoutResult(tx->{
                if(userEntry)repository.createUserConversation(no,customer,"support",MARKER,now);
                else repository.createConversationWithMessage(no,customer,"advisor",null,"Unassigned",MARKER,customer,"Fixture sender",now);
            })).hasStackTraceContaining("TIMEOUT_FIXTURE_FAILURE_AFTER_REAL_MESSAGE_INSERT");
            assertThat(probe.failureReached).isTrue();probe.failAfterMessageNo=null;
            for(String table:List.of("nx_conversation_timeout_segment","nx_conversation","nx_conversation_message"))
                assertThat(outside.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE conversation_no=?",Long.class,no)).isZero();
        }
        verifyNoInteractions(unusedOwnership);
    }

    @AfterEach void exactOwnedCleanupIndependentReadbackAndCloseBothPools() throws Throwable {
        Throwable failure=null;
        try {if(beforeCounts!=null)cleanupOwned();}catch(Throwable cleanupFailure){failure=cleanupFailure;}
        try {if(beforeCounts!=null)assertOutsideClean();}catch(Throwable readbackFailure){if(failure==null)failure=readbackFailure;else failure.addSuppressed(readbackFailure);}
        try {if(outsideDataSource!=null)outsideDataSource.close();}finally {if(dataSource!=null)dataSource.close();}
        if(failure!=null)throw failure;
    }

    private ConversationIdleTimeoutScheduler scheduler() {
        var environment=new StandardEnvironment();environment.setActiveProfiles("dev");
        return new ConversationIdleTimeoutScheduler(timeouts,audit,events,clock,new ProductionSupportPathGuard(environment,null));
    }
    private long customer() {
        String token=unique();assertThat(jdbc.update("INSERT INTO nx_user(country_code,phone,client_ip,password_hash,nickname,referral_code) VALUES('+0',?,'127.0.0.1','fixture',?,?)",token,MARKER,token)).isEqualTo(1);
        long id=jdbc.queryForObject("SELECT id FROM nx_user WHERE country_code='+0' AND phone=? AND nickname=?",Long.class,token,MARKER);
        customers.add(new Customer(id,token));return id;
    }
    private String number(long customer) {
        assertThat(customers.stream().map(Customer::id).toList()).contains(customer);String no=unique();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation WHERE conversation_no=?",Long.class,no)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_timeout_segment WHERE conversation_no=?",Long.class,no)).isZero();
        conversations.put(no,customer);return no;
    }
    private String conversation(long customer,int ageMinutes,Integer warn,Integer close) {
        String no=number(customer);var at=now.minusMinutes(ageMinutes);
        assertThat(jdbc.update("INSERT INTO nx_conversation(conversation_no,user_id,conversation_type,status,last_message,last_message_at,created_at,updated_at,version,is_deleted) VALUES(?,?,'support','OPEN',?,?,?,?,0,0)",no,customer,MARKER,at,at,at)).isEqualTo(1);
        if(warn!=null)assertThat(jdbc.update("INSERT INTO nx_conversation_timeout_segment(conversation_no,policy_version,warn_minutes,close_minutes) VALUES(?,1,?,?)",no,warn,close)).isEqualTo(1);
        return no;
    }
    private void userMessage(JdbcTemplate source,String no,LocalDateTime at) {
        assertThat(conversations).containsKey(no);
        assertThat(source.update("INSERT INTO nx_conversation_message(conversation_id,conversation_no,sender_id,sender_type,sender_name,content,created_at,updated_at,is_deleted) SELECT id,conversation_no,user_id,'user',?, ?,?,?,0 FROM nx_conversation WHERE conversation_no=? AND user_id=?",MARKER,MARKER,at,at,no,conversations.get(no))).isEqualTo(1);
    }
    private String status(String no){return jdbc.queryForObject("SELECT status FROM nx_conversation WHERE conversation_no=?",String.class,no);}
    private long eventCount(String no,String type){return jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_timeout_event WHERE conversation_no=? AND event_type=?",Long.class,no,type);}
    private long ordinaryUserCount(JdbcTemplate source,String no){return source.queryForObject("SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no=? AND sender_type='user'",Long.class,no);}
    private long version(JdbcTemplate source,String no){return source.queryForObject("SELECT version FROM nx_conversation WHERE conversation_no=?",Long.class,no);}
    private long connectionId(JdbcTemplate source){return source.queryForObject("SELECT CONNECTION_ID()",Long.class);}
    private void cleanupOwned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        outsideWriter.executeWithoutResult(tx->{
            for(var customer:customers) {
                var row=outside.queryForList("SELECT nickname,phone FROM nx_user WHERE id=?",customer.id());
                if(!row.isEmpty())assertThat(row).singleElement().satisfies(value->{assertThat(value.get("nickname")).isEqualTo(MARKER);assertThat(value.get("phone")).isEqualTo(customer.token());});
            }
            for(var entry:conversations.entrySet()) {
                String no=entry.getKey();var rows=outside.queryForList("SELECT id,user_id FROM nx_conversation WHERE conversation_no=?",no);
                if(!rows.isEmpty()) {
                    assertThat(rows).singleElement().satisfies(row->assertThat(((Number)row.get("user_id")).longValue()).isEqualTo(entry.getValue()));
                    long headerId=((Number)rows.get(0).get("id")).longValue();
                    assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no=? AND conversation_id<>?",Long.class,no,headerId)).isZero();
                    outside.update("DELETE FROM nx_conversation_message WHERE conversation_no=? AND conversation_id=?",no,headerId);
                } else assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no=?",Long.class,no)).isZero();
                for(String table:List.of("nx_conversation_message_receipt","nx_conversation_transfer","nx_support_reply_cursor"))
                    assertThat(outside.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE conversation_no=?",Long.class,no)).isZero();
                outside.update("DELETE FROM nx_conversation_timeout_event WHERE conversation_no=?",no);
                outside.update("DELETE FROM nx_conversation_timeout_segment WHERE conversation_no=?",no);
                outside.update("DELETE FROM nx_conversation WHERE conversation_no=? AND user_id=?",no,entry.getValue());
            }
            for(var customer:customers) {
                assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_conversation WHERE user_id=?",Long.class,customer.id())).isZero();
                int count=outside.update("DELETE FROM nx_user WHERE id=? AND nickname=? AND phone=?",customer.id(),MARKER,customer.token());assertThat(count).isBetween(0,1);
            }
        });
    }
    private Map<String,Long> outsideCounts() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return outside.execute((ConnectionCallback<Map<String,Long>>)connection->{
            var counts=new LinkedHashMap<String,Long>();
            try(var sql=connection.createStatement();var tables=sql.executeQuery("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
                var names=new ArrayList<String>();while(tables.next())names.add(tables.getString(1));
                for(String table:names){assertThat(table).matches("[A-Za-z0-9_]+");try(var count=connection.createStatement();var rows=count.executeQuery("SELECT COUNT(*) FROM `"+table+"`")){assertThat(rows.next()).isTrue();counts.put(table,rows.getLong(1));}}
            }
            assertThat(counts).containsKeys("nx_user","nx_conversation_timeout_policy","nx_conversation_timeout_segment");return counts;
        });
    }
    private void assertOutsideClean() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        outside.execute((ConnectionCallback<Void>)connection->{
            for(var customer:customers)try(var sql=connection.prepareStatement("SELECT COUNT(*) FROM nx_user WHERE id=?")){sql.setLong(1,customer.id());try(var rows=sql.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isZero();}}
            for(String no:conversations.keySet())for(String table:OWNED_CONVERSATION_TABLES)
                try(var sql=connection.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE conversation_no=?")){sql.setString(1,no);try(var rows=sql.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isZero();}}
            for(var entry:beforeCounts.entrySet())try(var sql=connection.createStatement();var rows=sql.executeQuery("SELECT COUNT(*) FROM `"+entry.getKey()+"`")){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).as(entry.getKey()).isEqualTo(entry.getValue());}
            return null;
        });
        assertThat(outsideCounts()).isEqualTo(beforeCounts);
        assertThat(outside.queryForMap("SELECT * FROM nx_conversation_timeout_policy WHERE policy_key='GLOBAL'")).isEqualTo(beforeGlobal);
    }
    private static HikariDataSource pool(String url,String username,String password,int size) {
        var source=new HikariDataSource();source.setJdbcUrl(url);source.setUsername(username);source.setPassword(password);
        source.setMaximumPoolSize(size);source.setMinimumIdle(0);source.setConnectionTimeout(10000);return source;
    }
    private static TransactionTemplate rr(HikariDataSource source) {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(30);return tx;
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing timeout acceptance setting: "+name);return value;}
    private static String unique(){return "ST"+UUID.randomUUID().toString().replace("-","").substring(0,24);}
    private record Customer(long id,String token){}

    @Intercepts({@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}),
        @Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class})})
    private static final class SqlProbe implements Interceptor {
        private HikariDataSource dataSource;private JdbcTemplate jdbc;private Map<String,Long> owned;
        private volatile String racingNo,failAfterMessageNo;private volatile CountDownLatch closeReads;private boolean failureReached;
        private final Set<Long> raceConnections=ConcurrentHashMap.newKeySet();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var statement=(MappedStatement)invocation.getArgs()[0];String id=statement.getId();Object parameter=invocation.getArgs()[1];
            boolean timeout=id.startsWith(ConversationTimeoutPolicyMapper.class.getName()+".");
            boolean mutation=invocation.getMethod().getName().equals("update");
            if(timeout && (id.endsWith(".ensurePolicyTable")||id.endsWith(".ensureEventTable")||id.endsWith(".insertDefaultPolicy")))throw new IllegalStateException("Native timeout test cannot install or seed GLOBAL");
            if(mutation || (timeout&&!id.endsWith(".selectPolicy"))) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(dataSource);assertThat(holder).isNotNull();
                var connection=((Executor)invocation.getTarget()).getTransaction().getConnection();assertThat(connection).isSameAs(holder.getConnection());
                try(var sql=connection.createStatement();var row=sql.executeQuery("SELECT @@transaction_isolation")){assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("REPEATABLE-READ");}
            }
            if(parameter instanceof Map<?,?> map && map.containsKey("conversationNo") && map.get("conversationNo") instanceof String no)assertThat(owned).containsKey(no);
            Object entity=parameter instanceof Map<?,?> map&&map.containsKey("et")?map.get("et"):parameter;
            if(entity instanceof ConversationEntity header)assertThat(owned).containsEntry(header.getConversationNo(),header.getUserId());
            if(entity instanceof ConversationMessageEntity message)assertThat(owned).containsKey(message.getConversationNo());
            Object result=invocation.proceed();
            if(id.endsWith(".selectDueWarningCandidates")||id.endsWith(".selectDueCloseCandidates")) {
                // Keep the exact production SQL/results; fail before the scheduler can touch any foreign candidate.
                var candidates=(List<?>)result;for(Object row:candidates)assertThat(owned).containsKey(((ConversationIdleCandidate)row).conversationNo());
                if(id.endsWith(".selectDueCloseCandidates")&&racingNo!=null) {
                    assertThat(candidates).anyMatch(row->racingNo.equals(((ConversationIdleCandidate)row).conversationNo()));
                    raceConnections.add(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));closeReads.countDown();
                    assertThat(closeReads.await(10,TimeUnit.SECONDS)).isTrue();
                }
            }
            if(id.equals(ConversationMessageMapper.class.getName()+".insert")&&entity instanceof ConversationMessageEntity message
                    &&message.getConversationNo().equals(failAfterMessageNo)) {
                assertThat(result).isEqualTo(1);
                for(String table:List.of("nx_conversation_timeout_segment","nx_conversation","nx_conversation_message"))
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE conversation_no=?",Long.class,message.getConversationNo())).isEqualTo(1);
                failureReached=true;throw new IllegalStateException("TIMEOUT_FIXTURE_FAILURE_AFTER_REAL_MESSAGE_INSERT");
            }
            return result;
        }
    }
}
