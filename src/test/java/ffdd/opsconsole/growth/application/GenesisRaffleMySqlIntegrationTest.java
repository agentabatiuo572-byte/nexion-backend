package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.infrastructure.H4EventSqlConfiguration;
import ffdd.opsconsole.growth.mapper.H4EventSqlMapper;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.growth.dto.GenesisRaffleRequest.Prize;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.treasury.facade.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** MySQL transaction/lock/receipt proof with a DB-backed award double, not production wallet E2E. */
@EnabledIfEnvironmentVariable(named="H4_EVENT_JDBC_URL",matches=".+")
class GenesisRaffleMySqlIntegrationTest {
    JdbcTemplate jdbc;TransactionTemplate tx;GenesisRaffleService service;
    EarningsReleaseService earnings;TreasuryLedgerPostingFacade ledger;
    PausingRepository repository;
    ObjectMapper json=new ObjectMapper();
    @BeforeEach void prepare() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("H4_EVENT_JDBC_URL"),System.getenv("H4_EVENT_DB_USER"),System.getenv("H4_EVENT_DB_PASSWORD"));
        jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).startsWith("h4_events_");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_user(id BIGINT PRIMARY KEY,status VARCHAR(32),sandbox TINYINT(1),is_deleted INT,country_code VARCHAR(8),region VARCHAR(32),created_at DATETIME,language VARCHAR(16),v_rank VARCHAR(16))");
        jdbc.execute("ALTER TABLE nx_user MODIFY COLUMN sandbox TINYINT(1)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_staking_position(position_no VARCHAR(96) PRIMARY KEY,user_id BIGINT,status VARCHAR(32),is_deleted INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_g7_repurchase_ticket(ticket_no VARCHAR(96) PRIMARY KEY,position_no VARCHAR(96),user_id BIGINT,quantity INT,status VARCHAR(32),issued_at DATETIME(6),is_deleted INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_order(order_no VARCHAR(96) PRIMARY KEY,user_id BIGINT,amount_usdt DECIMAL(18,6),payment_status VARCHAR(24),order_status VARCHAR(24),paid_at DATETIME(6),is_deleted INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nx_promotion_refund_hold(order_no VARCHAR(96),status VARCHAR(24))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS raffle_test_credit(receipt VARCHAR(64) PRIMARY KEY,user_id BIGINT,amount DECIMAL(18,6))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS raffle_test_ledger(receipt VARCHAR(64) PRIMARY KEY,user_id BIGINT,amount DECIMAL(18,6))");
        try(var connection=ds.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource("scripts/migrations/20261025_genesis_raffle.sql"));}
        for(String table:List.of("nx_genesis_raffle_result","nx_genesis_raffle_entry","nx_genesis_raffle_ticket","nx_genesis_raffle_draw","raffle_test_credit","raffle_test_ledger","nx_g7_repurchase_ticket","nx_staking_position","nx_promotion_refund_hold","nx_order","nx_user"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO nx_user(id,status,sandbox,is_deleted) VALUES(100,'ACTIVE',0,0)");
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        earnings=mock(EarningsReleaseService.class);ledger=mock(TreasuryLedgerPostingFacade.class);
        var coverage=mock(TreasuryCoverageFacade.class);when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("120"),new BigDecimal("100")));
        when(earnings.creditReward(anyLong(),anyString(),anyString(),anyString(),any(BigDecimal.class),anyString())).thenAnswer(call->{
            jdbc.update("INSERT INTO raffle_test_credit(receipt,user_id,amount) VALUES(?,?,?)",call.getArgument(5),call.getArgument(0),call.getArgument(4));return call.getArgument(5);
        });
        accountingSucceeds();
        var idempotency=mock(AdminIdempotencyService.class);
        // Exercise the real entry SQL in a real transaction; key replay belongs to the shared boundary tests.
        when(idempotency.executeRetained(anyString(),anyString(),anyString(),eq(ApiResult.class),any()))
                .thenAnswer(call->tx.execute(s->((Supplier<?>)call.getArgument(4)).get()));
        repository=new PausingRepository(new H4EventSqlConfiguration().h4EventRawMapper(ds));
        service=new GenesisRaffleService(repository,json,Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"),ZoneOffset.UTC),
                idempotency,earnings,ledger,coverage,mock(EventDeviceGiftService.class),mock(AuditLogService.class),mock(A2RuntimePolicy.class), org.mockito.Mockito.mock(ffdd.opsconsole.device.application.TestCloudActivationAdmission.class));
    }
    void accountingSucceeds(){
        doAnswer(call->{jdbc.update("INSERT INTO raffle_test_ledger(receipt,user_id,amount) VALUES(?,?,?)",call.getArgument(0),call.getArgument(1),call.getArgument(5));return null;})
                .when(ledger).postLedgerEntry(anyString(),anyLong(),anyString(),anyString(),anyString(),any(BigDecimal.class),anyString(),anyString());
    }
    void createDraw(String id,String drawAt) throws Exception {
        var prize=new Prize("one","奖励","Reward","Phần thưởng","USDT",new BigDecimal("5"),1,null);
        jdbc.update("INSERT INTO nx_genesis_raffle_draw(draw_id,title_zh,title_en,title_vi,starts_at,draw_at,status,prizes_json) VALUES(?,'创世','Genesis','Genesis','2026-10-24',?,'OPEN',?)",id,drawAt,json.writeValueAsString(List.of(prize)));
    }
    void ready(String position) throws Exception {
        createDraw("round","2026-10-25");
        tx.executeWithoutResult(s->service.grant(100L,"H4_REINVEST","test",1,position));
        jdbc.update("UPDATE nx_genesis_raffle_ticket SET used_quantity=1");
        jdbc.update("INSERT INTO nx_genesis_raffle_entry(draw_id,ticket_id,user_id,quantity) SELECT 'round',id,user_id,1 FROM nx_genesis_raffle_ticket");
    }
    void concurrently(Runnable action) throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<2;i++)futures.add(pool.submit(()->{start.await();action.run();return null;}));
            start.countDown();for(var future:futures)future.get(20,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
    }
    @Test void concurrentDrawAndPayCallsFixOneWinnerAndCommitOneAward() throws Exception {
        ready(null);
        concurrently(()->tx.executeWithoutResult(s->service.draw("round")));
        assertThat(jdbc.queryForObject("SELECT status FROM nx_genesis_raffle_draw",String.class)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT status FROM nx_genesis_raffle_result",String.class)).isEqualTo("PENDING");
        verifyNoInteractions(earnings,ledger);
        concurrently(()->tx.executeWithoutResult(s->{service.draw("round");service.pay("round","one",0);}));
        assertThat(jdbc.queryForObject("SELECT status FROM nx_genesis_raffle_draw",String.class)).isEqualTo("DRAWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_genesis_raffle_result",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_genesis_raffle_result",String.class)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM raffle_test_credit",BigDecimal.class)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM raffle_test_ledger",BigDecimal.class)).isEqualByComparingTo("5");
        verify(earnings,times(1)).creditReward(anyLong(),anyString(),anyString(),anyString(),any(BigDecimal.class),anyString());
    }
    @Test void failedPaymentPreservesTheOriginalWinnerAndCanRecoverWithoutRedraw() throws Exception {
        ready(null);
        jdbc.update("INSERT INTO nx_user(id,status,sandbox,is_deleted) VALUES(101,'ACTIVE',0,0)");
        tx.executeWithoutResult(s->service.grant(101L,"H4_PROMOTION","second",1));
        jdbc.update("UPDATE nx_genesis_raffle_ticket SET used_quantity=1 WHERE user_id=101");
        jdbc.update("INSERT INTO nx_genesis_raffle_entry(draw_id,ticket_id,user_id,quantity) SELECT 'round',id,user_id,1 FROM nx_genesis_raffle_ticket WHERE user_id=101");
        tx.executeWithoutResult(s->service.draw("round"));
        var original=jdbc.queryForMap("SELECT user_id,ticket_id,receipt_json FROM nx_genesis_raffle_result");
        doThrow(new IllegalStateException("ledger unavailable")).when(ledger).postLedgerEntry(anyString(),anyLong(),anyString(),anyString(),anyString(),any(BigDecimal.class),anyString(),anyString());
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.pay("round","one",0))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raffle_test_credit",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raffle_test_ledger",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM nx_genesis_raffle_result",String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM nx_genesis_raffle_draw",String.class)).isEqualTo("PROCESSING");
        concurrently(()->tx.executeWithoutResult(s->service.draw("round")));
        assertThat(jdbc.queryForMap("SELECT user_id,ticket_id,receipt_json FROM nx_genesis_raffle_result")).isEqualTo(original);
        accountingSucceeds();concurrently(()->tx.executeWithoutResult(s->service.pay("round","one",0)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raffle_test_credit",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raffle_test_ledger",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_id FROM raffle_test_credit",Long.class)).isEqualTo(((Number)original.get("user_id")).longValue());
        assertThat(jdbc.queryForObject("SELECT status FROM nx_genesis_raffle_draw",String.class)).isEqualTo("DRAWN");
    }
    @Test void withdrawnPositionCannotWinEvenAfterItWasEntered() throws Exception {
        jdbc.update("INSERT INTO nx_staking_position VALUES('rp',100,'ACTIVE',0)");
        jdbc.update("INSERT INTO nx_g7_repurchase_ticket VALUES('base','rp',100,1,'AVAILABLE',NOW(),0)");
        ready("rp");
        jdbc.update("UPDATE nx_g7_repurchase_ticket SET status='FORFEITED'");
        tx.executeWithoutResult(s->service.draw("round"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_genesis_raffle_result",Integer.class)).isZero();
        verifyNoInteractions(earnings);
    }
    @Test void mysqlBooleanSandboxFlagDisqualifiesAnAlreadyEnteredAccount() throws Exception {
        ready(null);
        jdbc.update("UPDATE nx_user SET sandbox=1 WHERE id=100");
        assertThat(H4EventRuleService.number(repository.row("SELECT sandbox FROM nx_user WHERE id=100").get("sandbox")))
                .isEqualByComparingTo("1");
        tx.executeWithoutResult(s->service.draw("round"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_genesis_raffle_result",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM nx_genesis_raffle_draw",String.class)).isEqualTo("DRAWN");
        verifyNoInteractions(earnings,ledger);
    }
    @Test void simultaneousEntriesInDifferentDrawsCannotSpendTheSameTicketsTwice() throws Exception {
        createDraw("a","2026-10-26");createDraw("b","2026-10-26");
        tx.executeWithoutResult(s->service.grant(100L,"H4_PROMOTION","entry",2));
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> calls=new ArrayList<>();
            for(String id:List.of("a","b"))calls.add(pool.submit(()->{
                start.await();
                try {service.enter(100L,id,2,"key-"+id);return true;}
                catch(BizException ex){assertThat(ex).hasMessageContaining("RAFFLE_TICKETS_INSUFFICIENT");return false;}
            }));
            start.countDown();int succeeded=0;
            for(var call:calls)if(call.get(20,TimeUnit.SECONDS))succeeded++;
            assertThat(succeeded).isEqualTo(1);
        } finally {pool.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT SUM(used_quantity) FROM nx_genesis_raffle_ticket",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM nx_genesis_raffle_entry",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_genesis_raffle_entry",Integer.class)).isEqualTo(1);
    }
    @Test void exitCommittingAfterCandidateReadButBeforeUserLockInvalidatesTheCandidate() throws Exception {
        jdbc.update("INSERT INTO nx_staking_position VALUES('rp',100,'ACTIVE',0)");
        jdbc.update("INSERT INTO nx_g7_repurchase_ticket VALUES('base','rp',100,1,'AVAILABLE',NOW(),0)");
        ready("rp");
        sourceChangeWinsRace(()->{
            jdbc.update("UPDATE nx_staking_position SET status='EARLY_WITHDRAWN' WHERE position_no='rp'");
            jdbc.update("UPDATE nx_g7_repurchase_ticket SET status='FORFEITED' WHERE position_no='rp'");
        });
    }
    @Test void refundHoldCommittingAfterCandidateReadButBeforeUserLockInvalidatesTheCandidate() throws Exception {
        ready(null);
        jdbc.update("INSERT INTO nx_order(order_no,user_id,payment_status,order_status,is_deleted) VALUES('order',100,'PAID','ACTIVE',0)");
        jdbc.update("UPDATE nx_genesis_raffle_ticket SET order_no='order'");
        sourceChangeWinsRace(()->jdbc.update("INSERT INTO nx_promotion_refund_hold(order_no,status) VALUES('order','HELD')"));
    }
    void sourceChangeWinsRace(Runnable sourceChange) throws Exception {
        repository.userLockAttempt=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        List<Future<?>> calls=new ArrayList<>();
        try {
            tx.executeWithoutResult(s->{
                jdbc.queryForList("SELECT id FROM nx_user WHERE id=100 FOR UPDATE");
                calls.add(pool.submit(()->tx.executeWithoutResult(drawTx->service.draw("round"))));
                await(repository.userLockAttempt);
                // The draw already loaded its candidate snapshot and now waits for this authoritative user lock.
                sourceChange.run();
            });
            calls.get(0).get(20,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_genesis_raffle_result",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM nx_genesis_raffle_draw",String.class)).isEqualTo("DRAWN");
        verifyNoInteractions(earnings,ledger);
    }
    @Test void sourceExitAfterWinnersAreFixedDoesNotRewriteTheCommittedResult() throws Exception {
        jdbc.update("INSERT INTO nx_staking_position VALUES('rp',100,'ACTIVE',0)");
        jdbc.update("INSERT INTO nx_g7_repurchase_ticket VALUES('base','rp',100,1,'AVAILABLE',NOW(),0)");
        ready("rp");tx.executeWithoutResult(s->service.draw("round"));
        var winner=jdbc.queryForMap("SELECT user_id,ticket_id,receipt_json FROM nx_genesis_raffle_result");
        jdbc.update("UPDATE nx_g7_repurchase_ticket SET status='FORFEITED'");
        tx.executeWithoutResult(s->service.pay("round","one",0));
        assertThat(jdbc.queryForMap("SELECT user_id,ticket_id,receipt_json FROM nx_genesis_raffle_result")).isEqualTo(winner);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_genesis_raffle_result",String.class)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raffle_test_credit",Integer.class)).isEqualTo(1);
    }
    @Test void legacyMigrationPreservesFullLengthPositionIdentity() throws Exception {
        String position="p".repeat(96),ticket="t".repeat(96);
        jdbc.update("INSERT INTO nx_staking_position VALUES(?,100,'ACTIVE',0)",position);
        jdbc.update("INSERT INTO nx_g7_repurchase_ticket VALUES(?,?,100,2,'AVAILABLE',NOW(),0)",ticket,position);
        try(var connection=jdbc.getDataSource().getConnection()){
            ScriptUtils.executeSqlScript(connection,new FileSystemResource("scripts/migrations/20261025_genesis_raffle.sql"));
        }
        assertThat(jdbc.queryForObject("SELECT position_no FROM nx_genesis_raffle_ticket",String.class)).isEqualTo(position);
        assertThat(jdbc.queryForObject("SELECT source_id FROM nx_genesis_raffle_ticket",String.class)).isEqualTo(ticket);
        assertThat(service.state(100L).getData()).containsEntry("availableTickets",2L);
    }
    @Test void overdueOpenDrawsAreNotBlockedByPendingPayoutsAndRetriesRotate() throws Exception {
        createDraw("new-open","2026-10-25");
        for(int i=0;i<20;i++){
            String id="pending-"+i;createDraw(id,"2026-10-24");
            jdbc.update("UPDATE nx_genesis_raffle_draw SET status='PROCESSING' WHERE draw_id=?",id);
        }
        var first=service.due();
        assertThat(first).contains("new-open").hasSize(11);
        var attempted=first.stream().filter(id->id.startsWith("pending-")).toList();
        for(String id:attempted)tx.executeWithoutResult(s->service.markAttempt(id));
        var next=service.due();
        assertThat(next).contains("new-open").hasSize(11);
        assertThat(next.stream().filter(id->id.startsWith("pending-")).toList()).doesNotContainAnyElementsOf(attempted);
        // Failed payout does not roll back the separately committed retry timestamp.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_genesis_raffle_draw WHERE last_attempt_at IS NOT NULL",Integer.class)).isEqualTo(10);
    }
    @Test void grantReplayCannotChangeTheAwardQuantity(){
        tx.executeWithoutResult(s->service.grant(100L,"H4_PROMOTION","same",2,null,null));
        tx.executeWithoutResult(s->service.grant(100L,"H4_PROMOTION","same",2,null,null));
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM nx_genesis_raffle_ticket",Long.class)).isEqualTo(2);
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.grant(100L,"H4_PROMOTION","same",3,null,null))).isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class);
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM nx_genesis_raffle_ticket",Long.class)).isEqualTo(2);
    }
    static void await(CountDownLatch latch) {
        try {assertThat(latch.await(10,TimeUnit.SECONDS)).isTrue();}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}
    }
    static class PausingRepository extends H4EventRuleRepository {
        volatile CountDownLatch userLockAttempt;
        PausingRepository(H4EventSqlMapper mapper){super(mapper);}
        @Override public List<Map<String,Object>> rows(String sql,Object... args){
            if(userLockAttempt!=null&&sql.contains("FROM nx_user")&&sql.contains("FOR UPDATE"))userLockAttempt.countDown();
            return super.rows(sql,args);
        }
    }
}
