package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Executes the complete production candidate reader in a UUID-owned disposable MySQL schema. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD", matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssignmentCandidatesMySqlIntegrationTest {
    private static final String MIGRATION="scripts/migrations/20260914_task_assignment_active_read_index.sql";
    private static final String INDEX="idx_task_assignment_active";
    private Connection connection;
    private JdbcTemplate jdbc;
    private String database;
    private boolean created;
    private Configuration configuration;
    private AppTaskAssignmentMapper mapper;

    @BeforeAll void createOwnedDatabase() throws Exception {
        connection=DriverManager.getConnection(System.getenv().getOrDefault("NEXION_TEST_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/nexion?useSSL=false&allowPublicKeyRetrieval=true"),
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME","root"),System.getenv("NEXION_TEST_DB_PASSWORD"));
        var source=new SingleConnectionDataSource(connection,true);
        jdbc=new JdbcTemplate(source);
        database="nx_assignment_test_"+UUID.randomUUID().toString().replace("-","");
        assertThat(database).matches("nx_assignment_test_[a-f0-9]{32}");
        jdbc.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        created=true;
        jdbc.execute("USE `"+database+"`");
        configuration=new Configuration(new Environment("assignment-read-test",new SpringManagedTransactionFactory(),source));
        configuration.addMapper(AppTaskAssignmentMapper.class);
        mapper=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration)).getMapper(AppTaskAssignmentMapper.class);
    }
    @BeforeEach void resetOwnedTables() {
        assertOwned();
        jdbc.execute("SELECT RELEASE_ALL_LOCKS()");
        jdbc.execute("DROP TABLE IF EXISTS nx_compute_task,nx_user_device,nx_user,nx_user_device_runtime,nx_compute_dc_ops_state,nx_onboarding_calibration,nx_compute_device_task_lock");
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(32),is_deleted TINYINT,sandbox TINYINT)");
        jdbc.execute("""
                CREATE TABLE nx_user_device(id BIGINT PRIMARY KEY,user_id BIGINT,is_deleted TINYINT,
                  source_environment VARCHAR(16),run_id VARCHAR(64),ownership_status VARCHAR(32),activated_at DATETIME,
                  deactivated_at DATETIME,pending_deactivate TINYINT,status VARCHAR(32),vram_total_gb INT,
                  device_type VARCHAR(32),dc_location VARCHAR(32))
                """);
        jdbc.execute("CREATE TABLE nx_user_device_runtime(user_device_id BIGINT PRIMARY KEY,is_deleted TINYINT,paused_reason VARCHAR(64),online_status VARCHAR(32))");
        jdbc.execute("CREATE TABLE nx_compute_dc_ops_state(dc_location VARCHAR(32) PRIMARY KEY,is_deleted TINYINT,dispatch_paused TINYINT)");
        jdbc.execute("CREATE TABLE nx_onboarding_calibration(user_device_id BIGINT,user_id BIGINT,activation_status VARCHAR(32),source_environment VARCHAR(16),run_id VARCHAR(64),is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_compute_device_task_lock(user_device_id BIGINT,user_id BIGINT,source_environment VARCHAR(16),is_deleted TINYINT,lock_until DATETIME)");
        jdbc.execute("""
                CREATE TABLE nx_compute_task(id BIGINT PRIMARY KEY,user_id BIGINT,user_device_id BIGINT,
                  status VARCHAR(32),source_environment VARCHAR(16),is_deleted TINYINT,lease_expires_at DATETIME,
                  created_at DATETIME,payload VARCHAR(1024),task_no VARCHAR(512),
                  KEY idx_compute_task_device_time(user_device_id,created_at),
                  KEY idx_task_development_count(user_id,user_device_id,status,source_environment,is_deleted,task_no))
                """);
        jdbc.update("INSERT INTO nx_user VALUES(7,'ACTIVE',0,0),(8,'ACTIVE',0,1),(9,'INACTIVE',0,0),(10,'ACTIVE',1,0)");
        // Pin statement time so expired/equal/future comparisons are identical across old and new reads.
        jdbc.execute("SET timestamp=2000000000");
    }
    @AfterAll void dropOwnedDatabase() throws Exception {
        try { if(created){assertOwned();jdbc.execute("DROP DATABASE `"+database+"`");} }
        finally {if(connection!=null) connection.close();}
    }
    @Test void allDeviceUserCalibrationPauseAndPaginationBoundariesRemainEquivalent() throws Exception {
        migrate();
        for(int id=1;id<=20;id++) device(id);
        jdbc.update("UPDATE nx_user_device SET user_id=8 WHERE id=2");
        jdbc.update("UPDATE nx_user_device SET user_id=9 WHERE id=3");
        jdbc.update("UPDATE nx_user_device SET user_id=10 WHERE id=4");
        jdbc.update("UPDATE nx_user_device SET is_deleted=1 WHERE id=5");
        jdbc.update("UPDATE nx_user_device SET source_environment='SANDBOX' WHERE id=6");
        jdbc.update("UPDATE nx_user_device SET run_id='acceptance' WHERE id=7");
        jdbc.update("UPDATE nx_user_device SET ownership_status='SOLD' WHERE id=8");
        jdbc.update("UPDATE nx_user_device SET activated_at=NULL WHERE id=9");
        jdbc.update("UPDATE nx_user_device SET deactivated_at=NOW() WHERE id=10");
        jdbc.update("UPDATE nx_user_device SET pending_deactivate=1 WHERE id=11");
        jdbc.update("UPDATE nx_user_device SET status='OFFLINE' WHERE id=12");
        jdbc.update("UPDATE nx_user_device SET vram_total_gb=-1 WHERE id=13");
        jdbc.update("UPDATE nx_user_device SET dc_location='paused' WHERE id=14");
        jdbc.update("INSERT INTO nx_compute_dc_ops_state VALUES('paused',0,1)");
        jdbc.update("INSERT INTO nx_user_device_runtime VALUES(15,0,' operator ','ONLINE'),(16,0,'','OFFLINE')");
        jdbc.update("UPDATE nx_user_device SET device_type='MOBILE' WHERE id IN(17,18)");
        jdbc.update("INSERT INTO nx_onboarding_calibration VALUES(17,8,'ACTIVE','PRODUCTION','',0),(18,7,'ACTIVE','PRODUCTION','',0)");
        jdbc.update("INSERT INTO nx_compute_device_task_lock VALUES(19,7,'PRODUCTION',0,DATE_ADD(NOW(),INTERVAL 1 DAY))");
        jdbc.update("UPDATE nx_user_device SET run_id=NULL WHERE id=20");
        assertThat(ids(0,50)).containsExactly(1L,18L,20L);
        for(long cursor:List.of(0L,1L,17L,18L,20L,99L)) for(int limit:List.of(0,1,2,50)) assertLegacy(cursor,limit);
    }
    @Test void taskStatusLeaseNullAndEnvironmentBoundariesRemainEquivalent() throws Exception {
        migrate();
        for(int id=1;id<=12;id++) device(id);
        task(1,1,"CLAIMED","PRODUCTION",0,null);
        task(2,2,"running","PRODUCTION",0,2000000001L);
        task(3,3,"RUNNING","PRODUCTION",0,1999999999L);
        task(4,4,"CLAIMED","PRODUCTION",0,2000000000L);
        task(5,5,"RUNNING","SANDBOX",0,null);
        task(6,6,"RUNNING","PRODUCTION",1,null);
        task(7,7,"COMPLETED","PRODUCTION",0,null);
        task(8,8,null,"PRODUCTION",0,null);
        task(9,9,"runnıng","PRODUCTION",0,null);
        task(10,10,"RUNNING","PRODUCTION",0,null);
        jdbc.update("UPDATE nx_compute_task SET user_id=8 WHERE id=10");
        task(11,11,"CLAIMED","PRODUCTION",0,1999999999L);
        task(12,11,"RUNNING","PRODUCTION",0,null);
        assertThat(ids(0,50)).containsExactly(3L,4L,5L,6L,7L,8L,10L,12L);
        assertLegacy(0,50);
        // Current state changes remain immediately visible; the generated key is not a stale snapshot.
        jdbc.update("UPDATE nx_compute_task SET status='COMPLETED' WHERE id=1");
        assertThat(ids(0,1)).containsExactly(1L);
        assertLegacy(0,50);
    }
    @Test void binaryCollationAndSeededMixedTasksMatchLegacy() throws Exception {
        jdbc.execute("ALTER TABLE nx_compute_task MODIFY status VARCHAR(32) COLLATE utf8mb4_bin");
        migrate();
        for(int id=1;id<=30;id++) device(id);
        var random=new Random(20260914);
        String[] statuses={"CLAIMED","running","runnıng","COMPLETED","RÚNNING",null};
        Long[] leases={null,1999999999L,2000000000L,2000000001L};
        for(int id=1;id<=600;id++) task(id,1+random.nextInt(30),statuses[random.nextInt(statuses.length)],
                random.nextBoolean()?"PRODUCTION":"SANDBOX",random.nextInt(2),leases[random.nextInt(leases.length)]);
        for(long cursor:List.of(0L,5L,15L,25L,30L)) for(int limit:List.of(0,1,5,50)) assertLegacy(cursor,limit);
        assertThat(jdbc.queryForObject("SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_compute_task' AND column_name='canonical_assignment_status'",String.class)).isEqualTo("utf8mb4_bin");
    }
    @Test void completedHistoryUsesTwoCoveringEqualityProbes() throws Exception {
        migrate();device(1);device(2);
        jdbc.execute("SET SESSION cte_max_recursion_depth=20001");
        jdbc.update("""
                INSERT INTO nx_compute_task(id,user_id,user_device_id,status,source_environment,is_deleted,
                  lease_expires_at,created_at,payload,task_no)
                WITH RECURSIVE seq AS (SELECT 1 n UNION ALL SELECT n+1 FROM seq WHERE n<20000)
                SELECT n,7,1+MOD(n,2),'COMPLETED','PRODUCTION',0,NOW(),NOW(),REPEAT('x',900),CONCAT('fixture-',n) FROM seq
                """);
        task(20001,2,"RUNNING","PRODUCTION",0,null);
        jdbc.execute("ANALYZE TABLE nx_compute_task");
        String plan=jdbc.queryForObject("EXPLAIN ANALYZE "+sql(),String.class,0L,50);
        assertThat(plan).contains("Covering index lookup on t using "+INDEX,
                "canonical_assignment_status='CLAIMED'","canonical_assignment_status='RUNNING'");
        assertThat(ids(0,50)).containsExactly(1L);assertLegacy(0,50);
    }
    @Test void migrationIsIdempotentResumesAfterColumnAndDoesNotChangeRows() throws Exception {
        task(1,1,"runnıng","PRODUCTION",0,null);
        jdbc.execute("SET SESSION lock_wait_timeout=37");
        migrate();migrate();
        jdbc.execute("ALTER TABLE nx_compute_task DROP INDEX "+INDEX);
        migrate();
        assertThat(jdbc.queryForObject("SELECT @@session.lock_wait_timeout",Integer.class)).isEqualTo(37);
        assertThat(jdbc.queryForObject("SELECT IS_FREE_LOCK(@assignment_read_lock)",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE id=1",String.class)).isEqualTo("runnıng");
        String startup=Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        assertThat(startup.split("20260914_task_assignment_active_read_index.sql",-1)).hasSize(2);
    }
    @Test void incompatibleIndexOrColumnFailsBeforeAnyAddedColumn() throws Exception {
        jdbc.execute("CREATE INDEX "+INDEX+" ON nx_compute_task(user_id)");
        assertThatThrownBy(this::migrate).hasStackTraceContaining("ASSIGNMENT_READ_INDEX_SHAPE_INVALID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_compute_task' AND column_name='canonical_assignment_status'",Integer.class)).isZero();
        jdbc.execute("DROP INDEX "+INDEX+" ON nx_compute_task");
        jdbc.execute("ALTER TABLE nx_compute_task ADD canonical_assignment_status VARCHAR(32)");
        assertThatThrownBy(this::migrate).hasStackTraceContaining("ASSIGNMENT_READ_COLUMN_SHAPE_INVALID");
        jdbc.execute("ALTER TABLE nx_compute_task DROP COLUMN canonical_assignment_status");
        migrate();
        jdbc.execute("ALTER TABLE nx_compute_task ALTER INDEX "+INDEX+" INVISIBLE");
        assertThatThrownBy(this::migrate).hasStackTraceContaining("ASSIGNMENT_READ_INDEX_SHAPE_INVALID");
    }
    @Test void incompatibleSourceFailsBeforeDDL() {
        jdbc.execute("ALTER TABLE nx_compute_task MODIFY status VARCHAR(64)");
        assertThatThrownBy(this::migrate).hasStackTraceContaining("ASSIGNMENT_READ_SOURCE_SHAPE_INVALID");
    }
    private void device(long id){jdbc.update("INSERT INTO nx_user_device VALUES(?,7,0,'PRODUCTION','','OWNED',NOW(),NULL,0,'ACTIVE',8,'GPU','normal')",id);}
    private void task(long id,long device,String status,String env,int deleted,Long lease){
        jdbc.update("INSERT INTO nx_compute_task(id,user_id,user_device_id,status,source_environment,is_deleted,lease_expires_at) VALUES(?,7,?,?,?,?,FROM_UNIXTIME(?))",id,device,status,env,deleted,lease);
    }
    private String sql(){return configuration.getMappedStatement(AppTaskAssignmentMapper.class.getName()+".assignmentCandidates").getBoundSql(Map.of("afterDeviceId",0L,"limit",50)).getSql();}
    private List<Long> ids(long cursor,int limit){return mapper.assignmentCandidates(cursor,limit).stream().map(AppTaskAssignmentMapper.AssignmentCandidate::deviceId).toList();}
    private void assertLegacy(long cursor,int limit){
        assertThat(ids(cursor,limit)).containsExactlyElementsOf(jdbc.query(LEGACY,(rs,row)->rs.getLong("deviceId"),cursor,limit));
    }
    private void assertOwned(){assertThat(database).matches("nx_assignment_test_[a-f0-9]{32}");assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo(database);}
    private void migrate() throws Exception {assertOwned();ScriptUtils.executeSqlScript(connection,new FileSystemResource(MIGRATION));}
    // Frozen pre-optimization reader, populated from the base commit when this regression was added.
    private static final String LEGACY = """
SELECT d.user_id AS userId, d.id AS deviceId
              FROM nx_user_device d
              JOIN nx_user u ON u.id = d.user_id
                AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
              LEFT JOIN nx_user_device_runtime r
                ON r.user_device_id = d.id AND r.is_deleted = 0
              LEFT JOIN nx_compute_dc_ops_state dc
                ON dc.dc_location = d.dc_location AND dc.is_deleted = 0
             WHERE d.is_deleted = 0
               AND d.source_environment = 'PRODUCTION' AND COALESCE(d.run_id, '') = ''
               AND UPPER(d.ownership_status) = 'OWNED'
               AND d.activated_at IS NOT NULL AND d.deactivated_at IS NULL
               AND d.pending_deactivate = 0
               AND UPPER(d.status) IN ('ACTIVE','ONLINE')
               AND d.vram_total_gb IS NOT NULL AND d.vram_total_gb >= 0
               AND COALESCE(dc.dispatch_paused, 0) = 0
               AND COALESCE(TRIM(r.paused_reason), '') = ''
               AND (r.online_status IS NULL OR UPPER(r.online_status) = 'ONLINE')
               AND (UPPER(d.device_type) NOT IN ('MOBILE','PHONE')
                    OR EXISTS (SELECT 1 FROM nx_onboarding_calibration oc
                                WHERE oc.user_device_id = d.id AND oc.user_id = d.user_id
                                  AND oc.activation_status = 'ACTIVE'
                                  AND oc.source_environment = 'PRODUCTION' AND oc.run_id = ''
                                  AND oc.is_deleted = 0))
               AND NOT EXISTS (SELECT 1 FROM nx_compute_task t
                                WHERE t.user_id = d.user_id AND t.user_device_id = d.id
                                  AND t.source_environment = 'PRODUCTION' AND t.is_deleted = 0
                                  AND UPPER(t.status) IN ('CLAIMED','RUNNING')
                                  AND (t.lease_expires_at IS NULL OR t.lease_expires_at > CURRENT_TIMESTAMP))
               AND NOT EXISTS (SELECT 1 FROM nx_compute_device_task_lock l
                                WHERE l.user_id = d.user_id AND l.user_device_id = d.id
                                  AND l.source_environment = 'PRODUCTION' AND l.is_deleted = 0
                                  AND l.lock_until > CURRENT_TIMESTAMP)
               AND d.id > ?
             ORDER BY d.id
             LIMIT ?
            """;
}
