package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.finance.cregis.CregisPayoutControl;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Real concurrent InnoDB transactions, confined to a dedicated disposable local schema. */
@EnabledIfEnvironmentVariable(named="CREGIS_EXCEPTION_MYSQL_IT",matches="true")
@ResourceLock("cregis_payout_dispatch_test")
class WithdrawalPayoutDispatchGuardMySqlTest {
    static final String SCHEMA="cregis_payout_dispatch_test";
    final LocalDateTime now=LocalDateTime.of(2026,10,22,0,0);
    DriverManagerDataSource data;JdbcTemplate admin,jdbc;boolean owned;
    WithdrawalPayoutMapper mapper;CregisDepositMapper deposits;TransactionTemplate tx;
    final CregisPayoutControl control=mock(CregisPayoutControl.class);
    @BeforeEach void setup(){
        String url=System.getenv("CREGIS_EXCEPTION_MYSQL_JDBC");
        assertThat(url).matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/cregis_exception_test(?:\\?.*)?");
        String user=System.getenv("CREGIS_EXCEPTION_MYSQL_USER"),password=System.getenv("CREGIS_EXCEPTION_MYSQL_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));admin.execute("CREATE DATABASE "+SCHEMA);owned=true;
        data=new DriverManagerDataSource(url.replace("/cregis_exception_test","/"+SCHEMA),user,password);jdbc=new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE nx_cregis_provision_gate(id INT PRIMARY KEY,state VARCHAR(16),assign_enabled INT,credit_enabled INT,payout_enabled INT,version BIGINT,switch_reason VARCHAR(255),updated_at DATETIME) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO nx_cregis_provision_gate VALUES(1,'IDLE',1,1,1,0,NULL,NOW())");
        jdbc.execute("CREATE TABLE nx_config_item(config_key VARCHAR(128) PRIMARY KEY,config_value VARCHAR(32),is_deleted INT,status INT) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO nx_config_item VALUES('withdrawal.bep20.enabled','true',0,1),('withdrawal.erc20.enabled','false',0,1)");
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(24),sandbox INT,is_deleted INT) ENGINE=InnoDB");jdbc.execute("INSERT INTO nx_user VALUES(7,'ACTIVE',0,0)");
        jdbc.execute("CREATE TABLE nx_withdrawal_order(id BIGINT PRIMARY KEY,withdrawal_no VARCHAR(96) UNIQUE,user_id BIGINT,chain VARCHAR(32),target_address VARCHAR(64),amount DECIMAL(18,6),d2_net_receive DECIMAL(18,6),d2_nex_burned DECIMAL(18,6),status VARCHAR(32),d5_payout_due_at DATETIME,d5_provider_cid BIGINT,d5_provider_idempotency_key VARCHAR(96),d5_payout_source VARCHAR(24),chain_broadcast_attempts INT,c2_frozen_by_user_status INT,c2_previous_status VARCHAR(32),completed_at DATETIME,is_deleted INT,d5_payout_lease_until DATETIME,chain_submitted_at DATETIME,next_broadcast_at DATETIME,last_broadcast_error VARCHAR(255),failure_reason VARCHAR(255),failed_at DATETIME,chain_tx_hash VARCHAR(66),created_at DATETIME,updated_at DATETIME) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO nx_withdrawal_order(id,withdrawal_no,user_id,chain,target_address,amount,d2_net_receive,d2_nex_burned,status,chain_broadcast_attempts,c2_frozen_by_user_status,is_deleted) VALUES(1,'WD-guard',7,'USDT-BEP20','0x"+"a".repeat(40)+"',10,1,0,'REVIEW_PASSED',0,0,0)");
        var config=new Configuration(new Environment("dispatch-race",new SpringManagedTransactionFactory(),data));
        config.addMapper(WithdrawalPayoutMapper.class);config.addMapper(CregisDepositMapper.class);
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));mapper=session.getMapper(WithdrawalPayoutMapper.class);deposits=session.getMapper(CregisDepositMapper.class);
        tx=new TransactionTemplate(new DataSourceTransactionManager(data));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        when(control.enabled()).thenReturn(true);when(control.enabled(any())).thenReturn(true);
    }
    @AfterEach void cleanup(){if(owned)admin.execute("DROP DATABASE "+SCHEMA);}
    @Test void committedStopWinsTheGateAndLeavesNoClaimOrIntent()throws Exception {
        var attempted=new CountDownLatch(1);var observed=mock(CregisDepositMapper.class);
        when(observed.lockProvisionGate()).thenAnswer(call->{attempted.countDown();return deposits.lockProvisionGate();});
        var guard=new WithdrawalPayoutDispatchGuard(mapper,observed,control);var pool=Executors.newSingleThreadExecutor();
        try(Connection stop=data.getConnection()){
            stop.setAutoCommit(false);stop.createStatement().executeQuery("SELECT id FROM nx_cregis_provision_gate WHERE id=1 FOR UPDATE").close();
            stop.createStatement().executeUpdate("UPDATE nx_cregis_provision_gate SET payout_enabled=0,state='BLOCKED' WHERE id=1");
            var pending=pool.submit(()->tx.execute(ignored->guard.begin(WithdrawalPayoutDispatchGuardTest.row("REVIEW_PASSED",null),now)));
            assertThat(attempted.await(5,TimeUnit.SECONDS)).isTrue();stop.commit();
            assertThat(pending.get(5,TimeUnit.SECONDS)).isNull();
            assertThat(jdbc.queryForMap("SELECT status,d5_payout_source,chain_broadcast_attempts FROM nx_withdrawal_order WHERE id=1"))
                    .containsEntry("status","REVIEW_PASSED").containsEntry("d5_payout_source",null).containsEntry("chain_broadcast_attempts",0);
        }finally{pool.shutdownNow();}
    }
    @Test void intentWinsTheGateThenLaterStopCannotCreateASecondIntent()throws Exception {
        var admitted=new CountDownLatch(1);var release=new CountDownLatch(1);var stopAttempted=new CountDownLatch(1);
        when(control.enabled()).thenAnswer(call->{admitted.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test barrier timeout");return true;});
        var guard=new WithdrawalPayoutDispatchGuard(mapper,deposits,control);var pool=Executors.newFixedThreadPool(2);
        try{
            var intent=pool.submit(()->tx.execute(ignored->guard.begin(WithdrawalPayoutDispatchGuardTest.row("REVIEW_PASSED",null),now)));
            assertThat(admitted.await(5,TimeUnit.SECONDS)).isTrue();
            var stop=pool.submit(()->tx.execute(ignored->{stopAttempted.countDown();deposits.lockProvisionGate();jdbc.update("UPDATE nx_cregis_provision_gate SET payout_enabled=0,state='BLOCKED' WHERE id=1");return true;}));
            assertThat(stopAttempted.await(5,TimeUnit.SECONDS)).isTrue();release.countDown();
            var original=intent.get(5,TimeUnit.SECONDS);assertThat(original.payoutSource()).isEqualTo("provider");assertThat(stop.get(5,TimeUnit.SECONDS)).isTrue();
            WithdrawalPayoutMapper.PayoutRow stopped=tx.execute(ignored->guard.begin(WithdrawalPayoutDispatchGuardTest.row("REVIEW_PASSED",null),now));
            assertThat(stopped).isNull();
            assertThat(mapper.markProviderIntent("WD-guard",now)).isZero();assertThat(mapper.claimNewPayout("WD-guard",now,now.plusMinutes(2))).isZero();
            assertThat(jdbc.queryForObject("SELECT chain_broadcast_attempts FROM nx_withdrawal_order WHERE id=1",Integer.class)).isEqualTo(1);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void intentConflictRollsBackTheProcessingClaim(){
        var failing=mock(WithdrawalPayoutMapper.class);
        when(failing.lockNetworkPayoutEnabled(anyString())).thenReturn("true");
        when(failing.lockCashCustomer(7L)).thenAnswer(call->mapper.lockCashCustomer(7L));
        when(failing.claimNewPayout("WD-guard",now,now.plusMinutes(2))).thenAnswer(call->mapper.claimNewPayout("WD-guard",now,now.plusMinutes(2)));
        var guard=new WithdrawalPayoutDispatchGuard(failing,deposits,control);
        assertThatThrownBy(()->tx.execute(ignored->guard.begin(WithdrawalPayoutDispatchGuardTest.row("REVIEW_PASSED",null),now))).hasMessage("CREGIS_DISPATCH_INTENT_CONFLICT");
        assertThat(jdbc.queryForMap("SELECT status,d5_payout_source,chain_broadcast_attempts FROM nx_withdrawal_order WHERE id=1"))
                .containsEntry("status","REVIEW_PASSED").containsEntry("d5_payout_source",null).containsEntry("chain_broadcast_attempts",0);
    }
}
