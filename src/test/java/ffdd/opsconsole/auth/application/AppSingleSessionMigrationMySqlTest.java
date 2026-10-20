package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Production baseline and migration SQL only. Legacy display labels are not authenticated session groups. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD", matches=".+")
class AppSingleSessionMigrationMySqlTest {
    private static final Pattern OWNED=Pattern.compile("nx_session_migration_[a-f0-9]{32}");
    private static final LocalDateTime OLD_REVOCATION=LocalDateTime.of(2026,1,2,3,4,5);
    private Connection admin;
    private JdbcTemplate jdbc;
    private String schema;
    private boolean created;

    @BeforeEach void setup() throws Exception {
        String serverUrl=System.getenv("NEXION_TEST_DB_SERVER_URL");
        String username=System.getenv("NEXION_TEST_DB_USERNAME");
        String password=System.getenv("NEXION_TEST_DB_PASSWORD");
        String expectedDirectory=System.getenv("NEXION_TEST_DB_EXPECTED_DATADIR");
        assertThat(serverUrl).matches("jdbc:mysql://(127[.]0[.]0[.]1|localhost):[0-9]+/([?].*)?");
        int port=URI.create(serverUrl.substring("jdbc:".length())).getPort();
        assertThat(port).isBetween(1,65535);
        assertThat(username).isNotBlank();assertThat(password).isNotBlank();assertThat(expectedDirectory).isNotBlank();
        admin=DriverManager.getConnection(serverUrl,username,password);
        try(var statement=admin.createStatement();var rows=statement.executeQuery("SELECT @@port,@@datadir,DATABASE()")){
            assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(port);
            assertThat(rows.getString(2).replace('\\','/').replaceAll("/+$",""))
                    .isEqualTo(expectedDirectory.replace('\\','/').replaceAll("/+$",""));
            assertThat(rows.getString(3)).isNull();
        }
        schema="nx_session_migration_"+UUID.randomUUID().toString().replace("-","");
        assertThat(OWNED.matcher(schema).matches()).isTrue();
        try(var statement=admin.createStatement()){
            statement.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");created=true;
        }
        int query=serverUrl.indexOf('?');
        String fixtureUrl=serverUrl.substring(0,query<0?serverUrl.length():query)+schema+(query<0?"":serverUrl.substring(query));
        jdbc=new JdbcTemplate(new DriverManagerDataSource(fixtureUrl,username,password));
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo(schema);
        System.out.println("APP_SESSION_MIGRATION_FIXTURE_CREATE="+schema);
        String baseline=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:List.of("nx_user_session","nx_event_schema_registry","nx_event_schema_property")){
            int start=baseline.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");
            jdbc.execute(baseline.substring(start,baseline.indexOf(';',start)+1));
        }
    }

    @Test void displayLabelsRemainUnknownAndReplayPreservesRevocationsAndAllNewFixedContextRows() throws Exception {
        List<String> columns=jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_user_session'",String.class);
        assertThat(columns).contains("device_name","client_ip","session_chain_id")
                .doesNotContain("session_group","installation_id","app_session_version","execution_supported","login_attempt_id","session_ended_reason");
        // The deployed baseline contains no trustworthy App/H5 discriminator. Labels must never supply one.
        String[] labels={"APP","H5",null};
        for(int i=0;i<labels.length;i++){
            seedLegacy("legacy-active-"+i,labels[i],null);
            seedLegacy("legacy-revoked-"+i,labels[i],OLD_REVOCATION);
        }
        var original=rows("nx_user_session");
        migrate(null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_session WHERE session_group='UNKNOWN' AND session_ended_reason='REAUTH_REQUIRED' AND revoked_at IS NOT NULL",Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_session WHERE installation_id IS NOT NULL OR app_session_version IS NOT NULL OR execution_supported<>0 OR login_attempt_id IS NOT NULL",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_app_session_state",Integer.class)).isZero();
        for(var prior:original){
            String token=(String)prior.get("refresh_token_id");
            var migrated=jdbc.queryForMap("SELECT * FROM nx_user_session WHERE refresh_token_id=?",token);
            for(String unchanged:List.of("id","user_id","refresh_token_id","session_chain_id","device_name","client_ip","expires_at","created_at","last_active_at","is_deleted"))
                assertThat(migrated.get(unchanged)).as(token+" "+unchanged).isEqualTo(prior.get(unchanged));
            if(token.startsWith("legacy-revoked-"))
                assertThat(jdbc.queryForObject("SELECT revoked_at FROM nx_user_session WHERE refresh_token_id=?",LocalDateTime.class,token)).isEqualTo(OLD_REVOCATION);
        }
        var migratedLegacy=rows("nx_user_session");

        seedFixed(101,"fixed-app","APP","H5",true,null,null);
        seedFixed(102,"fixed-h5","H5","APP",false,null,null);
        seedFixed(103,"fixed-app-revoked","APP","H5",true,OLD_REVOCATION,"SECURITY_REVOKED");
        seedFixed(104,"fixed-h5-revoked","H5","APP",false,OLD_REVOCATION,"LOGOUT");
        jdbc.update("INSERT INTO nx_app_session_state(user_id,app_session_version,session_chain_id,installation_id,execution_supported) VALUES(101,7,'chain-fixed-app','install-fixed-app',1)");
        jdbc.update("INSERT INTO nx_app_login_attempt(attempt_id,user_id,context_hash,recovery_hash,session_id,created_at) VALUES('attempt-fixed-app',101,?,?, 'fixed-app','2026-01-01')","c".repeat(64),"r".repeat(64));
        var allSessions=rows("nx_user_session");
        var state=jdbc.queryForList("SELECT * FROM nx_app_session_state ORDER BY user_id");
        var attempts=jdbc.queryForList("SELECT * FROM nx_app_login_attempt ORDER BY attempt_id");
        long currentSeconds=jdbc.queryForObject("SELECT UNIX_TIMESTAMP()",Long.class);
        // Different server clocks make an accidental revoked_at reset detectable without wall-clock sleeps.
        for(long offset:List.of(60L,120L)){
            migrate(currentSeconds+offset);
            assertThat(rows("nx_user_session")).isEqualTo(allSessions);
            assertThat(jdbc.queryForList("SELECT * FROM nx_user_session WHERE refresh_token_id LIKE 'legacy-%' ORDER BY id")).isEqualTo(migratedLegacy);
            assertThat(jdbc.queryForList("SELECT * FROM nx_app_session_state ORDER BY user_id")).isEqualTo(state);
            assertThat(jdbc.queryForList("SELECT * FROM nx_app_login_attempt ORDER BY attempt_id")).isEqualTo(attempts);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_session WHERE revoked_at IS NULL AND session_group IN ('APP','H5')",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_schema_registry WHERE event_name='account.app_session_replaced'",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id WHERE s.event_name='account.app_session_replaced'",Integer.class)).isEqualTo(6);
        }
    }

    private void seedLegacy(String token,String label,LocalDateTime revoked){
        jdbc.update("INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,device_name,client_ip,expires_at,revoked_at,last_active_at,created_at) VALUES(1,?,?,?,'127.0.0.1','2030-01-01',?,'2026-01-01','2026-01-01')",token,"chain-"+token,label,revoked);
    }
    private void seedFixed(long user,String token,String group,String misleadingLabel,boolean execution,LocalDateTime revoked,String reason){
        jdbc.update("INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,device_name,client_ip,expires_at,revoked_at,last_active_at,created_at,session_group,installation_id,app_session_version,execution_supported,login_attempt_id,session_ended_reason) VALUES(?,?,?,?,'127.0.0.1','2030-01-01',?,'2026-01-01','2026-01-01',?,?,7,?,?,?)",
                user,token,"chain-"+token,misleadingLabel,revoked,group,"install-"+token,execution,"attempt-"+token,reason);
    }
    private List<Map<String,Object>> rows(String table){return jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id");}
    private void migrate(Long serverSeconds) throws Exception {
        String script=Files.readString(Path.of("scripts/migrations/20261019_app_single_session.sql"));
        String sql=script.lines().filter(line->!line.stripLeading().startsWith("--")).reduce("",(a,b)->a+"\n"+b);
        // SET/PREPARE/EXECUTE must use one physical connection; JdbcTemplate per-statement connections are not sufficient.
        try(var connection=jdbc.getDataSource().getConnection();var statement=connection.createStatement()){
            if(serverSeconds!=null)statement.execute("SET timestamp="+serverSeconds);
            for(String command:sql.split(";"))if(!command.isBlank())statement.execute(command);
        }
    }
    @AfterEach void cleanup() throws Exception {
        try{
            if(created && schema!=null && OWNED.matcher(schema).matches()){
                try(var statement=admin.createStatement()){statement.execute("DROP DATABASE `"+schema+"`");}
                try(var statement=admin.prepareStatement("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?")){
                    statement.setString(1,schema);try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isZero();}
                }
                System.out.println("APP_SESSION_MIGRATION_FIXTURE_DROP="+schema);
            }
        }finally{if(admin!=null)admin.close();}
    }
}
