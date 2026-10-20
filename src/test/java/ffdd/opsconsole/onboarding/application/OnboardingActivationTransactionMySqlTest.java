package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import ffdd.opsconsole.common.boundary.PhoneReplacementSupportContract;
import ffdd.opsconsole.content.terms.LegalTermsService;
import ffdd.opsconsole.content.terms.infrastructure.MybatisLegalTermsRepository;
import ffdd.opsconsole.content.terms.mapper.*;
import ffdd.opsconsole.growth.application.WheelSandboxProfile;
import ffdd.opsconsole.onboarding.mapper.*;
import ffdd.opsconsole.platform.application.*;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import ffdd.opsconsole.shared.canonical.PhoneActivationCapacityService;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.config.*;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.*;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.shared.security.*;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Real activation entry point, domain, capacity, session authority, Terms, A2 and A4 writes.
 * SQL seeds observations/credentials/configuration only; no business decision is stubbed.
 * Failure spies throw AFTER the real sink has written, so transaction rollback is observable. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnboardingActivationTransactionMySqlTest {
    Connection admin;
    String schema;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    OnboardingCalibrationService phone;
    AppPhoneReplacementService app;
    PhoneReplacementService domain;
    final AtomicReference<String> fail=new AtomicReference<>();
    boolean ready, failed;
    @RegisterExtension final TestWatcher outcome=new TestWatcher(){
        @Override public void testFailed(ExtensionContext context,Throwable cause){failed=true;}
    };

    @BeforeAll void setup() throws Exception {
        String url=System.getenv("NEXION_TEST_DB_SERVER_URL");
        if(url==null || !url.matches("jdbc:mysql://(127[.]0[.]0[.]1|localhost):33319/([?].*)?"))
            throw new IllegalArgumentException("Dedicated session-phone test server on 33319 required");
        String user=System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME","root");
        String password=System.getenv("NEXION_TEST_DB_PASSWORD");
        admin=DriverManager.getConnection(url,user,password);
        schema="nx_activation_tx_"+UUID.randomUUID().toString().replace("-","");
        admin.createStatement().execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");
        System.out.println("ACTIVATION_TX_FIXTURE_CREATE="+schema);
        int q=url.indexOf('?');
        var ds=new DriverManagerDataSource(url.substring(0,q<0?url.length():q)+schema+(q<0?"":url.substring(q)),user,password);
        jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        String baseline=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:List.of("nx_user","nx_user_security","nx_user_session","nx_phone_binding","nx_user_device",
                "nx_user_device_runtime","nx_compute_task","nx_onboarding_phone_tier_config","nx_onboarding_yield_comparison_config",
                "nx_onboarding_calibration","nx_config_item","nx_order","nx_order_item","nx_product","nx_trial_claim",
                "nx_user_wallet","nx_wallet_ledger","nx_earning_event",
                "nx_audit_log","nx_event_outbox","nx_event_schema_registry","nx_event_schema_property"))createTable(baseline,table);
        createTable(Files.readString(Path.of("scripts/migrations/20260810_ab_pending_closure.sql")),"nx_admin_event_lifecycle");
        String termsSql=Files.readString(Path.of("scripts/migrations/20260817_legal_terms_versioned.sql"));
        createTable(termsSql,"nx_legal_terms_version");createTable(termsSql,"nx_legal_terms_ack");
        jdbc.execute("ALTER TABLE nx_trial_claim ADD COLUMN settled_at DATETIME(6) NULL");
        jdbc.execute("CREATE TABLE nx_promotion_reservation(reservation_id VARCHAR(64) PRIMARY KEY,beneficiary_id BIGINT,asset VARCHAR(16),status VARCHAR(16),amount DECIMAL(18,6)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_promotion_reward(obligation_id VARCHAR(64) PRIMARY KEY,reservation_id VARCHAR(64),status VARCHAR(32)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_promotion_device_receipt(obligation_id VARCHAR(64),device_id BIGINT) ENGINE=InnoDB");
        migrate("20261019_app_single_session.sql");migrate("20261019_phone_replacement_domain.sql");
        jdbc.update("UPDATE nx_config_item SET config_value='on' WHERE config_key=?",PhoneReplacementPolicy.PREFIX+"allowReplacement");
        jdbc.update("UPDATE nx_config_item SET config_value='0' WHERE config_key=?",PhoneReplacementPolicy.PREFIX+"minReplacementIntervalDays");
        jdbc.update("INSERT INTO nx_config_item(config_key,config_value) VALUES('device.max_active_slots','3'),(?,?)",
                PhoneCalibrationConfigService.KEY,"{\"revision\":1,\"current\":{\"version\":1,\"effectiveAt\":0,\"thresholds\":[19,25,35,47],\"rules\":[{\"id\":\"fixture\",\"platform\":\"android\",\"model\":\"\",\"soc\":\"test soc\",\"gpu\":\"Mali-G715\",\"minMemoryGb\":1,\"maxMemoryGb\":129,\"computeValue\":28.3,\"evidence\":\"Test only\"}]},\"scheduled\":null}");
        jdbc.update("INSERT INTO nx_onboarding_phone_tier_config(tier,name,tops_min,tops_max,base_rate_usdt,base_rate_nex) VALUES(1,'T1',8,18,.04,6),(2,'T2',19,24,.05,8),(3,'T3',25,34,.06,10),(4,'T4',35,46,.08,13),(5,'T5',47,58,.095,16)");
        jdbc.update("INSERT INTO nx_onboarding_yield_comparison_config(config_key,label,daily_usdt,daily_nex) VALUES('phone','Phone',.06,10)");
        jdbc.update("INSERT INTO nx_legal_terms_version(locale,jurisdiction,version_label,effective_at,status,title,summary,sections_json) VALUES('en','GLOBAL','v1','2026-01-01','PUBLISHED','Activation integration terms','Terms for the isolated test account',?)",
                "[{\"key\":\"use\",\"title\":\"Service use\",\"body\":\"Use the service responsibly.\",\"sortOrder\":1}]");
        var configuration=new MybatisConfiguration(new Environment("activation-tx",new SpringManagedTransactionFactory(),ds));
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.setGlobalConfig(configuration,new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig())
                .setMetaObjectHandler(new MybatisMetaObjectHandler(Clock.system(DateTimeFormatConfig.BUSINESS_ZONE))));
        for(Class<?> type:List.of(OnboardingCalibrationMapper.class,PhoneReplacementMapper.class,PhoneInstallationMapper.class,
                CanonicalStateMapper.class,AuthSessionMapper.class,UserOpsMapper.class,AuditLogMapper.class,EventOutboxMapper.class,
                LegalTermsVersionMapper.class,LegalTermsAckMapper.class))configuration.addMapper(type);
        var sql=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var environment=new MockEnvironment();environment.setActiveProfiles("dev");
        var runtime=new WheelSandboxProfile(environment);
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenAnswer(call->jdbc.query("SELECT config_value FROM nx_config_item WHERE config_key=? AND is_deleted=0",
                (rs,row)->rs.getString(1),(String)call.getArgument(0)).stream().findFirst());
        var a2=mock(A2RuntimePolicy.class);when(a2.retentionMonths()).thenReturn(13);when(a2.schemaVersion()).thenReturn("v3");
        var json=new ObjectMapper().findAndRegisterModules();
        var audit=spy(new AuditLogService(sql.getMapper(AuditLogMapper.class),new AuditLogSanitizer(json),
                new ApplicationNameProperties(),new AuditProperties(),mock(AdminMapper.class),a2));
        doAnswer(call->{Object result=call.callRealMethod();AuditLogWriteRequest r=call.getArgument(0);
            if("audit".equals(fail.get()) && "PHONE_REPLACEMENT_COMMAND".equals(r.getAction()))throw new IllegalStateException("injected-audit-after-write");return result;
        }).when(audit).recordRequired(any());
        var outbox=spy(new EventOutboxService(sql.getMapper(EventOutboxMapper.class),json,new OutboxProperties(),mock(A4RuntimePolicyService.class)));
        doAnswer(call->{Object result=call.callRealMethod();
            if("outbox".equals(fail.get()) && "phone.replacement_succeeded".equals(call.getArgument(2)))throw new IllegalStateException("injected-outbox-after-write");return result;
        }).when(outbox).publish(anyString(),anyString(),anyString(),any());
        var users=sql.getMapper(UserOpsMapper.class);
        var current=proxy(new CurrentAppSessionService(sql.getMapper(AuthSessionMapper.class),config,
                mock(UserAccountBlocklistVerifier.class),users,environment));
        domain=proxy(new PhoneReplacementService(sql.getMapper(PhoneReplacementMapper.class),Clock.systemUTC(),audit,outbox));
        var terms=new UserBusinessWriteGateFilter(users,new LegalTermsService(new MybatisLegalTermsRepository(
                sql.getMapper(LegalTermsVersionMapper.class),sql.getMapper(LegalTermsAckMapper.class)),Clock.systemUTC(),environment,audit));
        phone=proxy(new OnboardingCalibrationService(sql.getMapper(OnboardingCalibrationMapper.class),runtime,environment,audit,outbox,
                config,Clock.systemUTC(),proxy(new PhoneInstallationService(sql.getMapper(PhoneInstallationMapper.class),current)),domain,
                proxy(new PhoneActivationCapacityService(sql.getMapper(CanonicalStateMapper.class))),terms));
        // These tests only read operation receipts; support ownership is reviewed separately.
        app=proxy(new AppPhoneReplacementService(domain,current,sql.getMapper(OnboardingCalibrationMapper.class),runtime,environment,
                mock(PhoneReplacementSupportContract.class),phone));
        ready=true;
    }
    @SuppressWarnings("unchecked") <T>T proxy(T target){var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (T)factory.getProxy();}
    void createTable(String source,String table){int start=source.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");jdbc.execute(source.substring(start,source.indexOf(';',start)+1));}
    void migrate(String filename)throws Exception{String source=Files.readString(Path.of("scripts/migrations",filename)).replaceAll("(?m)^--.*$","");
        try(var c=jdbc.getDataSource().getConnection();var s=c.createStatement()){for(String command:source.split(";"))if(!command.isBlank())s.execute(command);}}
    void seed(long user){jdbc.update("INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,language) VALUES(?,'00',?,'local','unused','Activation test',?,'en')",user,"test-"+user,"ref-"+user);ack(user);}
    void ack(long user){jdbc.update("INSERT INTO nx_legal_terms_ack(user_id,source_environment,run_id,locale,jurisdiction,version_label,idempotency_key,acknowledged_at) VALUES(?,'PRODUCTION','','en','GLOBAL','v1',?,NOW())",user,"ack-"+user);}
    Authentication session(long user,String install,String group,boolean execution){
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,expires_at,last_active_at,session_group,installation_id,app_session_version,execution_supported) VALUES(?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 2 DAY),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),?,?,1,?)",user,id,id,group,install,execution);
        if("APP".equals(group))jdbc.update("INSERT INTO nx_app_session_state(user_id,app_session_version,session_chain_id,installation_id,execution_supported) VALUES(?,1,?,?,?) ON DUPLICATE KEY UPDATE session_chain_id=VALUES(session_chain_id),installation_id=VALUES(installation_id),execution_supported=VALUES(execution_supported)",user,id,install,execution);
        var auth=new UsernamePasswordAuthenticationToken(Long.toString(user),null,List.of());auth.setDetails(Map.of("subjectType","USER","sessionId",id));return auth;
    }
    long calibrate(long user,String install){var signals=new OnboardingCalibrationService.Signals(8.0,8,"Synthetic phone","Synthetic","Mali-G715",3.0,42.0,80,true,true,"android","test soc");
        var result=phone.calibrate(user,new OnboardingCalibrationService.Request(install,0,"calibrate-"+user+"-"+install,signals));
        assertThat(result.getCode()).as(result.getMessage()).isZero();return ((Number)result.getData().get("revision")).longValue();}
    OnboardingCalibrationService.ActionRequest action(String install,long revision,Long binding,String key){return new OnboardingCalibrationService.ActionRequest(install,revision,key,false,binding,1L,null,true,true);}
    ApiResult<Map<String,Object>> first(long user){seed(user);long revision=calibrate(user,"phone-a");var result=phone.activate(user,action("phone-a",revision,null,"activate-first-"+user),session(user,"phone-a","APP",true));assertThat(result.getCode()).as(result.getMessage()).isZero();return result;}
    int count(String table,long user){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Integer.class,user);}
    long device(long user){return jdbc.queryForObject("SELECT user_device_id FROM nx_phone_binding WHERE user_id=?",Long.class,user);}
    void task(long user){long device=device(user);jdbc.update("INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_type,client_name,status) VALUES(?,?,?,'PHONE','test','RUNNING')","task-"+user,user,device);
        jdbc.update("INSERT INTO nx_user_device_runtime(user_device_id,online_status,active_task_no,network_reachable,heartbeat_at) VALUES(?,'ONLINE',?,1,NOW(6))",device,"task-"+user);}
    Map<String,Object> snapshot(long user){var result=new LinkedHashMap<String,Object>();
        for(String table:List.of("nx_phone_binding","nx_phone_replacement_history_state","nx_phone_replacement_operation","nx_phone_replacement_event","nx_phone_replacement_request","nx_phone_replacement_grant","nx_user_device","nx_compute_task","nx_onboarding_calibration"))
            result.put(table,jdbc.queryForList("SELECT * FROM "+table+" WHERE user_id=?",user));
        result.put("runtime",jdbc.queryForList("SELECT r.* FROM nx_user_device_runtime r JOIN nx_user_device d ON d.id=r.user_device_id WHERE d.user_id=?",user));
        result.put("audit",jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log",Long.class));result.put("outbox",jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox",Long.class));return result;}

    @Test void firstBindingReplacementReplayAndOriginalReceiptRemainDistinctFromCurrentBinding(){
        long user=101;var first=first(user);assertThat(count("nx_phone_replacement_event",user)).isZero();
        long old=device(user);task(user);
        jdbc.update("INSERT INTO nx_user_device(user_id,instance_no,name,device_type,status,source_channel,activated_at) VALUES(?,?,'Box','BOX','OFFLINE','STORE',NOW()),(?,?,'Box','BOX','OFFLINE','STORE',NOW())",user,"box-1-"+user,user,"box-2-"+user);
        long revision=calibrate(user,"phone-b");var auth=session(user,"phone-b","APP",true);var request=action("phone-b",revision,1L,"replace-b-"+user);
        var result=phone.activate(user,request,auth);assertThat(result.getCode()).as(result.getMessage()).isZero();
        assertThat(count("nx_phone_replacement_event",user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT activated_at IS NULL FROM nx_user_device WHERE id=?",Boolean.class,old)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no=?",String.class,"task-"+user)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device WHERE user_id=? AND activated_at IS NOT NULL",Integer.class,user)).isEqualTo(3);
        var before=snapshot(user);assertThat(phone.activate(user,request,auth).getData().get("operationId")).isEqualTo(result.getData().get("operationId"));assertThat(snapshot(user)).isEqualTo(before);
        long cRevision=calibrate(user,"phone-c");var current=session(user,"phone-c","APP",true);
        assertThat(phone.activate(user,action("phone-c",cRevision,2L,"replace-c-"+user),current).getCode()).isZero();
        Receipt original=app.operation(current,(String)result.getData().get("operationId"));
        assertThat(((Map<?,?>)original.originalResult()).get("deviceId")).isEqualTo("phone-b");
        assertThat(((BindingSummary)original.currentResource()).installationId()).isEqualTo("phone-c");
        assertThat(count("nx_phone_replacement_event",user)).isEqualTo(2);
        assertThat(app.operation(current,(String)first.getData().get("operationId")).status()).isEqualTo("SUCCEEDED");
    }
    @Test void returningToOriginalPhoneReusesItsRowCountsAgainAndPreservesOtherAssets(){
        long user=601;first(user);long originalDevice=device(user);task(user);
        jdbc.update("INSERT INTO nx_user_device(user_id,instance_no,name,device_type,status,source_channel,activated_at) "
                +"VALUES(?,?,'Purchased box','BOX','ACTIVE','STORE',NOW()),(?,?,'Trial box','BOX','ACTIVE','TRIAL',NOW())",
                user,"purchased-"+user,user,"trial-"+user);
        long trialDevice=jdbc.queryForObject("SELECT id FROM nx_user_device WHERE instance_no=?",Long.class,"trial-"+user);
        jdbc.update("INSERT INTO nx_trial_claim(user_id,claim_no,status,user_device_id,device_name,claimed_at,expires_at) "
                +"VALUES(?,?,'ACTIVE',?,'Trial box',NOW(),DATE_ADD(NOW(),INTERVAL 3 DAY))",user,"trial-claim-"+user,trialDevice);
        jdbc.update("INSERT INTO nx_user_wallet(user_id,usdt_available,nex_available,lifetime_earned,version) VALUES(?,10,20,10,7)",user);
        for(String asset:List.of("USDT","NEX")){
            int amount="USDT".equals(asset)?10:20;
            jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status) "
                    +"VALUES(?,?,'COMPUTE_TASK_REWARD',?,'IN',?,?,'SUCCESS')",user,"settled-before-replacement-"+user,asset,amount,amount);
            jdbc.update("INSERT INTO nx_earning_event(event_no,user_id,user_device_id,receipt_no,asset,amount,status,wallet_posted_at) "
                    +"VALUES(?,?,?,?,?,?,'POSTED',NOW())",asset+"-settled-"+user,user,originalDevice,"settled-before-replacement-"+user,asset,amount);
        }
        var assetsBefore=nonPhoneAssets(user);
        long bRevision=calibrate(user,"phone-b");
        var b=phone.activate(user,action("phone-b",bRevision,1L,"replace-b-return-"+user),session(user,"phone-b","APP",true));
        assertThat(b.getCode()).as(b.getMessage()).isZero();long otherDevice=device(user);
        assertThat(otherDevice).isNotEqualTo(originalDevice);
        assertThat(count("nx_phone_replacement_event",user)).isEqualTo(1);
        assertThat(nonPhoneAssets(user)).isEqualTo(assetsBefore);

        // A's existing calibration/device were deactivated by B, so this exercises the upsert's UPDATE branch.
        long aRevision=jdbc.queryForObject("SELECT row_version FROM nx_onboarding_calibration WHERE user_id=? AND device_id='phone-a'",Long.class,user);
        var aAuth=session(user,"phone-a","APP",true);
        var returnRequest=action("phone-a",aRevision,2L,"replace-back-a-"+user);
        var returned=phone.activate(user,returnRequest,aAuth);
        assertThat(returned.getCode()).as(returned.getMessage()).isZero();
        assertThat(device(user)).isEqualTo(originalDevice);
        assertThat(jdbc.queryForObject("SELECT version FROM nx_phone_binding WHERE user_id=?",Long.class,user)).isEqualTo(3);
        assertThat(count("nx_phone_replacement_event",user)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device WHERE user_id=? AND source_channel='ONBOARDING'",Integer.class,user)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT activated_at IS NOT NULL FROM nx_user_device WHERE id=?",Boolean.class,originalDevice)).isTrue();
        assertThat(jdbc.queryForObject("SELECT activated_at IS NULL FROM nx_user_device WHERE id=?",Boolean.class,otherDevice)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no=?",String.class,"task-"+user)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT active_task_no FROM nx_user_device_runtime WHERE user_device_id=?",String.class,originalDevice)).isNull();
        assertThat(nonPhoneAssets(user)).isEqualTo(assetsBefore);
        var after=snapshot(user);
        assertThat(phone.activate(user,returnRequest,aAuth).getData().get("operationId")).isEqualTo(returned.getData().get("operationId"));
        assertThat(snapshot(user)).isEqualTo(after);assertThat(nonPhoneAssets(user)).isEqualTo(assetsBefore);
    }
    Map<String,Object> nonPhoneAssets(long user){
        var result=new LinkedHashMap<String,Object>();
        result.put("devices",jdbc.queryForList("SELECT * FROM nx_user_device WHERE user_id=? AND source_channel<>'ONBOARDING' ORDER BY id",user));
        for(String table:List.of("nx_trial_claim","nx_user_wallet","nx_wallet_ledger","nx_earning_event"))
            result.put(table,jdbc.queryForList("SELECT * FROM "+table+" WHERE user_id=? ORDER BY id",user));
        return result;
    }
    @Test void requiredAuditAndOutboxFailuresRollBackEveryEarlierActivationWrite(){
        long user=200;
        for(String phase:List.of("audit","outbox")){
            user++;first(user);task(user);long revision=calibrate(user,"phone-b");var auth=session(user,"phone-b","APP",true);
            var created=app.createRequest(auth,new AppPhoneReplacementService.RequestInput(1L,"Verified test replacement"),"request-failure-"+user);
            var replacementRequest=(Request)created.currentResource();long account=user;
            var issued=new org.springframework.transaction.support.TransactionTemplate(manager).execute(status->{
                var locked=domain.lockAccount(domain.lockPolicy(),Account.production(account),null);
                var input=new IssueInput(replacementRequest.requestId(),1,true,true,"Verified test replacement");
                var command=domain.openOperation(locked,new Actor("ADMIN",900),"PHONE_GRANT_ISSUE","issue-failure-"+account,input);
                return domain.issueGrant(locked,input,new VerifiedEvidence("support:test-owned",account,900,true,Map.of("owner",account)),command);
            });
            String grant=((GrantSummary)issued.currentResource()).grantId();
            var request=new OnboardingCalibrationService.ActionRequest("phone-b",revision,"replace-failure-"+user,false,1L,1L,grant,true,true);
            var before=snapshot(user);fail.set(phase);
            try{assertThatThrownBy(()->phone.activate(account,request,auth)).hasMessage("injected-"+phase+"-after-write");}
            finally{fail.set(null);}
            assertThat(snapshot(user)).as(phase+" must restore binding/device/task/calibration/history/audit/outbox").isEqualTo(before);
            assertThat(phone.activate(user,request,auth).getCode()).isZero();assertThat(count("nx_phone_replacement_event",user)).isEqualTo(1);
        }
    }
    @Test void realTermsAndHardPauseGateNewCommandsButDoNotInvalidateReadOnlyReceipts(){
        long user=301;var initial=first(user);task(user);
        jdbc.update("DELETE FROM nx_legal_terms_ack WHERE user_id=?",user);
        var a=session(user,"phone-a","APP",true);
        assertThat(app.operation(a,(String)initial.getData().get("operationId")).status()).isEqualTo("SUCCEEDED");
        long revision=calibrate(user,"phone-b");var b=session(user,"phone-b","APP",true);var request=action("phone-b",revision,1L,"replace-terms-"+user);
        var before=snapshot(user);assertThatThrownBy(()->phone.activate(user,request,b)).isInstanceOf(BizException.class).hasMessage("LEGAL_TERMS_ACK_REQUIRED");assertThat(snapshot(user)).isEqualTo(before);
        ack(user);jdbc.update("UPDATE nx_user_device_runtime SET paused_reason='RISK_PAUSED' WHERE user_device_id=?",device(user));
        var rejected=phone.activate(user,request,b);assertThat(rejected.getCode()).isEqualTo(409);assertThat(rejected.getMessage()).isEqualTo("PHONE_REPLACEMENT_RISK_PAUSED");
        assertThat(count("nx_phone_replacement_event",user)).isZero();assertThat(count("nx_user_device",user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT installation_id FROM nx_phone_binding WHERE user_id=?",String.class,user)).isEqualTo("phone-a");
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no=?",String.class,"task-"+user)).isEqualTo("RUNNING");
        var after=snapshot(user);assertThat(phone.activate(user,request,b).getCode()).isEqualTo(409);assertThat(snapshot(user)).isEqualTo(after);
    }
    @Test void fixedSessionGroupInstallationExecutionAndVersionAreAuthoritative(){
        long user=401;seed(user);long revision=calibrate(user,"phone-a");var request=action("phone-a",revision,null,"activate-session-"+user);
        var stale=session(user,"phone-a","APP",true);
        rejectWithoutWrites(user,request,session(user,"browser","H5",false),"APP_SESSION_REQUIRED");
        rejectWithoutWrites(user,request,session(user,"phone-b","APP",true),"INSTALLATION_MISMATCH");
        rejectWithoutWrites(user,request,session(user,"phone-a","APP",false),"PHONE_EXECUTION_UNSUPPORTED");
        rejectWithoutWrites(user,request,stale,"SESSION_REPLACED");
        assertThat(phone.activate(user,request,session(user,"phone-a","APP",true)).getCode()).isZero();
        long changed=jdbc.queryForObject("SELECT CAST(UNIX_TIMESTAMP(changed_at)*1000 AS UNSIGNED) FROM nx_phone_binding WHERE user_id=?",Long.class,user);
        jdbc.update("INSERT INTO nx_user_device_runtime(user_device_id,paused_reason,online_status,heartbeat_at) VALUES(?,'RISK_PAUSED','OFFLINE',NOW(6))",device(user));
        long savedRevision=jdbc.queryForObject("SELECT row_version FROM nx_onboarding_calibration WHERE user_id=?",Long.class,user);
        assertThat(phone.activate(user,action("phone-a",savedRevision,1L,"activate-same-"+user),session(user,"phone-a","APP",true)).getCode()).isZero();
        assertThat(count("nx_phone_replacement_event",user)).isZero();
        assertThat(jdbc.queryForObject("SELECT CAST(UNIX_TIMESTAMP(changed_at)*1000 AS UNSIGNED) FROM nx_phone_binding WHERE user_id=?",Long.class,user)).isEqualTo(changed);
        assertThat(jdbc.queryForObject("SELECT paused_reason FROM nx_user_device_runtime WHERE user_device_id=?",String.class,device(user))).isEqualTo("RISK_PAUSED");
    }
    void rejectWithoutWrites(long user,OnboardingCalibrationService.ActionRequest request,Authentication auth,String reason){
        var before=snapshot(user);assertThatThrownBy(()->phone.activate(user,request,auth)).isInstanceOf(BizException.class).hasMessage(reason);assertThat(snapshot(user)).isEqualTo(before);
    }
    @Test void eligibilityRequiresCurrentInstallationCalibrationAndCurrentTermsWithoutWriting(){
        long user=501;first(user);var current=session(user,"phone-b","APP",true);
        var before=snapshot(user);
        var missing=app.eligibility(current);
        assertThat(missing.canReplace()).isFalse();
        assertThat(missing.blockedReasons()).containsExactly("ONBOARDING_CALIBRATION_REQUIRED");
        assertThat(missing.usedCount()).isZero();
        assertThat(snapshot(user)).isEqualTo(before);

        calibrate(user,"phone-b");before=snapshot(user);
        var ready=app.eligibility(current);
        assertThat(ready.canReplace()).isTrue();assertThat(ready.blockedReasons()).isEmpty();
        assertThat(ready.binding().installationId()).isEqualTo("phone-a");
        assertThat(snapshot(user)).isEqualTo(before);

        // Existing v1 acknowledgement must not authorize newly published v2 Terms.
        jdbc.update("UPDATE nx_legal_terms_version SET version_label='v2' WHERE locale='en' AND jurisdiction='GLOBAL'");
        try {
            before=snapshot(user);var termsChanged=app.eligibility(current);
            assertThat(termsChanged.canReplace()).isFalse();
            assertThat(termsChanged.blockedReasons()).containsExactly("LEGAL_TERMS_ACK_REQUIRED");
            assertThat(termsChanged.usedCount()).isZero();
            assertThat(snapshot(user)).isEqualTo(before);
        } finally {
            jdbc.update("UPDATE nx_legal_terms_version SET version_label='v1' WHERE locale='en' AND jurisdiction='GLOBAL'");
        }
        before=snapshot(user);
        assertThat(app.eligibility(current).canReplace()).isTrue();
        assertThat(snapshot(user)).isEqualTo(before);
    }
    @AfterAll void cleanup() throws Exception {
        if(admin==null)return;
        if(ready && !failed){admin.createStatement().execute("DROP DATABASE `"+schema+"`");System.out.println("ACTIVATION_TX_FIXTURE_DROP="+schema);}
        else System.out.println("ACTIVATION_TX_FIXTURE_RETAIN="+schema);
        admin.close();
    }
}
