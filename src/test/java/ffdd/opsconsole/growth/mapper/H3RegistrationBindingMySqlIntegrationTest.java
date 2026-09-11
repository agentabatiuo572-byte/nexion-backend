package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.growth.application.DayOneInstanceFacadeAdapter;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import java.math.BigDecimal;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Executes the real forward migration in a new, owned, loopback-only schema. */
@EnabledIfEnvironmentVariable(named="NEXION_H3_REGISTRATION_IT", matches="true")
class H3RegistrationBindingMySqlIntegrationTest {
    private Connection admin;
    private String schema;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private static final String MIGRATION = "scripts/migrations/20260911_h3_day_one_registration_bindings.sql";

    @BeforeEach void fixture() throws Exception {
        String url = System.getenv("NEXION_TEST_DB_SERVER_URL");
        requireIsolatedServer(System.getenv("NEXION_H3_REGISTRATION_IT"), url);
        String user = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = System.getenv().getOrDefault("NEXION_TEST_DB_PASSWORD", "");
        admin = DriverManager.getConnection(url,user,password);
        schema = "nx_registration_binding_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.createStatement().execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");
        int query = url.indexOf('?');
        String fixtureUrl = (query < 0 ? url : url.substring(0,query))+schema+(query < 0 ? "" : url.substring(query));
        dataSource = new DriverManagerDataSource(fixtureUrl,user,password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE nx_admin_operation_mutex(lock_key VARCHAR(64) PRIMARY KEY,updated_at DATETIME)");
        jdbc.execute("CREATE TABLE nx_mission(id BIGINT AUTO_INCREMENT PRIMARY KEY,mission_code VARCHAR(64) UNIQUE,mission_name VARCHAR(128),mission_category VARCHAR(32),action_route VARCHAR(255),reward_points INT,mission_type VARCHAR(32),status TINYINT,is_deleted TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(32),created_at DATETIME,is_deleted TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE nx_config_item(config_key VARCHAR(128) PRIMARY KEY,config_value VARCHAR(128),status TINYINT,is_deleted TINYINT DEFAULT 0)");
        String old = Files.readString(Path.of("scripts/migrations/20260722_h3_canonical_event_listener.sql"));
        jdbc.execute(old.substring(old.indexOf("CREATE TABLE"),old.indexOf("INSERT INTO nx_mission")).trim());
        for (String code : List.of("bind_bank_card","visit_earn","visit_store","view_product_roi","setup_profile","invite_friend")) {
            jdbc.update("INSERT INTO nx_mission(mission_code,mission_name,mission_category,action_route,reward_points,mission_type,status) VALUES(?,?, 'EXPLORE',?,10,'DAY_ONE',1)",code,code,"/pages/me/profile");
        }
        jdbc.update("INSERT INTO nx_config_item VALUES('growth.quest.day_one.tri_reward','500 / 200 / 0 NEX',1,0)");
        run("scripts/migrations/20260909_h3_day_one_instance_snapshot.sql");
    }

    @AfterEach void cleanup() throws Exception {
        if (admin == null) return;
        try {
            if (schema != null && schema.matches("nx_registration_binding_test_[0-9a-f]{32}"))
                admin.createStatement().execute("DROP DATABASE `"+schema+"`");
        } finally { admin.close(); }
    }

