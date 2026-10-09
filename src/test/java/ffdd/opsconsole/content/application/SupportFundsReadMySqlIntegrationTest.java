package ffdd.opsconsole.content.application;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.application.FinanceSupportReadService;
import ffdd.opsconsole.finance.application.SupportFundsReadService;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.*;
import ffdd.opsconsole.finance.mapper.DepositOrderMapper;
import ffdd.opsconsole.finance.mapper.SupportFundsReadMapper;
import ffdd.opsconsole.finance.mapper.WithdrawalOrderMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Opt-in fixture-only reads against the already provisioned exclusive analytics MySQL instance. */
@EnabledIfEnvironmentVariable(named="SUPPORT_CAPTURE_MYSQL_ENABLED",matches="true")
class SupportFundsReadMySqlIntegrationTest {
    private static final String SERVER_UUID="3556ddae-c1a1-11f1-8853-a40c6626953d";
    private static final String MARKER="support-funds-fixture";
    private static final List<String> TABLES=List.of("nx_user","nx_user_wallet","nx_withdrawal_order","nx_wallet_ledger",
        "nx_payment_record","nx_order","nx_wallet_bill","nx_deposit_order","nx_cregis_deposit_event",
        "nx_topup_card_settlement","nx_vietqr_intent","nx_vietqr_reconciliation","nx_hdpay_payin_order",
        "nx_user_device","nx_trial_claim","nx_support_payment_attribution","nx_support_payment_history_birth",
        "nx_user_device_runtime","nx_admin","nx_admin_role_relation","nx_support_agent_profile",
        "nx_support_agent_user_assignment","nx_support_group","nx_support_group_owner_history",
        "nx_support_group_member_history","nx_support_account_qualification_history",
        "nx_support_customer_route_history","nx_admin_role");
    private final List<Customer> customers=new ArrayList<>();
    private final List<Withdrawal> withdrawals=new ArrayList<>();
    private final ReadProbe probe=new ReadProbe();
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc,outside;
    private TransactionTemplate transaction,reader,outsideWriter;
    private SupportFundsReadService funds;
    private FinanceSupportReadService legacy;

