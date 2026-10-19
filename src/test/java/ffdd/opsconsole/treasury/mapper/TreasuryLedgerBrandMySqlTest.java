package ffdd.opsconsole.treasury.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Actual D4 read methods on a disposable MySQL schema, including case/trailing-space semantics. */
class TreasuryLedgerBrandMySqlTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_D4_BRAND_IT", matches = "true")
    void bothD4ProjectionsPreserveCustomNamesAndLedgerFacts() throws Exception {
        assertThat(System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT")).isEqualTo("127.0.0.1:13306");
        String schema = "nx_d4_brand_it_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:mysql://127.0.0.1:13306/?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        System.out.println("D4_BRAND_SCHEMA_CREATED " + schema);
        try (var connection = new DriverManagerDataSource(url, "root", "").getConnection()) {
            connection.setCatalog(schema);
            var dataSource = new SingleConnectionDataSource(connection, true);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,nickname VARCHAR(100),phone VARCHAR(32),is_deleted INT DEFAULT 0)");
            jdbc.execute("CREATE TABLE nx_wallet_ledger(id BIGINT PRIMARY KEY,user_id BIGINT,biz_no VARCHAR(100),biz_type VARCHAR(40),asset VARCHAR(10),direction VARCHAR(10),amount DECIMAL(20,6),balance_after DECIMAL(20,6),status VARCHAR(20),remark VARCHAR(200),created_at DATETIME,updated_at DATETIME,is_deleted INT DEFAULT 0)");
            record NameCase(String raw, String phone, String expected) { }
            var names = List.of(
                    new NameCase("NexGrid-F343FA44", null, "UVEL-F343FA44"),
                    new NameCase("NexGrid-f343fa44", null, "NexGrid-f343fa44"),
                    new NameCase("NexGrid-F343FA44 ", null, "NexGrid-F343FA44 "),
                    new NameCase("NexGrid-F343FA4G", null, "NexGrid-F343FA4G"),
                    new NameCase("NexGrid 1058", "18000001058", "UVEL 1058"),
                    new NameCase("NexGrid 1058", "18000009999", "NexGrid 1058"),
                    new NameCase("NexGrid 1058", null, "NexGrid 1058"),
                    new NameCase("Custom NexGrid 1058", "18000001058", "Custom NexGrid 1058"),
                    new NameCase("nexgrid 1058", "18000001058", "nexgrid 1058"),
                    new NameCase("NEXGRID 1058", "18000001058", "NEXGRID 1058"),
                    new NameCase("NexGrid 1058 ", "18000001058", "NexGrid 1058 "),
                    new NameCase(" NexGrid 1058", "18000001058", " NexGrid 1058"),
                    new NameCase("NexGrid1058", "18000001058", "NexGrid1058"),
                    new NameCase("NexGrid  1058", "18000001058", "NexGrid  1058"),
                    new NameCase("Nexion 3778", "18000003778", "Nexion 3778"),
                    new NameCase("NexGrid 10x8", "180000010x8", "NexGrid 10x8"),
                    new NameCase("Nex***", "18000001058", "Nex***"),
                    new NameCase(null, "18000001058", null),
                    new NameCase("", "18000001058", ""),
                    new NameCase("UVEL 1058", "18000001058", "UVEL 1058"));
            for (int i = 0; i < names.size(); i++) {
                var sample = names.get(i);
                jdbc.update("INSERT INTO nx_user(id,nickname,phone) VALUES(?,?,?)",100+i,sample.raw(),sample.phone());
                jdbc.update("INSERT INTO nx_wallet_ledger VALUES(?,?,'F5-COMMISSION-410-RELEASE','TEAM_COMMISSION','USDT','IN',0.333333,1.333333,'SUCCESS','NexGrid audit unchanged',NOW(),NOW(),0)",100+i,100+i);
            }
            jdbc.update("INSERT INTO nx_user VALUES(900,'NexGrid 1058','18000001058',1)");
            jdbc.update("INSERT INTO nx_wallet_ledger VALUES(900,900,'DELETED-USER','TEAM_COMMISSION','USDT','IN',1,1,'SUCCESS','unchanged',NOW(),NOW(),0)");
            jdbc.update("INSERT INTO nx_wallet_ledger VALUES(901,901,'MISSING-USER','TEAM_COMMISSION','USDT','IN',1,1,'SUCCESS','unchanged',NOW(),NOW(),0)");
            Configuration config = new Configuration(new Environment("d4-brand-owned", new JdbcTransactionFactory(), dataSource));
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(TreasuryLedgerMapper.class);
            try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
                var mapper = session.getMapper(TreasuryLedgerMapper.class);
                for (int i = 0; i < names.size(); i++) {
                    long id = 100L+i;
                    var page = mapper.pageLedgerBills("commission",id,null,"F5-COMMISSION-410-RELEASE","SUCCESS",null,null,10,0);
                    assertThat(page).hasSize(1);
                    assertThat(mapper.countLedgerBills("commission",id,null,"F5-COMMISSION-410-RELEASE","SUCCESS",null,null)).isEqualTo(1);
                    var row = page.get(0);
                    assertThat(row.nickname()).as("name boundary %s",i).isEqualTo(names.get(i).expected());
                    assertThat(mapper.userLedgerRows(id,10)).containsExactly(row);
                    assertThat(row.userId()).isEqualTo(id);
                    assertThat(row.amount()).isEqualByComparingTo("0.333333");
                    assertThat(row.balanceAfter()).isEqualByComparingTo("1.333333");
                    assertThat(row.remark()).isEqualTo("NexGrid audit unchanged");
                    assertThat(jdbc.queryForObject("SELECT nickname FROM nx_user WHERE id=?",String.class,id)).isEqualTo(names.get(i).raw());
                }
                for (long id : List.of(900L,901L)) {
                    assertThat(mapper.userLedgerRows(id,10).get(0).nickname()).isNull();
                    assertThat(mapper.pageLedgerBills(null,id,null,null,null,null,null,10,0).get(0).nickname()).isNull();
                }
                assertThat(mapper.pageLedgerBills(null,null,null,"UNMATCHED",null,null,null,10,0)).isEmpty();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger",Integer.class)).isEqualTo(names.size()+2);
            }
        } finally {
            admin.execute("DROP DATABASE " + schema);
            assertThat(admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,schema)).isZero();
            System.out.println("D4_BRAND_SCHEMA_DROPPED " + schema);
        }
    }
}
