package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;

@EnabledIfEnvironmentVariable(named="CS_ANALYTICS_GROUPS_ENABLED",matches="true")
class SupportGroupMigrationRuntimeTest {
    @Test void originalEvidenceConflictCutoverAndSecondRunAreProvenInOwnedScratchSchema() throws Exception {
        var target=SupportRuntimeTarget.current();
        String base="jdbc:mysql://127.0.0.1:"+target.databasePort()+"/", options="?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
        String user=System.getenv("NEXION_DB_USERNAME"),password=System.getenv("NEXION_DB_PASSWORD");
        var main=new JdbcTemplate(new DriverManagerDataSource(base+target.database()+options,user,password));
        var json=new ObjectMapper().findAndRegisterModules();
        SupportExclusiveRuntimeOwnership.requireActual(json.readTree(Path.of(System.getenv("CS_ENHANCE_ACTOR_CONTEXT")).toFile()),target,main);
        assertThat(main.queryForObject("SELECT @@server_uuid",String.class)).isEqualTo("3556ddae-c1a1-11f1-8853-a40c6626953d");
        assertThat(main.queryForObject("SELECT @@port",Integer.class)).isEqualTo(33337);
        assertThat(main.queryForObject("SELECT CURRENT_USER()",String.class)).isEqualTo("cs_analytics_runner@127.0.0.1");
        String schema=target.database()+"_c1_audit";
        boolean owned=false;
        Map<String,Object> evidence=new LinkedHashMap<>();
        try {
            // Existing scratch is never reused or emptied. CREATE without IF NOT EXISTS is the ownership gate.
            main.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");owned=true;
            var ds=new DriverManagerDataSource(base+schema+options,user,password);var db=new JdbcTemplate(ds);
            for(String ddl:List.of(
                "CREATE TABLE nx_admin(id BIGINT PRIMARY KEY,status INT,is_deleted INT)",
                "CREATE TABLE nx_user(id BIGINT PRIMARY KEY,is_deleted INT)",
                "CREATE TABLE nx_admin_role(id BIGINT PRIMARY KEY,role_code VARCHAR(32),status INT,is_deleted INT)",
                "CREATE TABLE nx_admin_role_relation(id BIGINT PRIMARY KEY,admin_id BIGINT,role_id BIGINT,is_deleted INT)",
                "CREATE TABLE nx_support_agent_profile(admin_id BIGINT PRIMARY KEY,seat_type VARCHAR(16),service_types VARCHAR(64),enabled INT,is_deleted INT,version BIGINT)",
                "CREATE TABLE nx_support_agent_user_assignment(id BIGINT PRIMARY KEY,user_id BIGINT,agent_admin_id BIGINT,status VARCHAR(16),starts_at DATETIME,ends_at DATETIME,is_deleted INT,assignment_type VARCHAR(20))",
                "CREATE TABLE nx_support_binding_pool(customer_id BIGINT PRIMARY KEY)",
                "CREATE TABLE nx_support_rules(id BIGINT PRIMARY KEY,inheritance_mode VARCHAR(20),max_inheritance_depth INT,version BIGINT)"))db.execute(ddl);
            db.update("INSERT INTO nx_admin VALUES(1,1,0),(2,0,0),(3,1,0),(4,1,0)");
            db.update("INSERT INTO nx_user VALUES(10,0),(11,0),(12,0)");
            db.update("INSERT INTO nx_admin_role VALUES(1,'SUPPORT',1,0)");
            db.update("INSERT INTO nx_admin_role_relation VALUES(1,1,1,0),(2,2,1,0),(3,3,1,0),(4,4,1,0)");
            db.update("INSERT INTO nx_support_agent_profile VALUES(1,'DEDICATED','support,advisor',1,0,8),(2,'DEDICATED','advisor',0,0,9),(3,'MANAGER','support',1,0,3),(4,'GENERAL','support',1,0,6)");
            db.update("INSERT INTO nx_support_agent_user_assignment VALUES(101,10,1,'ACTIVE','2026-01-01',NULL,0,'advisor'),(102,11,2,'ACTIVE','2026-01-02',NULL,0,'legacy')");
            db.update("INSERT INTO nx_support_binding_pool VALUES(12)");
            db.update("INSERT INTO nx_support_rules VALUES(1,'LIMITED',0,17)");
            var bindings=db.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id");
            var profiles=db.queryForList("SELECT * FROM nx_support_agent_profile ORDER BY admin_id");
            try(var c=ds.getConnection()){
                ScriptUtils.executeSqlScript(c,new FileSystemResource("scripts/migrations/20261007_support_groups.sql"));
                ScriptUtils.executeSqlScript(c,new FileSystemResource("scripts/migrations/20261007_support_groups.sql"));
            }
            String sql=Files.readString(Path.of("scripts/migrations/20261007_support_groups_qualification_cutover.sql"));
            db.execute("CREATE TABLE nx_support_migration(id VARCHAR(64) PRIMARY KEY,committed_at DATETIME(6) NOT NULL)");
            int start=sql.indexOf("CREATE PROCEDURE"),end=sql.lastIndexOf("END$$");assertThat(start).isGreaterThan(0);assertThat(end).isGreaterThan(start);
            db.execute(sql.substring(start,end+3));
            db.update("INSERT INTO nx_support_agent_user_assignment VALUES(103,10,2,'ACTIVE','2026-01-03',NULL,0,'support')");
            assertThatThrownBy(()->db.execute("CALL support_groups_qualification_cutover()")) .hasStackTraceContaining("SUPPORT_GROUP_CUTOVER_REVIEW_REQUIRED");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM nx_support_account_qualification_history",Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM nx_support_agent_user_assignment WHERE user_id=10",Integer.class)).isEqualTo(2);
            db.update("DELETE FROM nx_support_agent_user_assignment WHERE id=103");
            db.execute("CALL support_groups_qualification_cutover()");
            var qualifications=db.queryForList("SELECT * FROM nx_support_account_qualification_history ORDER BY id");
            var members=db.queryForList("SELECT * FROM nx_support_group_member_history ORDER BY id");
            var routes=db.queryForList("SELECT * FROM nx_support_customer_route_history ORDER BY id");
            db.execute("CALL support_groups_qualification_cutover()");
            assertThat(db.queryForList("SELECT * FROM nx_support_account_qualification_history ORDER BY id")).isEqualTo(qualifications);
            assertThat(db.queryForList("SELECT * FROM nx_support_group_member_history ORDER BY id")).isEqualTo(members);
            assertThat(db.queryForList("SELECT * FROM nx_support_customer_route_history ORDER BY id")).isEqualTo(routes);
            assertThat(db.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id")).isEqualTo(bindings);
            assertThat(db.queryForList("SELECT * FROM nx_support_agent_profile ORDER BY admin_id")).isEqualTo(profiles);
            assertThat(db.queryForObject("SELECT state FROM nx_support_account_qualification_history WHERE admin_id=2",String.class)).isEqualTo("DISABLED");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM nx_support_account_qualification_history WHERE admin_id=4",Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM nx_support_group_member_history WHERE group_id IS NULL",Integer.class)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM nx_support_group_member_history WHERE starts_at<(SELECT committed_at FROM nx_support_migration WHERE id='support-groups-20261007')",Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT max_inheritance_depth FROM nx_support_rules WHERE id=1",Integer.class)).isZero();
            evidence.put("BE-SUP-02",Map.of("bindingIdsAndAllColumnsPreserved",true,"originalProfileVersionsPreserved",true,"assignmentTypeColumnRetained",true,"secondRunByteValuesEqual",true,"disabledPreserved",true,"rulesUnchanged",true));
            evidence.put("BE-SUP-03",Map.of("duplicateConflictAbortsWithoutDedup",true,"generalMixedLegacyUnprovenNotEnabled",true,"noBackdatedHistory",true,"qualifications",qualifications,"members",members));
        }finally{
            if(owned){main.execute("DROP DATABASE "+schema);assertThat(main.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,schema)).isZero();}
        }
        evidence.put("ownedScratchRemoved",true);evidence.put("workflowRunId",System.getenv("WORKFLOW_RUN_ID"));evidence.put("snapshotHash",System.getenv("WORKFLOW_SNAPSHOT_HASH"));
        Files.writeString(Path.of(System.getenv("CS_ENHANCE_EVIDENCE_DIR"),"groups-migration-runtime.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
}
