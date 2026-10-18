package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ffdd.opsconsole.growth.application.*;
import ffdd.opsconsole.growth.facade.*;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.user.application.UserWeeklyRegionFacadeAdapter;
import ffdd.opsconsole.user.facade.UserWeeklyRegionFacade;
import ffdd.opsconsole.user.infrastructure.UserWeeklyRegionMapper;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Actual isolated MySQL RR, locks, canonical slot recovery, and final claim CAS. No product database. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegionalWeeklyPeriodMySqlIntegrationTest extends WeeklyQuestSnapshotMySqlFixture {
    private static final String KEY="WEEK:2026-W42", TYPE="H3_GENESIS_SECONDARY_MARKET_VIEWED";
    private final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-20T00:00:00Z"));
    private UserWeeklyRegionFacade users;
    private WeeklyRegionPolicyService policy;
    private WeeklyQuestSnapshotService service;
    private EventOutboxMapper outbox;
    private AppGrowthEngagementMapper engagement;
    private PlatformConfigFacade config;

    @BeforeAll void regionalDependencies() {
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,country_code VARCHAR(8),status VARCHAR(16),is_deleted TINYINT DEFAULT 0,sandbox TINYINT DEFAULT 0,created_at DATETIME DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
        jdbc.execute("ALTER TABLE nx_user_mission ADD COLUMN updated_at DATETIME NULL");
        jdbc.execute("CREATE TABLE nx_event_outbox(event_id VARCHAR(96) PRIMARY KEY,event_type VARCHAR(128),payload JSON,event_ts DATETIME(3),is_server_authoritative TINYINT,status VARCHAR(32),next_retry_at DATETIME,published_at DATETIME,last_error VARCHAR(128),updated_at DATETIME,is_deleted TINYINT DEFAULT 0) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_event_consumer_delivery(event_id VARCHAR(96),consumer_group VARCHAR(64),status VARCHAR(32),is_deleted TINYINT DEFAULT 0) ENGINE=InnoDB");
        var configuration=fixtureSessions.getConfiguration();
        for(Class<?> type:List.of(UserWeeklyRegionMapper.class,WeeklyRegionPolicyMapper.class,EventOutboxMapper.class,AppGrowthEngagementMapper.class)) configuration.addMapper(type);
        var adapter=new UserWeeklyRegionFacadeAdapter(fixtureSessions.getMapper(UserWeeklyRegionMapper.class));
        var proxy=new ProxyFactory(adapter);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(fixtureSource),new AnnotationTransactionAttributeSource()));
        users=(UserWeeklyRegionFacade)proxy.getProxy();
        config=mock(PlatformConfigFacade.class);
        when(config.activeValue(WeeklyRegionPolicyService.KEY)).thenAnswer(call->configValue(false));
        when(config.activeValueForUpdate(WeeklyRegionPolicyService.KEY)).thenAnswer(call->configValue(true));
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return Clock.fixed(now.get(),zone);} public Instant instant(){return now.get();}};
        policy=new WeeklyRegionPolicyService(config,fixtureSessions.getMapper(WeeklyRegionPolicyMapper.class),clock);
        GrowthRhythmFacade rhythm=()->new GrowthRhythmSnapshot(12,3,"P2",50,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,new BigDecimal("0.15"),30,new BigDecimal("5000"),new BigDecimal("2"),false,List.of("H1.rhythm.currentMonth"));
        var environment=new MockEnvironment();environment.setActiveProfiles("dev");
        service=new WeeklyQuestSnapshotService(mapper,rhythm,clock,null,policy,users,environment);
        outbox=fixtureSessions.getMapper(EventOutboxMapper.class);
        engagement=fixtureSessions.getMapper(AppGrowthEngagementMapper.class);
    }
    private Optional<String> configValue(boolean lock) {
        var values=jdbc.queryForList("SELECT config_value FROM nx_config_item WHERE config_key=?"+(lock?" FOR UPDATE":""),String.class,WeeklyRegionPolicyService.KEY);
        return values.stream().findFirst();
    }
    @BeforeEach void seedRegionalFixture() {
        jdbc.update("DELETE FROM nx_event_consumer_delivery");jdbc.update("DELETE FROM nx_event_outbox");jdbc.update("DELETE FROM nx_user");
        jdbc.update("INSERT INTO nx_user(id,country_code,status) VALUES(1,'84','ACTIVE'),(2,'86','ACTIVE'),(3,'86','DISABLED')");
        now.set(Instant.parse("2026-10-20T00:00:00Z"));
        var legacy=new WeeklyQuestSnapshotMapper.Header(KEY,LocalDateTime.parse("2026-10-12T00:00:00"),LocalDateTime.parse("2026-10-19T00:00:00"),BigDecimal.ONE,3,0);
        mapper.insertHeader(legacy);
        jdbc.update("INSERT INTO nx_mission VALUES(1,'weekly_t2_genesis_browse','Frozen name','EXPLORE','/pages/tabs/store',10,'WEEKLY_T2',1,0)");
        jdbc.update("INSERT INTO nx_growth_quest_event_binding VALUES(11,'FROZEN','SYSTEM',?,'weekly_t2_genesis_browse','user_id',1,0)",TYPE);
        setPolicy(1,"Asia/Ho_Chi_Minh");
    }
    private void setPolicy(long revision,String zone) {
        var state=new WeeklyRegionPolicyService.State(revision,"Asia/Ho_Chi_Minh",List.of(new WeeklyRegionPolicyService.Mapping("+84",zone),new WeeklyRegionPolicyService.Mapping("+86","Asia/Shanghai")),Instant.parse("2026-10-18T16:00:00Z"),KEY);
        jdbc.update("INSERT INTO nx_config_item(config_key,config_value,status) VALUES(?,?,1) ON DUPLICATE KEY UPDATE config_value=VALUES(config_value)",WeeklyRegionPolicyService.KEY,policy.encode(state));
    }

    @Test void twoRegionsFreezeDistinctBridgeDeadlinesAndKeepAssignmentAfterMappingChange() {
        var vn=transaction.execute(status->service.current(1L));var cn=transaction.execute(status->service.current(2L));
        assertThat(transaction.<Boolean>execute(status->service.eligible(1L,vn,LocalDateTime.parse("2026-10-20T08:00:00")))).isTrue();
        assertThat(vn.instanceKey()).hasSize(37).isNotEqualTo(cn.instanceKey());
        assertThat(vn.eligibleFrom()).isEqualTo(cn.eligibleFrom()).isEqualTo(LocalDateTime.parse("2026-10-19T00:00:00"));
        assertThat(vn.eligibleUntil()).isEqualTo(LocalDateTime.parse("2026-10-26T01:00:00"));
        assertThat(cn.eligibleUntil()).isEqualTo(LocalDateTime.parse("2026-10-26T00:00:00"));
        setPolicy(2,"Asia/Tokyo");
        assertThat(transaction.<WeeklyQuestSnapshotMapper.Header>execute(status->service.current(1L))).isEqualTo(vn);
        assertThat(transaction.<Boolean>execute(status->service.eligible(2L,vn,LocalDateTime.parse("2026-10-20T08:00:00")))).isFalse();
    }

    @Test void differentAccountFirstReadsDoNotDeadlockOnEmptyAssignmentRanges() throws Exception {
        CountDownLatch lockedUsers=new CountDownLatch(2);
        UserWeeklyRegionFacade coordinated=(id,sandbox)->{
            var result=users.lockActiveRegisteredCallingCode(id,sandbox);lockedUsers.countDown();
            try {assertThat(lockedUsers.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException ex){throw new RuntimeException(ex);}
            return result;
        };
        var environment=new MockEnvironment();environment.setActiveProfiles("dev");
        GrowthRhythmFacade rhythm=()->new GrowthRhythmSnapshot(12,3,"P2",50,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,new BigDecimal("0.15"),30,new BigDecimal("5000"),BigDecimal.ONE,false,List.of());
        var testService=new WeeklyQuestSnapshotService(mapper,rhythm,Clock.fixed(now.get(),ZoneOffset.UTC),null,policy,coordinated,environment);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->transaction.execute(status->testService.current(1L)));
            var b=pool.submit(()->transaction.execute(status->testService.current(2L)));
            assertThat(a.get(15,TimeUnit.SECONDS)).isNotNull();assertThat(b.get(15,TimeUnit.SECONDS)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_account_weekly_period",Integer.class)).isEqualTo(2);
        }finally{pool.shutdownNow();}
    }

    @Test void h3CurrentPolicyReadIgnoresEarlierRepeatableReadSnapshot() throws Exception {
        CountDownLatch oldSnapshot=new CountDownLatch(1),updated=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var result=pool.submit(()->transaction.execute(status->{
                assertThat(policy.read().revision()).isEqualTo(1);oldSnapshot.countDown();
                try {assertThat(updated.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException ex){throw new RuntimeException(ex);}
                return service.current(1L);
            }));
            assertThat(oldSnapshot.await(5,TimeUnit.SECONDS)).isTrue();
            transaction.execute(status->{mapper.ensureMutex();mapper.lockMutex();setPolicy(2,"Asia/Shanghai");return null;});
            updated.countDown();
            assertThat(result.get(15,TimeUnit.SECONDS).businessTimezone()).isEqualTo("Asia/Shanghai");
            assertThat(jdbc.queryForObject("SELECT mapping_version FROM nx_growth_account_weekly_period WHERE user_id=1",Long.class)).isEqualTo(2L);
        }finally{updated.countDown();pool.shutdownNow();}
    }

    @Test void inactiveSecondaryThroughRealMandatoryProxyDoesNotMarkOuterTransactionRollbackOnly() {
        transaction.execute(status->{
            assertThat(service.lockAccountsForProjection(List.of(3L,1L))).containsExactly(1L);
            assertThat(status.isRollbackOnly()).isFalse();
            jdbc.update("INSERT INTO nx_config_item(config_key,config_value,status) VALUES('active-beneficiary-proof','ok',1)");return null;
        });
        assertThat(jdbc.queryForObject("SELECT config_value FROM nx_config_item WHERE config_key='active-beneficiary-proof'",String.class)).isEqualTo("ok");
    }

    @Test void actualFinalClaimCasRequiresAccountOwnershipAndUnexpiredAbsoluteWindow() {
        LocalDateTime dbNow=jdbc.queryForObject("SELECT CONVERT_TZ(UTC_TIMESTAMP(6),'+00:00','+08:00')",LocalDateTime.class);
        String key="WEEK:cccccccccccccccccccccccccccccccc";
        mapper.insertHeader(new WeeklyQuestSnapshotMapper.Header(key,dbNow.minusHours(1),dbNow.plusHours(1),BigDecimal.ONE,3,1,"Asia/Ho_Chi_Minh",LocalDate.of(2026,10,19),"PHONE_REGION_V1"));
        mapper.insertAssignment(new WeeklyQuestSnapshotMapper.Assignment(1L,LocalDate.of(2026,10,19),key,"+84",1));
        jdbc.update("INSERT INTO nx_user_mission(user_id,mission_id,instance_key,mission_status) VALUES(1,1,?,'CLAIMABLE'),(2,1,?,'CLAIMABLE')",key,key);
        assertThat(transaction.<Integer>execute(status->engagement.claimQuest(2L,1L,key))).isZero();
        assertThat(transaction.<Integer>execute(status->engagement.claimQuest(1L,1L,key))).isEqualTo(1);
        jdbc.update("UPDATE nx_user_mission SET mission_status='CLAIMABLE' WHERE user_id=1");
        jdbc.update("UPDATE nx_growth_weekly_definition SET eligible_until=? WHERE instance_key=?",dbNow.minusSeconds(1),key);
        assertThat(transaction.<Integer>execute(status->engagement.claimQuest(1L,1L,key))).isZero();
    }

    @Test void frozenInviterSlotRedrivesOnlyItsAssignedBeneficiaryAndExpiredThresholdNeedsNoBinding() {
        LocalDateTime dbNow=jdbc.queryForObject("SELECT CONVERT_TZ(UTC_TIMESTAMP(6),'+00:00','+08:00')",LocalDateTime.class);
        String key="WEEK:dddddddddddddddddddddddddddddddd";
        mapper.insertHeader(new WeeklyQuestSnapshotMapper.Header(key,dbNow.minusHours(1),dbNow.plusHours(1),BigDecimal.ONE,3,1,"Asia/Ho_Chi_Minh",LocalDate.of(2026,10,19),"PHONE_REGION_V1"));
        mapper.insertAssignment(new WeeklyQuestSnapshotMapper.Assignment(2L,LocalDate.of(2026,10,19),key,"+86",1));
        jdbc.update("INSERT INTO nx_growth_weekly_definition_item(instance_key,source_mission_id,quest_code,name,layer,category,action_route,reward_points,ordinal) VALUES(?,1,'custom','custom','WEEKLY_T2','EXPLORE','/pages/tabs/store',10,0)",key);
        jdbc.update("INSERT INTO nx_growth_weekly_definition_binding(instance_key,source_mission_id,source_binding_id,binding_code,producer,event_type,user_id_field) VALUES(?,1,11,'INV','SYSTEM','CUSTOM_WEEKLY','inviter_user_id')",key);
        pending("right","CUSTOM_WEEKLY","{\"user_id\":1,\"inviter_user_id\":2,\"instanceKey\":\""+key+"\"}",dbNow);
        pending("wrong","CUSTOM_WEEKLY","{\"user_id\":2,\"inviter_user_id\":1,\"instanceKey\":\""+key+"\"}",dbNow);
        assertThat(outbox.requeuePublishedPendingBinding("CUSTOM_WEEKLY","h3-quest-completion","PENDING_BINDING","PENDING","PUBLISHED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_event_outbox WHERE event_id='wrong'",String.class)).isEqualTo("PUBLISHED");
        jdbc.update("DELETE FROM nx_growth_weekly_definition_binding WHERE instance_key=?",key);
        jdbc.update("UPDATE nx_growth_weekly_definition SET eligible_until=? WHERE instance_key=?",dbNow.minusSeconds(1),key);
        pending("expired",TYPE,"{\"user_id\":2,\"instanceKey\":\""+key+"\"}",dbNow);
        assertThat(outbox.requeuePublishedPendingBinding(TYPE,"h3-quest-completion","PENDING_BINDING","PENDING","PUBLISHED")).isEqualTo(1);
    }
    private void pending(String id,String eventType,String payload,LocalDateTime at) {
        jdbc.update("INSERT INTO nx_event_outbox(event_id,event_type,payload,event_ts,is_server_authoritative,status) VALUES(?,?,?,?,1,'PUBLISHED')",id,eventType,payload,at);
        jdbc.update("INSERT INTO nx_event_consumer_delivery(event_id,consumer_group,status) VALUES(?,'h3-quest-completion','PENDING_BINDING')",id);
    }
}
