package ffdd.opsconsole.growth.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.mapper.NotificationCampaignMapper;
import ffdd.opsconsole.growth.infrastructure.H4EventSqlConfiguration;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real H4 transactions and public notification SQL, restricted to a disposable local MySQL schema. */
@EnabledIfEnvironmentVariable(named="NEXION_H4_REMINDER_IT",matches="true")
class H4ReminderDeliveryMySqlTest {
    static final String CODE="evt-black-friday";
    JdbcTemplate admin,jdbc;
    String schema;
    H4EventEngine engine;
    H4EventSettlementScheduler scheduler;
    NotificationCampaignMapper notifications;
    AppGrowthEngagementMapper events;

    @BeforeEach void setup() throws Exception {
        String endpoint=System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        assertThat(endpoint).matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}").doesNotEndWith(":3306");
        String base="jdbc:mysql://"+endpoint+"/",options="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        String password=System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD","");
        admin=new JdbcTemplate(new DriverManagerDataSource(base+options,"root",password));
        schema="h4_reminder_it_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+schema);
        var ds=new DriverManagerDataSource(base+schema+options,"root",password);
        jdbc=new JdbcTemplate(ds);
        String h4=Files.readString(Path.of("scripts/migrations/20261025_h4_event_rules.sql"));
        for(String table:List.of("nx_growth_event_rule","nx_growth_event_reminder","nx_growth_event_pool_result","nx_growth_event_claim","nx_growth_event_holding"))
            jdbc.execute(tableDdl(h4,table));
        jdbc.execute(tableDdl(Files.readString(Path.of("scripts/schema.sql")),"nx_notification"));
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(16),sandbox INT,is_deleted INT,created_at DATETIME,language VARCHAR(32))");
        jdbc.execute("CREATE TABLE nx_user_preference(user_id BIGINT PRIMARY KEY,is_deleted INT,notify_system INT,notify_commission INT,notify_team INT,notify_staking INT,notify_market INT,notify_genesis INT)");
        jdbc.update("INSERT INTO nx_user VALUES(7,'ACTIVE',0,0,NOW(),'en'),(8,'ACTIVE',0,0,NOW(),'en')");
        jdbc.update("INSERT INTO nx_growth_event_rule(event_code,kind,enabled,starts_at,ends_at,activated_at,rules_json) VALUES(?,'seasonal',1,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 HOUR),UTC_TIMESTAMP(6),JSON_OBJECT())",CODE);
        var db=new H4EventRuleRepository(new H4EventSqlConfiguration().h4EventRawMapper(ds));
        var commerce=mock(H4EventCommerceService.class);
        var policies=new H4EventRuleService(db,new ObjectMapper(),mock(AuditLogService.class),commerce,mock(TreasuryCoverageFacade.class),mock(A2RuntimePolicy.class));
        events=mock(AppGrowthEngagementMapper.class);
        for(String locale:List.of("en","zh","vi"))
            when(events.eventState(7L,locale)).thenReturn(List.of(Map.of("eventCode",CODE,"title","title-"+locale,"subtitle","body-"+locale)));
        var raw=new H4EventEngine(db,policies,commerce,events,null,null,null,mock(AdminIdempotencyService.class),mock(AuditLogService.class), org.mockito.Mockito.mock(ffdd.opsconsole.device.application.TestCloudActivationAdmission.class));
        var proxy=new ProxyFactory(raw);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        engine=(H4EventEngine)proxy.getProxy();
        scheduler=new H4EventSettlementScheduler(db,engine);
        var config=new MybatisConfiguration(new Environment("notifications",new SpringManagedTransactionFactory(),ds));
        config.addMapper(NotificationCampaignMapper.class);
        notifications=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(NotificationCampaignMapper.class);
    }

    @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("DROP DATABASE "+schema);}

    @ParameterizedTest @CsvSource({"en,en,View activity","zh-CN,zh,查看活动","vi-VN,vi,Xem hoạt động"})
    void subscribedReminderBecomesVisibleOnceAtStart(String language,String locale,String cta){
        jdbc.update("UPDATE nx_user SET language=? WHERE id=7",language);
        assertThat(engine.remind(7L,CODE).getCode()).isZero();
        engine.remind(7L,CODE);
        scheduler.tick();
        assertThat(notifications.selectUserNotifications(7L,null,null,100)).isEmpty();
        startEvent();scheduler.tick();scheduler.tick();engine.notifyReminder(7L,CODE);
        assertThat(notifications.selectUserNotifications(7L,null,null,100)).singleElement().satisfies(n->{
            assertThat(n.kind()).isEqualTo("event");assertThat(n.title()).isEqualTo("title-"+locale);
            assertThat(n.body()).isEqualTo("body-"+locale);assertThat(n.ctaLabel()).isEqualTo(cta);
            assertThat(n.ctaHref()).isEqualTo("/pages/events/detail?id="+CODE);
        });
        assertThat(notifications.countUnreadForUser(7L)).isEqualTo(1);
        assertThat(notifications.selectUserNotifications(8L,null,null,100)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_notification",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_event_reminder WHERE notified_at IS NOT NULL",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT push_status,push_attempts,next_push_at,pushed_at FROM nx_notification"))
                .containsEntry("push_status","DELIVERED").containsEntry("push_attempts",0).containsEntry("next_push_at",null)
                .extractingByKey("pushed_at").isNotNull();
    }

    @Test void databaseFailureRemainsRetryableAndNeverMarksTheReminderNotified(){
        engine.remind(7L,CODE);startEvent();
        jdbc.execute("CREATE TRIGGER reject_h4_notification BEFORE INSERT ON nx_notification FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='reminder test failure'");
        scheduler.tick();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_event_reminder WHERE notified_at IS NULL",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_notification",Integer.class)).isZero();
        jdbc.execute("DROP TRIGGER reject_h4_notification");scheduler.tick();
        assertThat(notifications.selectUserNotifications(7L,null,null,100)).hasSize(1);
    }

    @Test void disabledNotificationPreferenceIsStillRespected(){
        engine.remind(7L,CODE);startEvent();
        jdbc.update("INSERT INTO nx_user_preference VALUES(7,0,0,1,1,1,1,1)");scheduler.tick();
        assertThat(notifications.selectUserNotifications(7L,null,null,100)).isEmpty();
        assertThat(notifications.countUnreadForUser(7L)).isZero();
        assertThat(jdbc.queryForObject("SELECT push_status FROM nx_notification",String.class)).isEqualTo("DELIVERED");
    }

    @Test void markerFailureRollsBackTheNotificationAndCanRetry(){
        engine.remind(7L,CODE);startEvent();
        jdbc.execute("CREATE TRIGGER reject_h4_marker BEFORE UPDATE ON nx_growth_event_reminder FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='marker test failure'");
        scheduler.tick();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_notification",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_event_reminder WHERE notified_at IS NULL",Integer.class)).isEqualTo(1);
        jdbc.execute("DROP TRIGGER reject_h4_marker");scheduler.tick();
        assertThat(notifications.selectUserNotifications(7L,null,null,100)).hasSize(1);
    }

    @ParameterizedTest @CsvSource({"regional,evt-regional-pk","holding,evt-nex-holders-share"})
    void detailProjectsOnlyThisUsersPaidPoolReward(String kind,String code){
        jdbc.update("INSERT INTO nx_growth_event_rule(event_code,kind,enabled,starts_at,ends_at,activated_at,settled_at,rules_json) VALUES(?,?,0,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 HOUR),DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 HOUR),UTC_TIMESTAMP(6),JSON_OBJECT())",code,kind);
        when(events.eventState(7L,"en")).thenReturn(List.of(Map.of("eventCode",code,"title","Pool","userStatus","CLAIMED")));
        jdbc.update("INSERT INTO nx_growth_event_pool_result(event_code,user_id,region_name,reward_usdt,status) VALUES(?,8,'CN',0.828040,'PAID')",code);
        assertThat(engine.detail(7L,code,"en").getData()).containsEntry("myRewardAmount",null);
        jdbc.update("INSERT INTO nx_growth_event_pool_result(event_code,user_id,region_name,reward_usdt,status) VALUES(?,7,'CN',0.171960,'READY')",code);
        assertThat(engine.detail(7L,code,"en").getData()).containsEntry("myRewardAmount",null);
        jdbc.update("UPDATE nx_growth_event_pool_result SET status='PAID' WHERE event_code=? AND user_id=7",code);
        var detail=engine.detail(7L,code,"en").getData();
        assertThat(detail.get("myRewardAmount")).isEqualTo(new java.math.BigDecimal("0.171960"));
        if("regional".equals(kind))assertThat(((List<?>)detail.get("results"))).singleElement()
                .satisfies(row->assertThat(((Map<?,?>)row).get("rewardAmount")).isEqualTo(new java.math.BigDecimal("1.000000")));
        jdbc.update("UPDATE nx_growth_event_pool_result SET reward_usdt=0 WHERE event_code=? AND user_id=7",code);
        assertThat(engine.detail(7L,code,"en").getData().get("myRewardAmount")).isEqualTo(new java.math.BigDecimal("0.000000"));
        jdbc.update("UPDATE nx_growth_event_rule SET kind='wheel' WHERE event_code=?",code);
        assertThat(engine.detail(7L,code,"en").getData()).containsEntry("myRewardAmount",null);
    }

    @Test void historicalRepairIsExactAndReplaySafe() throws Exception {
        jdbc.update("INSERT INTO nx_growth_event_reminder VALUES(?,7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),('other-event',7,NULL,UTC_TIMESTAMP(6))",CODE);
        String prefix="INSERT INTO nx_notification(biz_no,user_id,type,title,body,push_status,is_deleted) VALUES";
        jdbc.update(prefix+"('H4-REMINDER:evt-black-friday',7,'EVENT','old','old','SKIPPED',0),"
                +"('H4-REMINDER:evt-black-friday',8,'EVENT','other user','old','SKIPPED',0),"
                +"('H4-REMINDER:other-event',7,'EVENT','not notified','old','SKIPPED',0),"
                +"('OTHER',7,'EVENT','unrelated','old','SKIPPED',0),"
                +"('H4-REMINDER:deleted',7,'EVENT','deleted','old','SKIPPED',1),"
                +"('H4-REMINDER:other-type',7,'SYSTEM','other type','old','SKIPPED',0)");
        jdbc.update("INSERT INTO nx_growth_event_reminder VALUES('deleted',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),('other-type',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        String repair=Files.readString(Path.of("scripts/migrations/20261025_h4_reminder_visibility.sql"));
        assertThat(jdbc.update(repair)).isEqualTo(1);assertThat(jdbc.update(repair)).isZero();
        assertThat(notifications.selectUserNotifications(7L,null,null,100)).singleElement()
                .satisfies(n->assertThat(n.title()).isEqualTo("old"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_notification WHERE push_status='SKIPPED'",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_notification WHERE push_status IN ('QUEUED','PENDING')",Integer.class)).isZero();
    }

    private void startEvent(){jdbc.update("UPDATE nx_growth_event_rule SET starts_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE event_code=?",CODE);}
    private static String tableDdl(String sql,String table){int start=sql.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");return sql.substring(start,sql.indexOf(';',start)+1);}
}
