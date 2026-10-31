package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.finance.mapper.CregisDepositPolicyMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.math.BigDecimal;
import java.util.Map;
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

/** Real MySQL locking in an independently created loopback-only test schema. */
@EnabledIfEnvironmentVariable(named="CREGIS_EXCEPTION_MYSQL_IT", matches="true")
class CregisDepositPolicyMySqlTest {
    JdbcTemplate base, jdbc;
    String schema;
    TransactionTemplate tx;
    CregisDepositPolicyService service;
    CregisDepositPolicyMapper policies;
    CregisDepositMapper deposits;
    AuditLogService audit;
    final String hash="0x"+"a".repeat(64);

    @BeforeEach void setup() {
        String url=System.getenv("CREGIS_EXCEPTION_MYSQL_JDBC");
        assertThat(url).matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/cregis_exception_test(?:\\?.*)?");
        String user=System.getenv("CREGIS_EXCEPTION_MYSQL_USER"), password=System.getenv("CREGIS_EXCEPTION_MYSQL_PASSWORD");
        base=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        schema="cregis_policy_it_"+UUID.randomUUID().toString().replace("-","");
        base.execute("CREATE DATABASE "+schema);
        String schemaUrl=url.replaceFirst("/cregis_exception_test(?=\\?|$)","/"+schema);
        var ds=new DriverManagerDataSource(schemaUrl,user,password);
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE nx_cregis_deposit_policy_lock(id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO nx_cregis_deposit_policy_lock VALUES(1)");
        jdbc.execute("CREATE TABLE nx_cregis_deposit_policy(version BIGINT NOT NULL PRIMARY KEY,min_deposit_usdt DECIMAL(18,6) NOT NULL,fee_usdt DECIMAL(18,6) NOT NULL,confirmations INT NOT NULL,operation_id VARCHAR(64),reason VARCHAR(300) NOT NULL) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO nx_cregis_deposit_policy VALUES(0,1,0,15,NULL,'fixture')");
        jdbc.execute("CREATE TABLE nx_cregis_deposit_policy_snapshot(project_id BIGINT NOT NULL,chain_id VARCHAR(16) NOT NULL,token_id VARCHAR(64) NOT NULL,txid VARCHAR(66) NOT NULL,policy_version BIGINT NOT NULL,required_confirmations INT NOT NULL,PRIMARY KEY(project_id,chain_id,token_id,txid)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_cregis_deposit_event(chain_id VARCHAR(16) NOT NULL DEFAULT '2510',id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,project_id BIGINT NOT NULL,cid BIGINT NOT NULL,txid VARCHAR(66) NOT NULL,log_index INT NOT NULL,origin_log_index INT NOT NULL,address VARCHAR(64) NOT NULL,gross_amount DECIMAL(18,6) NOT NULL,fee_amount DECIMAL(18,6) NOT NULL,net_amount DECIMAL(18,6) NOT NULL,block_number BIGINT NOT NULL,block_hash VARCHAR(66) NOT NULL,confirmations INT NOT NULL,canonical_until BIGINT,last_canonical_checked_block BIGINT,last_canonical_checked_hash VARCHAR(66),status VARCHAR(24) NOT NULL,UNIQUE KEY(project_id,chain_id,txid,log_index),UNIQUE KEY(project_id,cid)) ENGINE=InnoDB");
        var conf=new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("fixture",new SpringManagedTransactionFactory(),ds));
        conf.addMapper(CregisDepositPolicyMapper.class);conf.addMapper(CregisDepositMapper.class);
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(conf));
        policies=session.getMapper(CregisDepositPolicyMapper.class);deposits=session.getMapper(CregisDepositMapper.class);
        var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        audit=mock(AuditLogService.class);
        var proxy=new ProxyFactory(new CregisDepositPolicyService(policies,audit,new CregisProperties()));proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        service=(CregisDepositPolicyService)proxy.getProxy();
    }
    @AfterEach void cleanup() { A2ReplayContext.exitReplay(); if(base!=null && schema!=null) base.execute("DROP DATABASE "+schema); }

    @Test void initialPolicyAndDistinctLogsKeepOneImmutableTransactionSnapshot() {
        var original=service.discover(88,hash.toUpperCase());
        assertThat(original.version()).isZero();assertThat(original.minDepositUsdt()).isEqualByComparingTo("1");assertThat(original.feeUsdt()).isEqualByComparingTo("0");
        insertEvent(11,1,"2");insertEvent(12,2,"3");
        publish(0);
        assertThat(service.discover(88,hash).version()).isZero();
        assertThat(service.snapshot(88,hash).feeUsdt()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_cregis_deposit_policy_snapshot",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT gross_amount FROM nx_cregis_deposit_event WHERE txid=? ORDER BY log_index",BigDecimal.class,hash)).containsExactly(new BigDecimal("2.000000"),new BigDecimal("3.000000"));
    }
    @Test void discoverFirstKeepsOldPolicyWhileConcurrentPublishWaits() throws Exception { orderedRace(true); }
    @Test void publishFirstAppliesNewPolicyToConcurrentDiscovery() throws Exception { orderedRace(false); }
    void orderedRace(boolean discoverFirst) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        try {
            Future<CregisDepositPolicy> first=pool.submit(()->tx.execute(s->{
                var result=discoverFirst?service.discover(88,hash):publish(0);
                locked.countDown();await(release);return result;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            Future<CregisDepositPolicy> second=pool.submit(()->{started.countDown();return discoverFirst?publish(0):service.discover(88,hash);});
            assertThat(started.await(10,TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS).version()).isEqualTo(discoverFirst?0:1);
            assertThat(second.get(15,TimeUnit.SECONDS).version()).isEqualTo(1);
            assertThat(service.snapshot(88,hash).version()).isEqualTo(discoverFirst?0:1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_cregis_deposit_policy",Integer.class)).isEqualTo(2);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void a2ReplayAndExpectedVersionAreRequiredAndAuditFailureRollsBack() {
        assertThatThrownBy(()->service.publish(params(0),"checker")).hasMessage("A2_CONFIRMATION_REQUIRED");
        publish(0);
        assertThatThrownBy(()->publish(0)).hasMessage("CREGIS_DEPOSIT_POLICY_VERSION_CONFLICT");
        doThrow(new IllegalStateException("AUDIT_FAILURE")).when(audit).recordRequired(any());
        assertThatThrownBy(()->publish(1)).hasMessage("AUDIT_FAILURE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_cregis_deposit_policy",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT operation_id FROM nx_cregis_deposit_policy WHERE version=1",String.class)).isEqualTo("fixture-a2");
    }
    CregisDepositPolicy publish(long version) {
        A2ReplayContext.enterReplay("fixture-a2");
        try {return service.publish(params(version),"checker");} finally {A2ReplayContext.exitReplay();}
    }
    Map<String,Object> params(long version) {return Map.of("expectedVersion",version,"minDepositUsdt","10","feeUsdt","1","confirmations",20,"reason","isolated test policy");}
    void insertEvent(long cid,int log,String amount) {
        BigDecimal value=new BigDecimal(amount);
        assertThat(deposits.insertEvent(7,88,cid,hash,log,"0x"+"b".repeat(40),value,BigDecimal.ZERO,value,100,hash,15,"PENDING")).isEqualTo(1);
    }
    static void await(CountDownLatch latch) {try {if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("timed out");} catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
}
