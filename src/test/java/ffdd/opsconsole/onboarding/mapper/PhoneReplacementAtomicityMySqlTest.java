package ffdd.opsconsole.onboarding.mapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import ffdd.opsconsole.onboarding.application.PhoneReplacementPolicy;
import ffdd.opsconsole.onboarding.application.PhoneReplacementService;
import ffdd.opsconsole.onboarding.application.PhoneReplacementService.*;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.audit.AuditLogSanitizer;
import ffdd.opsconsole.shared.audit.ApplicationNameProperties;
import ffdd.opsconsole.shared.audit.AuditProperties;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.outbox.OutboxProperties;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL locks, mappings, A2 audit and A4 outbox; only policy lookups and injected failure are test doubles. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhoneReplacementAtomicityMySqlTest {
    static final long DAY=PhoneReplacementPolicy.DAY, START=1_800_000_000_000L;
    final AtomicLong now=new AtomicLong(START);
    final AtomicReference<String> failAudit=new AtomicReference<>();
    Connection admin;
    String schema;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    PhoneReplacementMapper mapper;
    PhoneReplacementService service;
    int passed;

    @BeforeAll void setup() throws Exception {
        String url=System.getenv("NEXION_TEST_DB_SERVER_URL");
        if(url==null || !url.matches("jdbc:mysql://(127[.]0[.]0[.]1|localhost):[0-9]+/([?].*)?"))
            throw new IllegalArgumentException("Dedicated loopback MySQL server URL without catalog required");
        String user=System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME","root"), password=System.getenv("NEXION_TEST_DB_PASSWORD");
        admin=DriverManager.getConnection(url,user,password);
        schema="nx_phone_domain_test_"+UUID.randomUUID().toString().replace("-","");
        admin.createStatement().execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");
        System.out.println("PHONE_DOMAIN_FIXTURE_CREATE="+schema);
        int query=url.indexOf('?');
        var ds=new DriverManagerDataSource(url.substring(0,query<0?url.length():query)+schema+(query<0?"":url.substring(query)),user,password);
        jdbc=new JdbcTemplate(ds);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setIsolationLevelName("ISOLATION_REPEATABLE_READ");
        String baseline=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:List.of("nx_config_item","nx_event_schema_registry","nx_event_schema_property","nx_audit_log","nx_event_outbox")) createTable(baseline,table);
        createTable(Files.readString(Path.of("scripts/migrations/20260810_ab_pending_closure.sql")),"nx_admin_event_lifecycle");
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(16),is_deleted TINYINT DEFAULT 0) ENGINE=InnoDB");
        createTable(Files.readString(Path.of("scripts/migrations/20260928_phone_calibration_policy.sql")),"nx_phone_binding");
        migrate();
        var config=new MybatisConfiguration();
        config.setEnvironment(new Environment("phone-domain",new SpringManagedTransactionFactory(),ds));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PhoneReplacementMapper.class);
        config.addMapper(AuditLogMapper.class);config.addMapper(EventOutboxMapper.class);
        var sqlSession=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        mapper=sqlSession.getMapper(PhoneReplacementMapper.class);
        A2RuntimePolicy a2=mock(A2RuntimePolicy.class);
        when(a2.retentionMonths()).thenReturn(13);when(a2.schemaVersion()).thenReturn("v3");
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        AuditLogService audit=spy(new AuditLogService(sqlSession.getMapper(AuditLogMapper.class),new AuditLogSanitizer(json),
                new ApplicationNameProperties(),new AuditProperties(),mock(AdminMapper.class),a2));
        doAnswer(call->{
            AuditLogWriteRequest r=call.getArgument(0);
            if(r.getAction().equals(failAudit.get())) throw new IllegalStateException("injected audit failure");
            return call.callRealMethod();
        }).when(audit).recordRequired(any());
        EventOutboxService outbox=new EventOutboxService(sqlSession.getMapper(EventOutboxMapper.class),json,
                new OutboxProperties(),mock(A4RuntimePolicyService.class));
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;}
            public Instant instant(){return Instant.ofEpochMilli(now.get());}};
        service=new PhoneReplacementService(mapper,clock,audit,outbox);
    }
    @BeforeEach void defaults(){
        now.set(START); failAudit.set(null);
        jdbc.update("UPDATE nx_config_item SET config_value='on' WHERE config_key=?",PhoneReplacementPolicy.PREFIX+"allowReplacement");
        jdbc.update("UPDATE nx_config_item SET config_value='0' WHERE config_key=?",PhoneReplacementPolicy.PREFIX+"minReplacementIntervalDays");
    }
    void createTable(String sql,String table){int start=sql.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");jdbc.execute(sql.substring(start,sql.indexOf(';',start)+1));}
    void migrate() throws Exception {
        String sql=Files.readString(Path.of("scripts/migrations/20261019_phone_replacement_domain.sql"));
        for(String part:sql.replaceAll("(?m)^--.*$","").split(";")) if(!part.isBlank()) jdbc.execute(part);
    }
    Account account(long id){jdbc.update("INSERT INTO nx_user(id,status) VALUES(?,'ACTIVE')",id);return Account.production(id);}
    <T> T transaction(Supplier<T> work){return tx.execute(status->work.get());}
    LockedAccount lock(Account a){return service.lockAccount(service.lockPolicy(),a,()->{});}
    Command command(LockedAccount l,String type,String key,Object input){return service.openOperation(l,new Actor("USER",l.account().userId()),type,key,input);}
    Receipt activate(Account a,String target,Long expected,String grant,String key){return transaction(()->activateLocked(lock(a),target,expected,grant,key));}
    Receipt activateLocked(LockedAccount l,String target,Long expected,String grant,String key){
        var input=new ActivationInput(expected,true,1L,true,target,"hw",grant);
        var c=command(l,"PHONE_ACTIVATE",key,input);
        if(c.replay()) return service.receipt(c.operation());
        var permit=service.prepareActivation(l,input,List.of());
        long version=l.binding()==null?1:l.binding().version()+1;
        long changed=l.binding()==null || permit.replacement()?now.get():l.binding().changedAt();
        jdbc.update("INSERT INTO nx_phone_binding(user_id,source_environment,run_id,installation_id,execution_installation_id,user_device_id,hardware_key,changed_at,version) "
                +"VALUES(?,'PRODUCTION','',?,?,?,'hw',FROM_UNIXTIME(?/1000),?) ON DUPLICATE KEY UPDATE installation_id=VALUES(installation_id),execution_installation_id=VALUES(execution_installation_id),changed_at=VALUES(changed_at),version=VALUES(version)",
                l.account().userId(),target,target,l.account().userId()*10,changed,version);
        return service.recordActivationSuccess(l,permit,c,new Binding(target,l.account().userId()*10,"hw",changed,version),Map.of("installationId",target,"bindingVersion",version));
    }
    Request request(Account a,String target,String key){return transaction(()->{
        var l=lock(a); var c=command(l,"PHONE_REQUEST_CREATE",key,Map.of("target",target));
        service.createRequest(l,target,l.binding().version(),"Please verify this replacement",c);
        return service.currentRequest(a,target).request();
    });}
    Receipt issue(Account a,Request r,String key){return transaction(()->{
        var l=lock(a);var input=new IssueInput(r.requestId(),r.bindingVersion(),true,true,"Verified support replacement");
        var c=service.openOperation(l,new Actor("ADMIN",900),"PHONE_GRANT_ISSUE",key,input);
        return service.issueGrant(l,input,new VerifiedEvidence("support:owned",a.userId(),900,true,Map.of("ticketOwner",a.userId())),c);
    });}
    int count(String table,Account a){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Integer.class,a.userId());}

    @Test void migrationAndVerifiedImportReplayPreservePolicyAndUnknownHistory() throws Exception {
        Account a=account(101);activate(a,"A",null,null,"first-101");
        transaction(()->{var l=lock(a);service.importVerifiedEvent(l,"AUDIT","verified-1",new Binding("X",1L,"h",1L,1),new Binding("A",1010L,"hw",2L,2),START-1,1);return null;});
        migrate();
        transaction(()->{var l=lock(a);service.importVerifiedEvent(l,"AUDIT","verified-1",new Binding("X",1L,"h",1L,1),new Binding("A",1010L,"hw",2L,2),START-1,1);return null;});
        assertThat(count("nx_phone_replacement_event",a)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT config_value FROM nx_config_item WHERE config_key=?",String.class,PhoneReplacementPolicy.PREFIX+"minReplacementIntervalDays")).isEqualTo("0");
        Account legacy=account(102);
        transaction(()->{lock(legacy);return null;});
        assertThat(mapper.state(legacy).completeFrom()).isNull();assertThat(mapper.state(legacy).allHistoryKnown()).isFalse();
        passed++;
    }
    @Test void twoRequestClocksAndExpiredOriginalIssueNeverRenewOrExposeEvidence(){
        Account a=account(201);activate(a,"A",null,null,"first-201");
        Request r=request(a,"B","request-201");
        now.addAndGet(23*DAY/24);Receipt original=issue(a,r,"issue-201");
        GrantSummary granted=(GrantSummary)original.currentResource();
        now.addAndGet(2*DAY/24);
        assertThat(service.currentRequest(a,"B").grant().status()).isEqualTo("ACTIVE");
        assertThat(service.currentRequest(a,"B").request().status()).isEqualTo("GRANTED");
        now.addAndGet(DAY);
        Receipt replay=issue(a,r,"issue-201");
        assertThat(replay.operationId()).isEqualTo(original.operationId());
        assertThat(((GrantSummary)replay.currentResource()).status()).isEqualTo("EXPIRED");
        assertThat(((GrantSummary)replay.currentResource()).expiresAt()).isEqualTo(granted.expiresAt());
        assertThat(count("nx_phone_replacement_grant",a)).isEqualTo(1);
        assertThat(service.userOperation(a,original.operationId())).isNull();
        passed++;
    }
    @Test void auditFailureRollsBackBindingEventGrantReceiptAndOutboxTogether(){
        Account a=account(301);activate(a,"A",null,null,"first-301");
        Request r=request(a,"B","request-301");String grant=((GrantSummary)issue(a,r,"issue-301").currentResource()).grantId();
        int auditBefore=jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log",Integer.class);
        int outboxBefore=jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox",Integer.class);
        failAudit.set("PHONE_REPLACEMENT_COMMAND");
        assertThatThrownBy(()->activate(a,"B",1L,grant,"replace-301")).hasMessage("injected audit failure");
        assertThat(mapper.binding(a).installationId()).isEqualTo("A");
        assertThat(mapper.grant(a,grant).status()).isEqualTo("ACTIVE");
        assertThat(count("nx_phone_replacement_event",a)).isZero();
        assertThat(service.operationByKey(a,new Actor("USER",a.userId()),"PHONE_ACTIVATE","replace-301")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log",Integer.class)).isEqualTo(auditBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox",Integer.class)).isEqualTo(outboxBefore);
        failAudit.set(null);Receipt success=activate(a,"B",1L,grant,"replace-301");
        now.addAndGet(2*DAY);
        assertThat(activate(a,"B",1L,grant,"replace-301").operationId()).isEqualTo(success.operationId());
        assertThat(count("nx_phone_replacement_event",a)).isEqualTo(1);
        passed++;
    }
    @Test void grantTerminalStateFollowsExpiryAndBindingChangeOrderWithoutRewritingTheIssueReceipt(){
        for(boolean expiresFirst:List.of(true,false)){
            now.set(START);
            Account a=account(expiresFirst?1201:1202);
            activate(a,"A",null,null,"first-"+a.userId());
            Request request=request(a,"B","request-"+a.userId());
            String issueKey="issue-"+a.userId();
            Receipt original=issue(a,request,issueKey);
            GrantSummary issued=(GrantSummary)original.currentResource();
            long changeAt=issued.expiresAt()+(expiresFirst?1:-1);
            now.set(changeAt);
            String expected=expiresFirst?"EXPIRED":"STALE";
            String event="admin.phone_replacement_grant_"+expected.toLowerCase(Locale.ROOT);
            if(expiresFirst){
                // A read derives expiry without materializing; a later write must preserve that earlier fact.
                assertThat(service.currentRequest(a,"B").grant().status()).isEqualTo("EXPIRED");
                assertThat(mapper.grant(a,issued.grantId()).status()).isEqualTo("ACTIVE");
                failAudit.set("PHONE_REPLACEMENT_COMMAND");
                assertThatThrownBy(()->activate(a,"C",1L,null,"change-"+a.userId())).hasMessage("injected audit failure");
                assertThat(mapper.binding(a).installationId()).isEqualTo("A");
                assertThat(mapper.grant(a,issued.grantId()).status()).isEqualTo("ACTIVE");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_name=? AND aggregate_id=?",
                        Integer.class,event,issued.grantId())).isZero();
                failAudit.set(null);
            }
            activate(a,"C",1L,null,"change-"+a.userId());
            now.set(issued.expiresAt()+DAY);
            activate(a,"C",2L,null,"same-binding-"+a.userId());
            Grant terminal=mapper.grant(a,issued.grantId());
            assertThat(terminal.status()).isEqualTo(expected);
            assertThat(terminal.terminalAt()).isEqualTo(expiresFirst?issued.expiresAt():changeAt);
            Receipt replay=issue(a,request,issueKey);
            assertThat(replay.operationId()).isEqualTo(original.operationId());
            assertThat(replay.originalResult()).isEqualTo(original.originalResult());
            assertThat(((GrantSummary)replay.currentResource()).status()).isEqualTo(expected);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_name=? AND aggregate_id=?",
                    Integer.class,event,issued.grantId())).isEqualTo(1);
            String other="admin.phone_replacement_grant_"+(expiresFirst?"stale":"expired");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_name=? AND aggregate_id=?",
                    Integer.class,other,issued.grantId())).isZero();
        }
        passed++;
    }
    @Test void concurrentFirstBindingsHaveExactlyOneWinner() throws Exception {
        Account a=account(401);
        List<Object> results=race(()->activate(a,"B",null,null,"first-B-401"),()->activate(a,"C",null,null,"first-C-401"));
        assertThat(results.stream().filter(Receipt.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(Throwable.class::isInstance)).hasSize(1);
        assertThat(mapper.binding(a).version()).isEqualTo(1);
        assertThat(count("nx_phone_replacement_event",a)).isZero();
        assertThat(count("nx_phone_replacement_operation",a)).isEqualTo(1);
        passed++;
    }
    @Test void frozenAccountAllowsAdministrativeReceiptRecoveryRevokeAndCloseButNoNewGrantOrAppActivation(){
        Account a=account(1301);
        activate(a,"A",null,null,"first-1301");
        Request b=request(a,"B","request-B-1301"), c=request(a,"C","request-C-1301");
        var facade=new ffdd.opsconsole.onboarding.facade.PhoneReplacementFacade(service);
        var evidence=new VerifiedEvidence("support:owned",a.userId(),900,true,Map.of("ticketOwner",a.userId()));
        var input=new IssueInput(b.requestId(),1,true,true,"Verified support replacement");
        Receipt original=transaction(()->facade.issueGrant(a,900,input,evidence,"issue-1301"));
        String grant=((GrantSummary)original.currentResource()).grantId();
        jdbc.update("UPDATE nx_user SET status='FROZEN' WHERE id=?",a.userId());

        Receipt replay=transaction(()->facade.issueGrant(a,900,input,evidence,"issue-1301"));
        assertThat(replay.operationId()).isEqualTo(original.operationId());
        assertThat(replay.originalResult()).isEqualTo(original.originalResult());
        var newInput=new IssueInput(c.requestId(),1,true,true,"Verified support replacement");
        assertThatThrownBy(()->transaction(()->facade.issueGrant(a,900,newInput,evidence,"new-issue-1301")))
                .hasMessage("USER_AUTH_REQUIRED");
        assertThatThrownBy(()->activate(a,"B",1L,grant,"frozen-app-1301")).hasMessage("USER_AUTH_REQUIRED");
        assertThat(count("nx_phone_replacement_grant",a)).isEqualTo(1);
        assertThat(mapper.grant(a,grant).status()).isEqualTo("ACTIVE");

        Receipt revoked=transaction(()->facade.revokeGrant(a,900,grant,"Frozen account safety revoke","revoke-1301"));
        Receipt revokeReplay=transaction(()->facade.revokeGrant(a,900,grant,"Frozen account safety revoke","revoke-1301"));
        assertThat(revokeReplay.operationId()).isEqualTo(revoked.operationId());
        assertThat(revokeReplay.originalResult()).isEqualTo(revoked.originalResult());
        assertThat(mapper.grant(a,grant).status()).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_name='admin.phone_replacement_grant_revoked' AND aggregate_id=?",
                Integer.class,grant)).isEqualTo(1);
        Receipt issueAfterRevoke=transaction(()->facade.issueGrant(a,900,input,evidence,"issue-1301"));
        assertThat(issueAfterRevoke.originalResult()).isEqualTo(original.originalResult());
        assertThat(((GrantSummary)issueAfterRevoke.currentResource()).status()).isEqualTo("REVOKED");
        transaction(()->facade.closeRequest(a,900,c.requestId(),"Frozen account request closed","close-1301"));
        assertThat(mapper.request(a,c.requestId()).status()).isEqualTo("CLOSED");
        assertThat(mapper.binding(a).installationId()).isEqualTo("A");
        passed++;
    }
    @Test void consumeAndRevokeCannotBothWin() throws Exception {
        Account a=account(501);activate(a,"A",null,null,"first-501");
        Request r=request(a,"B","request-501");String grant=((GrantSummary)issue(a,r,"issue-501").currentResource()).grantId();
        List<Object> results=race(()->activate(a,"B",1L,grant,"replace-501"),()->transaction(()->{
            var l=lock(a);var c=service.openOperation(l,new Actor("ADMIN",900),"PHONE_GRANT_REVOKE","revoke-501",Map.of("grant",grant));
            return service.revokeGrant(l,grant,"Verified revocation request",c);
        }));
        assertThat(results.stream().filter(Receipt.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(Throwable.class::isInstance)).hasSize(1);
        String status=mapper.grant(a,grant).status();assertThat(status).isIn("CONSUMED","REVOKED");
        assertThat(count("nx_phone_replacement_event",a)).isEqualTo("CONSUMED".equals(status)?1:0);
        assertThat(mapper.binding(a).installationId()).isEqualTo("CONSUMED".equals(status)?"B":"A");
        passed++;
    }
    @Test void duplicateRequestsAndCrossAccountAdminKeysAreSerialized() throws Exception {
        Account a=account(601),b=account(602);activate(a,"A",null,null,"first-601");activate(b,"A",null,null,"first-602");
        List<Object> results=race(()->request(a,"B","request-601-a"),()->request(a,"B","request-601-b"));
        assertThat(results.stream().filter(Request.class::isInstance)).hasSize(2);
        assertThat(count("nx_phone_replacement_request",a)).isEqualTo(1);
        Request ra=(Request)results.get(0),rb=request(b,"B","request-602");
        issue(a,ra,"admin-shared-601");
        assertThatThrownBy(()->issue(b,rb,"admin-shared-601")).hasMessage("IDEMPOTENCY_MISMATCH");
        assertThat(count("nx_phone_replacement_grant",b)).isZero();
        passed++;
    }
    @Test void preexistingRepeatableReadSnapshotCannotHideCommittedBindingHistoryOrReceipt() throws Exception {
        Account a=account(701);activate(a,"A",null,null,"first-701");
        CountDownLatch snap=new CountDownLatch(1),committed=new CountDownLatch(1);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<Receipt> stale=pool.submit(()->transaction(()->{
                assertThat(mapper.eventTimes(a,0,START)).isEmpty(); // Establish a real older RR snapshot.
                snap.countDown();await(committed);
                var l=lock(a);assertThat(l.binding().installationId()).isEqualTo("B");
                assertThat(service.eligibility(a,"C","hw",true,List.of()).usedCount()).isEqualTo(1);
                return activateLocked(l,"B",1L,null,"replace-701");
            }));
            assertThat(snap.await(10,TimeUnit.SECONDS)).isTrue();
            Receipt success=activate(a,"B",1L,null,"replace-701");committed.countDown();
            assertThat(stale.get(15,TimeUnit.SECONDS).operationId()).isEqualTo(success.operationId());
            assertThat(count("nx_phone_replacement_event",a)).isEqualTo(1);
        } finally {committed.countDown();pool.shutdownNow();}
        passed++;
    }
    @Test void partialWaiversKeepTheOtherKnownLimitAndNeverConsumeOnRejection(){
        for(boolean waiveCount:List.of(true,false)){
            jdbc.update("UPDATE nx_config_item SET config_value='0' WHERE config_key=?",PhoneReplacementPolicy.PREFIX+"minReplacementIntervalDays");
            Account a=account(waiveCount?1501:1502);
            activate(a,"A",null,null,"first-"+a.userId());
            activate(a,"B",1L,null,"second-"+a.userId());
            activate(a,"C",2L,null,"third-"+a.userId());
            jdbc.update("UPDATE nx_config_item SET config_value='1' WHERE config_key=?",PhoneReplacementPolicy.PREFIX+"minReplacementIntervalDays");
            Request request=request(a,"D","partial-request-"+a.userId());
            Receipt issued=transaction(()->{
                var locked=lock(a);
                var input=new IssueInput(request.requestId(),3L,waiveCount,!waiveCount,"Verified partial waiver");
                var command=service.openOperation(locked,new Actor("ADMIN",900),"PHONE_GRANT_ISSUE","partial-issue-"+a.userId(),input);
                return service.issueGrant(locked,input,new VerifiedEvidence("support:partial",a.userId(),900,true,Map.of("ticketOwner",a.userId())),command);
            });
            String grantId=((GrantSummary)issued.currentResource()).grantId();
            String blocked=waiveCount?"PHONE_REPLACEMENT_COOLDOWN":"PHONE_REPLACEMENT_LIMIT_REACHED";
            var eligibility=service.eligibility(a,"D","hw",true,List.of());
            assertThat(eligibility.usedCount()).isEqualTo(2);
            assertThat(eligibility.remainingCount()).isZero();
            assertThat(eligibility.blockedReasons()).containsExactly(blocked);
            var grantBefore=mapper.grant(a,grantId);var bindingBefore=mapper.binding(a);
            int operationsBefore=count("nx_phone_replacement_operation",a);
            assertThatThrownBy(()->activate(a,"D",3L,grantId,"partial-activate-"+a.userId())).hasMessage(blocked);
            assertThat(mapper.binding(a)).isEqualTo(bindingBefore);
            assertThat(mapper.grant(a,grantId)).isEqualTo(grantBefore);
            assertThat(mapper.grant(a,grantId).status()).isEqualTo("ACTIVE");
            assertThat(mapper.grant(a,grantId).consumedOperationId()).isNull();
            assertThat(count("nx_phone_replacement_event",a)).isEqualTo(2);
            assertThat(count("nx_phone_replacement_operation",a)).isEqualTo(operationsBefore);
        }
        passed++;
    }
    @Test void simultaneousGrantsForDifferentTargetsCannotStack() throws Exception {
        Account a=account(801);activate(a,"A",null,null,"first-801");
        Request b=request(a,"B","request-801-B"),c=request(a,"C","request-801-C");
        List<Object> results=race(()->issue(a,b,"grant-801-B"),()->issue(a,c,"grant-801-C"));
        assertThat(results.stream().filter(Receipt.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(Throwable.class::isInstance)).hasSize(1);
        Throwable rejected=(Throwable)results.stream().filter(Throwable.class::isInstance).findFirst().orElseThrow();
        assertThat(rejected).hasMessage("ACTIVE_GRANT_EXISTS");
        assertThat(count("nx_phone_replacement_grant",a)).isEqualTo(1);
        assertThat(mapper.activeGrants(a)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_phone_replacement_request WHERE user_id=? AND status='GRANTED'",Integer.class,a.userId())).isEqualTo(1);
        passed++;
    }
    @Test void missingCanonicalEventSchemaRollsBackTheWholeActivationThenRecovers(){
        Account a=account(901);activate(a,"A",null,null,"first-901");
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log",Integer.class);
        jdbc.update("UPDATE nx_event_schema_registry SET status='DISABLED' WHERE event_name='phone.replacement_succeeded'");
        try {
            assertThatThrownBy(()->activate(a,"B",1L,null,"replace-901")).hasMessage("A4_SCHEMA_NOT_REGISTERED");
            assertThat(mapper.binding(a).installationId()).isEqualTo("A");
            assertThat(count("nx_phone_replacement_event",a)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log",Integer.class)).isEqualTo(before);
            assertThat(service.operationByKey(a,new Actor("USER",a.userId()),"PHONE_ACTIVATE","replace-901")).isNull();
        } finally {jdbc.update("UPDATE nx_event_schema_registry SET status='ACTIVE' WHERE event_name='phone.replacement_succeeded'");}
        assertThat(activate(a,"B",1L,null,"replace-901").status()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_name='phone.replacement_succeeded' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.user_id'))=?",Integer.class,Long.toString(a.userId()))).isEqualTo(1);
        passed++;
    }

    @Test void manualCoverageCutoverReplayAndGapPreserveFactsWithoutInventingHistory() throws Exception {
        String sql=Files.readString(Path.of("scripts/manual_migrations/20261019_phone_history_coverage.sql"));
        int separator=sql.indexOf("DELIMITER $$");
        jdbc.execute(sql.substring(0,separator));
        String routine=sql.substring(separator+"DELIMITER $$".length(),sql.indexOf("DELIMITER ;"));
        for(String statement:routine.split("\\$\\$")) if(!statement.isBlank()) jdbc.execute(statement);
        Account a=account(1101);
        long t0=System.currentTimeMillis()-DAY, t1=t0+1000;
        now.set(t0);
        jdbc.update("INSERT INTO nx_phone_binding(user_id,source_environment,run_id,installation_id,execution_installation_id,user_device_id,hardware_key,changed_at,version) "
                +"VALUES(?,'PRODUCTION','','A','A',11010,'hw',FROM_UNIXTIME(?/1000),1)",a.userId(),t0-DAY);
        transaction(()->{lock(a);return null;});
        assertThat(service.eligibility(a,"B","hw",true,List.of()).historyCompleteFrom()).isNull();
        coverage(a,"cutover-1101","ESTABLISH",t0,1,"evidence:verified-T0");
        coverage(a,"cutover-1101","ESTABLISH",t0,1,"evidence:verified-T0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_phone_history_coverage_revision WHERE user_id=?",Integer.class,a.userId())).isEqualTo(1);
        assertThatThrownBy(()->coverage(a,"cutover-1101","ESTABLISH",t0,1,"evidence:changed"))
                .hasMessageContaining("PHONE_HISTORY_REVISION_INPUT_CHANGED");
        transaction(()->{var l=lock(a);service.importVerifiedEvent(l,"AUDIT","coverage-import",new Binding("X",1L,"h",t0-1,0),
                l.binding(),t0,1);return null;});
        migrate();
        transaction(()->{var l=lock(a);service.importVerifiedEvent(l,"AUDIT","coverage-import",new Binding("X",1L,"h",t0-1,0),
                l.binding(),t0,1);return null;});
        assertThat(count("nx_phone_replacement_event",a)).isEqualTo(1);
        assertThat(mapper.state(a).completeFrom()).isEqualTo(t0);
        assertThat(mapper.state(a).allHistoryKnown()).isFalse();
        now.set(t0+30*DAY-1);
        assertThat(service.eligibility(a,"B","hw",true,List.of()).usedCount()).isNull();
        now.incrementAndGet();
        assertThat(service.eligibility(a,"B","hw",true,List.of()).usedCount()).isZero();
        assertThatThrownBy(()->coverage(a,"later-without-gap","ESTABLISH",t1,0,"evidence:T1"))
                .hasMessageContaining("PHONE_HISTORY_INVALIDATE_GAP_FIRST");
        coverage(a,"gap-1101","INVALIDATE",null,0,"evidence:old-writer-gap");
        assertThat(mapper.state(a).completeFrom()).isNull();
        assertThat(mapper.state(a).changedAtVerified()).isFalse();
        assertThat(service.eligibility(a,"B","hw",true,List.of()).usedCount()).isNull();
        coverage(a,"restart-1101","ESTABLISH",t1,0,"evidence:T1");
        assertThat(mapper.state(a).completeFrom()).isEqualTo(t1);
        assertThat(count("nx_phone_replacement_event",a)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(previous_state,'$.completeFrom') FROM nx_phone_history_coverage_revision WHERE revision_key='gap-1101'",Long.class)).isEqualTo(t0);
        coverage(a,"cutover-1101","ESTABLISH",t0,1,"evidence:verified-T0");
        assertThat(mapper.state(a).completeFrom()).isEqualTo(t1); // old revision replay cannot resurrect pre-gap coverage
        passed++;
    }

    @Test void adminOriginalKeyLocatorIsScopedBeforeSelectingOneRow() {
        long user=account(1901).userId();
        var actor=new Actor("ADMIN",900);
        var scopes=List.of(Account.production(user),new Account(user,"SANDBOX","run-first"),new Account(user,"SANDBOX","run-second"));
        for(int i=0;i<scopes.size();i++) {
            var a=scopes.get(i);String id="PRO-scoped-"+i;
            transaction(()->{
                mapper.insertOperation(a,actor,id,"PHONE_GRANT_ISSUE","same-original-key","a".repeat(64),START);
                mapper.finishOperation(a,id,"SUCCEEDED",START+1,"{\"grantId\":\"original\"}",null,null,"audit-scoped");
                return null;
            });
        }
        for(int i=0;i<scopes.size();i++) {
            var a=scopes.get(i);
            var original=service.adminOperationContextByKey(900,"PHONE_GRANT_ISSUE","same-original-key",a.sourceEnvironment(),a.runId());
            assertThat(original.operationId()).isEqualTo("PRO-scoped-"+i);
            assertThat(service.operation(a,original.operationId()).status()).isEqualTo("SUCCEEDED");
        }
        assertThat(service.adminOperationContextByKey(900,"PHONE_GRANT_ISSUE","same-original-key","SANDBOX","missing-run")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_phone_replacement_operation WHERE idempotency_key='same-original-key'",Integer.class)).isEqualTo(3);
        passed++;
    }

    void coverage(Account a,String revision,String action,Long from,int verifyBindingTime,String evidence){
        jdbc.execute("{call apply_phone_history_coverage(?,?,?,?,?,?,?,?,?)}",
                (org.springframework.jdbc.core.CallableStatementCallback<Void>) call->{
                    call.setString(1,revision);call.setLong(2,a.userId());call.setString(3,"PRODUCTION");
                    call.setString(4,"");call.setString(5,action);call.setObject(6,from,Types.BIGINT);
                    call.setLong(7,1L);call.setInt(8,verifyBindingTime);call.setString(9,evidence);
                    call.execute();return null;
                });
    }
    List<Object> race(Supplier<?> left,Supplier<?> right) throws Exception {
        var pool=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
        try {
            List<Future<Object>> jobs=new ArrayList<>();
            for(Supplier<?> work:List.of(left,right)) jobs.add(pool.submit(()->{await(go);try{return work.get();}catch(RuntimeException e){return e;}}));
            go.countDown();return List.of(jobs.get(0).get(20,TimeUnit.SECONDS),jobs.get(1).get(20,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }
    static void await(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS))throw new IllegalStateException("latch timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    @AfterAll void cleanup() throws Exception {
        if(admin==null)return;
        try {
            if(passed==14){admin.createStatement().execute("DROP DATABASE `"+schema+"`");System.out.println("PHONE_DOMAIN_FIXTURE_DROP="+schema);}
            else System.out.println("PHONE_DOMAIN_FIXTURE_RETAINED="+schema);
        } finally {admin.close();}
    }
}
