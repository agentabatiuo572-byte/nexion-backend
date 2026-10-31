package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import ffdd.opsconsole.auth.facade.PaymentAuthorizationFacade.Authorization;
import ffdd.opsconsole.auth.mapper.PaymentPasswordMapper;
import ffdd.opsconsole.auth.mapper.PaymentSecurityMapper;
import ffdd.opsconsole.content.application.SupportOwnershipService;
import ffdd.opsconsole.auth.web.AppPaymentPasswordController;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="PAYMENT_PIN_TEST_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:33348/payment_pin_backend_test.*")
class PaymentPasswordMySqlIntegrationTest {
    static DataSource source;
    static JdbcTemplate jdbc;
    static PaymentPasswordMapper mapper;
    static PaymentSecurityMapper securityMapper;
    static final AtomicLong USERS=new AtomicLong(System.currentTimeMillis());
    AppPaymentPasswordService service;
    MutableClock clock;
    UserOtpDeliveryService delivery;
    long user;

    @BeforeAll static void installMinimalSchema() throws Exception {
        DriverManagerDataSource db=new DriverManagerDataSource(System.getenv("PAYMENT_PIN_TEST_DB_URL"),
                System.getenv("PAYMENT_PIN_TEST_DB_USERNAME"),System.getenv("PAYMENT_PIN_TEST_DB_PASSWORD"));
        source=db; jdbc=new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_user(id BIGINT PRIMARY KEY,user_no VARCHAR(64),nickname VARCHAR(64),country_code VARCHAR(8),phone VARCHAR(32),status VARCHAR(24),is_deleted TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_admin_idempotency_record(id BIGINT AUTO_INCREMENT PRIMARY KEY,scope VARCHAR(128),idempotency_key VARCHAR(128),request_hash CHAR(64),status VARCHAR(24),response_json MEDIUMTEXT,is_deleted TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS pin_test_effect(user_id BIGINT PRIMARY KEY,balance BIGINT NOT NULL DEFAULT 100,effects INT NOT NULL DEFAULT 0)");
        try(Connection c=db.getConnection();Statement statement=c.createStatement()) {
            for(String sql:Files.readString(Path.of("scripts/migrations/20261030_payment_password.sql")).split(";"))
                if(!sql.isBlank()) statement.execute(sql);
        }
        Configuration config=new Configuration();
        config.setEnvironment(new Environment("pin-isolated",new SpringManagedTransactionFactory(),db));
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.addMapper(PaymentPasswordMapper.class);
        config.addMapper(PaymentSecurityMapper.class);
        var session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        mapper=session.getMapper(PaymentPasswordMapper.class);
        securityMapper=session.getMapper(PaymentSecurityMapper.class);
    }

    @BeforeEach void fixture() {
        jdbc.update("UPDATE nx_payment_password_policy SET max_attempts=5,window_minutes=15,lock_minutes=15,version=0,updated_at=NULL,updated_by=NULL WHERE id=1");
        user=USERS.incrementAndGet(); clock=new MutableClock();
        jdbc.update("INSERT INTO nx_user(id,country_code,phone,status) VALUES(?, '+86', ?, 'ACTIVE')",user,"13"+user);
        jdbc.update("INSERT INTO pin_test_effect(user_id) VALUES(?)",user);
        delivery=mock(UserOtpDeliveryService.class);
        when(delivery.available("+86")).thenReturn(true);
        when(delivery.verificationCode("+86")).thenReturn("507193");
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        service=new AppPaymentPasswordService(mapper,new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),clock,delivery,config,
                mock(ffdd.opsconsole.shared.audit.AuditLogService.class),
                new DataSourceTransactionManager(source),"isolated-test-pepper-32-bytes-or-longer","v1",5,900,900);
    }
    @AfterEach void cleanSyntheticRows() {
        for(String table:List.of("nx_payment_security_audit","nx_payment_security_record","nx_payment_security_admin_command","nx_payment_password_attempt","nx_payment_password_reset","nx_payment_command",
                "nx_verified_phone","nx_payment_password","pin_test_effect"))
            jdbc.update("DELETE FROM "+table+" WHERE user_id=?",user);
        jdbc.update("DELETE FROM nx_user WHERE id=?",user);
        jdbc.update("DELETE FROM nx_payment_security_audit WHERE user_id=0");
        jdbc.update("DELETE FROM nx_payment_security_admin_command WHERE user_id=0");
        SecurityContextHolder.clearContext();
    }
    void setup() { assertThat(service.setup(user,"setup-"+user,new AppPaymentPasswordService.Setup("018290","018290")).getCode()).isZero(); }
    Authorization pin(String value,String attempt) { return new Authorization(value,1L,attempt); }
    ApiResult<Map<String,Object>> pay(String key,Authorization authorization) {
        return service.execute(user,"order-pay",key,"order:one",authorization,() -> {
            jdbc.update("UPDATE pin_test_effect SET balance=balance-10,effects=effects+1 WHERE user_id=?",user);
            return ApiResult.ok(Map.of("orderNo","TEST-"+user,"paid",true));
        });
    }
    @Test void incorrectPinDoesNotConsumeBusinessKeyAndSuccessfulReplayDoesNotNeedPin() {
        setup();
        assertThat(pay("purchase-one",pin("999998","attempt-wrong-1")).getMessage()).isEqualTo("PAYMENT_PASSWORD_INCORRECT");
        assertThat(service.state(user).remainingAttempts()).isEqualTo(4);
        assertThat(pay("purchase-one",pin("018290","attempt-right-1")).getCode()).isZero();
        assertThat(pay("purchase-one",null).getCode()).isZero();
        assertThat(jdbc.queryForMap("SELECT balance,effects FROM pin_test_effect WHERE user_id=?",user)).containsEntry("balance",90L).containsEntry("effects",1);
        assertThat(service.query(user,"order-pay","purchase-one")).containsEntry("status","SUCCEEDED").containsEntry("canResumeSameKey",false);
        assertThat(service.state(user).remainingAttempts()).isEqualTo(5);
    }
    @Test void networkResendDoesNotRecountAndFiveNewGuessesLockAcrossPurposes() {
        setup();
        Authorization wrong=pin("999998","same-attempt-1");
        pay("purchase-lock",wrong); pay("purchase-lock",wrong);
        assertThat(service.state(user).remainingAttempts()).isEqualTo(4);
        for(int i=2;i<=4;i++) pay("purchase-lock",pin("999998","new-attempt-"+i));
        ApiResult<?> changed=service.change(user,"change-lock",new AppPaymentPasswordService.Change("999998","394827","394827",1L,"attempt-change-5"));
        assertThat(changed.getCode()).isEqualTo(429);
        assertThat(pay("purchase-lock",pin("018290","attempt-during-lock")).getMessage()).isEqualTo("PAYMENT_PASSWORD_LOCKED");
        assertThat(jdbc.queryForObject("SELECT effects FROM pin_test_effect WHERE user_id=?",Integer.class,user)).isZero();
        clock.advance(901);
        assertThat(service.state(user).lockedUntil()).isNull();
        assertThat(service.state(user).remainingAttempts()).isEqualTo(5);
        assertThat(pay("purchase-lock",pin("018290","attempt-after-lock")).getCode()).isZero();
    }
    @Test void rollingWindowDropsOldFailuresAndCredentialVersionRejectsStaleClients() {
        setup(); pay("purchase-roll",pin("999998","rolling-failure-1")); clock.advance(901);
        assertThat(service.state(user).remainingAttempts()).isEqualTo(5);
        assertThat(service.change(user,"change-okay",new AppPaymentPasswordService.Change("018290","394827","394827",1L,"attempt-change-okay")).getCode()).isZero();
        assertThat(service.state(user).credentialVersion()).isEqualTo(2);
        assertThat(pay("purchase-roll",pin("018290","attempt-stale-version")).getMessage()).isEqualTo("PAYMENT_PASSWORD_VERSION_CONFLICT");
        assertThat(service.state(user).remainingAttempts()).isEqualTo(5);
    }
    @Test void aBusinessExceptionRollsBackMoneyButKeepsPinAttemptAndTerminalRejection() {
        setup();
        ApiResult<?> rejection=service.execute(user,"trade-in","purchase-fails","source:1",pin("018290","attempt-business-failure"),() -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("READ-COMMITTED");
            jdbc.update("UPDATE pin_test_effect SET balance=balance-10,effects=effects+1 WHERE user_id=?",user);
            throw new BizException(409,"TRADEIN_QUOTE_CHANGED");
        });
        assertThat(rejection.getMessage()).isEqualTo("TRADEIN_QUOTE_CHANGED");
        assertThat(jdbc.queryForObject("SELECT effects FROM pin_test_effect WHERE user_id=?",Integer.class,user)).isZero();
        assertThat(mapper.attempt(user,"attempt-business-failure")).containsEntry("result_message","BUSINESS_REJECTED");
        assertThat(service.query(user,"trade-in","purchase-fails")).containsEntry("status","REJECTED");
        assertThat(service.execute(user,"trade-in","purchase-fails","source:1",pin("018290","attempt-late-original"),() -> {throw new AssertionError("late request executed");}).getCode()).isEqualTo(409);
    }
    @Test void sameKeyRecoverySerializesLateOriginalAndShowsProcessingWhileLocked() throws Exception {
        setup();
        CountDownLatch inside=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            Future<ApiResult<Map<String,Object>>> original=executor.submit(() -> service.execute(user,"capacity-keep","purchase-race","product:one",pin("018290","attempt-original"),() -> {
                inside.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                jdbc.update("UPDATE pin_test_effect SET effects=effects+1 WHERE user_id=?",user);
                return ApiResult.ok(Map.of("orderNo","RACE-"+user));
            }));
            assertThat(inside.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(service.query(user,"capacity-keep","purchase-race")).containsEntry("status","PROCESSING").containsEntry("canResumeSameKey",false);
            Future<ApiResult<Map<String,Object>>> resumed=executor.submit(() -> service.execute(user,"capacity-keep","purchase-race","product:one",pin("018290","attempt-resumed"),() -> { throw new AssertionError("duplicate purchase"); }));
            release.countDown();
            assertThat(original.get(10,TimeUnit.SECONDS).getCode()).isZero();
            assertThat(resumed.get(10,TimeUnit.SECONDS).getCode()).isZero();
            assertThat(jdbc.queryForObject("SELECT effects FROM pin_test_effect WHERE user_id=?",Integer.class,user)).isEqualTo(1);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void checkedCallbackFailureRollsBackReadCommittedBusinessAndCannotWriteSuccessReceipt() {
        setup();
        assertThatThrownBy(() -> service.execute(user,"capacity-keep","purchase-checked-failure","product:one",pin("018290","attempt-checked-failure"),() -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("READ-COMMITTED");
            jdbc.update("UPDATE pin_test_effect SET balance=balance-10,effects=effects+1 WHERE user_id=?",user);
            PaymentPasswordMySqlIntegrationTest.<RuntimeException>throwChecked(new Exception("checked callback failure"));
            return ApiResult.ok(Map.of("orderNo","unreachable"));
        })).hasRootCauseMessage("checked callback failure");
        assertThat(jdbc.queryForMap("SELECT balance,effects FROM pin_test_effect WHERE user_id=?",user)).containsEntry("balance",100L).containsEntry("effects",0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_payment_command WHERE user_id=? AND command_key='purchase-checked-failure' AND (status='SUCCEEDED' OR response_json IS NOT NULL)",Integer.class,user)).isZero();
        assertThat(mapper.attempt(user,"attempt-checked-failure")).isNotNull();
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwChecked(Throwable failure) throws E { throw (E)failure; }
    @Test void setupIsAtomicAndSecretsNeverReachDurableBusinessSummary() throws Exception {
        setup();
        assertThat(service.setup(user,"other-setup",new AppPaymentPasswordService.Setup("394827","394827")).getMessage()).isEqualTo("PAYMENT_PASSWORD_ALREADY_CONFIGURED");
        pay("purchase-secret",pin("018290","attempt-secret"));
        String dump=jdbc.queryForList("SELECT * FROM nx_payment_command WHERE user_id=?",user).toString()
                +jdbc.queryForList("SELECT * FROM nx_payment_password_attempt WHERE user_id=?",user);
        assertThat(dump).doesNotContain("018290","394827");
        assertThat(String.valueOf(mapper.credential(user,false).get("password_hash"))).startsWith("$2a$12$");
    }
    @Test void missingPinAndAllFiveKindsFailBeforeAnyBusinessEffects() {
        setup();
        for(String kind:List.of("order-pay","trial-convert","trade-in","capacity-replace","capacity-keep")) {
            assertThat(service.execute(user,kind,"missing-"+kind,"intent",null,() -> {
                throw new AssertionError("unauthorized business action");
            }).getMessage()).isEqualTo("PAYMENT_PASSWORD_REQUIRED");
        }
        assertThat(service.state(user).remainingAttempts()).isEqualTo(5);
    }
    @Test void completedEffectsSurviveOrdinaryReceiptCacheExpiryAndChangedSummaryCannotExecute() {
        setup(); pay("durable-receipt",pin("018290","durable-attempt"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin_idempotency_record WHERE scope=?",Integer.class,"APP:ORDER_PAYMENT:USER:"+user)).isZero();
        assertThat(service.query(user,"order-pay","durable-receipt")).containsEntry("status","SUCCEEDED");
        assertThat(pay("durable-receipt",null).getCode()).isZero();
        assertThatThrownBy(() -> service.execute(user,"order-pay","durable-receipt","changed-summary",pin("018290","changed-attempt"),() -> {
            throw new AssertionError("changed intent executed");
        })).hasMessage("PAYMENT_COMMAND_INPUT_CHANGED");
        assertThat(jdbc.queryForObject("SELECT effects FROM pin_test_effect WHERE user_id=?",Integer.class,user)).isEqualTo(1);
    }
    @Test void unknownRuntimeFailureRollsBackEffectsAndUserCanResumeOnlyWithNewAttempt() {
        setup();
        assertThat(service.query(user,"trial-convert","unknown-intent")).containsEntry("status","NOT_SEEN").containsEntry("canResumeSameKey",true);
        assertThatThrownBy(() -> service.execute(user,"trial-convert","unknown-intent","same-summary",pin("018290","unknown-original"),() -> {
            jdbc.update("UPDATE pin_test_effect SET effects=effects+1 WHERE user_id=?",user);
            throw new IllegalStateException("isolated transport failure before commit");
        })).hasMessageContaining("isolated transport failure");
        assertThat(service.query(user,"trial-convert","unknown-intent")).containsEntry("status","NOT_SEEN");
        assertThat(service.execute(user,"trial-convert","unknown-intent","same-summary",pin("018290","unknown-original"),() -> {throw new AssertionError("old authorization reused");}).getMessage())
                .isEqualTo("PAYMENT_PASSWORD_ATTEMPT_ALREADY_USED");
        assertThat(service.execute(user,"trial-convert","unknown-intent","same-summary",pin("018290","unknown-resumed"),() -> {
            jdbc.update("UPDATE pin_test_effect SET effects=effects+1 WHERE user_id=?",user);
            return ApiResult.ok(Map.of("orderNo","RESUMED-"+user));
        }).getCode()).isZero();
        assertThat(jdbc.queryForObject("SELECT effects FROM pin_test_effect WHERE user_id=?",Integer.class,user)).isEqualTo(1);
    }
    @Test void twoFirstSetupsCannotOverwriteEachOther() throws Exception {
        ExecutorService executor=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Future<ApiResult<Map<String,Object>>> first=executor.submit(() -> { start.await(); return service.setup(user,"parallel-setup-a",new AppPaymentPasswordService.Setup("018290","018290")); });
            Future<ApiResult<Map<String,Object>>> second=executor.submit(() -> { start.await(); return service.setup(user,"parallel-setup-b",new AppPaymentPasswordService.Setup("394827","394827")); });
            start.countDown();
            List<Integer> codes=List.of(first.get(10,TimeUnit.SECONDS).getCode(),second.get(10,TimeUnit.SECONDS).getCode());
            assertThat(codes).containsExactlyInAnyOrder(0,409);
            assertThat(service.state(user).credentialVersion()).isEqualTo(1);
        } finally { start.countDown(); executor.shutdownNow(); }
    }
    @Test void resetRequiresExistingTrustedPhoneSessionAndOneTimeProof() {
        setup();
        assertThatThrownBy(() -> service.resetChallenge(user,"session-a")).hasMessage("PAYMENT_PASSWORD_RECOVERY_UNAVAILABLE");
        service.recordVerifiedPhone(user,"+86","13"+user);
        assertThatThrownBy(() -> service.resetChallenge(user,"session-a")).hasMessage("PAYMENT_PASSWORD_RECOVERY_UNAVAILABLE");
        assertThat(service.change(user,"trust-current-phone",new AppPaymentPasswordService.Change("018290","394827","394827",1L,"attempt-trust-phone")).getCode()).isZero();
        Map<String,Object> challenge=service.resetChallenge(user,"session-a");
        String number=String.valueOf(challenge.get("challengeNo"));
        assertThat(service.resetVerify(user,"session-b",number,"507193").getCode()).isNotZero();
        assertThat(service.resetVerify(user,"session-a",number,"507194").getCode()).isEqualTo(422);
        ApiResult<Map<String,Object>> verified=service.resetVerify(user,"session-a",number,"507193");
        assertThat(verified.getCode()).isZero();
        String proof=String.valueOf(verified.getData().get("resetProof"));
        assertThat(service.reset(user,"session-a","reset-okay",new AppPaymentPasswordService.Reset(proof,"583091","583091",2L)).getCode()).isZero();
        assertThat(service.state(user).credentialVersion()).isEqualTo(3);
        assertThat(service.reset(user,"session-a","reset-reused",new AppPaymentPasswordService.Reset(proof,"394827","394827",3L)).getCode()).isNotZero();
        assertThat(service.reset(user,"session-a","reset-okay",new AppPaymentPasswordService.Reset(proof,"583091","583091",2L)).getCode()).isZero();
        verify(delivery).deliver(eq("+86"),eq("13"+user),eq(number),eq("507193"),eq(5));
    }
    @Test void servletHttpContractReturnsStateAndNeverTreatsWrongPinAsLoginExpired() throws Exception {
        UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken(String.valueOf(user),null,List.of());
        auth.setDetails(Map.of("subjectType","USER","sessionId","isolated-http-session"));
        MockMvc http=MockMvcBuilders.standaloneSetup(new AppPaymentPasswordController(service)).build();
        http.perform(get("/api/app/security/payment-password").principal(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false)).andExpect(jsonPath("$.data.credentialVersion").value(0));
        http.perform(post("/api/app/security/payment-password/setup").principal(auth).header("Idempotency-Key","http-setup")
                .contentType("application/json").content("{\"newPassword\":\"018290\",\"confirmPassword\":\"018290\"}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.state.configured").value(true));
        http.perform(post("/api/app/security/payment-password/change").principal(auth).header("Idempotency-Key","http-change")
                .contentType("application/json").content("{\"currentPassword\":\"999998\",\"newPassword\":\"394827\",\"confirmPassword\":\"394827\",\"expectedCredentialVersion\":1,\"attemptNo\":\"http-wrong-attempt\"}"))
                .andExpect(jsonPath("$.code").value(422)).andExpect(jsonPath("$.message").value("PAYMENT_PASSWORD_INCORRECT"))
                .andExpect(jsonPath("$.data.remainingAttempts").value(4));
    }

    private OpsPaymentSecurityService admin(ffdd.opsconsole.shared.audit.AuditLogService audit) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("9001",null,List.of(
                new SimpleGrantedAuthority("user_c5_payment_password_release"),new SimpleGrantedAuthority("user_c5_payment_password_policy_write"))));
        var ownership=mock(SupportOwnershipService.class); when(ownership.actorId()).thenReturn(9001L);
        return new OpsPaymentSecurityService(mapper,securityMapper,ownership,audit,
                new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),clock);
    }
    private String lockForAdmin() {
        setup();
        for(int i=0;i<5;i++) pay("admin-lock-"+user,pin("999998","admin-attempt-"+i));
        return String.valueOf(securityMapper.latest(user).get("record_no"));
    }
    @Test void adminReleaseIsAtomicCasAuditedAndIdempotentWithoutChangingThePin() {
        String record=lockForAdmin();
        var audit=mock(ffdd.opsconsole.shared.audit.AuditLogService.class);var admin=admin(audit);
        var request=new OpsPaymentSecurityService.ReleaseRequest(String.valueOf(user),record,"RELEASE_LOCK",0L,"客户身份已独立完成核验",true);
        var transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
        Map<String,Object> receipt=transactions.execute(tx->admin.release(record,null,false,"admin-release-"+user,request));
        assertThat(receipt).containsEntry("status","APPLIED");
        assertThat(service.state(user).lockedUntil()).isNull();
        assertThat(service.state(user).credentialVersion()).isEqualTo(1);
        assertThat(service.state(user).remainingAttempts()).isEqualTo(5);
        assertThat(securityMapper.record(record,false)).containsEntry("state","RELEASED").containsEntry("version",1L);
        assertThat(transactions.<Map<String,Object>>execute(tx->admin.release(record,null,false,"admin-release-"+user,request))).containsEntry("status","APPLIED");
        verify(audit,times(1)).recordRequired(any());
        assertThat(securityMapper.audits(record)).hasSize(1);
        assertThat(admin.list(null,false,String.valueOf(user),"ALL","2026-10-30","2026-10-30",1,10)).containsEntry("total",1L);
        assertThat(pay("after-admin-release",pin("018290","after-admin-attempt")).getCode()).isZero();
    }
    @Test void requiredAuditFailureRollsBackAdminUnlockAndItsReceipt() {
        String record=lockForAdmin();
        var audit=mock(ffdd.opsconsole.shared.audit.AuditLogService.class);
        doThrow(new IllegalStateException("isolated audit unavailable")).when(audit).recordRequired(any());
        var admin=admin(audit);var transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
        var request=new OpsPaymentSecurityService.ReleaseRequest(String.valueOf(user),record,"RELEASE_LOCK",0L,"客户身份已独立完成核验",true);
        assertThatThrownBy(()->transactions.execute(tx->admin.release(record,null,false,"admin-fails-"+user,request))).hasMessage("isolated audit unavailable");
        assertThat(service.state(user).lockedUntil()).isNotNull();
        assertThat(securityMapper.record(record,false)).containsEntry("state","ACTIVE").containsEntry("version",0L);
        assertThat(securityMapper.command(9001L,"global","admin-fails-"+user,false)).isNull();
        assertThat(securityMapper.audits(record)).isEmpty();
    }
    @Test void policyUpdateControlsNewAttemptsButPreservesExistingLockDeadline() {
        String record=lockForAdmin();Long until=((Number)mapper.credential(user,false).get("locked_until")).longValue();
        var admin=admin(mock(ffdd.opsconsole.shared.audit.AuditLogService.class));
        var transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
        var request=new OpsPaymentSecurityService.PolicyRequest(new OpsPaymentSecurityService.Values(3,10,20),0L,"调整钱包密码错误控制策略",true);
        assertThat(transactions.<Map<String,Object>>execute(tx->admin.changePolicy("policy-change-"+user,request))).containsEntry("status","APPLIED");
        assertThat(mapper.credential(user,false)).containsEntry("locked_until",until);
        assertThat(admin.policy()).containsEntry("version",1L);
        assertThat(transactions.<Map<String,Object>>execute(tx->admin.changePolicy("policy-stale-"+user,request))).containsEntry("status","REJECTED");
        assertThat(securityMapper.record(record,false)).containsEntry("state","ACTIVE");
    }
    static final class MutableClock extends Clock {
        private Instant now=Instant.parse("2026-10-30T13:00:00Z");
        void advance(long seconds) { now=now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
