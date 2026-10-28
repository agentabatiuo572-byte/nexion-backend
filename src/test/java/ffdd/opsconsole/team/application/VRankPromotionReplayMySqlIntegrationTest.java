package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.outbox.*;
import ffdd.opsconsole.shared.outbox.mapper.EventConsumerDeliveryMapper;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.team.domain.*;
import ffdd.opsconsole.team.infrastructure.MybatisTeamCommissionRepository;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Real listener, delivery SQL, engine writes and Spring/MySQL transactions.
 * Only performance/config inputs and external reward effects are fixtures. */
@EnabledIfEnvironmentVariable(named = "NEXION_VRANK_REPLAY_MYSQL_IT", matches = "true")
class VRankPromotionReplayMySqlIntegrationTest {
    JdbcTemplate admin, jdbc;
    String schema;
    DataSourceTransactionManager transactions;
    AnnotationConfigApplicationContext context;
    EventOutboxDispatchScheduler scheduler;
    EventOutboxService outbox;
    EventOutboxMapper outboxMapper;
    final AtomicBoolean failReward = new AtomicBoolean();
    static final String ORIGINAL = "original-promotion";

    @BeforeEach void fixture() {
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        if (!"127.0.0.1:13306".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        String url = "jdbc:mysql://" + endpoint + "/?useSSL=false&allowPublicKeyRetrieval=true";
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
        assertThat(admin.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
        schema = "nexion_vrank_replay_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + schema);
        System.out.println("VRANK_REPLAY_SCHEMA_CREATED " + schema);
        var source = new DriverManagerDataSource(url.replace("/?", "/" + schema + "?"), "root", "");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,v_rank VARCHAR(16),updated_at DATETIME,is_deleted INT DEFAULT 0) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_team_member(id BIGINT PRIMARY KEY,user_id BIGINT,member_user_id BIGINT,level INT,v_rank VARCHAR(16),updated_at DATETIME,is_deleted INT DEFAULT 0) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_sponsorship(user_id BIGINT PRIMARY KEY,sponsor_user_id BIGINT,is_deleted INT DEFAULT 0) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_v_rank_config(id BIGINT PRIMARY KEY,rank_code VARCHAR(16),self_compute_points DECIMAL(18,6),direct_refs INT,team_compute_points DECIMAL(18,6),required_downline_rank VARCHAR(16),required_downline_count INT,sort_order INT,unilevel_depth VARCHAR(64),peer_bonus_rate DECIMAL(18,6),leadership_votes INT,status INT DEFAULT 1,is_deleted INT DEFAULT 0) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_user_level_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,level_type VARCHAR(16),from_code VARCHAR(16),to_code VARCHAR(16),reason VARCHAR(64),operator VARCHAR(64),snapshot JSON,trigger_event_id VARCHAR(64),audit_no VARCHAR(64),is_manual INT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE fixture_reward_effect(rank_code VARCHAR(16)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_audit_log(id BIGINT PRIMARY KEY,biz_no VARCHAR(96),is_deleted INT DEFAULT 0) ENGINE=InnoDB");
        jdbc.execute("""
                CREATE TABLE nx_event_consumer_delivery(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id VARCHAR(64),consumer_group VARCHAR(128),topic VARCHAR(128),
                  msg_id VARCHAR(128),event_type VARCHAR(96),aggregate_type VARCHAR(64),aggregate_id VARCHAR(128),
                  status VARCHAR(32),attempt_count INT,rocketmq_reconsume_times INT,next_retry_at DATETIME,
                  processed_at DATETIME,dead_at DATETIME,created_commissions INT,last_error VARCHAR(512),
                  first_seen_at DATETIME,last_seen_at DATETIME,created_at DATETIME,updated_at DATETIME,is_deleted INT DEFAULT 0,
                  UNIQUE KEY uk_event_consumer_event_group(event_id,consumer_group)) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE nx_event_outbox(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id VARCHAR(64) NOT NULL,aggregate_type VARCHAR(64),aggregate_id VARCHAR(128),
                  event_type VARCHAR(96),event_name VARCHAR(96),family_key VARCHAR(64),event_ts DATETIME(3),phase VARCHAR(32),
                  account_age_months INT,cohort VARCHAR(32),is_server_authoritative BOOLEAN,schema_revision INT,
                  schema_registered BOOLEAN,analytics_event BOOLEAN,payload JSON,status VARCHAR(32) DEFAULT 'PENDING',
                  retry_count INT DEFAULT 0,next_retry_at DATETIME,published_at DATETIME,last_error VARCHAR(512),
                  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME,is_deleted INT DEFAULT 0,
                  UNIQUE KEY uk_event_outbox_event_id(event_id)) ENGINE=InnoDB
                """);
        jdbc.update("INSERT INTO nx_user(id,v_rank) VALUES(100,'V0')");
        jdbc.update("INSERT INTO nx_team_member(id,user_id,member_user_id,level,v_rank) VALUES(1,100,100,0,'V0')");
        jdbc.update("INSERT INTO nx_sponsorship(user_id,sponsor_user_id) VALUES(200,100)");
        jdbc.update("INSERT INTO nx_v_rank_config(id,rank_code,self_compute_points,direct_refs,team_compute_points,required_downline_count,sort_order,peer_bonus_rate,leadership_votes) VALUES(1,'V0',0,0,0,0,0,0,0),(2,'V1',500,3,0,0,1,0,0),(3,'V2',0,0,5000,0,2,0,0)");
        var configuration = new Configuration(new Environment("vrank-replay-isolated", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(TeamCommissionMapper.class);
        configuration.addMapper(EventConsumerDeliveryMapper.class);
        configuration.addMapper(EventOutboxMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var repository = new MybatisTeamCommissionRepository(session.getMapper(TeamCommissionMapper.class));
        var delivery = proxy(new EventConsumerDeliveryService(session.getMapper(EventConsumerDeliveryMapper.class), new EventConsumerDeliveryProperties()));
        outboxMapper = session.getMapper(EventOutboxMapper.class);
        outbox = spy(new EventOutboxService(outboxMapper, new ObjectMapper(), new OutboxProperties(), null));
        // Unrelated dispatcher sweeps need their own domain tables, excluded from this fixture.
        doReturn(0).when(outbox).retireRecordOnlyPending(anyInt());
        doReturn(0).when(outbox).retireUnlinkedC1ProfileEvidence(anyInt());
        doReturn(0).when(outbox).requeuePublishedPendingBinding(anyString(), anyString());
        var performance = mock(VRankPerformanceRepository.class);
        when(performance.computeSnapshot(100L)).thenReturn(new VRankEvaluationSnapshot(500L, 6000L, 3, Map.of()));
        var config = mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        var rewards = mock(VRankRewardDispatcher.class);
        doAnswer(call -> {
            jdbc.update("INSERT INTO fixture_reward_effect VALUES(?)", (String) call.getArgument(1));
            if (failReward.get()) throw new IllegalStateException("fixture reward failure after write");
            return null;
        }).when(rewards).dispatch(anyLong(), anyString(), any(), any());
        var engine = proxy(new VRankPromotionEngine(repository, performance, config, new ObjectMapper(), rewards, outbox));
        var consumer = proxy(new VRankPromotionEventConsumer(new ObjectMapper(), repository, engine, delivery));
        context = new AnnotationConfigApplicationContext();
        context.registerBean(VRankPromotionEventConsumer.class, () -> consumer);
        context.refresh();
        scheduler = new EventOutboxDispatchScheduler(outbox, context);
        insertEvent(ORIGINAL);
    }

    @AfterEach void cleanup() {
        if (context != null) context.close();
        if (schema != null && schema.matches("nexion_vrank_replay_it_[a-f0-9]{32}")) {
            admin.execute("DROP DATABASE " + schema);
            assertThat(admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema)).isZero();
            System.out.println("VRANK_REPLAY_SCHEMA_DROPPED " + schema);
        }
    }

    @Test void acknowledgementWriteFailureReplaysWithoutAnotherPromotion() {
        jdbc.execute("CREATE TRIGGER reject_ack BEFORE UPDATE ON nx_event_outbox FOR EACH ROW BEGIN IF NEW.event_id='original-promotion' AND NEW.status='PUBLISHED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture ack failure'; END IF; END");
        scheduler.dispatchPending();
        assertPromotion("V1", 1);
        assertThat(status()).isEqualTo("FAILED");
        assertThat(receipt()).isEqualTo("SUCCESS");
        jdbc.execute("DROP TRIGGER reject_ack");
        retryNow();
        scheduler.dispatchPending();
        assertPromotion("V1", 1);
        assertThat(status()).isEqualTo("PUBLISHED");
    }

    @Test void differentEventStillAdvancesNextRank() {
        scheduler.dispatchPending();
        insertEvent("different-promotion");
        scheduler.dispatchPending();
        assertPromotion("V2", 2);
        assertThat(jdbc.queryForList("SELECT trigger_event_id FROM nx_user_level_log ORDER BY id", String.class))
                .containsExactly(ORIGINAL, "different-promotion");
    }

    @Test void rewardFailureRollsBackClaimRankAuditAndEffectsThenRecovers() {
        failReward.set(true);
        scheduler.dispatchPending();
        assertPromotion("V0", 0);
        assertThat(receiptCount()).isZero();
        assertThat(status()).isEqualTo("FAILED");
        failReward.set(false);
        retryNow();
        scheduler.dispatchPending();
        assertPromotion("V1", 1);
        assertThat(receipt()).isEqualTo("SUCCESS");
    }

    @Test void completionWriteFailureAlsoRollsBackPromotionThenRecovers() {
        jdbc.execute("CREATE TRIGGER reject_completion BEFORE UPDATE ON nx_event_consumer_delivery FOR EACH ROW BEGIN IF NEW.status='SUCCESS' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture completion failure'; END IF; END");
        scheduler.dispatchPending();
        assertPromotion("V0", 0);
        assertThat(receiptCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isEqualTo(1);
        jdbc.execute("DROP TRIGGER reject_completion");
        retryNow();
        scheduler.dispatchPending();
        assertPromotion("V1", 1);
        assertThat(receipt()).isEqualTo("SUCCESS");
    }

    @Test void concurrentSameEventCommitsOnlyOnePromotion() throws Exception {
        var message = outboxMapper.listPendingByEventType("VRANK_PROMOTION_COMPLETED", 10).get(0);
        var start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> consume = () -> { start.await(10, TimeUnit.SECONDS); context.publishEvent(message); return null; };
            var first = executor.submit(consume);
            var second = executor.submit(consume);
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertPromotion("V1", 1);
        assertThat(receiptCount()).isEqualTo(1);
        assertThat(receipt()).isEqualTo("SUCCESS");
    }

    void insertEvent(String id) {
        jdbc.update("INSERT INTO nx_event_outbox(event_id,aggregate_type,aggregate_id,event_type,payload,analytics_event) VALUES(?,'vrank','200','VRANK_PROMOTION_COMPLETED',?,false)", id,
                "{\"userId\":200,\"fromCode\":\"V0\",\"toCode\":\"V1\"}");
    }
    void retryNow() { jdbc.update("UPDATE nx_event_outbox SET next_retry_at=NULL WHERE event_id=?", ORIGINAL); }
    String status() { return jdbc.queryForObject("SELECT status FROM nx_event_outbox WHERE event_id=?", String.class, ORIGINAL); }
    String receipt() { return jdbc.queryForObject("SELECT status FROM nx_event_consumer_delivery WHERE event_id=?", String.class, ORIGINAL); }
    int receiptCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_consumer_delivery WHERE event_id=?", Integer.class, ORIGINAL); }
    void assertPromotion(String rank, int effects) {
        assertThat(jdbc.queryForObject("SELECT v_rank FROM nx_team_member WHERE id=1", String.class)).isEqualTo(rank);
        assertThat(jdbc.queryForObject("SELECT v_rank FROM nx_user WHERE id=100", String.class)).isEqualTo(rank);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_level_log", Integer.class)).isEqualTo(effects);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture_reward_effect", Integer.class)).isEqualTo(effects);
    }
    @SuppressWarnings("unchecked") <T> T proxy(T target) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
