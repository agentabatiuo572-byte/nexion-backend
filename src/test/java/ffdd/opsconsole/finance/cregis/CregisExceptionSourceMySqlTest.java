package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.finance.mapper.CregisExceptionSourceMapper;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Real row locks/CAS on a named isolated MySQL schema, never a production wallet/database. */
@EnabledIfEnvironmentVariable(named="CREGIS_EXCEPTION_MYSQL_IT",matches="true")
class CregisExceptionSourceMySqlTest {
    CregisExceptionSourceService service;CregisExceptionSourceMapper mapper;TransactionTemplate tx;JdbcTemplate jdbc;
    CregisExceptionSourceService.Source source;
    @BeforeEach void setup() {
        String url=System.getenv("CREGIS_EXCEPTION_MYSQL_JDBC");
        assertThat(url).matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/cregis_exception_test(?:\\?.*)?");
        var ds=new DriverManagerDataSource(url,System.getenv("CREGIS_EXCEPTION_MYSQL_USER"),System.getenv("CREGIS_EXCEPTION_MYSQL_PASSWORD"));
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_cregis_exception_source(project_id BIGINT NOT NULL,txid VARCHAR(66) NOT NULL,log_index INT NOT NULL,cid BIGINT NOT NULL,address VARCHAR(64) NOT NULL,gross_amount DECIMAL(18,6) NOT NULL,state VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE',operation_kind VARCHAR(16),operation_ref VARCHAR(96),alias_of_log_index INT,version BIGINT NOT NULL DEFAULT 0,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(project_id,txid,log_index)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_cregis_deposit_event(id BIGINT AUTO_INCREMENT PRIMARY KEY,project_id BIGINT,txid VARCHAR(66),log_index INT,cid BIGINT,address VARCHAR(64),gross_amount DECIMAL(18,6),status VARCHAR(24),ledger_id BIGINT,UNIQUE KEY eventlog(project_id,txid,log_index)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_cregis_chain_observation(project_id BIGINT,txid VARCHAR(66),log_index INT,address VARCHAR(64),raw_amount VARCHAR(80),status VARCHAR(32),checked_at DATETIME,last_error VARCHAR(64)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_cregis_deposit_delivery(accepted INT,cid BIGINT,txid VARCHAR(66),address VARCHAR(64),gross_amount DECIMAL(18,6),processed_at DATETIME,reason VARCHAR(64)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_cregis_risk_alert(project_id BIGINT,alert_key VARCHAR(128),kind VARCHAR(48),resolved_at DATETIME) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_cregis_deposit_address(project_id BIGINT,chain_id VARCHAR(16),address VARCHAR(64),user_id BIGINT,state VARCHAR(24),updated_at DATETIME) ENGINE=InnoDB");
        var configuration=new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("fixture",new SpringManagedTransactionFactory(),ds));
        configuration.addMapper(CregisExceptionSourceMapper.class);
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(CregisExceptionSourceMapper.class);
        var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        var deposits=mock(CregisDepositMapper.class);when(deposits.unresolvedExposure(anyLong())).thenReturn(BigDecimal.ZERO);
        var proxy=new ProxyFactory(new CregisExceptionSourceService(mapper,deposits));proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));service=(CregisExceptionSourceService)proxy.getProxy();
        source=new CregisExceptionSourceService.Source(88,UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE,"0x"+(UUID.randomUUID().toString()+UUID.randomUUID()).replace("-",""),2,"0x"+"a".repeat(40),BigDecimal.TEN);
    }
    @Test void claimAndRefundCompeteForOnePhysicalSource() throws Exception {race("CLAIM","REFUND");}
    @Test void automaticCreditAndRefundCompeteForOnePhysicalSource() throws Exception {race("AUTO","REFUND");}
    void race(String a,String b) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CyclicBarrier(2);
        try {
            Future<Boolean> first=pool.submit(()->attempt(a,start));Future<Boolean> second=pool.submit(()->attempt(b,start));
            assertThat((first.get(15,TimeUnit.SECONDS)?1:0)+(second.get(15,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_cregis_exception_source WHERE project_id=88 AND txid=? AND state='RESERVED'",Integer.class,source.txid())).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    boolean attempt(String kind,CyclicBarrier start) throws Exception {
        start.await(10,TimeUnit.SECONDS);
        try {tx.executeWithoutResult(s->service.reserve(source,kind,kind+"-case"));return true;}catch(BizException occupied){assertThat(occupied).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");return false;}
    }
    @Test void sameLogDifferentCidCannotCreateAnotherFinancialSource(){
        tx.executeWithoutResult(s->service.reserve(source,"CLAIM","CL-one"));
        var other=new CregisExceptionSourceService.Source(88,source.cid()==Long.MAX_VALUE?1:source.cid()+1,source.txid(),2,source.address(),BigDecimal.TEN);
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.reserve(other,"REFUND","RF-two"))).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
    }
    @Test void historicalCreditedCidRejectsMovedGlobalLogIndex(){
        jdbc.update("INSERT INTO nx_cregis_deposit_event(project_id,txid,log_index,cid,status,ledger_id) VALUES(88,?,1,?,'CREDITED',42)",source.txid(),source.cid());
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.reserve(source,"REFUND","RF-moved"))).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
        jdbc.update("DELETE FROM nx_cregis_deposit_event WHERE project_id=88 AND txid=?",source.txid());
    }
    @Test void reminedIndexConsumesBothIdentitiesAndPersistsAlias(){
        jdbc.update("INSERT INTO nx_cregis_deposit_event(project_id,txid,log_index,cid,address,gross_amount,status,ledger_id) VALUES(88,?,?,?,?,?,'REORG_INVESTIGATING',42)",source.txid(),source.logIndex(),source.cid(),source.address(),source.grossAmount());
        var moved=new CregisExceptionSourceService.Source(88,source.cid(),source.txid(),9,source.address(),source.grossAmount());
        tx.executeWithoutResult(s->service.relocateCredited(source,moved,"CR-REORG-1:2"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_cregis_exception_source WHERE txid=? AND state='CREDITED'",Integer.class,source.txid())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT alias_of_log_index FROM nx_cregis_exception_source WHERE txid=? AND log_index=9",Integer.class,source.txid())).isEqualTo(source.logIndex());
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.reserve(source,"REFUND","RF-original"))).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.reserve(moved,"CLAIM","CL-new"))).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
    }
    @Test void occupiedNewIndexRollsBackOriginalLegacyAlias(){
        var moved=new CregisExceptionSourceService.Source(88,source.cid(),source.txid(),9,source.address(),source.grossAmount());
        tx.executeWithoutResult(s->service.reserve(moved,"REFUND","RF-pending"));
        jdbc.update("INSERT INTO nx_cregis_deposit_event(project_id,txid,log_index,cid,address,gross_amount,status,ledger_id) VALUES(88,?,?,?,?,?,'REORG_REVERSED',42)",source.txid(),source.logIndex(),source.cid(),source.address(),source.grossAmount());
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.relocateCredited(source,moved,"CR-REORG-1:2"))).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_cregis_exception_source WHERE txid=? AND state='CREDITED'",Integer.class,source.txid())).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM nx_cregis_exception_source WHERE txid=? AND log_index=9",String.class,source.txid())).isEqualTo("RESERVED");
    }
    @Test void failedBusinessTransactionRollsBackReservation(){
        assertThatThrownBy(()->tx.executeWithoutResult(s->{service.reserve(source,"CLAIM","CL-one");throw new IllegalStateException("AUDIT_FAILURE");})).hasMessage("AUDIT_FAILURE");
        tx.executeWithoutResult(s->service.reserve(source,"REFUND","RF-one"));
        assertThat(jdbc.queryForObject("SELECT operation_ref FROM nx_cregis_exception_source WHERE txid=?",String.class,source.txid())).isEqualTo("RF-one");
    }
    @Test void unknownRefundRemainsReservedAcrossRetriesAndWrongOwnerCannotRelease(){
        tx.executeWithoutResult(s->service.reserve(source,"REFUND","RF-original"));
        tx.executeWithoutResult(s->service.ensureReserved(source,"REFUND","RF-original"));
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.release(source,"REFUND","RF-new"))).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.reserve(source,"AUTO","CR-77"))).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
    }
    @Test void completionIsAtomicIdempotentAndRejectsStaleVersion(){
        tx.executeWithoutResult(s->{service.reserve(source,"REFUND","RF-original");assertThat(mapper.transition(source,"REFUNDED","REFUND","RF-original",-1)).isZero();service.complete(source,"REFUND","RF-original","REFUNDED");});
        tx.executeWithoutResult(s->service.complete(source,"REFUND","RF-original","REFUNDED"));
        assertThat(jdbc.queryForObject("SELECT version FROM nx_cregis_exception_source WHERE txid=?",Long.class,source.txid())).isEqualTo(2);
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.reserve(source,"CLAIM","CL-after"))).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
    }
}
