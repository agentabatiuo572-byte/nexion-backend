package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.finance.mapper.D1BankOrderMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class D1BankOrderReadMySqlTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_HOSTED_RAIL_IT", matches = "true")
    void pageReadsStayBoundedWithUnrelatedWalletHistoryAndPreserveFiltersAndSettlementFacts() throws Exception {
        var fixture = new HdPayCallbackSettlementSpringTransactionMySqlIntegrationTest();
        String schema = "nexion_hosted_callback_it_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(fixture.dataSource(""));
        admin.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource dataSource = (DriverManagerDataSource) fixture.dataSource(schema);
            dataSource.setUrl(dataSource.getUrl() + "&rewriteBatchedStatements=true");
            fixture.createFixtureSchema(dataSource);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.batchUpdate("""
                    INSERT INTO nx_vietqr_intent(intent_no,user_id,create_idempotency_key,create_request_hash,
                      requested_usdt,payable_vnd,locked_fx_rate_vnd_per_usdt,fx_quote_version,memo_code,
                      payment_rail,status,expires_at,created_at)
                    VALUES (?,41,?,REPEAT('a',64),10,200000,20000,1,?,'HDPAY','AWAITING_PAYMENT',
                      DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY),'2026-10-01 00:00:00')
                    """, IntStream.range(0, 80).mapToObj(i -> new Object[]{order(i), "create-" + i, "MEMO" + i}).toList());
            jdbc.update("""
                    INSERT INTO nx_hdpay_payin_order(merchant_order_id,amount_vnd,submission_status,
                      provider_order_id,provider_status,request_hash,settlement_status)
                    SELECT intent_no,200000,'CREATED',CONCAT('P',id),1,REPEAT('a',64),'UNSETTLED'
                    FROM nx_vietqr_intent
                    """);
            jdbc.batchUpdate("""
                    INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status)
                    VALUES (42,?,'EARNING','USDT','IN',1,1,'SUCCESS')
                    """, IntStream.range(0, 20_000).mapToObj(i -> new Object[]{"UNRELATED-" + i}).toList());
            var mapper = new SqlSessionTemplate(fixture.sessionFactory(dataSource)).getMapper(D1BankOrderMapper.class);
            LocalDateTime asOf = LocalDateTime.now(ZoneOffset.UTC);
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(tx -> {
                long before = rowsRead(jdbc);
                assertThat(mapper.countOrders(null, null, null, asOf)).isEqualTo(80);
                List<Map<String, Object>> first = mapper.listOrders(null, null, null, asOf, 20, 0);
                assertThat(first).extracting(r -> r.get("intentNo"))
                        .containsExactlyElementsOf(IntStream.iterate(79, i -> i - 1).limit(20).mapToObj(D1BankOrderReadMySqlTest::order).toList());
                assertThat(first).allSatisfy(r -> assertThat(((Number) r.get("settlementFactCount")).longValue()).isZero());
                assertThat(rowsRead(jdbc) - before)
                        .as("a 20-row page must not scan the 20000 unrelated wallet rows for every order")
                        .isLessThan(2_000);
            });
            assertThat(mapper.listOrders("HDPAY", "AWAITING_PAYMENT", null, asOf, 20, 20))
                    .extracting(r -> r.get("intentNo"))
                    .containsExactlyElementsOf(IntStream.iterate(59, i -> i - 1).limit(20).mapToObj(D1BankOrderReadMySqlTest::order).toList());
            assertThat(mapper.countOrders("MANUAL", null, null, asOf)).isZero();
            assertThat(mapper.countOrders(null, "EXPIRED", null, asOf)).isZero();
            assertThat(mapper.countOrders(null, null, "MEMO79", asOf)).isOne();
            assertThat(mapper.countOrders(null, null, "41", asOf)).isEqualTo(80);
            // Both direct and receipt-linked ledger rows remain financial conflicts, including deleted evidence.
            jdbc.update("""
                    INSERT INTO nx_vietqr_reconciliation(reconciliation_no,intent_no,user_id,view_type,status,
                      payable_vnd,received_vnd,locked_fx_rate_vnd_per_usdt,credited_usdt,is_deleted)
                    VALUES ('PERF-RECEIPT',?,41,'MATCHED','OPEN',200000,200000,20000,0,1)
                    """, order(79));
            for (String bizNo : List.of(order(79), "D1-VIETQR-PERF-RECEIPT")) {
                jdbc.update("""
                        INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,is_deleted)
                        VALUES (41,?,'VIETQR_DEPOSIT','USDT','IN',10,110,'SUCCESS',1)
                        """, bizNo);
            }
            assertThat(mapper.countSettlementFacts(order(79))).isEqualTo(2);
            assertThat(((Number) mapper.listOrders(null, null, order(79), asOf, 20, 0)
                    .get(0).get("settlementFactCount")).longValue()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_vietqr_intent", Long.class)).isEqualTo(80);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger", Long.class)).isEqualTo(20_002);
        } finally {
            admin.execute("DROP DATABASE " + schema);
        }
    }

    private static String order(int index) { return "VQR-PERF-" + String.format("%05d", index); }

    private static long rowsRead(JdbcTemplate jdbc) {
        return jdbc.queryForList("SHOW SESSION STATUS WHERE Variable_name IN ('Handler_read_next','Handler_read_rnd_next')")
                .stream().mapToLong(row -> Long.parseLong(String.valueOf(row.get("Value")))).sum();
    }
}
