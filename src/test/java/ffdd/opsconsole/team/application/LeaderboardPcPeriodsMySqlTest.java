package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.team.mapper.LeaderboardPauseSnapshotMapper;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Executes the production one-statement capture and projection in a new owned MySQL schema. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD",matches=".+")
class LeaderboardPcPeriodsMySqlTest {
    Connection admin; String schema; LeaderboardPauseSnapshotService service;
    ObjectMapper json = new ObjectMapper();
    @BeforeEach void create() throws Exception {
        String url=System.getenv("NEXION_TEST_DB_SERVER_URL");
        assertThat(url).matches("jdbc:mysql://[^/]+/([?].*)?");
        String user=System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME","root"), password=System.getenv("NEXION_TEST_DB_PASSWORD");
        admin=DriverManager.getConnection(url,user,password);
        schema="nx_lb_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE DATABASE `"+schema+"`"); admin.setCatalog(schema);
        sql("CREATE TABLE nx_config_item(config_key VARCHAR(150),config_value TEXT,status INT,is_deleted INT)");
        sql("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,nickname VARCHAR(40),v_rank VARCHAR(10),sponsor_user_id BIGINT,status VARCHAR(20),is_deleted INT,sandbox INT)");
        sql("CREATE TABLE nx_commission_event(user_id BIGINT,amount_usdt DECIMAL(20,6),status VARCHAR(20),commission_type VARCHAR(40),created_at DATETIME,is_deleted INT)");
        sql("CREATE TABLE nx_team_member(id BIGINT,user_id BIGINT,member_user_id BIGINT,volume DECIMAL(20,6),is_deleted INT)");
        sql("CREATE TABLE nx_team_leaderboard_action(period VARCHAR(20),member_user_id BIGINT,action_type VARCHAR(20),reason VARCHAR(100),is_deleted INT)");
        sql("CREATE TABLE nx_risk_signal(user_id BIGINT,signal_type VARCHAR(60),created_at DATETIME,is_deleted INT)");
        sql("CREATE TABLE nx_user_device(user_id BIGINT,is_deleted INT)");
        sql("CREATE TABLE nx_page_snapshot(page_code VARCHAR(80),locale VARCHAR(20),snapshot_key VARCHAR(100),snapshot_value JSON,status VARCHAR(20),published_at DATETIME,is_deleted INT)");
        sql("INSERT INTO nx_config_item VALUES('team.ui.F.leaderboard.minUsd','1',1,0),('team.ui.F.leaderboard.poolUsd','1000',1,0),('team.ui.F.pool.periodPrize','{\"today\":10,\"week\":20,\"month\":30,\"allTime\":40}',1,0)");
        sql("INSERT INTO nx_user VALUES(1,'one','V1',NULL,'ACTIVE',0,0),(2,'inactive','V1',NULL,'DISABLED',0,0),(3,'sandbox','V1',NULL,'ACTIVE',0,1),(4,'risk','V1',NULL,'ACTIVE',0,0)");
        sql("INSERT INTO nx_team_member VALUES(1,1,1,99999,0),(2,1,2,88888,0),(3,1,3,77777,0)");
        int q=url.indexOf('?'); String fixtureUrl=url.substring(0,q<0?url.length():q)+schema+(q<0?"":url.substring(q));
        var cfg=new Configuration(new Environment("lb-periods",new SpringManagedTransactionFactory(),new DriverManagerDataSource(fixtureUrl,user,password)));
        cfg.addMapper(LeaderboardPauseSnapshotMapper.class);
        var mapper=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(cfg)).getMapper(LeaderboardPauseSnapshotMapper.class);
        service=new LeaderboardPauseSnapshotService(mapper,mock(PlatformConfigFacade.class),json);
        System.out.println("LEADERBOARD_FIXTURE_CREATE="+schema);
    }
    void sql(String sql) throws Exception {try(var s=admin.createStatement()){s.execute(sql);}}
    void event(long user,String amount,String status,LocalDateTime at) throws Exception {
        try(var s=admin.prepareStatement("INSERT INTO nx_commission_event VALUES(?, ?, ?, 'direct_purchase', ?,0)")) {
            s.setLong(1,user);s.setBigDecimal(2,new BigDecimal(amount));s.setString(3,status);s.setObject(4,at);s.executeUpdate();
        }
    }
    @AfterEach void drop() throws Exception {
        A2ReplayContext.exitReplay();
        if(admin!=null)try {if(schema!=null){sql("DROP DATABASE `"+schema+"`");System.out.println("LEADERBOARD_FIXTURE_DROP="+schema);}}finally{admin.close();}
    }
    @Test void actualFourPeriodQuerySeparatesHistoricalEarningsFromWeekAndNeverUsesMemberGv() throws Exception {
        var today=LocalDate.now(DateTimeFormatConfig.BUSINESS_ZONE);
        event(1,"195.80","UNLOCKED",today.minusMonths(2).atStartOfDay());
        event(1,"999","COOLING",today.atStartOfDay());
        event(2,"999","UNLOCKED",today.atStartOfDay());event(3,"999","UNLOCKED",today.atStartOfDay());
        var periods=service.pcLeaderboards(LeaderboardPauseSnapshotService.State.live());
        assertThat(periods.get("week")).containsEntry("participantCount",0).containsEntry("podium",List.of());
        assertThat((BigDecimal)periods.get("week").get("poolUsd")).isEqualByComparingTo("1000");
        assertThat(periods.get("all")).containsEntry("participantCount",1).containsEntry("topN",1);
        var podium=(List<Map<String,Object>>)periods.get("all").get("podium");
        assertThat(podium.get(0)).containsEntry("memberUserId",1L);
        assertThat(new BigDecimal(podium.get(0).get("gmvLabel").toString().substring(1))).isEqualByComparingTo("195.80");
        assertThat((BigDecimal)periods.get("all").get("poolUsd")).isEqualByComparingTo("40");
    }
    @Test void frozenPcAndAppKeepSamePeriodFactsWhileLiveConfigAndEventsChange() throws Exception {
        var today=LocalDate.now(DateTimeFormatConfig.BUSINESS_ZONE);
        event(1,"12.34","UNLOCKED",today.atStartOfDay());event(4,"100","UNLOCKED",today.atStartOfDay());
        sql("INSERT INTO nx_team_leaderboard_action VALUES('week',4,'DISQUALIFIED','fixture',0)");
        A2ReplayContext.enterReplay("fixture-pause-571");var frozen=service.capture();A2ReplayContext.exitReplay();
        var pc=service.pcLeaderboards(frozen);
        assertThat(pc.get("week")).containsEntry("participantCount",1);
        assertThat(frozen.appRows("week",json)).singleElement().satisfies(row->assertThat(row.earnedUsdt()).isEqualByComparingTo("12.34"));
        assertThat(pc.get("all")).containsEntry("participantCount",2);
        sql("UPDATE nx_config_item SET config_value='8000' WHERE config_key='team.ui.F.leaderboard.poolUsd'");
        event(1,"10","UNLOCKED",today.atStartOfDay());
        assertThat(service.pcLeaderboards(frozen)).isEqualTo(pc);
        var live=service.pcLeaderboards(LeaderboardPauseSnapshotService.State.live());
        assertThat((BigDecimal)live.get("week").get("poolUsd")).isEqualByComparingTo("8000");
        var livePodium=(List<Map<String,Object>>)live.get("week").get("podium");
        assertThat(new BigDecimal(livePodium.get(0).get("gmvLabel").toString().substring(1))).isEqualByComparingTo("22.34");
    }
}
