package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.growth.web.AppTrialLifecycleController;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.canonical.StorefrontProductReleasePolicy;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.outbox.OutboxProperties;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Real MySQL/A4/HTTP/transaction regression; all writes go to connection-local temporary tables. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class TrialGraceStateMySqlIntegrationTest {
    private static final String EVENT = "trial.grace_entered";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"),
            ZoneId.of("Asia/Shanghai"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EarningsReleaseService earnings = mock(EarningsReleaseService.class);
    private final TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
    private Connection connection;
    private JdbcTemplate jdbc;
    private MockMvc http;
    private EventOutboxService outbox;

    @BeforeEach
    void isolatedTablesAndRealTransactionalBoundary() throws Exception {
        connection = DriverManager.getConnection(System.getenv().getOrDefault("NEXION_TEST_DB_URL",
                        "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8"
                                + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"),
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
                System.getenv("NEXION_TEST_DB_PASSWORD"));
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(dataSource);
        // Copy only DDL, never live rows. Closing this connection removes every fixture,
        // including after a failed assertion; no DROP/DELETE touches persistent tables.
        for (String table : List.of("nx_event_schema_registry", "nx_event_schema_property",
                "nx_event_schema_revision", "nx_admin_event_lifecycle", "nx_event_outbox", "nx_trial_claim")) {
            String ddl = jdbc.queryForObject("SHOW CREATE TABLE " + table, (rs, row) -> rs.getString(2));
            // MySQL temporary tables cannot carry foreign keys. Keep all columns,
            // indexes and unique constraints; the live migration retains its FKs.
            jdbc.execute(ddl.replaceFirst("CREATE TABLE", "CREATE TEMPORARY TABLE")
                    .replaceAll(",\\n\\s*CONSTRAINT\\s+`[^`]+`\\s+FOREIGN KEY[^\\n]*", ""));
        }

        Configuration configuration = new Configuration(new Environment("trial-grace-test",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AppTrialLifecycleMapper.class);
        configuration.addMapper(EventOutboxMapper.class);
        SqlSessionTemplate session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        AppTrialLifecycleMapper persisted = session.getMapper(AppTrialLifecycleMapper.class);
        AppTrialLifecycleMapper mapper = mock(AppTrialLifecycleMapper.class);
        when(mapper.activeUser(42L)).thenReturn(42L);
        when(mapper.trial(42L)).thenAnswer(call -> persisted.trial(42L));
        when(mapper.lockTrial(42L)).thenAnswer(call -> persisted.lockTrial(42L));
        when(mapper.enterGrace(anyLong(), anyLong(), any())).thenAnswer(call ->
                persisted.enterGrace(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        when(mapper.attribution(42L)).thenReturn(new AppTrialLifecycleMapper.Attribution("P2", 2, "2026-W36"));
        when(mapper.policies()).thenReturn(List.of(new AppTrialLifecycleMapper.PolicyRow("graceDays", "7")));
        outbox = new EventOutboxService(session.getMapper(EventOutboxMapper.class), json,
                new OutboxProperties(), mock(A4RuntimePolicyService.class));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        AppTrialLifecycleService target = new AppTrialLifecycleService(mapper, earnings,
                mock(AdminIdempotencyService.class), coverage, audit, outbox,
                mock(StorefrontProductReleasePolicy.class), mock(CanonicalStateMapper.class), environment, CLOCK);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        http = MockMvcBuilders.standaloneSetup(new AppTrialLifecycleController(
                        (AppTrialLifecycleService) proxy.getProxy()))
                .setControllerAdvice(new GlobalExceptionHandler(audit)).build();
    }

    @AfterEach
    void closeTemporaryTables() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void missingSchemaDoesNotBlockThePureReadProjectionOrWriteAnything() throws Exception {
        seedTrial("ACTIVE", NOW.minusSeconds(1));
        for (int retry = 0; retry < 2; retry++) {
            state().andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.state").value("GRACE"));
            assertPersisted("ACTIVE", 0, 0);
        }
        verifyNoInteractions(audit, earnings, coverage);
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,0", "ACTIVE,-1", "CLAIMED,0", "CLAIMED,-1"})
    void expiredTrialProjectsGraceWithoutPersistingOrPublishing(String initialState, long expiryOffsetSeconds) throws Exception {
        migrate();
        migrate();
        seedTrial(initialState, NOW.plusSeconds(expiryOffsetSeconds));
        for (int retry = 0; retry < 2; retry++) {
            state().andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.state").value("GRACE"))
                    .andExpect(jsonPath("$.data.version").value(0))
                    .andExpect(jsonPath("$.data.shadowUsdt").value(120))
                    .andExpect(jsonPath("$.data.shadowNex").value(15));
        }
        assertPersisted(initialState, 0, 0);
        verifyNoInteractions(earnings, coverage);
    }

    @Test
    void unexpiredTrialDoesNotPublishAnEventOrRequireTheMissingSchema() throws Exception {
        seedTrial("ACTIVE", NOW.plusSeconds(1));
        state().andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("ACTIVE"));
        assertPersisted("ACTIVE", 0, 0);
        verifyNoInteractions(audit, earnings, coverage);
    }

    @Test
    void operatorDisabledLifecycleCannotTurnTheReadProjectionIntoAWrite() throws Exception {
        migrate();
        jdbc.update("UPDATE nx_admin_event_lifecycle SET lifecycle_state='disabled',version=9,changed_by='operator' WHERE event_name=?", EVENT);
        migrate();
        seedTrial("ACTIVE", NOW);
        state().andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.state").value("GRACE"));
        assertPersisted("ACTIVE", 0, 0);
        assertThat(jdbc.queryForObject("SELECT version FROM nx_admin_event_lifecycle", Long.class)).isEqualTo(9);
        verifyNoInteractions(audit, earnings, coverage);
    }

    @Test
    void futureSchemaRevisionAndPropertiesAreNotDowngraded() throws Exception {
        migrate();
        jdbc.update("UPDATE nx_event_schema_registry SET current_revision=315,reason='future contract' WHERE event_name=?", EVENT);
        jdbc.update("UPDATE nx_event_schema_property SET registry_revision=315,required_field=0");
        jdbc.update("UPDATE nx_event_schema_revision SET current_revision=315 WHERE id=1");
        migrate();
        assertThat(jdbc.queryForObject("SELECT current_revision FROM nx_event_schema_registry", Integer.class)).isEqualTo(315);
        assertThat(jdbc.queryForObject("SELECT current_revision FROM nx_event_schema_revision", Integer.class)).isEqualTo(315);
        assertThat(jdbc.queryForObject("SELECT reason FROM nx_event_schema_registry", String.class)).isEqualTo("future contract");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_schema_property WHERE registry_revision=315 AND required_field=0", Integer.class)).isEqualTo(3);
    }

    @Test
    void schemaVersionDoesNotAffectPureReadTrialState() throws Exception {
        migrate();
        jdbc.update("UPDATE nx_event_schema_registry SET current_revision=313,producer='old-producer' WHERE event_name=?", EVENT);
        jdbc.update("UPDATE nx_event_schema_property SET registry_revision=313,property_type='string',required_field=0");
        migrate();
        seedTrial("ACTIVE", NOW);
        state().andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("GRACE"));
        assertPersisted("ACTIVE", 0, 0);
        assertThat(jdbc.queryForObject("SELECT producer FROM nx_event_schema_registry", String.class))
                .isEqualTo("AppTrialLifecycleService");
    }

    @Test
    void migrationDoesNotResurrectARetiredOrDeletedSchema() throws Exception {
        migrate();
        jdbc.update("UPDATE nx_event_schema_registry SET status='RETIRED',is_deleted=1 WHERE event_name=?", EVENT);
        migrate();
        seedTrial("ACTIVE", NOW);
        state().andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.state").value("GRACE"));
        assertPersisted("ACTIVE", 0, 0);
    }

    @Test
    void migrationDoesNotResurrectAnOperatorDeletedProperty() throws Exception {
        migrate();
        jdbc.update("UPDATE nx_event_schema_property SET is_deleted=1 WHERE property_name='grace_days'");
        migrate();
        seedTrial("ACTIVE", NOW);
        state().andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.state").value("GRACE"));
        assertPersisted("ACTIVE", 0, 0);
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM nx_event_schema_property WHERE property_name='grace_days'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void registeredFieldsStillRejectMissingAndMistypedPayloadsBeforeOutboxWrite() throws Exception {
        migrate();
        assertThatThrownBy(() -> outbox.publishUserEvent("TRIAL", "test-only", EVENT, 42L,
                "P2", 2, "2026-W36", Map.of("shadow_usdt", 120, "shadow_nex", 15)))
                .hasMessage("A4_SCHEMA_REQUIRED_PROPERTY_MISSING");
        assertThatThrownBy(() -> outbox.publishUserEvent("TRIAL", "test-only", EVENT, 42L,
                "P2", 2, "2026-W36", Map.of("grace_days", "invalid", "shadow_usdt", 120, "shadow_nex", 15)))
                .hasMessage("A4_SCHEMA_PROPERTY_TYPE_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isZero();
    }

    private void migrate() throws Exception {
        ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/migrations/"
                + TrialGraceEnteredEventSchemaMigrationContractTest.MIGRATION));
    }

    private ResultActions state() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken("42", null, List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        return http.perform(get("/api/trial/state").principal(authentication));
    }

    private void seedTrial(String initialState, LocalDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO nx_trial_claim(id,user_id,claim_no,status,device_name,duration_days,
                    daily_usdt,daily_nex,offset_cap_usdt,price_usdt,claimed_at,expires_at,version)
                VALUES(1,42,'TRIAL-GRACE-REGRESSION',?,'Test trial',3,40,5,50,1299,?,?,0)
                """, initialState, NOW.minusDays(3), expiresAt);
    }

    private void assertPersisted(String expectedState, long expectedVersion, int eventCount) {
        assertThat(jdbc.queryForObject("SELECT status FROM nx_trial_claim WHERE id=1", String.class)).isEqualTo(expectedState);
        assertThat(jdbc.queryForObject("SELECT version FROM nx_trial_claim WHERE id=1", Long.class)).isEqualTo(expectedVersion);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isEqualTo(eventCount);
    }
}
