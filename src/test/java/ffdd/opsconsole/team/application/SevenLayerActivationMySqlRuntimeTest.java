package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.market.mapper.NexMarketMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.*;
import ffdd.opsconsole.shared.outbox.mapper.EventConsumerDeliveryMapper;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.team.dto.SevenLayerActivationRequest;
import ffdd.opsconsole.team.mapper.DirectReferralMapper;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Real local MySQL, mapper, transactions, consumer and outbox; never a shared/business schema. */
@EnabledIfEnvironmentVariable(named="SEVEN_ACTIVATION_MYSQL",matches="1")
class SevenLayerActivationMySqlRuntimeTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private DirectReferralMapper mapper;
    private DirectReferralPolicyService policies;
    private DirectReferralService settlement;
    private EventConsumerDeliveryService deliveries;
    private EventOutboxService outbox;
    private EventConsumerDeliveryMapper deliveryMapper;
    private final EventConsumerDeliveryProperties consumerProperties=new EventConsumerDeliveryProperties();
    private final OutboxProperties outboxProperties=new OutboxProperties();
    private final TreasuryCoverageFacade coverage=mock(TreasuryCoverageFacade.class);
    private final AuditLogService audit=mock(AuditLogService.class);
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final Instant t=Instant.parse("2026-10-18T05:00:00.123456789Z");
    private volatile Instant serverNow=t;
    private final Clock serverClock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return serverNow;}};
    private final List<String> tables=List.of("nx_user","nx_order","nx_config_item","nx_commission_rule","nx_commission_event",
            "nx_direct_referral_policy","nx_direct_referral_settlement","nx_unilevel_order_settlement","nx_unilevel_event_recovery",
            "nx_commission_operation","nx_event_consumer_delivery","nx_event_outbox");

    @BeforeEach void setup() throws Exception {
        String url=System.getenv("SEVEN_ACTIVATION_MYSQL_URL");
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:13288/bug631_activation_20261018?");
        var ds=new DriverManagerDataSource(url,"root","");jdbc=new JdbcTemplate(ds);tx=new DataSourceTransactionManager(ds);
        String schema=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:tables){var match=Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS "+table+" \\(.*?\\) ENGINE[^;]+;").matcher(schema);assertThat(match.find()).as(table).isTrue();jdbc.execute(match.group());jdbc.execute("TRUNCATE TABLE "+table);}
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.FileSystemResource(
                "scripts/migrations/20261006_seven_layer_direct_split.sql")).execute(ds);
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("owned-local-activation",new SpringManagedTransactionFactory(),ds));
        configuration.addMapper(DirectReferralMapper.class);configuration.addMapper(EventConsumerDeliveryMapper.class);configuration.addMapper(EventOutboxMapper.class);
        var session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));mapper=session.getMapper(DirectReferralMapper.class);
        var config=mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenAnswer(i->value(i.getArgument(0),false));
        when(config.activeValueForUpdate(anyString())).thenAnswer(i->value(i.getArgument(0),true));
        doAnswer(i->{put(i.getArgument(0),i.getArgument(1));return null;}).when(config).upsertAdminValue(anyString(),anyString(),anyString(),anyString(),anyString());
        put(DirectReferralPolicyService.SEVEN_REVISION_KEY,"0");put("team.direct-referral.policy-version","4");
        String rule="{\"enabled\":true,\"totalRatePct\":10,\"usdtSharePct\":50,\"coolingDays\":0}";
        jdbc.update("INSERT INTO nx_direct_referral_policy VALUES(4,'2026-10-01',CAST(? AS JSON),CAST(? AS JSON),'original-policy','local fixture',NOW(3))",rule,rule);
        var env=new MockEnvironment();env.setActiveProfiles("prod");
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("200"),new BigDecimal("100"),true));
        policies=proxy(new DirectReferralPolicyService(mapper,config,mock(NexMarketMapper.class),coverage,audit,json,serverClock,env));
        var engine=mock(UnilevelCommissionService.class);
        when(engine.prepareBudget(anyLong(),nullable(Long.class),any())).thenReturn(Map.of("layers",List.of(),"orderBasisUsdt",BigDecimal.TEN,"allocatedBudgetUsdt",BigDecimal.ZERO,"coolingDays",30));
        settlement=proxy(new DirectReferralService(mapper,policies,null,null,null,null,null,json,serverClock,engine,null));
        deliveryMapper=session.getMapper(EventConsumerDeliveryMapper.class);
        deliveries=proxy(new EventConsumerDeliveryService(deliveryMapper,consumerProperties));
        outbox=new EventOutboxService(session.getMapper(EventOutboxMapper.class),json,outboxProperties,null);
        auth();
    }
    @AfterEach void cleanup(){A2ReplayContext.exitReplay();SecurityContextHolder.clearContext();}
    private void auth(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("checker",null,List.of(new SimpleGrantedAuthority("network_f2_policy_amplify"),new SimpleGrantedAuthority("platform_a2_operation_approve"))));}
    private Optional<String> value(String key,boolean lock){return jdbc.query("SELECT config_value FROM nx_config_item WHERE config_key=? AND status=1 AND is_deleted=0"+(lock?" FOR UPDATE":""),(r,n)->r.getString(1),key).stream().findFirst();}
    private void put(String key,String value){jdbc.update("INSERT INTO nx_config_item(config_key,config_value,value_type,config_group,visibility,status,is_deleted) VALUES(?,?,'STRING','team','ADMIN',1,0) ON DUPLICATE KEY UPDATE config_value=VALUES(config_value)",key,value);}
    private <T> T proxy(T target){var p=new ProxyFactory(target);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(tx,new AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
    private Map<String,Object> change(String action,long revision){A2ReplayContext.enterReplay("WO-local-"+action+revision);try{return policies.activation("same-intent-"+action+revision,new SevenLayerActivationRequest(action,revision,4),"正常本机隔离七层启用行为验证");}finally{A2ReplayContext.exitReplay();}}
    private LocalDateTime order(String no,LocalDateTime paid){jdbc.update("INSERT IGNORE INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code) VALUES(1,'00','local-only','127.0.0.1','unused','fixture','fixture-ref')");jdbc.update("INSERT INTO nx_order(user_id,order_no,product_id,subtotal_usdt,amount_usdt,payment_status,order_status,paid_at) VALUES(1,?,1,10,10,'PAID','COMPLETED',?)",no,paid);return jdbc.queryForObject("SELECT paid_at FROM nx_order WHERE order_no=?",LocalDateTime.class,no);}
    private EventOutboxMessage event(String id,String order){var e=new EventOutboxMessage();e.setEventId(id);e.setEventType("checkout.completed");e.setAggregateType("ORDER");e.setAggregateId(order);e.setPayload("{\"user_id\":1}");jdbc.update("INSERT INTO nx_event_outbox(event_id,event_type,aggregate_type,aggregate_id,payload,status,retry_count) VALUES(?,'checkout.completed','ORDER',?,?,'PENDING',0)",id,order,e.getPayload());return e;}
    private String status(String table,String event){return jdbc.queryForObject("SELECT status FROM "+table+" WHERE event_id=?",String.class,event);}

    @Test void authoritativeTimeAndOldNewSelectionFreezeAcrossPauseResume() {
        var cutover=LocalDateTime.ofInstant(t,ZoneId.of("Asia/Shanghai")).withNano(123456000);
        order("before",cutover.minusNanos(1000));order("after",cutover);
        var activated=change("ACTIVATE",0);
        assertThat(activated.get("cutoverAt")).isEqualTo(Instant.parse("2026-10-18T05:00:00.123456Z"));
        assertThat(activated.get("sevenLayerRevision")).isEqualTo(1L);
        settlement.prepareSettlement("direct_purchase","before",1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_unilevel_order_settlement WHERE order_no='before'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT policy_version FROM nx_direct_referral_settlement WHERE source_ref='before'",Long.class)).isEqualTo(4);
        settlement.prepareSettlement("direct_purchase","after",1L);
        var frozen=jdbc.queryForMap("SELECT * FROM nx_unilevel_order_settlement WHERE order_no='after'");
        assertThat(frozen.get("settlement_mode")).isEqualTo("SEVEN_V2");assertThat(((Number)frozen.get("split_enabled")).intValue()).isZero();
        serverNow=t.plusSeconds(60);change("PAUSE",1);settlement.prepareSettlement("direct_purchase","after",1L);settlement.prepareSettlement("direct_purchase","before",1L);
        assertThat(jdbc.queryForMap("SELECT * FROM nx_unilevel_order_settlement WHERE order_no='after'")).isEqualTo(frozen);
        serverNow=t.plusSeconds(120);change("RESUME",2);assertThat(policies.cutoverAt()).isEqualTo(activated.get("cutoverAt"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_direct_referral_policy",Integer.class)).isEqualTo(1);
    }
    @Test void pauseExhaustsRealConsumerAndOutboxThenResumeRedrivesOnlyTheOriginalPausedFact() {
        change("ACTIVATE",0);change("PAUSE",1);
        var paid=order("new-original",LocalDateTime.ofInstant(t,ZoneId.of("Asia/Shanghai")).plusSeconds(1));
        var e=event("original-event","new-original");var consumer=new DirectReferralConsumer(deliveries,settlement,json);
        for(int attempt=0;attempt<5;attempt++){
            assertThatThrownBy(()->consumer.consume(e)).hasMessage(DirectReferralPolicyService.PAUSED_ERROR);
            outbox.markFailed(e.getEventId(),DirectReferralPolicyService.PAUSED_ERROR);
        }
        assertThat(status("nx_event_consumer_delivery",e.getEventId())).isEqualTo("DEAD");assertThat(status("nx_event_outbox",e.getEventId())).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_unilevel_order_settlement",Integer.class)).isZero();
        for(String other:List.of("other-error","successful")){
            var otherEvent=event(other,"new-original");deliveries.claim(otherEvent,DirectReferralConsumer.GROUP,"local",other,0);
            if(other.equals("other-error")){deliveries.markFailure(DirectReferralConsumer.GROUP,other,5,"DIFFERENT_ERROR");outbox.markFailed(other,"DIFFERENT_ERROR");}
            else{deliveries.markSuccess(DirectReferralConsumer.GROUP,other,0);outbox.markPublished(other);}
        }
        var before=jdbc.queryForMap("SELECT event_id,aggregate_id,payload,event_ts FROM nx_event_outbox WHERE event_id=?",e.getEventId());
        var restored=change("RESUME",2);assertThat(((Number)restored.get("resumedDeliveryRows")).intValue()).isEqualTo(2);
        assertThat(status("nx_event_outbox",e.getEventId())).isEqualTo("FAILED");assertThat(status("nx_event_consumer_delivery",e.getEventId())).isEqualTo("FAILED");
        assertThat(status("nx_event_consumer_delivery","other-error")).isEqualTo("DEAD");assertThat(status("nx_event_outbox","successful")).isEqualTo("PUBLISHED");
        consumer.consume(e);outbox.markPublished(e.getEventId());consumer.consume(e);
        assertThat(status("nx_event_consumer_delivery",e.getEventId())).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_unilevel_order_settlement WHERE order_no='new-original'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_occurred_at FROM nx_unilevel_order_settlement WHERE order_no='new-original'",LocalDateTime.class)).isEqualTo(paid);
        assertThat(jdbc.queryForMap("SELECT event_id,aggregate_id,payload,event_ts FROM nx_event_outbox WHERE event_id=?",e.getEventId())).isEqualTo(before);
        assertThat(policies.cutoverAt()).isEqualTo(Instant.parse("2026-10-18T05:00:00.123456Z"));
    }
    @Test void permissionCasAndCoverageFailuresNeverSwitchOrIncrement() {
        SecurityContextHolder.clearContext();assertThatThrownBy(()->change("ACTIVATE",0)).hasMessageContaining("PERMISSION_DENIED");auth();
        assertThatThrownBy(()->change("ACTIVATE",9)).hasMessage("SEVEN_LAYER_REVISION_CONFLICT");
        A2ReplayContext.enterReplay("stale-policy");try{assertThatThrownBy(()->policies.activation("stale",new SevenLayerActivationRequest("ACTIVATE",0,3),"正常批准理由必须保留版本")).hasMessage("DIRECT_REFERRAL_VERSION_CONFLICT");}finally{A2ReplayContext.exitReplay();}
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(BigDecimal.ZERO,BigDecimal.ONE,true));
        assertThatThrownBy(()->change("ACTIVATE",0)).hasMessage("COVERAGE_BELOW_REDLINE");
        assertThat(policies.cutoverAt()).isNull();assertThat(policies.sevenLayerRevision()).isZero();assertThat(value(DirectReferralPolicyService.PAUSED_KEY,false)).isEmpty();
        verify(audit,never()).recordRequired(any());
    }
    @Test void requiredAuditFailureRollsBackTheActivationInstantAndRevision() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordRequired(any());
        assertThatThrownBy(()->change("ACTIVATE",0)).hasMessage("audit unavailable");
        assertThat(policies.cutoverAt()).isNull();assertThat(policies.sevenLayerRevision()).isZero();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void resumeBeforeFinalFailureMetadataStillRecoversTheSameOriginalEvent(boolean consumerFailureAlreadyCommitted) throws Exception {
        consumerProperties.setMaxRetries(1);outboxProperties.setMaxRetries(1);
        change("ACTIVATE",0);change("PAUSE",1);
        var paid=order("interleaved-original",LocalDateTime.ofInstant(t,ZoneId.of("Asia/Shanghai")).plusSeconds(1));
        var e=event("interleaved-event","interleaved-original");
        var reachedFailure=new CountDownLatch(1);var resumeCommitted=new CountDownLatch(1);
        var waitingDelivery=new EventConsumerDeliveryService(deliveryMapper,consumerProperties){
            @Override public ConsumerFailure markFailure(String group,String id,int times,String error){
                ConsumerFailure result=consumerFailureAlreadyCommitted?deliveries.markFailure(group,id,times,error):null;
                reachedFailure.countDown();try{if(!resumeCommitted.await(15,TimeUnit.SECONDS))throw new IllegalStateException("fixture barrier timed out");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                return consumerFailureAlreadyCommitted?result:deliveries.markFailure(group,id,times,error);
            }
        };
        var consumer=new DirectReferralConsumer(waitingDelivery,settlement,json);
        var thread=Executors.newSingleThreadExecutor();
        try{
            var attempt=thread.submit(()->{try{consumer.consume(e);throw new AssertionError("pause must propagate");}catch(ffdd.opsconsole.shared.exception.BizException failure){outbox.markFailed(e.getEventId(),failure.getMessage());}});
            assertThat(reachedFailure.await(15,TimeUnit.SECONDS)).isTrue();
            assertThat(status("nx_event_outbox",e.getEventId())).isEqualTo("PENDING");
            assertThat(((Number)change("RESUME",2).get("resumedDeliveryRows")).intValue()).isZero();
            resumeCommitted.countDown();attempt.get(15,TimeUnit.SECONDS);
            assertThat(status("nx_event_outbox",e.getEventId())).isEqualTo("DEAD");
            assertThat(status("nx_event_consumer_delivery",e.getEventId())).isEqualTo("DEAD");
            // Run the production recovery scheduler, not a replacement event or a mocked mapper write.
            new DirectReferralRecoveryScheduler(mapper,settlement,policies).recover();
            assertThat(status("nx_event_outbox",e.getEventId())).isEqualTo("FAILED");
            new DirectReferralConsumer(deliveries,settlement,json).consume(e);outbox.markPublished(e.getEventId());
            assertThat(status("nx_event_consumer_delivery",e.getEventId())).isEqualTo("SUCCESS");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_unilevel_order_settlement WHERE order_no='interleaved-original'",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT source_occurred_at FROM nx_unilevel_order_settlement WHERE order_no='interleaved-original'",LocalDateTime.class)).isEqualTo(paid);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox",Integer.class)).isEqualTo(1);
        }finally{resumeCommitted.countDown();thread.shutdownNow();}
    }
    @ParameterizedTest @ValueSource(strings={"ACTIVATE","PAUSE"})
    void oldRepeatableReadSnapshotWaitsForControlLockThenUsesCommittedStateAndOriginalSourceTime(String action) throws Exception {
        if(action.equals("PAUSE"))change("ACTIVATE",0);
        var paid=order("control-wait-original",LocalDateTime.ofInstant(t,ZoneId.of("Asia/Shanghai")).plusSeconds(1));
        var oldSnapshot=new CountDownLatch(1);var controlWritten=new CountDownLatch(1);var commitControl=new CountDownLatch(1);
        var threads=Executors.newFixedThreadPool(2);
        try{
            var source=threads.submit(()->{
                var transaction=new org.springframework.transaction.support.TransactionTemplate(tx);
                transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                return transaction.execute(status->{
                    // A real plain SELECT establishes the old RR view before the approved control transaction.
                    assertThat(value(action.equals("ACTIVATE")?DirectReferralPolicyService.CUTOVER_KEY:DirectReferralPolicyService.PAUSED_KEY,false))
                            .isEqualTo(action.equals("ACTIVATE")?Optional.empty():Optional.of("false"));
                    oldSnapshot.countDown();await(controlWritten);
                    return settlement.prepareSettlement("direct_purchase","control-wait-original",1L);
                });
            });
            assertThat(oldSnapshot.await(15,TimeUnit.SECONDS)).isTrue();
            var control=threads.submit(()->{
                auth();try{return new org.springframework.transaction.support.TransactionTemplate(tx).execute(status->{
                    var result=change(action,action.equals("ACTIVATE")?0:1);controlWritten.countDown();await(commitControl);return result;
                });}finally{SecurityContextHolder.clearContext();}
            });
            assertThat(controlWritten.await(15,TimeUnit.SECONDS)).isTrue();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);int waits=0;
            while(waits==0&&System.nanoTime()<deadline){waits=jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Integer.class);if(waits==0)Thread.sleep(10);}
            assertThat(waits).as("real source transaction must wait for the approved revision row lock").isPositive();
            assertThat(source.isDone()).isFalse();commitControl.countDown();control.get(15,TimeUnit.SECONDS);
            if(action.equals("PAUSE")){
                assertThatThrownBy(()->source.get(15,TimeUnit.SECONDS)).hasRootCauseMessage(DirectReferralPolicyService.PAUSED_ERROR);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_unilevel_order_settlement",Integer.class)).isZero();
            }else{
                source.get(15,TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject("SELECT settlement_mode FROM nx_unilevel_order_settlement WHERE order_no='control-wait-original'",String.class)).isEqualTo("SEVEN_V2");
                assertThat(jdbc.queryForObject("SELECT source_occurred_at FROM nx_unilevel_order_settlement WHERE order_no='control-wait-original'",LocalDateTime.class)).isEqualTo(paid);
            }
        }finally{controlWritten.countDown();commitControl.countDown();threads.shutdownNow();}
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS))throw new IllegalStateException("fixture barrier timed out");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}}
}