    @BeforeEach void existingExclusiveResourceAndRealMapperTransactionSetup() throws Exception {
        var target=SupportRuntimeTarget.select(Map.of("SUPPORT_RUNTIME_TARGET","analytics-20261007"));
        String url=required("NEXION_DB_URL"),username=required("NEXION_DB_USERNAME"),password=required("NEXION_DB_PASSWORD");
        assertThat(url).startsWith(target.jdbcPrefix());assertThat(username).isEqualTo(target.username());
        dataSource=new DriverManagerDataSource(url,username,password);jdbc=new JdbcTemplate(dataSource);
        var outsideDataSource=new DriverManagerDataSource(url,username,password);outside=new JdbcTemplate(outsideDataSource);
        var json=new ObjectMapper();Path proofPath=Path.of(required("SUPPORT_CAPTURE_OWNERSHIP")).toAbsolutePath().normalize();
        byte[] proofBytes=Files.readAllBytes(proofPath);JsonNode proof=json.readTree(proofBytes);
        assertThat(proof.path("databaseIdentity").path("serverUuid").asText()).isEqualTo(SERVER_UUID);
        var context=json.createObjectNode();context.put("schemaVersion",2).put("ownershipMode","EXCLUSIVE_ANALYTICS");
        context.set("resourceIdentity",proof.path("resourceIdentity").deepCopy());
        context.putObject("resourceOwnership").put("path",proofPath.toString())
            .put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(proofBytes)));
        SupportExclusiveRuntimeOwnership.requireActual(context,target,jdbc);assertThat(jdbc.queryForObject("SELECT @@server_uuid",String.class)).isEqualTo(SERVER_UUID);
        // No DDL/migration/bootstrap. Missing installed columns/tables is a real failing prerequisite.
        var configuration=new Configuration(new Environment("support-funds-actual-mysql",new SpringManagedTransactionFactory(),dataSource));
        configuration.setMapUnderscoreToCamelCase(true);configuration.addInterceptor(probe);
        configuration.addMapper(SupportFundsReadMapper.class);configuration.addMapper(DepositOrderMapper.class);configuration.addMapper(WithdrawalOrderMapper.class);
        var factory=new MybatisSqlSessionFactoryBuilder().build(configuration);var template=new SqlSessionTemplate(factory);
        assertThat(factory.getConfiguration().getEnvironment().getDataSource()).isSameAs(dataSource);
        probe.dataSource=dataSource;probe.ownedCustomers=customers;
        var manager=new DataSourceTransactionManager(dataSource);transaction=rr(manager,false);reader=rr(manager,true);
        outsideWriter=rr(new DataSourceTransactionManager(outsideDataSource),false);
        outsideWriter.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        funds=new SupportFundsReadService(template.getMapper(SupportFundsReadMapper.class));
        legacy=new FinanceSupportReadService(template.getMapper(DepositOrderMapper.class),template.getMapper(WithdrawalOrderMapper.class));
    }

    @Test void realBatchMappingMissingWalletDoubleCurrencyD2AndLegacyContractWithinRollback() {
        var before=outsideCounts();
        try {
            transaction.executeWithoutResult(tx->{
                try {
                    long zero=customer(true,"0.000000","999999999999.123456"),missing=customer(false,null,null);
                    long excluded=customer(true,"999.000001","888.000002"),compatible=customer(true,"20.123456","30.654321");
                    var expected=new LinkedHashMap<Long,WithdrawalState>();
                    for(String status:List.of("SUBMITTED","REVIEW_PENDING","EXTENDED_HOLD","REVIEW_PASSED","PROCESSING","SENT"))
                        expected.put(withdraw(zero,"USDT",status,"10.123456","0.123456","10.000000",false),WithdrawalState.PROCESSING);
                    expected.put(withdraw(zero,"USDT","CONFIRMED","10.123456","0.123456","10.000000",true),WithdrawalState.SUCCESS);
                    expected.put(withdraw(zero,"NEX","SUCCESS","999999999999.123456","0.000001","999999999999.123455",true),WithdrawalState.SUCCESS);
                    for(String status:List.of("REVIEW_REJECTED","ADDRESS_INVALID","TX_FAILED","REFUNDED"))
                        expected.put(withdraw(zero,"USDT",status,"10","1","9",false),WithdrawalState.NOT_SUCCESSFUL);
                    expected.put(withdraw(zero,"USDT","FROZEN","10","1","9",false),WithdrawalState.HELD);
                    expected.put(withdraw(zero,"USDT","TX_ORPHANED","10","1","9",false),WithdrawalState.RECOVERY);
                    expected.put(withdraw(zero,"USDT","UNKNOWN_FUTURE","10","1","9",false),WithdrawalState.UNKNOWN);
                    expected.put(withdraw(zero,"USDT","CONFIRMED","10","1","8",true),WithdrawalState.UNKNOWN);
                    expected.put(withdraw(zero,"USDT","SUCCESS","10","1","9",false),WithdrawalState.UNKNOWN);
                    long excludedWithdrawal=withdraw(excluded,"USDT","CONFIRMED","999","1","998",true);
                    withdraw(compatible,"USDT","CONFIRMED","10.123456","0.123456","10.000000",true);
                    withdraw(compatible,"USDT","SUBMITTED","3.654321","0.654321","3.000000",false);

                    probe.reads.clear();assertThat(funds.readCurrent(List.of()).wallets().rows()).isEmpty();assertThat(probe.reads).isEmpty();
                    var result=funds.readCurrent(List.of(missing,zero,zero));assertThat(result.customerIds()).containsExactlyElementsOf(sorted(zero,missing));
                    assertTwoBatchReads(sorted(zero,missing));assertThat(result.wallets().state()).isEqualTo(ReadState.PARTIAL);
                    assertThat(wallet(result,zero).state()).isEqualTo(EvidenceState.READY);
                    assertThat(wallet(result,zero).usdtAvailable()).isEqualTo(new BigDecimal("0.000000"));
                    assertThat(wallet(result,zero).nexAvailable()).isEqualTo(new BigDecimal("999999999999.123456"));
                    assertThat(wallet(result,zero).version()).isEqualTo(7L);assertThat(wallet(result,zero).walletId()).isPositive();
                    assertThat(wallet(result,missing).state()).isEqualTo(EvidenceState.UNKNOWN);assertThat(wallet(result,missing).usdtAvailable()).isNull();
                    assertThat(wallet(result,missing).nexAvailable()).isNull();assertThat(wallet(result,missing).reasons()).containsExactly(Reason.WALLET_MISSING);
                    assertThat(result.withdrawals().rows()).hasSize(expected.size()).noneMatch(w->w.customerId()!=zero || w.withdrawalId()==excludedWithdrawal);
                    for(var row:result.withdrawals().rows())assertThat(row.state()).as("D2 raw row %s",row.withdrawalId()).isEqualTo(expected.get(row.withdrawalId()));
                    assertThat(result.withdrawals().state()).isEqualTo(ReadState.PARTIAL);assertUnknownCoverage(result);
                    assertThat(result.toString()).doesNotContain("fixture-required-address","fixture-required-chain");
                    assertThat(result.withdrawals().rows()).filteredOn(w->w.state()==WithdrawalState.SUCCESS).allSatisfy(w->{
                        assertThat(w.completedAt()).isNotNull();assertThat(w.updatedAt()).isNotNull();
                        assertThat(w.principal()).isEqualByComparingTo(w.actualFee().add(w.net()));
                    });
                    probe.reads.clear();var emptyRows=funds.readCurrent(List.of(missing));assertTwoBatchReads(List.of(missing));
                    assertThat(emptyRows.wallets().state()).isEqualTo(ReadState.UNKNOWN);assertThat(emptyRows.withdrawals().rows()).isEmpty();assertUnknownCoverage(emptyRows);

                    // Existing single-customer service uses real production mappers; no credit sources are seeded.
                    var old=legacy.totals(compatible);assertThat(old.get("balanceSourceStatus")).isEqualTo("READY");
                    Map<String,Object> usdt=legacyCurrency(old,"USDT"),nex=legacyCurrency(old,"NEX");
                    assertThat(usdt).containsEntry("availableBalance","20.123456").containsEntry("creditedDepositTotal","0")
                        .containsEntry("successfulWithdrawalPrincipalTotal","10.123456").containsEntry("successfulWithdrawalFeeTotal","0.123456")
                        .containsEntry("successfulWithdrawalNetTotal","10").containsEntry("processingWithdrawalPrincipalTotal","3.654321");
                    assertThat(nex).containsEntry("availableBalance","30.654321").containsEntry("creditedDepositTotal","0");
                } finally {tx.setRollbackOnly();}
            });
        } finally {assertOutsideClean(before);}
    }

    @Test void externalCommitBetweenSourcesStaysInvisibleForContinuousRrButFreshRrSeesItAndCleanupRestoresCounts() {
        var before=outsideCounts();
        try {
            var fixture=transaction.execute(tx->{
                long customer=customer(true,"10.123456","20.654321");
                long withdrawal=withdraw(customer,"USDT","SUBMITTED","3.123456","0.123456","3.000000",false);
                return new long[]{customer,withdrawal};
            });
            assertThat(fixture).isNotNull();long customer=fixture[0],withdrawal=fixture[1];
            assertThat(outside.queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",BigDecimal.class,customer)).isEqualByComparingTo("10.123456");
            probe.afterWallet=()->outsideWriter.executeWithoutResult(tx->{
                assertThat(outside.queryForObject("SELECT CONNECTION_ID()",Long.class)).isNotEqualTo(probe.reads.get(0).connectionId());
                assertThat(outside.update("UPDATE nx_user_wallet SET usdt_available=11.000001,nex_available=21.000002,version=version+1,updated_at=NOW() WHERE user_id=? AND version=7",customer)).isEqualTo(1);
                assertThat(outside.update("UPDATE nx_withdrawal_order SET status='CONFIRMED',amount=4.123456,fee=0.123456,d2_gross_fee=0.123456,d2_actual_fee=0.123456,d2_net_receive=4.000000,completed_at=NOW(),updated_at=NOW() WHERE id=? AND user_id=? AND status='SUBMITTED'",withdrawal,customer)).isEqualTo(1);
                withdrawUsing(outside,customer,"NEX","SUCCESS","6.000003","0.000003","6.000000",true);
            });
            probe.reads.clear();
            reader.executeWithoutResult(tx->{
                var first=funds.readCurrent(List.of(customer));assertOldView(first,customer,withdrawal);
                var second=funds.readCurrent(List.of(customer));assertOldView(second,customer,withdrawal);
                assertThat(probe.reads).hasSize(4);assertThat(probe.reads).extracting(ReadObservation::connectionId).containsOnly(probe.reads.get(0).connectionId());
                assertThat(probe.reads).extracting(ReadObservation::customerIds).containsOnly(List.of(customer));
            });
            assertThat(probe.afterWallet).isNull();assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(outside.queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",BigDecimal.class,customer)).isEqualByComparingTo("11.000001");
            assertThat(outside.queryForObject("SELECT status FROM nx_withdrawal_order WHERE id=? AND user_id=?",String.class,withdrawal,customer)).isEqualTo("CONFIRMED");
            probe.reads.clear();reader.executeWithoutResult(tx->{
                var fresh=funds.readCurrent(List.of(customer));assertTwoBatchReads(List.of(customer));
                assertThat(wallet(fresh,customer).usdtAvailable()).isEqualByComparingTo("11.000001");assertThat(wallet(fresh,customer).nexAvailable()).isEqualByComparingTo("21.000002");
                assertThat(wallet(fresh,customer).version()).isEqualTo(8L);assertThat(fresh.withdrawals().rows()).hasSize(2).allSatisfy(w->assertThat(w.state()).isEqualTo(WithdrawalState.SUCCESS));
                assertThat(fresh.withdrawals().rows()).filteredOn(w->w.withdrawalId()==withdrawal).singleElement().satisfies(w->assertThat(w.principal()).isEqualByComparingTo("4.123456"));
                assertUnknownCoverage(fresh);
            });
        } finally {probe.afterWallet=null;cleanupCommitted();assertOutsideClean(before);}
    }

    private void assertOldView(Snapshot result,long customer,long withdrawal) {
        assertThat(wallet(result,customer).usdtAvailable()).isEqualByComparingTo("10.123456");assertThat(wallet(result,customer).nexAvailable()).isEqualByComparingTo("20.654321");
        assertThat(wallet(result,customer).version()).isEqualTo(7L);assertThat(result.withdrawals().rows()).singleElement().satisfies(w->{
            assertThat(w.withdrawalId()).isEqualTo(withdrawal);assertThat(w.state()).isEqualTo(WithdrawalState.PROCESSING);assertThat(w.principal()).isEqualByComparingTo("3.123456");assertThat(w.completedAt()).isNull();
        });assertUnknownCoverage(result);
    }
    private static WalletEvidence wallet(Snapshot result,long customer) {return result.wallets().rows().stream().filter(w->w.customerId()==customer).findFirst().orElseThrow();}
    private static void assertUnknownCoverage(Snapshot result) {
        assertThat(result.withdrawals().historicalEnvironmentStatus()).isEqualTo(CoverageState.UNKNOWN);assertThat(result.withdrawals().eventOwnershipStatus()).isEqualTo(CoverageState.UNKNOWN);
    }
    private void assertTwoBatchReads(List<Long> ids) {
        assertThat(probe.reads).hasSize(2);assertThat(probe.reads).extracting(ReadObservation::statement).containsExactly("wallets","withdrawals");
        assertThat(probe.reads).extracting(ReadObservation::customerIds).containsOnly(ids);
        assertThat(probe.reads.get(0).connectionId()).isEqualTo(probe.reads.get(1).connectionId());
    }
    private long customer(boolean withWallet,String usdt,String nex) {
        String token=unique();assertThat(jdbc.update("INSERT INTO nx_user(country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox) VALUES('0',?,'127.0.0.1','fixture-only',?,?,'ACTIVE',0)",token,MARKER,token)).isEqualTo(1);
        long id=jdbc.queryForObject("SELECT id FROM nx_user WHERE country_code='0' AND phone=? AND nickname=?",Long.class,token,MARKER);customers.add(new Customer(id,token));
        if(withWallet)assertThat(jdbc.update("INSERT INTO nx_user_wallet(user_id,usdt_available,nex_available,version) VALUES(?,?,?,7)",id,new BigDecimal(usdt),new BigDecimal(nex))).isEqualTo(1);
        return id;
    }
    private long withdraw(long customer,String currency,String status,String amount,String fee,String net,boolean completed) {
        return withdrawUsing(jdbc,customer,currency,status,amount,fee,net,completed);
    }
    private long withdrawUsing(JdbcTemplate writer,long customer,String currency,String status,String amount,String fee,String net,boolean completed) {
        assertThat(customers.stream().anyMatch(c->c.id()==customer)).isTrue();String token=unique();
        LocalDateTime at=writer.queryForObject("SELECT NOW()",LocalDateTime.class);
        assertThat(writer.update("INSERT INTO nx_withdrawal_order(user_id,withdrawal_no,asset,chain,amount,fee,target_address,status,completed_at,updated_at,d2_penalty_fee_rate,d2_gross_fee,d2_nex_burned,d2_nex_fee_offset_rate,d2_fee_waived,d2_actual_fee,d2_net_receive) VALUES(?,?,?,'fixture-required-chain',?,?,'fixture-required-address',?,?,?,0,?,0,0.4,0,?,?)",
            customer,token,currency,new BigDecimal(amount),new BigDecimal(fee),status,completed?at:null,at,new BigDecimal(fee),new BigDecimal(fee),new BigDecimal(net))).isEqualTo(1);
        long id=writer.queryForObject("SELECT id FROM nx_withdrawal_order WHERE withdrawal_no=? AND user_id=?",Long.class,token,customer);withdrawals.add(new Withdrawal(id,customer,token));return id;
    }
    private void cleanupCommitted() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        outsideWriter.executeWithoutResult(tx->{
            for(var withdrawal:withdrawals) {
                int removed=outside.update("DELETE FROM nx_withdrawal_order WHERE id=? AND user_id=? AND withdrawal_no=?",withdrawal.id(),withdrawal.customer(),withdrawal.token());assertThat(removed).isBetween(0,1);
            }
            for(var customer:customers) {
                var existing=outside.queryForList("SELECT nickname,phone FROM nx_user WHERE id=?",customer.id());
                if(existing.isEmpty())continue;assertThat(existing).singleElement().satisfies(row->{assertThat(row.get("nickname")).isEqualTo(MARKER);assertThat(row.get("phone")).isEqualTo(customer.token());});
                assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_withdrawal_order WHERE user_id=?",Long.class,customer.id())).isZero();
                assertThat(outside.update("DELETE FROM nx_user_wallet WHERE user_id=?",customer.id())).isBetween(0,1);
                assertThat(outside.update("DELETE FROM nx_user WHERE id=? AND nickname=? AND phone=?",customer.id(),MARKER,customer.token())).isEqualTo(1);
            }
        });
    }
    private Map<String,Long> outsideCounts() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return outside.execute((ConnectionCallback<Map<String,Long>>) connection->{
            var result=new LinkedHashMap<String,Long>();try(var sql=connection.createStatement()) {
                for(String table:TABLES)try(var rows=sql.executeQuery("SELECT COUNT(*) FROM "+table)){assertThat(rows.next()).isTrue();result.put(table,rows.getLong(1));assertThat(rows.next()).isFalse();}
            }return result;
        });
    }
    private void assertOutsideClean(Map<String,Long> before) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        // One fresh, unbound connection verifies every owned ID and all business counts together.
        outside.execute((ConnectionCallback<Void>) connection->{
            for(var customer:customers)for(String table:List.of("nx_user","nx_user_wallet","nx_withdrawal_order")) {
                String column=table.equals("nx_user")?"id":"user_id";
                try(var sql=connection.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE "+column+"=?")){sql.setLong(1,customer.id());try(var rows=sql.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isZero();}}
            }
            for(var withdrawal:withdrawals)try(var sql=connection.prepareStatement("SELECT COUNT(*) FROM nx_withdrawal_order WHERE id=?")){sql.setLong(1,withdrawal.id());try(var rows=sql.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isZero();}}
            try(var sql=connection.createStatement()){for(var entry:before.entrySet())try(var rows=sql.executeQuery("SELECT COUNT(*) FROM "+entry.getKey())){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).as(entry.getKey()).isEqualTo(entry.getValue());}}
            return null;
        });
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> legacyCurrency(Map<String,Object> result,String currency) {
        return ((List<Map<String,Object>>)result.get("byCurrency")).stream().filter(row->currency.equals(row.get("currency"))).findFirst().orElseThrow();
    }
    private static List<Long> sorted(long first,long second){return java.util.stream.Stream.of(first,second).sorted().toList();}
    private static TransactionTemplate rr(DataSourceTransactionManager manager,boolean readOnly) {
        var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setReadOnly(readOnly);tx.setTimeout(30);return tx;
    }
    private static String required(String key){String value=System.getenv(key);if(value==null || value.isBlank())throw new IllegalStateException("Missing funds acceptance setting: "+key);return value;}
    private static String unique(){return "SF"+UUID.randomUUID().toString().replace("-","").substring(0,24);}
    private record Customer(long id,String token){}
    private record Withdrawal(long id,long customer,String token){}
    private record ReadObservation(String statement,List<Long> customerIds,long connectionId){}
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    private static final class ReadProbe implements Interceptor {
        private DriverManagerDataSource dataSource;private List<Customer> ownedCustomers;
        private final List<ReadObservation> reads=new ArrayList<>();private Runnable afterWallet;
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String id=((MappedStatement)invocation.getArgs()[0]).getId();String prefix=SupportFundsReadMapper.class.getName()+".";
            if(!id.startsWith(prefix))return invocation.proceed();
            var parameter=(Map<?,?>)invocation.getArgs()[1];var scope=(Collection<?>)parameter.get("customerIds");
            List<Long> ids=scope.stream().map(value->(Long)value).toList();assertThat(ownedCustomers.stream().map(Customer::id).toList()).containsAll(ids);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(dataSource);assertThat(holder).isNotNull();
            Connection connection=((Executor)invocation.getTarget()).getTransaction().getConnection();assertThat(connection).isSameAs(holder.getConnection());
            long connectionId;try(var sql=connection.createStatement();var rows=sql.executeQuery("SELECT CONNECTION_ID(),@@transaction_isolation")){
                assertThat(rows.next()).isTrue();connectionId=rows.getLong(1);assertThat(rows.getString(2)).isEqualTo("REPEATABLE-READ");
            }
            String statement=id.substring(prefix.length());reads.add(new ReadObservation(statement,List.copyOf(ids),connectionId));
            Object result=invocation.proceed();
            if(statement.equals("wallets") && afterWallet!=null){Runnable callback=afterWallet;afterWallet=null;callback.run();}
            return result;
        }
    }
}
