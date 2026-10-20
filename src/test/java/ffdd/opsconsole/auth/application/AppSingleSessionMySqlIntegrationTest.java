package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import ffdd.opsconsole.auth.mapper.AppLoginAttemptMapper;
import ffdd.opsconsole.common.boundary.PhoneExecutionSessionContract;
import ffdd.opsconsole.growth.application.WheelSandboxProfile;
import ffdd.opsconsole.onboarding.application.OnboardingCalibrationService;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.config.MybatisMetaObjectHandler;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.*;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Production session + phone mappers and services, actual Spring/MySQL transactions.
 * Real audit/outbox services enforce the migrated canonical schema in the same transaction. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppSingleSessionMySqlIntegrationTest {
    Connection admin; String schema; JdbcTemplate jdbc; TransactionTemplate tx;
    AuthSessionMapper sessions; AppLoginAttemptMapper attempts; OnboardingCalibrationService phone;
    ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper tasks;
    AppSingleSessionService service; UserSessionRevocationService revocations; AuditLogService audit; EventOutboxService outbox;
    PlatformConfigFacade config; UserOpsMapper users; MockEnvironment environment;
    final UserAccountBlocklistVerifier blocklist=mock(UserAccountBlocklistVerifier.class);
    final JwtTokenProvider tokens=new JwtTokenProvider(new JwtProperties());
    final AtomicReference<String> failure=new AtomicReference<>();
    boolean passed, securityPassed, securityRollbackPassed;

    @BeforeAll void setup() throws Exception {
        String url=System.getenv("NEXION_TEST_DB_SERVER_URL");
        if (url==null || !url.matches("jdbc:mysql://(127[.]0[.]0[.]1|localhost|\\[::1\\]):[0-9]{1,5}/([?].*)?"))
            throw new IllegalArgumentException("Explicit loopback MySQL server URL without a catalog required");
        int port=java.net.URI.create(url.substring("jdbc:".length())).getPort();
        String username=System.getenv("NEXION_TEST_DB_USERNAME");
        String password=System.getenv("NEXION_TEST_DB_PASSWORD");
        String expectedDirectory=System.getenv("NEXION_TEST_DB_EXPECTED_DATADIR");
        if(port<1 || port>65535 || username==null || username.isBlank() || password==null || password.isBlank()
                || expectedDirectory==null || expectedDirectory.isBlank() || !Path.of(expectedDirectory).isAbsolute())
            throw new IllegalArgumentException("Explicit test credentials and verified absolute MySQL data directory required");
        admin=DriverManager.getConnection(url,username,password);
        try(var statement=admin.createStatement();var server=statement.executeQuery("SELECT @@port,@@datadir")){
            if(!server.next() || server.getInt(1)!=port
                    || !Path.of(server.getString(2)).normalize().equals(Path.of(expectedDirectory).normalize()))
                throw new IllegalArgumentException("MySQL server does not match the verified test target");
        }
        schema="nx_app_single_session_"+UUID.randomUUID().toString().replace("-","");
        try(var statement=admin.createStatement()){statement.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");}
        System.out.println("APP_SESSION_FIXTURE_CREATE="+schema);
        int q=url.indexOf('?');
        var ds=new DriverManagerDataSource(url.substring(0,q<0?url.length():q)+schema+(q<0?"":url.substring(q)),username,password);
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        String baseline=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:List.of("nx_user_session","nx_phone_binding","nx_user_device_runtime","nx_event_schema_registry","nx_event_schema_property","nx_audit_log","nx_event_outbox")) {
            int start=baseline.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");
            jdbc.execute(baseline.substring(start,baseline.indexOf(';',start)+1));
        }
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(32),sandbox INT,is_deleted INT)");
        jdbc.execute("CREATE TABLE nx_user_device(id BIGINT PRIMARY KEY,user_id BIGINT,source_channel VARCHAR(32),source_environment VARCHAR(32),run_id VARCHAR(96),device_type VARCHAR(32),is_deleted INT)");
        jdbc.execute("CREATE TABLE nx_compute_task(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,user_device_id BIGINT,task_no VARCHAR(96),source_environment VARCHAR(32),status VARCHAR(32),completion_nonce VARCHAR(128),completed_at DATETIME,proof_consumed_at DATETIME,last_error VARCHAR(96),updated_at DATETIME,is_deleted INT)");
        String lifecycle=Files.readString(Path.of("scripts/migrations/20260810_ab_pending_closure.sql"));
        int lifecycleStart=lifecycle.indexOf("CREATE TABLE IF NOT EXISTS nx_admin_event_lifecycle (");
        jdbc.execute(lifecycle.substring(lifecycleStart,lifecycle.indexOf(';',lifecycleStart)+1));
        migrate(); migrate();
        var configuration=new MybatisConfiguration(new Environment("app-single-session",new SpringManagedTransactionFactory(),ds));
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.setGlobalConfig(configuration,new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig())
                .setMetaObjectHandler(new MybatisMetaObjectHandler(Clock.system(DateTimeFormatConfig.BUSINESS_ZONE))));
        configuration.addMapper(AuthSessionMapper.class);configuration.addMapper(AppLoginAttemptMapper.class);
        configuration.addMapper(OnboardingCalibrationMapper.class);
        configuration.addMapper(ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.class);
        configuration.addMapper(ffdd.opsconsole.shared.audit.mapper.AuditLogMapper.class);
        configuration.addMapper(ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper.class);
        configuration.addMapper(ffdd.opsconsole.onboarding.mapper.PhoneInstallationMapper.class);
        var sql=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        sessions=sql.getMapper(AuthSessionMapper.class);attempts=sql.getMapper(AppLoginAttemptMapper.class);
        tasks=sql.getMapper(ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.class);
        config=mock(PlatformConfigFacade.class);when(config.activeValue(anyString())).thenReturn(Optional.empty());
        users=mock(UserOpsMapper.class);when(users.findLoginUserForUpdate(any())).thenAnswer(call->{
            UserEntity user=new UserEntity();user.setId(((Number)call.getArgument(0)).longValue());
            user.setStatus("ACTIVE");user.setSandbox(0);user.setIsDeleted(0);return user;});
        environment=new MockEnvironment();environment.setActiveProfiles("dev");
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var a2=mock(ffdd.opsconsole.platform.application.A2RuntimePolicy.class);
        when(a2.retentionMonths()).thenReturn(13);when(a2.schemaVersion()).thenReturn("v3");
        audit=spy(new AuditLogService(sql.getMapper(ffdd.opsconsole.shared.audit.mapper.AuditLogMapper.class),
                new ffdd.opsconsole.shared.audit.AuditLogSanitizer(json),
                new ffdd.opsconsole.shared.audit.ApplicationNameProperties(),new ffdd.opsconsole.shared.audit.AuditProperties(),
                mock(ffdd.opsconsole.auth.mapper.AdminMapper.class),a2));
        outbox=spy(new EventOutboxService(sql.getMapper(ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper.class),json,
                new ffdd.opsconsole.shared.outbox.OutboxProperties(),mock(ffdd.opsconsole.platform.application.A4RuntimePolicyService.class)));
        doAnswer(call->{call.callRealMethod();
            if("audit".equals(failure.get()))throw new IllegalStateException("injected-audit");return null;
        }).when(audit).recordRequired(any());
        doAnswer(call->{Object result=call.callRealMethod();
            if("outbox".equals(failure.get()))throw new IllegalStateException("injected-outbox");return result;
        }).when(outbox).publish(anyString(),anyString(),anyString(),any());
        phone=new OnboardingCalibrationService(sql.getMapper(OnboardingCalibrationMapper.class),mock(WheelSandboxProfile.class),
                environment,audit,outbox,config,Clock.systemUTC(),
                new ffdd.opsconsole.onboarding.application.PhoneInstallationService(
                        sql.getMapper(ffdd.opsconsole.onboarding.mapper.PhoneInstallationMapper.class),
                        new CurrentAppSessionService(sessions,config,blocklist,users,environment)),
                mock(ffdd.opsconsole.onboarding.application.PhoneReplacementService.class),
                mock(ffdd.opsconsole.common.boundary.PhoneActivationCapacityContract.class),
                mock(ffdd.opsconsole.common.boundary.UserTermsAcceptanceContract.class));
        service=create(sessions,phone);
        var revocationProxy=new ProxyFactory(new UserSessionRevocationService(sessions,phone));
        revocationProxy.setProxyTargetClass(true);
        revocationProxy.addAdvice(new TransactionInterceptor(tx.getTransactionManager(),new AnnotationTransactionAttributeSource()));
        revocations=(UserSessionRevocationService)revocationProxy.getProxy();
    }

    void migrate() throws Exception {
        String script=Files.readString(Path.of("scripts/migrations/20261019_app_single_session.sql"));
        String sql=script.lines().filter(line->!line.stripLeading().startsWith("--")).reduce("",(a,b)->a+"\n"+b);
        try(var connection=jdbc.getDataSource().getConnection();var statement=connection.createStatement()){
            for(String command:sql.split(";"))if(!command.isBlank())statement.execute(command);
        }
    }
    AppSingleSessionService create(AuthSessionMapper mapper,PhoneExecutionSessionContract runtime){
        return new AppSingleSessionService(mapper,attempts,tokens,runtime,audit,outbox,config,users,blocklist,environment);
    }
    AppLoginContext context(String installation,boolean execution,AppSessionSurface group){
        return new AppLoginContext(group,installation,execution,"attempt_"+UUID.randomUUID().toString().replace("-",""),
                UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""),"PASSWORD","synthetic-account");
    }
    AppSingleSessionService.Issued issue(long id,AppLoginContext context){return tx.execute(status->service.issue(id,context,"local",30));}
    AppSingleSessionService.Issued recover(AppLoginContext context){return tx.execute(status->service.recover(context));}
    void seed(long id){
        long device=id*10;
        jdbc.update("INSERT INTO nx_user VALUES(?,'ACTIVE',0,0)",id);
        jdbc.update("INSERT INTO nx_user_device VALUES(?,?,'ONBOARDING','PRODUCTION','','PHONE',0),(?,?,'STORE','PRODUCTION','','BOX',0)",device,id,device+1,id);
        jdbc.update("INSERT INTO nx_phone_binding VALUES(?,'PRODUCTION','','phone-a','phone-a',?,'hardware',NOW(6),1)",id,device);
        jdbc.update("INSERT INTO nx_user_device_runtime(user_device_id,online_status,battery_level,network_reachable,heartbeat_at,is_deleted) VALUES(?,'ONLINE',80,1,NOW(),0)",device);
        jdbc.update("INSERT INTO nx_compute_task(user_id,user_device_id,task_no,source_environment,status,is_deleted) VALUES(?,?,?,'PRODUCTION','RUNNING',0),(?,?,?,'PRODUCTION','RUNNING',0)",id,device,"phone-"+id,id,device+1,"box-"+id);
    }
    long version(long id){return jdbc.queryForObject("SELECT app_session_version FROM nx_app_session_state WHERE user_id=?",Long.class,id);}
    String task(long id){return jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no=?",String.class,"phone-"+id);}
    int active(long id){return jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_session WHERE user_id=? AND session_group='APP' AND revoked_at IS NULL AND session_ended_reason IS NULL",Integer.class,id);}
    int evidence(long id){return jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM nx_audit_log WHERE user_id=?)+(SELECT COUNT(*) FROM nx_event_outbox WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.user_id'))=?)",Integer.class,id,String.valueOf(id));}

    @Test void productionTransactionsCoverTakeoverReplayConcurrencyAndFailureRollback() throws Exception {
        seed(1);var a=context("phone-a",true,AppSessionSurface.APP);var initial=issue(1,a);
        var h1=issue(1,context("browser-1",false,AppSessionSurface.H5));
        var h2=issue(1,context("browser-2",false,AppSessionSurface.H5));
        assertThat(version(1)).isEqualTo(1);assertThat(task(1)).isEqualTo("RUNNING");
        issue(1,context("phone-a",true,AppSessionSurface.APP));
        assertThat(task(1)).isEqualTo("RUNNING");
        var b=context("phone-b",true,AppSessionSurface.APP);var takeover=issue(1,b);
        assertThat(active(1)).isEqualTo(1);assertThat(task(1)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no='box-1'",String.class)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT installation_id FROM nx_phone_binding WHERE user_id=1",String.class)).isEqualTo("phone-a");
        assertThat(jdbc.queryForObject("SELECT version FROM nx_phone_binding WHERE user_id=1",Long.class)).isEqualTo(1);
        tx.executeWithoutResult(status->{
            assertThat(sessions.countFixedContext(initial.session().getRefreshTokenId(),1)).isZero();
            assertThat(sessions.findAuthorizedSession(takeover.session().getRefreshTokenId(),1,30)).isNotNull();
            assertThat(sessions.countFixedContext(h1.session().getRefreshTokenId(),1)).isEqualTo(1);
            assertThat(sessions.countFixedContext(h2.session().getRefreshTokenId(),1)).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE aggregate_id='1' AND event_type='account.app_session_replaced' AND schema_registered=1 AND schema_revision=1 AND analytics_event=1",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE user_id=1 AND action='ACCOUNT.APP_SESSION_REPLACED'",Integer.class)).isEqualTo(3);
        long committedVersion=version(1);int committedEvidence=evidence(1);
        assertThat(recover(b).refreshToken()).isEqualTo(takeover.refreshToken());
        assertThat(version(1)).isEqualTo(committedVersion);assertThat(evidence(1)).isEqualTo(committedEvidence);
        issue(1,context("phone-c",false,AppSessionSurface.APP));
        assertThat(recover(b).refreshToken()).isNull();
        tx.executeWithoutResult(status->{sessions.lockAccount(1);service.logout(takeover.session());});
        assertThat(active(1)).isEqualTo(1);
        Integer unsupportedOwner=tx.execute(status->sessions.countExecutionOwner(1,"phone-c",30));
        assertThat(unsupportedOwner).isZero();

        // Each injected failure happens after the production write and must undo every earlier write.
        long id=10;
        for(String phase:List.of("insert","revoke","phone","audit","outbox")){
            long account=id++;seed(account);var original=issue(account,context("phone-a",true,AppSessionSurface.APP));
            int before=evidence(account);var mapper=spy(sessions);var runtime=spy(phone);
            if(phase.equals("insert"))doAnswer(call->{sessions.insert((UserSessionEntity)call.getArgument(0));throw new IllegalStateException("injected-insert");}).when(mapper).insert(any(UserSessionEntity.class));
            if(phase.equals("revoke"))doAnswer(call->{sessions.replaceAppSessions(account);throw new IllegalStateException("injected-revoke");}).when(mapper).replaceAppSessions(account);
            if(phase.equals("phone"))doAnswer(call->{phone.authenticatedApp(account,"phone-b",true);throw new IllegalStateException("injected-phone");}).when(runtime).authenticatedApp(account,"phone-b",true);
            failure.set(phase);var failing=create(mapper,runtime);var next=context("phone-b",true,AppSessionSurface.APP);
            assertThatThrownBy(()->tx.execute(status->failing.issue(account,next,"local",30))).hasMessageContaining("injected-");
            failure.set(null);
            assertThat(version(account)).as(phase).isEqualTo(1);assertThat(active(account)).isEqualTo(1);
            assertThat(task(account)).isEqualTo("RUNNING");assertThat(evidence(account)).isEqualTo(before);
            assertThat(attempts.find(next.loginAttemptId())).isNull();
            assertThat(jdbc.queryForObject("SELECT revoked_at FROM nx_user_session WHERE refresh_token_id=?",Timestamp.class,original.session().getRefreshTokenId())).isNull();
        }

        seed(30);issue(30,context("phone-a",true,AppSessionSurface.APP));
        var pool=Executors.newFixedThreadPool(2);var start=new CyclicBarrier(2);
        try{
            var bLogin=pool.submit(()->{start.await(10,TimeUnit.SECONDS);return issue(30,context("phone-b",true,AppSessionSurface.APP));});
            var cLogin=pool.submit(()->{start.await(10,TimeUnit.SECONDS);return issue(30,context("phone-c",true,AppSessionSurface.APP));});
            var versions=List.of(bLogin.get(15,TimeUnit.SECONDS).session().getAppSessionVersion(),cLogin.get(15,TimeUnit.SECONDS).session().getAppSessionVersion());
            assertThat(versions).containsExactlyInAnyOrder(2L,3L);assertThat(active(30)).isEqualTo(1);
            String predecessor=jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.newInstallationId')) FROM nx_event_outbox WHERE aggregate_id='30' AND event_type='account.app_session_replaced' AND JSON_EXTRACT(payload,'$.newSessionVersion')=2",String.class);
            assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.oldInstallationId')) FROM nx_event_outbox WHERE aggregate_id='30' AND event_type='account.app_session_replaced' AND JSON_EXTRACT(payload,'$.newSessionVersion')=3",String.class)).isEqualTo(predecessor);
        }finally{pool.shutdownNow();}

        // Establish an RR snapshot, wait behind another login, then recover the committed attempt.
        seed(40);var intent=context("phone-a",true,AppSessionSurface.APP);
        var writerReady=new CountDownLatch(1);var readerSawMissing=new CountDownLatch(1);
        var writerDone=new CountDownLatch(1);var rr=Executors.newFixedThreadPool(2);
        try{
            var writer=rr.submit(()->tx.execute(status->{sessions.lockAccount(40);writerReady.countDown();
                try{if(!readerSawMissing.await(10,TimeUnit.SECONDS))throw new IllegalStateException("reader-timeout");}catch(InterruptedException e){throw new RuntimeException(e);}
                return service.issue(40,intent,"local",30);}));
            var reader=rr.submit(()->tx.execute(status->{
                try{if(!writerReady.await(10,TimeUnit.SECONDS))throw new IllegalStateException("writer-timeout");}catch(InterruptedException e){throw new RuntimeException(e);}
                assertThat(attempts.find(intent.loginAttemptId())).isNull();readerSawMissing.countDown();
                return service.issue(40,intent,"local",30);
            }));
            var w=writer.get(15,TimeUnit.SECONDS);var r=reader.get(15,TimeUnit.SECONDS);
            assertThat(r.original()).isTrue();assertThat(r.refreshToken()).isEqualTo(w.refreshToken());assertThat(version(40)).isEqualTo(1);
        }finally{rr.shutdownNow();}
        // A late logout from A must not stop the later A chain after B was replaced.
        seed(50);var oldA=issue(50,context("phone-a",true,AppSessionSurface.APP));
        issue(50,context("phone-b",true,AppSessionSurface.APP));
        var newA=issue(50,context("phone-a",true,AppSessionSurface.APP));
        jdbc.update("UPDATE nx_compute_task SET status='RUNNING',proof_consumed_at=NULL,last_error=NULL WHERE task_no='phone-50'");
        tx.executeWithoutResult(status->{sessions.lockAccount(50);service.logout(oldA.session());});
        assertThat(task(50)).isEqualTo("RUNNING");
        UserSessionEntity currentA=tx.execute(status->sessions.findAuthorizedSession(newA.session().getRefreshTokenId(),50,30));
        assertThat(currentA).isNotNull();
        assertThat(jdbc.queryForObject("SELECT execution_installation_id FROM nx_phone_binding WHERE user_id=50",String.class)).isEqualTo("phone-a");

        // A valid refresh grace remains inside its persisted group and App version.
        seed(60);var app=issue(60,context("phone-a",true,AppSessionSurface.APP));
        var browser=issue(60,context("",false,AppSessionSurface.H5));
        var appSuccessor=rotate(app.session());var h5Successor=rotate(browser.session());
        var activity=new TransactionTemplate(tx.getTransactionManager());
        activity.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        Integer appGrace=activity.execute(status->sessions.touchRecentlyRotatedUserSession(app.session().getRefreshTokenId(),60L,30));
        assertThat(appGrace).isEqualTo(1);
        issue(60,context("phone-b",true,AppSessionSurface.APP));
        Integer oldGrace=activity.execute(status->sessions.touchRecentlyRotatedUserSession(app.session().getRefreshTokenId(),60L,30));
        Integer oldLive=activity.execute(status->sessions.touchActiveUserSession(appSuccessor.getRefreshTokenId(),60L,30));
        Integer h5Grace=activity.execute(status->sessions.touchRecentlyRotatedUserSession(browser.session().getRefreshTokenId(),60L,30));
        Integer h5Live=activity.execute(status->sessions.touchActiveUserSession(h5Successor.getRefreshTokenId(),60L,30));
        assertThat(oldGrace).isZero();assertThat(oldLive).isZero();assertThat(h5Grace).isEqualTo(1);assertThat(h5Live).isEqualTo(1);
        // Production execution guard and completion CAS share the account lock with takeover.
        // Both deterministic winner orders are covered; monetary settlement remains in its existing suite.
        completionRace(70,true);completionRace(71,false);
        adminProjectionExcludesInvalidSessionsAndCredentials();
        passed=true;
    }
    @Test void h5SecurityRevocationStopsAppAndPreservesOnlyTheRequestedCurrentChain(){
        for(boolean all:List.of(false,true)){
            long userId=all?902:901;seed(userId);
            var app=issue(userId,context("phone-a",true,AppSessionSurface.APP));
            var currentH5=issue(userId,context("current-browser",false,AppSessionSurface.H5));
            var otherH5=issue(userId,context("other-browser",false,AppSessionSurface.H5));
            var currentSuccessor=rotate(currentH5.session());
            var bindingBefore=jdbc.queryForMap("SELECT installation_id,user_device_id,hardware_key,changed_at,version FROM nx_phone_binding WHERE user_id=?",userId);
            if(all) assertThat(revocations.revokeAll(userId)).isPositive();
            else assertThat(revocations.revokeOthers(userId,currentH5.session().getRefreshTokenId())).isEqualTo(2);
            assertThat(active(userId)).isZero();assertThat(task(userId)).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no=?",String.class,"box-"+userId)).isEqualTo("RUNNING");
            assertThat(jdbc.queryForMap("SELECT installation_id,user_device_id,hardware_key,changed_at,version FROM nx_phone_binding WHERE user_id=?",userId)).isEqualTo(bindingBefore);
            assertThat(jdbc.queryForObject("SELECT execution_installation_id FROM nx_phone_binding WHERE user_id=?",String.class,userId)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT online_status FROM nx_user_device_runtime WHERE user_device_id=?",String.class,userId*10)).isEqualTo("OFFLINE");
            assertThat(jdbc.queryForObject("SELECT paused_reason FROM nx_user_device_runtime WHERE user_device_id=?",String.class,userId*10)).isEqualTo("PHONE_REPLACED");
            tx.executeWithoutResult(status->{
                assertThat(sessions.findAuthorizedSession(app.session().getRefreshTokenId(),userId,30)).isNull();
                assertThat(sessions.findAuthorizedSession(otherH5.session().getRefreshTokenId(),userId,30)).isNull();
                var remaining=sessions.findAuthorizedSession(currentSuccessor.getRefreshTokenId(),userId,30);
                if(all) assertThat(remaining).isNull();
                else assertThat(remaining).isNotNull();
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_session WHERE user_id=? AND revoked_at IS NULL AND session_ended_reason IS NULL",Integer.class,userId)).isEqualTo(all?0:1);
            assertThat(jdbc.queryForObject("SELECT session_ended_reason FROM nx_user_session WHERE refresh_token_id=?",String.class,app.session().getRefreshTokenId())).isEqualTo("SECURITY_REVOKED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_session WHERE user_id=? AND session_chain_id=? AND session_ended_reason IS NULL",Integer.class,userId,currentH5.session().getSessionChainId())).isEqualTo(all?0:2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE user_id=? AND action='TASK_ASSIGNMENT_PHONE_REPLACED'",Integer.class,userId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE aggregate_id=? AND event_type='TASK_ASSIGNMENT_PHONE_REPLACED'",Integer.class,"phone-"+userId)).isEqualTo(1);
        }
        securityPassed=true;
    }
    @Test void securityRevocationRollsBackSessionsPhoneAndBothSinksWhenPhoneStopEvidenceFails(){
        long nextUser=910;
        for(boolean all:List.of(false,true))for(String phase:List.of("audit","outbox")){
            long userId=nextUser++;seed(userId);
            var app=issue(userId,context("phone-a",true,AppSessionSurface.APP));
            var currentH5=issue(userId,context("current-browser",false,AppSessionSurface.H5));
            issue(userId,context("other-browser",false,AppSessionSurface.H5));
            var before=securitySnapshot(userId);failure.set(phase);
            try{
                assertThatThrownBy(()->{
                    if(all) revocations.revokeAll(userId);
                    else revocations.revokeOthers(userId,currentH5.session().getRefreshTokenId());
                }).hasMessage("injected-"+phase);
            }finally{failure.set(null);}
            assertThat(securitySnapshot(userId)).as((all?"all":"others")+"/"+phase).isEqualTo(before);
            assertThat(task(userId)).isEqualTo("RUNNING");assertThat(active(userId)).isEqualTo(1);
            tx.executeWithoutResult(status->{
                assertThat(sessions.findAuthorizedSession(app.session().getRefreshTokenId(),userId,30)).isNotNull();
                assertThat(sessions.findAuthorizedSession(currentH5.session().getRefreshTokenId(),userId,30)).isNotNull();
            });
        }
        securityRollbackPassed=true;
    }
    Map<String,Object> securitySnapshot(long userId){
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("sessions",jdbc.queryForList("SELECT id,revoked_at,session_ended_reason,last_active_at,updated_at FROM nx_user_session WHERE user_id=? ORDER BY id",userId));
        snapshot.put("binding",jdbc.queryForList("SELECT * FROM nx_phone_binding WHERE user_id=?",userId));
        snapshot.put("tasks",jdbc.queryForList("SELECT * FROM nx_compute_task WHERE user_id=? ORDER BY id",userId));
        snapshot.put("runtime",jdbc.queryForList("SELECT * FROM nx_user_device_runtime WHERE user_device_id=?",userId*10));
        snapshot.put("auditCount",jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log",Integer.class));
        snapshot.put("outboxCount",jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox",Integer.class));
        return snapshot;
    }
    void adminProjectionExcludesInvalidSessionsAndCredentials() throws Exception {
        long userId=801;
        seed(userId);
        var app=issue(userId,context("phone-a",true,AppSessionSurface.APP));
        var browser=issue(userId,context("summary-browser",false,AppSessionSurface.H5));
        issue(userId,context("summary-browser",false,AppSessionSurface.H5));
        var current=sessions.adminSessionSummary(userId,30);
        assertThat(current).extracting(AuthSessionMapper.SessionSummary::sessionGroup).containsExactly("APP","H5");
        assertThat(current).extracting(AuthSessionMapper.SessionSummary::sessionCount).containsExactly(1L,2L);
        assertThat(current.get(0).installationId()).isEqualTo("phone-a");
        assertThat(current.get(0).appSessionVersion()).isEqualTo(app.session().getAppSessionVersion());
        assertThat(current.get(0).executionSupported()).isTrue();
        assertThat(current.get(1).executionSupported()).isFalse();
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var wire=json.valueToTree(current);
        for(var row:wire){
            var fields=new HashSet<String>();row.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("sessionGroup","installationId","appSessionVersion",
                    "executionSupported","sessionCount","lastActiveAt");
        }
        String serialized=json.writeValueAsString(current);
        assertThat(serialized).doesNotContain(app.refreshToken(),browser.refreshToken(),
                app.session().getRefreshTokenId(),app.session().getSessionChainId(),app.session().getLoginAttemptId());
        // Each disqualifier is independent; a current H5 session stays visible throughout.
        for(String invalid:List.of(
                "last_active_at=DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 31 DAY)",
                "expires_at=DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 1 SECOND)",
                "revoked_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)",
                "session_ended_reason='SECURITY_REVOKED'", "is_deleted=1",
                "session_group='UNKNOWN'", "app_session_version=app_session_version+1",
                "installation_id='not-current'", "session_chain_id='not-current'", "execution_supported=0")) {
            jdbc.update("UPDATE nx_user_session SET "+invalid+" WHERE id=?",app.session().getId());
            assertThat(sessions.adminSessionSummary(userId,30)).as(invalid)
                    .extracting(AuthSessionMapper.SessionSummary::sessionGroup).containsExactly("H5");
            restoreProjectionSession(app.session());
            assertThat(sessions.adminSessionSummary(userId,30)).hasSize(2);
        }
        // H5 is also subject to expiry, inactivity, revocation, and terminal-chain status.
        for(String invalid:List.of(
                "last_active_at=DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 31 DAY)",
                "expires_at=DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 1 SECOND)",
                "revoked_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)",
                "session_ended_reason='LOGOUT'", "is_deleted=1")) {
            jdbc.update("UPDATE nx_user_session SET "+invalid+" WHERE user_id=? AND session_group='H5'",userId);
            assertThat(sessions.adminSessionSummary(userId,30)).as("H5 "+invalid)
                    .extracting(AuthSessionMapper.SessionSummary::sessionGroup).containsExactly("APP");
            jdbc.update("UPDATE nx_user_session SET last_active_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),"
                    +"expires_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 9 HOUR),revoked_at=NULL,session_ended_reason=NULL,is_deleted=0 "
                    +"WHERE user_id=? AND session_group='H5'",userId);
        }
        jdbc.update("UPDATE nx_user_session SET last_active_at=NULL,created_at=DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 31 DAY) WHERE id=?",app.session().getId());
        assertThat(sessions.adminSessionSummary(userId,30)).extracting(AuthSessionMapper.SessionSummary::sessionGroup).containsExactly("H5");
        jdbc.update("UPDATE nx_user_session SET created_at=DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 8 DAY) WHERE id=?",app.session().getId());
        assertThat(sessions.adminSessionSummary(userId,30)).hasSize(2);
        assertThat(sessions.adminSessionSummary(userId,7)).extracting(AuthSessionMapper.SessionSummary::sessionGroup).containsExactly("H5");
        assertThat(sessions.adminSessionSummary(802,30)).isEmpty();
    }
    void restoreProjectionSession(UserSessionEntity session){
        jdbc.update("UPDATE nx_user_session SET session_group=?,installation_id=?,app_session_version=?,"
                +"session_chain_id=?,execution_supported=?,is_deleted=0,revoked_at=NULL,session_ended_reason=NULL,"
                +"last_active_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),expires_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 9 HOUR) WHERE id=?",
                session.getSessionGroup(),session.getInstallationId(),session.getAppSessionVersion(),
                session.getSessionChainId(),session.getExecutionSupported(),session.getId());
    }
    void completionRace(long userId,boolean stopFirst) throws Exception {
        seed(userId);issue(userId,context("phone-a",true,AppSessionSurface.APP));
        jdbc.update("UPDATE nx_compute_task SET completion_nonce='nonce' WHERE task_no=?","phone-"+userId);
        var locked=new CountDownLatch(1);var competing=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var winner=pool.submit(()->tx.execute(status->{
                sessions.lockAccount(userId);locked.countDown();await(competing);
                if(stopFirst)service.issue(userId,context("phone-b",true,AppSessionSurface.APP),"local",30);
                else completeGuarded(userId);
                return true;
            }));
            var loser=pool.submit(()->{await(locked);competing.countDown();
                if(stopFirst){
                    assertThatThrownBy(()->tx.execute(status->{sessions.lockAccount(userId);completeGuarded(userId);return true;}))
                            .hasMessage("PHONE_EXECUTION_SESSION_REQUIRED");
                }else issue(userId,context("phone-b",true,AppSessionSurface.APP));
                return true;
            });
            assertThat(winner.get(15,TimeUnit.SECONDS)).isTrue();assertThat(loser.get(15,TimeUnit.SECONDS)).isTrue();
            assertThat(task(userId)).isEqualTo(stopFirst ? "CANCELLED" : "COMPLETED");
            assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM nx_compute_task WHERE task_no=?",Boolean.class,"phone-"+userId)).isEqualTo(!stopFirst);
        }finally{pool.shutdownNow();}
    }
    void completeGuarded(long userId){
        phone.requireExecutionOwner(userId,userId*10);
        assertThat(tasks.completeAssignment(userId,"phone-"+userId,"nonce","PRODUCTION",java.time.LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE))).isEqualTo(1);
    }
    void await(CountDownLatch latch){
        try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("race-timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    UserSessionEntity rotate(UserSessionEntity old){
        return tx.execute(status->{
            sessions.lockAccount(old.getUserId());
            var next=new UserSessionEntity();next.setUserId(old.getUserId());next.setRefreshTokenId(UUID.randomUUID().toString());
            next.setSessionChainId(old.getSessionChainId());next.setSessionGroup(old.getSessionGroup());
            next.setInstallationId(old.getInstallationId());next.setAppSessionVersion(old.getAppSessionVersion());
            next.setExecutionSupported(old.getExecutionSupported());next.setLoginAttemptId(old.getLoginAttemptId());
            next.setLastActiveAt(java.time.LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
            next.setExpiresAt(old.getExpiresAt());next.setIsDeleted(0);
            assertThat(sessions.markRefreshRotated(old.getId(),next.getRefreshTokenId())).isEqualTo(1);
            assertThat(sessions.insert(next)).isEqualTo(1);return next;
        });
    }
    @AfterAll void cleanup() throws Exception{
        if(admin==null)return;
        try{if(passed && securityPassed && securityRollbackPassed){try(var statement=admin.createStatement()){statement.execute("DROP DATABASE `"+schema+"`");}
            System.out.println("APP_SESSION_FIXTURE_DROP="+schema);
        }else System.out.println("APP_SESSION_FIXTURE_RETAINED="+schema);}finally{admin.close();}
    }
}
