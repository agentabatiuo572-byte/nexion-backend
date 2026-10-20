package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import ffdd.opsconsole.auth.application.*;
import ffdd.opsconsole.auth.captcha.CaptchaOtpGate;
import ffdd.opsconsole.auth.dto.*;
import ffdd.opsconsole.auth.mapper.*;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.config.*;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.*;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** SPEC07 S07/S08 continuous server-side observations in an owned schema.
 * Real auth/session/phone/terms mappers and Spring transactions; OTP delivery/captcha and support
 * notifications are adapters under test control. This is not HTTP, a browser cancellation or native evidence. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppSessionAcceptanceMySqlTest {
    final OnboardingActivationTransactionMySqlTest f=new OnboardingActivationTransactionMySqlTest();
    AppUserAuthService auth;
    CurrentAppSessionService current;
    final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder();
    static final String PASSWORD="Isolated-acceptance-123!";
    @RegisterExtension final TestWatcher result=new TestWatcher(){
        @Override public void testFailed(ExtensionContext c,Throwable t){f.failed=true;}
    };
    @BeforeAll void setup() throws Exception {
        f.setup();
        f.createTable(Files.readString(Path.of("scripts/schema.sql")),"nx_user_otp_challenge");
        var configuration=new MybatisConfiguration(new Environment("session-acceptance",new SpringManagedTransactionFactory(),f.jdbc.getDataSource()));
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.setGlobalConfig(configuration,new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig())
                .setMetaObjectHandler(new MybatisMetaObjectHandler(Clock.system(DateTimeFormatConfig.BUSINESS_ZONE))));
        for(Class<?> mapper:List.of(UserOpsMapper.class,AuthSessionMapper.class,AppLoginAttemptMapper.class,UserLoginGuardMapper.class))configuration.addMapper(mapper);
        var sql=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var users=sql.getMapper(UserOpsMapper.class);var sessions=sql.getMapper(AuthSessionMapper.class);
        var guards=sql.getMapper(UserLoginGuardMapper.class);
        guards.createTable();guards.createOtpSendGuardTable();guards.createOtpSendEventTable();
        var environment=new MockEnvironment();environment.setActiveProfiles("dev");
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenAnswer(call->f.jdbc.query("SELECT config_value FROM nx_config_item WHERE config_key=? AND is_deleted=0",
                (rs,row)->rs.getString(1),(String)call.getArgument(0)).stream().findFirst());
        var domain=AopTestUtils.getUltimateTargetObject(f.domain);
        var audit=(AuditLogService)ReflectionTestUtils.getField(domain,"audit");
        var outbox=(EventOutboxService)ReflectionTestUtils.getField(domain,"outbox");
        var blocklist=mock(UserAccountBlocklistVerifier.class);
        var properties=new JwtProperties();var tokens=new JwtTokenProvider(properties);
        var single=f.proxy(new AppSingleSessionService(sessions,sql.getMapper(AppLoginAttemptMapper.class),tokens,f.phone,audit,outbox,config,users,blocklist,environment));
        current=f.proxy(new CurrentAppSessionService(sessions,config,blocklist,users,environment));
        var delivery=mock(UserOtpDeliveryService.class);
        when(delivery.available(anyString())).thenReturn(true);when(delivery.verificationCode(anyString())).thenReturn("123456");
        var captcha=mock(CaptchaOtpGate.class);
        when(captcha.checkAndConsume(any(),any(),any(),anyInt())).thenReturn(new CaptchaOtpGate.Decision(true,0,"OK"));
        auth=f.proxy(new AppUserAuthService(users,sessions,guards,passwords,tokens,properties,blocklist,config,captcha,delivery,outbox,environment,
                mock(ffdd.opsconsole.content.application.SupportActivityService.class),single,f.proxy(new UserSessionRevocationService(sessions,f.phone))));
    }
    @AfterAll void cleanup() throws Exception {f.cleanup();}
    String phone(long user){return "1990000"+user;}
    void runningAccount(long user){
        f.first(user);f.task(user);
        f.jdbc.update("UPDATE nx_user SET country_code='+86',phone=?,password_hash=? WHERE id=?",phone(user),passwords.encode(PASSWORD),user);
    }
    AppLoginContext context(String method,String reference){return new AppLoginContext(AppSessionSurface.APP,"phone-b",true,
            "attempt_"+UUID.randomUUID().toString().replace("-",""),UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""),method,reference);}
    Map<String,Object> authority(long user){
        var state=new LinkedHashMap<>(f.snapshot(user));
        for(String table:List.of("nx_user_session","nx_app_session_state","nx_app_login_attempt"))state.put(table,f.jdbc.queryForList("SELECT * FROM "+table+" WHERE user_id=?",user));
        return state;
    }
    Authentication authentication(long user,String session){var value=new UsernamePasswordAuthenticationToken(Long.toString(user),null,List.of());value.setDetails(Map.of("subjectType","USER","sessionId",session));return value;}
    void requireCurrent(Authentication value){new TransactionTemplate(f.manager).execute(status->current.requireCurrent(value,"phone-b",true));}

    @Test void wrongPasswordPendingTwoFactorWrongOtpAndAbandonedChallengeLeaveTheOldSessionAndTaskUnchanged(){
        long user=7401;runningAccount(user);var before=authority(user);
        var original=context("PASSWORD","+86:"+phone(user));
        var wrong=auth.login(new UserLoginRequest("+86",phone(user),"incorrect"),"acceptance-7401",original);
        assertThat(wrong.getCode()).isEqualTo(401);assertThat(wrong.getData()).isNull();
        assertThat(authority(user)).isEqualTo(before);
        f.jdbc.update("UPDATE nx_user_security SET two_factor_enabled=1 WHERE user_id=?",user);
        var pending=auth.login(new UserLoginRequest("+86",phone(user),PASSWORD),"acceptance-7401",original);
        assertThat(pending.getCode()).isEqualTo(428);assertThat(pending.getMessage()).isEqualTo("USER_TWO_FACTOR_VERIFICATION_REQUIRED");
        assertThat(pending.getData().accessToken()).isNull();assertThat(pending.getData().refreshToken()).isNull();
        assertThat(authority(user)).isEqualTo(before);
        var second=new AppLoginContext(original.group(),original.installationId(),true,original.loginAttemptId(),original.recoveryProof(),"TWO_FACTOR",pending.getData().challengeNo());
        var invalid=auth.completeTwoFactorLogin(new UserTwoFactorLoginRequest("+86",phone(user),PASSWORD,pending.getData().challengeNo(),"654321"),"acceptance-7401",second);
        assertThat(invalid.getCode()).isEqualTo(422);
        assertThat(authority(user)).isEqualTo(before);
        // Abandonment means no completion command. The durable challenge alone must not transfer ownership.
        assertThat(f.jdbc.queryForObject("SELECT consumed_at IS NULL FROM nx_user_otp_challenge WHERE challenge_no=?",Boolean.class,pending.getData().challengeNo())).isTrue();
        assertThat(authority(user)).isEqualTo(before);
        assertThat(f.jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no=?",String.class,"task-"+user)).isEqualTo("RUNNING");
        assertThat(f.count("nx_phone_replacement_event",user)).isZero();
    }
    @Test void takeoverCommitsBeforeTermsAndCalibrationWhileBlockedExecutionCannotRestoreTheOldPhone(){
        long user=7402;runningAccount(user);
        String old=f.jdbc.queryForObject("SELECT session_chain_id FROM nx_app_session_state WHERE user_id=?",String.class,user);
        f.jdbc.update("DELETE FROM nx_legal_terms_ack WHERE user_id=?",user);
        var context=context("PASSWORD","+86:"+phone(user));
        var login=auth.login(new UserLoginRequest("+86",phone(user),PASSWORD),"acceptance-7402",context);
        assertThat(login.getCode()).as(login.getMessage()).isZero();
        assertThat(login.getData().accessToken()).isNotBlank();assertThat(login.getData().installationId()).isEqualTo("phone-b");
        assertThat(login.getData().appSessionVersion()).isEqualTo(2L);
        String id=f.jdbc.queryForObject("SELECT refresh_token_id FROM nx_user_session WHERE session_chain_id=?",String.class,login.getData().sessionChainId());
        var b=authentication(user,id);requireCurrent(b);
        assertThat(f.jdbc.queryForObject("SELECT session_ended_reason FROM nx_user_session WHERE session_chain_id=?",String.class,old)).isEqualTo("APP_REPLACED");
        assertThat(f.jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no=?",String.class,"task-"+user)).isEqualTo("CANCELLED");
        assertThat(f.jdbc.queryForObject("SELECT installation_id FROM nx_phone_binding WHERE user_id=?",String.class,user)).isEqualTo("phone-a");
        assertThat(f.jdbc.queryForObject("SELECT execution_installation_id FROM nx_phone_binding WHERE user_id=?",String.class,user)).isEmpty();
        var before=authority(user);
        assertThat(f.app.eligibility(b).blockedReasons()).contains("LEGAL_TERMS_ACK_REQUIRED","ONBOARDING_CALIBRATION_REQUIRED");
        assertThat(authority(user)).isEqualTo(before);
        assertThatThrownBy(()->f.phone.activate(user,f.action("phone-b",0,1L,"terms-block-7402"),b)).hasMessage("LEGAL_TERMS_ACK_REQUIRED");
        assertThat(authority(user)).isEqualTo(before);requireCurrent(b);
        f.ack(user);
        var denied=f.phone.activate(user,f.action("phone-b",0,1L,"calibration-block-7402"),b);
        assertThat(denied.getCode()).isEqualTo(409);assertThat(denied.getMessage()).isEqualTo("ONBOARDING_CALIBRATION_REQUIRED");
        requireCurrent(b);
        assertThat(f.jdbc.queryForObject("SELECT app_session_version FROM nx_app_session_state WHERE user_id=?",Long.class,user)).isEqualTo(2L);
        assertThat(f.jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no=?",String.class,"task-"+user)).isEqualTo("CANCELLED");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_session WHERE user_id=? AND revoked_at IS NULL AND session_group='APP'",Integer.class,user)).isEqualTo(1);
        assertThat(f.count("nx_phone_replacement_event",user)).isZero();
        assertThat(auth.recoverLogin(context).getData().sessionChainId()).isEqualTo(login.getData().sessionChainId());
    }
}
