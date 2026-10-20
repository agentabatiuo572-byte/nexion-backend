package ffdd.opsconsole.shared.audit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual production mapper and canonical hot/cold schema on an explicitly owned loopback server. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditProposalRecoveryMySqlTest {
    Connection admin; String schema; JdbcTemplate jdbc; AuditLogMapper mapper; AuditLogService audit;

    @BeforeAll void createOwnedSchema() throws Exception {
        String url=System.getenv("NEXION_TEST_DB_SERVER_URL");
        if (url==null || !url.matches("jdbc:mysql://(127[.]0[.]0[.]1|localhost|\\[::1\\]):[0-9]{1,5}/([?].*)?"))
            throw new IllegalArgumentException("Explicit loopback MySQL server URL without a catalog required");
        int port=java.net.URI.create(url.substring(5)).getPort();
        String username=System.getenv("NEXION_TEST_DB_USERNAME"), password=System.getenv("NEXION_TEST_DB_PASSWORD");
        String directory=System.getenv("NEXION_TEST_DB_EXPECTED_DATADIR");
        if (port<1 || port>65535 || username==null || username.isBlank() || directory==null || !Path.of(directory).isAbsolute())
            throw new IllegalArgumentException("Explicit credentials and verified absolute data directory required");
        admin=DriverManager.getConnection(url,username,password);
        try(var statement=admin.createStatement(); var server=statement.executeQuery("SELECT @@port,@@datadir")) {
            if(!server.next() || server.getInt(1)!=port || !Path.of(server.getString(2)).normalize().equals(Path.of(directory).normalize()))
                throw new IllegalArgumentException("MySQL server does not match the verified test target");
        }
        String candidate="nx_a2_proposal_"+UUID.randomUUID().toString().replace("-","");
        try(var statement=admin.createStatement()) { statement.execute("CREATE DATABASE `"+candidate+"` CHARACTER SET utf8mb4"); }
        schema=candidate;
        int q=url.indexOf('?');
        var source=new DriverManagerDataSource(url.substring(0,q<0?url.length():q)+schema+(q<0?"":url.substring(q)),username,password);
        jdbc=new JdbcTemplate(source);
        String baseline=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:List.of("nx_audit_log","nx_audit_log_archive")) {
            var ddl=java.util.regex.Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS "+table+" \\(.*?\\) ENGINE=InnoDB[^\\r\\n]*;").matcher(baseline);
            assertThat(ddl.find()).as("canonical DDL %s",table).isTrue();
            jdbc.execute(ddl.group());
        }
        var configuration=new MybatisConfiguration(new Environment("a2-proposal-recovery",new SpringManagedTransactionFactory(),source));
        configuration.setMapUnderscoreToCamelCase(true); configuration.addMapper(AuditLogMapper.class);
        mapper=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration)).getMapper(AuditLogMapper.class);
        audit=new AuditLogService(mapper,new AuditLogSanitizer(new com.fasterxml.jackson.databind.ObjectMapper()),
                new ApplicationNameProperties(),new AuditProperties(),mock(ffdd.opsconsole.auth.mapper.AdminMapper.class),
                mock(ffdd.opsconsole.platform.application.A2RuntimePolicy.class));
    }

    @AfterAll void dropOwnedSchema() throws Exception {
        if(admin!=null) try {
            if(schema!=null && schema.matches("nx_a2_proposal_[a-f0-9]{32}"))
                try(var statement=admin.createStatement()) { statement.execute("DROP DATABASE `"+schema+"`"); }
        } finally { admin.close(); }
    }
    @BeforeEach void clearFixture() { jdbc.update("DELETE FROM nx_audit_log_archive"); jdbc.update("DELETE FROM nx_audit_log"); }

    @Test void exactLookupSurvivesMoreThanFiveHundredLaterEventsWithoutWriting() {
        insert(9,"original-Key","WO-ORIGINAL","A2_OPERATION_PROPOSED","SUCCESS");
        for(int i=0;i<550;i++) insert(9,"noise-"+i,"WO-NOISE-"+i,"A2_OPERATION_PROPOSED","SUCCESS");
        assertThat(audit.findA2ProposalId(9,"original-Key")).isEqualTo("WO-ORIGINAL");
        assertThat(audit.findA2ProposalId(9,"original-key")).isNull(); // keys are case-sensitive
        assertThat(audit.findA2ProposalId(10,"original-Key")).isNull();
        assertThat(audit.findA2ProposalId(9,"missing")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log",Long.class)).isEqualTo(551L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log_archive",Long.class)).isZero();
    }

    @Test void productionArchiveThenHotDeletionPreservesExactRecovery() {
        insert(9,"archived-key","WO-ARCHIVED","A2_OPERATION_PROPOSED","SUCCESS");
        long id=jdbc.queryForObject("SELECT id FROM nx_audit_log",Long.class);
        assertThat(mapper.insertArchiveFromHot(id)).isEqualTo(1);
        assertThat(audit.findA2ProposalId(9,"archived-key")).isEqualTo("WO-ARCHIVED"); // hot+cold overlap is one identity
        assertThat(mapper.deleteHotAfterArchive(id)).isEqualTo(1);
        assertThat(audit.findA2ProposalId(9,"archived-key")).isEqualTo("WO-ARCHIVED");
        assertThat(audit.findA2ProposalId(10,"archived-key")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log_archive",Long.class)).isEqualTo(1L);
    }

    @Test void wrongActionsFailuresAndAmbiguousHistoricalKeysNeverReturnAnArbitraryTicket() {
        insert(9,"key","WO-APPROVAL","A2_OPERATION_APPROVED","SUCCESS");
        insert(9,"key","WO-FAILED","A2_OPERATION_PROPOSED","FAILED");
        insert(10,"key","WO-OTHER-MAKER","A2_OPERATION_PROPOSED","SUCCESS");
        assertThat(audit.findA2ProposalId(9,"key")).isNull();
        assertThat(audit.findA2ProposalId(10,"key")).isEqualTo("WO-OTHER-MAKER");
        insert(9,"key","WO-ONE","A2_OPERATION_PROPOSED","SUCCESS");
        insert(9,"key","WO-TWO","A2_OPERATION_PROPOSED","SUCCESS");
        assertThatThrownBy(()->audit.findA2ProposalId(9,"key"))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class).hasMessage("A2_PROPOSAL_RESULT_AMBIGUOUS");
    }

    private void insert(long actor,String key,String operation,String action,String result) {
        jdbc.update("""
                INSERT INTO nx_audit_log(service_name,action,resource_type,resource_id,actor_id,actor_type,
                  actor_username,result,detail_json,retention_policy_months,expire_at)
                VALUES('test',?,'A2_OPERATION',?,?,'ADMIN','maker',?,JSON_OBJECT('idempotencyKey',?,'sourceDomain','E'),1,DATE_SUB(NOW(),INTERVAL 1 DAY))
                """,action,operation,actor,result,key);
    }
}