    @Test void missingBindingsBlockSnapshotThenMigrationEnablesSixFrozenItemsAndIsRerunnable() throws Exception {
        jdbc.update("INSERT INTO nx_user VALUES(42,'ACTIVE','2026-09-11 10:00:00',0)");
        Configuration config = new Configuration(new Environment("registration",new SpringManagedTransactionFactory(),dataSource));
        config.setMapUnderscoreToCamelCase(true); config.addMapper(DayOneInstanceMapper.class);
        var mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(DayOneInstanceMapper.class);
        var rhythm = mock(GrowthRhythmFacade.class);
        when(rhythm.snapshot()).thenReturn(new GrowthRhythmSnapshot(12,1,"P1",0,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO,0,BigDecimal.ONE,BigDecimal.ONE,false,List.of()));
        var facade = new DayOneInstanceFacadeAdapter(mapper,rhythm);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThatThrownBy(() -> tx.execute(status -> facade.provisionForRegisteredUser(42L)))
                .hasMessage("DAY_ONE_SNAPSHOT_BINDING_REQUIRED");
        run(MIGRATION);
        var snapshot = tx.execute(status -> facade.provisionForRegisteredUser(42L));
        assertThat(snapshot.requiredTaskCount()).isEqualTo(6);
        assertThat(snapshot.eligibleUntil()).isEqualTo(snapshot.enteredAt().plusHours(72));
        assertThat(mapper.listItems(snapshot.id())).hasSize(6);
        var before = jdbc.queryForList("SELECT * FROM nx_growth_day_one_instance_binding ORDER BY id");
        run(MIGRATION);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_quest_event_binding",Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForList("SELECT * FROM nx_growth_day_one_instance_binding ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT producer,event_type,user_id_field FROM nx_growth_quest_event_binding WHERE quest_code='invite_friend'"))
                .containsEntry("producer","REFERRAL").containsEntry("event_type","H8_REFERRAL_REWARD_SETTLED").containsEntry("user_id_field","inviter_user_id");
    }

