package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.application.SupportPaymentAttributionService;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.finance.mapper.CregisDepositPolicyMapper;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

/** Real callback inbox, event and money rows in a disposable loopback-only MySQL schema. */
@EnabledIfEnvironmentVariable(named="CREGIS_EXCEPTION_MYSQL_IT",matches="true")
class CregisCreditedCallbackMySqlTest {
    static final String KEY="0123456789abcdef0123456789abcdef", ADDRESS="0x"+"1".repeat(40), TXID="0x"+"a".repeat(64), BLOCK="0x"+"b".repeat(64);
    JdbcTemplate base,jdbc; String schema;
    CregisDepositService service; CregisDepositPolicyService policy;
    CregisGateway gateway; BscDepositProof chain;
    CregisDepositMapper mapper; DataSourceTransactionManager manager; CregisProperties config; CregisGatewayRouter router;

    @BeforeEach void setup() {
        String url=System.getenv("CREGIS_EXCEPTION_MYSQL_JDBC");
        assertThat(url).matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/cregis_exception_test(?:\\?.*)?");
        String user=System.getenv("CREGIS_EXCEPTION_MYSQL_USER"), password=System.getenv("CREGIS_EXCEPTION_MYSQL_PASSWORD");
        base=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        schema="cregis_callback_it_"+UUID.randomUUID().toString().replace("-","");
        base.execute("CREATE DATABASE "+schema);
        var ds=new DriverManagerDataSource(url.replaceFirst("/cregis_exception_test(?=\\?|$)","/"+schema),user,password);
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE nx_cregis_provision_gate(id INT PRIMARY KEY,state VARCHAR(16),assign_enabled INT,credit_enabled INT,payout_enabled INT,version BIGINT,switch_reason VARCHAR(64),updated_at DATETIME) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO nx_cregis_provision_gate VALUES(1,'IDLE',1,1,1,0,NULL,NOW())");
        jdbc.execute("CREATE TABLE nx_cregis_deposit_policy_lock(id INT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO nx_cregis_deposit_policy_lock VALUES(1)");
        jdbc.execute("CREATE TABLE nx_cregis_deposit_policy(version BIGINT PRIMARY KEY,min_deposit_usdt DECIMAL(18,6),fee_usdt DECIMAL(18,6),confirmations INT,operation_id VARCHAR(64),reason VARCHAR(300)) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO nx_cregis_deposit_policy VALUES(0,1,0,15,NULL,'fixture')");
        jdbc.execute("CREATE TABLE nx_cregis_deposit_policy_snapshot(project_id BIGINT,chain_id VARCHAR(16),token_id VARCHAR(64),txid VARCHAR(66),policy_version BIGINT,required_confirmations INT,PRIMARY KEY(project_id,chain_id,token_id,txid)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_cregis_deposit_address(user_id BIGINT,project_id BIGINT,chain_id VARCHAR(16),address VARCHAR(64),state VARCHAR(24),allocation_block BIGINT,allocation_hash VARCHAR(66)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO nx_cregis_deposit_address VALUES(42,88,?,?, 'READY',100,?)",CregisConstants.BSC_CHAIN_ID,ADDRESS,BLOCK);
        jdbc.execute("CREATE TABLE nx_cregis_deposit_delivery(id BIGINT AUTO_INCREMENT PRIMARY KEY,payload_sha256 CHAR(64),raw_json TEXT,cid BIGINT,txid VARCHAR(66),address VARCHAR(64),gross_amount DECIMAL(18,6),accepted INT,reason VARCHAR(64),source_ip VARCHAR(45),signature_valid INT,timestamp_valid INT,ip_valid INT,last_error VARCHAR(64),processed_at DATETIME) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_cregis_deposit_event(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,project_id BIGINT,cid BIGINT,txid VARCHAR(66),log_index INT,address VARCHAR(64),gross_amount DECIMAL(18,6),net_amount DECIMAL(18,6),block_number BIGINT,block_hash VARCHAR(66),confirmations INT,status VARCHAR(32),ledger_id BIGINT,UNIQUE KEY(project_id,cid),UNIQUE KEY(project_id,txid,log_index)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO nx_cregis_deposit_event VALUES(12,42,88,77,?,0,?,1,1,101,?,15,'CREDITED',11)",TXID,ADDRESS,BLOCK);
        jdbc.execute("CREATE TABLE nx_user_wallet(user_id BIGINT PRIMARY KEY,usdt_available DECIMAL(18,6),cregis_risk_held DECIMAL(18,6),version BIGINT,is_deleted INT) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO nx_user_wallet VALUES(42,1,0,1,0)");
        jdbc.execute("CREATE TABLE nx_wallet_ledger(id BIGINT PRIMARY KEY,biz_no VARCHAR(64),amount DECIMAL(18,6),direction VARCHAR(8)) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO nx_wallet_ledger VALUES(11,'CR-77',1,'IN')");
        jdbc.execute("CREATE TABLE nx_deposit_order(id BIGINT PRIMARY KEY,deposit_no VARCHAR(64),ledger_id BIGINT,amount DECIMAL(18,6)) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO nx_deposit_order VALUES(1,'CR-77',11,1)");
        jdbc.execute("CREATE TABLE nx_treasury_reserve_ledger(id BIGINT PRIMARY KEY,reserve_no VARCHAR(64),amount_usd DECIMAL(18,6)) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO nx_treasury_reserve_ledger VALUES(1,'CR-77',1)");
        jdbc.execute("CREATE TABLE nx_cregis_chain_observation(project_id BIGINT,txid VARCHAR(66),log_index INT,address VARCHAR(64),raw_amount VARCHAR(80),block_number BIGINT,status VARCHAR(32)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_withdrawal_order(asset VARCHAR(16),is_deleted INT,status VARCHAR(32),amount DECIMAL(18,6)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_cregis_risk_alert(project_id BIGINT,alert_key VARCHAR(128),severity VARCHAR(8),kind VARCHAR(48),evidence TEXT,resolved_at DATETIME) ENGINE=InnoDB");
        var conf=new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("fixture",new SpringManagedTransactionFactory(),ds));
        conf.addMapper(CregisDepositMapper.class);conf.addMapper(CregisDepositPolicyMapper.class);
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(conf));
        manager=new DataSourceTransactionManager(ds); mapper=session.getMapper(CregisDepositMapper.class);
        config=new CregisProperties();config.setMode(CregisProperties.Mode.PROVIDER);config.setProjectId(88);config.setApiKey(KEY);config.setDepositCreditEnabled(true);config.setCallbackSourceIps("127.0.0.1");
        var proxy=new ProxyFactory(new CregisDepositPolicyService(session.getMapper(CregisDepositPolicyMapper.class),null,config));proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));policy=(CregisDepositPolicyService)proxy.getProxy();
        gateway=mock(CregisGateway.class);router=mock(CregisGatewayRouter.class);when(router.provider()).thenReturn(gateway);chain=mock(BscDepositProof.class);
        when(gateway.depositsByTxid(TXID)).thenReturn(List.of(new CregisGateway.DepositTrade(77,CregisConstants.BSC_CHAIN_ID,CregisConstants.USDT_BEP20_TOKEN_ID,ADDRESS,BigDecimal.ONE,TXID,1)));
        when(chain.verify(TXID,ADDRESS,BigDecimal.ONE,101)).thenReturn(Optional.of(new BscDepositProof.Proof(0,101,BLOCK,15)));when(chain.blockHash(100)).thenReturn(BLOCK);
        var json=new ObjectMapper();
        service=new CregisDepositService(config,router,chain,new CregisSigner(),json,session.getMapper(CregisDepositMapper.class),manager,null,null,new SupportPaymentAttributionService(null,null,null,ds,json),null,null,null);
        service.setPolicies(policy);
    }
    @AfterEach void cleanup() {if(service!=null)service.closePoolWorkers();if(base!=null && schema!=null)base.execute("DROP DATABASE "+schema);}

    @Test void repeatedAcceptedCallbackKeepsFirstDiscoveryPolicyAndOriginalFinancialFacts() throws Exception {
        String raw=callback("1","1");
        assertThat(service.receive(raw)).isEqualTo("success");
        assertThat(policy.snapshot(88,TXID).version()).isZero();
        jdbc.execute("INSERT INTO nx_cregis_deposit_policy VALUES(1,10,1,20,'fixture-a2','fixture publication')");
        assertThat(service.receive(raw)).isEqualTo("success");
        assertThat(policy.snapshot(88,TXID).version()).isZero();
        service.reconcile();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_cregis_deposit_delivery WHERE reason='CREDITED' AND processed_at IS NOT NULL",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_cregis_deposit_event WHERE status='CREDITED' AND ledger_id=11",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=42",BigDecimal.class)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT version FROM nx_user_wallet WHERE user_id=42",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no='CR-77' AND amount=1",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_deposit_order WHERE deposit_no='CR-77' AND ledger_id=11 AND amount=1",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_treasury_reserve_ledger WHERE reserve_no='CR-77' AND amount_usd=1",Integer.class)).isEqualTo(1);
    }
    @Test void oldTenOneCreditStaysNineAfterPolicyChangesToOneZero() throws Exception {
        jdbc.execute("UPDATE nx_cregis_deposit_policy SET min_deposit_usdt=10,fee_usdt=1 WHERE version=0");
        jdbc.execute("UPDATE nx_cregis_deposit_event SET gross_amount=10,net_amount=9 WHERE id=12");
        jdbc.execute("UPDATE nx_user_wallet SET usdt_available=9 WHERE user_id=42");
        jdbc.execute("UPDATE nx_wallet_ledger SET amount=9 WHERE id=11");
        jdbc.execute("UPDATE nx_deposit_order SET amount=9 WHERE id=1");
        jdbc.execute("UPDATE nx_treasury_reserve_ledger SET amount_usd=9 WHERE id=1");
        when(gateway.depositsByTxid(TXID)).thenReturn(List.of(new CregisGateway.DepositTrade(77,CregisConstants.BSC_CHAIN_ID,CregisConstants.USDT_BEP20_TOKEN_ID,ADDRESS,BigDecimal.TEN,TXID,1)));
        when(chain.verify(TXID,ADDRESS,BigDecimal.TEN.stripTrailingZeros(),101)).thenReturn(Optional.of(new BscDepositProof.Proof(0,101,BLOCK,15)));
        String raw=callback("1","10");
        assertThat(service.receive(raw)).isEqualTo("success");
        jdbc.execute("INSERT INTO nx_cregis_deposit_policy VALUES(1,1,0,15,'fixture-a2','fixture publication')");
        assertThat(service.receive(raw)).isEqualTo("success");
        assertThat(policy.snapshot(88,TXID).feeUsdt()).isEqualByComparingTo("1");
        service.reconcile();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_cregis_deposit_delivery WHERE reason='CREDITED' AND processed_at IS NOT NULL",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_cregis_deposit_event WHERE status='CREDITED' AND gross_amount=10 AND net_amount=9 AND ledger_id=11",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=42",BigDecimal.class)).isEqualByComparingTo("9");
        assertThat(jdbc.queryForObject("SELECT version FROM nx_user_wallet WHERE user_id=42",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no='CR-77' AND amount=9",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_deposit_order WHERE deposit_no='CR-77' AND ledger_id=11 AND amount=9",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_treasury_reserve_ledger WHERE reserve_no='CR-77' AND amount_usd=9",Integer.class)).isEqualTo(1);
    }
    @Test void firstOneZeroCreditPersistsEventWalletAndOrderWithMockAttributionAndTreasury() throws Exception {
        jdbc.execute("DELETE FROM nx_cregis_deposit_event");
        jdbc.execute("DELETE FROM nx_wallet_ledger");
        jdbc.execute("DELETE FROM nx_deposit_order");
        jdbc.execute("DELETE FROM nx_treasury_reserve_ledger");
        jdbc.execute("UPDATE nx_user_wallet SET usdt_available=0,version=0");
        jdbc.execute("ALTER TABLE nx_user_wallet ADD cumulative_deposit_usdt DECIMAL(18,6) NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE nx_cregis_deposit_event ADD origin_log_index INT, ADD fee_amount DECIMAL(18,6), ADD canonical_until BIGINT, ADD last_canonical_checked_block BIGINT, ADD last_canonical_checked_hash VARCHAR(66), ADD credited_at DATETIME");
        jdbc.execute("ALTER TABLE nx_wallet_ledger MODIFY id BIGINT NOT NULL AUTO_INCREMENT, ADD user_id BIGINT, ADD biz_type VARCHAR(32), ADD asset VARCHAR(16), ADD balance_after DECIMAL(18,6), ADD status VARCHAR(16), ADD remark TEXT");
        jdbc.execute("ALTER TABLE nx_deposit_order MODIFY id BIGINT NOT NULL AUTO_INCREMENT, ADD user_id BIGINT, ADD chain_name VARCHAR(32), ADD chain_tx_hash VARCHAR(66), ADD chain_log_index INT, ADD asset VARCHAR(16), ADD confirmations INT, ADD status VARCHAR(16), ADD confirmed_at DATETIME, ADD credited_at DATETIME");
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(16),is_deleted INT) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO nx_user VALUES(42,'ACTIVE',0)");
        var attribution=mock(SupportPaymentAttributionFacade.class);
        var prepared=mock(SupportPaymentAttributionFacade.Prepared.class);
        when(attribution.prepare(anyLong(),any(),anyString(),anyString())).thenReturn(prepared);
        when(attribution.insertLedger(eq(prepared),any(),any(),anyString())).thenAnswer(inv -> mapper.insertLedger("CR-77",42,inv.getArgument(1),inv.getArgument(2),inv.getArgument(3)));
        var treasury=mock(TreasuryLedgerRepository.class);
        var exceptions=mock(CregisExceptionSourceService.class);
        service.closePoolWorkers();
        service=new CregisDepositService(config,router,chain,new CregisSigner(),new ObjectMapper(),mapper,manager,treasury,null,attribution,null,exceptions,null);
        service.setPolicies(policy);
        assertThat(service.receive(callback("1","1"))).isEqualTo("success");
        service.reconcile();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_cregis_deposit_event WHERE status='CREDITED' AND gross_amount=1 AND fee_amount=0 AND net_amount=1 AND ledger_id IS NOT NULL",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=42",BigDecimal.class)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no='CR-77' AND amount=1",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_deposit_order WHERE deposit_no='CR-77' AND amount=1 AND ledger_id IS NOT NULL",Integer.class)).isEqualTo(1);
        verify(treasury).recordTopupReserve(eq("CR-77"),argThat(value -> value.compareTo(BigDecimal.ONE)==0),eq("CREGIS:77"));
        verify(attribution).record(prepared);
    }
    String callback(String status,String amount) throws Exception {
        Map<String,Object> value=new LinkedHashMap<>();value.put("pid",88);value.put("cid",77);value.put("chain_id",CregisConstants.BSC_CHAIN_ID);value.put("token_id",CregisConstants.USDT_BEP20_TOKEN_ID);value.put("currency",CregisConstants.USDT_BEP20_CURRENCY);value.put("address",ADDRESS);value.put("amount",amount);value.put("status",status);value.put("txid",TXID);value.put("block_height","101");value.put("nonce","ABC123");value.put("timestamp",Instant.now().toEpochMilli());value.put("sign",new CregisSigner().sign(KEY,value));return new ObjectMapper().writeValueAsString(value);
    }
}
