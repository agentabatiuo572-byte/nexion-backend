package ffdd.opsconsole.finance.cregis;
import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.finance.mapper.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;

/** Actual migration and mapper constraints in a dedicated local schema; never opens the business database. */
@EnabledIfEnvironmentVariable(named="CREGIS_AUTO_MYSQL_IT",matches="true")
@ResourceLock("cregis_auto_forward_it")
class CregisAutoForwardMySqlTest {
    static final String SCHEMA="cregis_auto_forward_it";final long project=1466552794661984L;
    DriverManagerDataSource root,data;JdbcTemplate jdbc;CregisAutoForwardMapper db;CregisDepositMapper deposits;boolean owned;
    final String a="0x"+"a".repeat(40),b="0x"+"b".repeat(40);
    @BeforeEach void setup(){
        String url=System.getenv("CREGIS_AUTO_MYSQL_JDBC");assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.*");
        root=new DriverManagerDataSource(url,System.getenv("CREGIS_AUTO_MYSQL_USER"),System.getenv("CREGIS_AUTO_MYSQL_PASSWORD"));
        new JdbcTemplate(root).execute("CREATE DATABASE "+SCHEMA);owned=true;
        data=new DriverManagerDataSource(url.replace("/?","/"+SCHEMA+"?"),System.getenv("CREGIS_AUTO_MYSQL_USER"),System.getenv("CREGIS_AUTO_MYSQL_PASSWORD"));jdbc=new JdbcTemplate(data);
        new ResourceDatabasePopulator(new FileSystemResource(Path.of("scripts/migrations/20261022_05_cregis_auto_forward.sql"))).execute(data);
        jdbc.execute("CREATE TABLE nx_cregis_deposit_event(chain_id VARCHAR(16) NOT NULL DEFAULT '2510',id BIGINT PRIMARY KEY,project_id BIGINT,user_id BIGINT,txid VARCHAR(66),log_index INT,address VARCHAR(42),gross_amount DECIMAL(18,6),net_amount DECIMAL(18,6),fee_amount DECIMAL(18,6),block_number BIGINT,status VARCHAR(30)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_cregis_deposit_address(project_id BIGINT,address VARCHAR(42),user_id BIGINT,chain_id VARCHAR(8),state VARCHAR(20)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_cregis_risk_alert(project_id BIGINT,alert_key VARCHAR(100),severity VARCHAR(5),kind VARCHAR(40),evidence JSON,resolved_at DATETIME,UNIQUE KEY uk(project_id,alert_key)) ENGINE=InnoDB");
        var c=new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("auto-forward-isolated",new SpringManagedTransactionFactory(),data));c.addMapper(CregisAutoForwardMapper.class);c.addMapper(CregisDepositMapper.class);
        var sessions=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c));db=sessions.getMapper(CregisAutoForwardMapper.class);deposits=sessions.getMapper(CregisDepositMapper.class);
        seed(1,a);seed(2,b);
    }
    @AfterEach void cleanup(){if(owned)new JdbcTemplate(root).execute("DROP DATABASE "+SCHEMA);}
    void seed(long id,String address){jdbc.update("INSERT INTO nx_cregis_deposit_event(id,project_id,user_id,txid,log_index,address,gross_amount,net_amount,fee_amount,block_number,status) VALUES(?,?,7,?,0,?,5,4,1,90,'CREDITED')",id,project,"0x"+"c".repeat(64),address);jdbc.update("INSERT INTO nx_cregis_deposit_address VALUES(?,?,7,'2510','READY')",project,address);}
    Map<String,Object> event(long id){return db.lockEvent(project,id);}
    void enqueue(long id){db.enqueue(project,event(id),CregisFundsPreparationService.PLATFORM,CregisFundsPreparationService.COUNTERPARTY,"CA-P-"+project+"-"+id);}
    Map<String,Object> task(){return db.page(project,0,20).get(0);}
    @Test void defaultsAndFirstActivationWatermarkExcludeHistory(){
        var p=db.policy(project);assertThat(p.get("collectionEnabled")).isIn(false,0);assertThat(p.get("paymentEnabled")).isIn(false,0);assertThat(p.get("startEventId")).isNull();
        assertThat(db.apply(project,0,1,1,"offline activation fixture")).isEqualTo(1);assertThat(db.policy(project).get("startEventId")).isEqualTo("2");
        seed(3,"0x"+"d".repeat(40));assertThat(db.candidates(project,2)).containsExactly(3L);
        db.pause(project,"fixture pause");db.apply(project,2,1,1,"fixture resume");assertThat(db.policy(project).get("startEventId")).isEqualTo("2");
    }
    @Test void realConcurrentCasAllowsOnlyOneCommittedIntent()throws Exception{
        enqueue(1);var row=task();row.put("intentAt",LocalDateTime.now());row.put("collectionFloor",100L);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{Callable<Integer> update=()->{start.await();return db.save(project,new HashMap<>(row),"COLLECTION_SUBMITTING",null);};
            var left=pool.submit(update);var right=pool.submit(update);start.countDown();assertThat(left.get(10,TimeUnit.SECONDS)+right.get(10,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(task().get("state")).isEqualTo("COLLECTION_SUBMITTING");assertThat(((Number)task().get("version")).longValue()).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void eventSourceCidAndMoneyConstraintsCannotBeDuplicated(){
        enqueue(1);assertThatThrownBy(()->enqueue(1)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        jdbc.update("UPDATE nx_cregis_deposit_event SET address=? WHERE id=2",a);jdbc.update("DELETE FROM nx_cregis_deposit_address WHERE address=?",b);
        assertThatThrownBy(()->enqueue(2)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        var first=task();first.put("collectionCid",123L);db.save(project,first,"COMPLETED",null);enqueue(2);
        var second=task();second.put("collectionCid",123L);assertThatThrownBy(()->db.save(project,second,"COLLECTION_PENDING",null)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE nx_cregis_auto_forward_task SET net_amount=9 WHERE event_id=2")).hasMessageContaining("ck_cregis_auto_money");
    }
    @Test void unknownAlertActuallyFeedsExistingPayoutRiskAndCanReopen(){
        db.holdUnknown(project,1,"unknown");assertThat(deposits.openAutoForwardUnknownAlertCount(project)).isEqualTo(1);
        db.resolveUnknown(project,1);assertThat(deposits.openAutoForwardUnknownAlertCount(project)).isZero();
        db.holdUnknown(project,1,"late uncertainty");assertThat(deposits.openAutoForwardUnknownAlertCount(project)).isEqualTo(1);
    }
}