    @Test void businessWriteReceiptAndOutboxRollBackTogetherAndReplayDoesNotRepublish() throws Exception {
        run(MIGRATION);
        jdbc.execute("ALTER TABLE nx_user ADD COLUMN nickname VARCHAR(64)");
        jdbc.update("INSERT INTO nx_user(id,status,created_at,nickname) VALUES(42,'ACTIVE','2026-09-11 10:00:00','old')");
        jdbc.execute("CREATE TABLE fixture_outbox(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_type VARCHAR(128))");
        var config = new Configuration(new Environment("business-receipt",new SpringManagedTransactionFactory(),dataSource));
        config.addMapper(H3DayOneBusinessFactReceiptMapper.class);
        var receipts = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(H3DayOneBusinessFactReceiptMapper.class);
        var instances = mock(DayOneInstanceMapper.class);
        var missions = mock(QuestCompletionFactMapper.class);
        var outbox = mock(ffdd.opsconsole.shared.outbox.EventOutboxService.class);
        String key="DAY_ONE:20260911T100000", event="H3_DAY_ONE_PROFILE_SAVED";
        when(missions.lockActiveUser(42L)).thenReturn(42L);
        when(instances.listInWindowSnapshotBindings(org.mockito.ArgumentMatchers.eq(List.of(42L)),org.mockito.ArgumentMatchers.eq(event),org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new DayOneInstanceMapper.DayOneSnapshotBinding(1L,42L,key,5L,"setup_profile","DAY_ONE_PROFILE_SAVED","SYSTEM",event,"user_id","{}")));
        when(missions.lockDayOneSnapshotMissionAt(org.mockito.ArgumentMatchers.eq(42L),org.mockito.ArgumentMatchers.eq(5L),org.mockito.ArgumentMatchers.eq("setup_profile"),org.mockito.ArgumentMatchers.eq(key),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new QuestCompletionFactMapper.MissionDefinition(5L,"setup_profile","DAY_ONE",key));
        when(missions.attribution(42L)).thenReturn(Map.of("phase","P1","accountAgeMonths",0,"cohort","2026-W37"));
        var raw = new ffdd.opsconsole.growth.application.H3DayOneBusinessFactService(instances,missions,receipts,outbox,
                java.time.Clock.fixed(java.time.Instant.parse("2026-09-11T03:00:00Z"),java.time.ZoneId.of("Asia/Shanghai")));
        var manager = new DataSourceTransactionManager(dataSource);
        var proxy = new org.springframework.aop.framework.ProxyFactory(raw);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var service=(ffdd.opsconsole.growth.application.H3DayOneBusinessFactService)proxy.getProxy();
        var rule=ffdd.opsconsole.growth.application.H3DayOneBusinessFactContract.PROFILE_SAVED;
        assertThatThrownBy(() -> service.record(42L,rule)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(outbox.publishUserEventAt(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            jdbc.update("INSERT INTO fixture_outbox(event_type) VALUES(?)",event);
            if (fail.get()) throw new IllegalStateException("outbox unavailable");
            return "fixture-event";
        });
        var tx = new TransactionTemplate(manager);
        assertThatThrownBy(() -> tx.execute(status -> {
            jdbc.update("UPDATE nx_user SET nickname='new' WHERE id=42");
            service.record(42L,rule); return null;
        })).hasMessage("outbox unavailable");
        assertThat(jdbc.queryForObject("SELECT nickname FROM nx_user WHERE id=42",String.class)).isEqualTo("old");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_day_one_business_fact_receipt",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture_outbox",Integer.class)).isZero();
        fail.set(false);
        tx.execute(status -> { jdbc.update("UPDATE nx_user SET nickname='new' WHERE id=42"); service.record(42L,rule); return null; });
        tx.execute(status -> { service.record(42L,rule); return null; });
        assertThat(jdbc.queryForObject("SELECT nickname FROM nx_user WHERE id=42",String.class)).isEqualTo("new");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_day_one_business_fact_receipt",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture_outbox",Integer.class)).isEqualTo(1);
    }

    @Test void conflictingOperatorSlotStopsBeforeAnyBindingMutation() {
        jdbc.update("INSERT INTO nx_growth_quest_event_binding(binding_code,producer,event_type,quest_code,user_id_field,status) VALUES('OPERATOR','SYSTEM','H3_DAY_ONE_PROFILE_SAVED','another_task','user_id',1)");
        assertThatThrownBy(() -> run(MIGRATION)).rootCause().hasMessageContaining("h3_registration_binding_conflict");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_quest_event_binding",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quest_code FROM nx_growth_quest_event_binding",String.class)).isEqualTo("another_task");
    }

    @Test void differentActiveEventOnKnownTaskStopsWithoutAddingAnAlternativeCompletionRoute() {
        jdbc.update("INSERT INTO nx_growth_quest_event_binding(binding_code,producer,event_type,quest_code,user_id_field,status) VALUES('OPERATOR','SYSTEM','H3_EXCHANGE_COMPLETED','setup_profile','user_id',1)");
        assertThatThrownBy(() -> run(MIGRATION)).rootCause().hasMessageContaining("h3_registration_binding_conflict");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_quest_event_binding",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_type FROM nx_growth_quest_event_binding",String.class)).isEqualTo("H3_EXCHANGE_COMPLETED");
    }

    @Test void unknownUnboundActiveTaskRollsBackAllSixNewBindings() {
        jdbc.update("INSERT INTO nx_mission(mission_code,mission_type,status) VALUES('custom_unbound','DAY_ONE',1)");
        assertThatThrownBy(() -> run(MIGRATION)).rootCause().hasMessageContaining("h3_registration_binding_conflict");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_quest_event_binding",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_mission WHERE status=1",Integer.class)).isEqualTo(7);
    }

    @Test void pausedTasksAreNotEnabledAndExistingExactBindingIdentityIsPreserved() throws Exception {
        jdbc.update("UPDATE nx_mission SET status=0 WHERE mission_code='bind_bank_card'");
        jdbc.update("INSERT INTO nx_growth_quest_event_binding(binding_code,producer,event_type,quest_code,user_id_field,status) VALUES('OPERATOR_PROFILE','SYSTEM','H3_DAY_ONE_PROFILE_SAVED','setup_profile','user_id',1)");
        run(MIGRATION);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_quest_event_binding",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT binding_code FROM nx_growth_quest_event_binding WHERE quest_code='setup_profile'",String.class)).isEqualTo("OPERATOR_PROFILE");
        assertThat(jdbc.queryForObject("SELECT status FROM nx_mission WHERE mission_code='bind_bank_card'",Integer.class)).isZero();
    }

    static void requireIsolatedServer(String optedIn, String url) {
        if (!"true".equals(optedIn) || url == null
                || !url.matches("^jdbc:mysql://127\\.0\\.0\\.1:33317/(?:\\?.*)?$"))
            throw new IllegalArgumentException("EXPLICIT_ISOLATED_33317_SERVER_REQUIRED");
    }

    private void run(String filename) throws Exception {
        try (Connection connection=dataSource.getConnection()) {
            try { ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of(filename))); }
            catch (RuntimeException ex) { connection.createStatement().execute("ROLLBACK"); throw ex; }
        }
    }
}
