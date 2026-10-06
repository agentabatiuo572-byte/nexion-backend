package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.mapper.AppTeamInsightsMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;

/** Read the actual MySQL union and aggregate with group, legacy and rejected-source facts. */
@EnabledIfEnvironmentVariable(named="DIRECT_REFERRAL_RUNTIME", matches="1")
class SevenLayerAppInsightsMySqlRuntimeTest {
    private static final LocalDateTime AT=LocalDateTime.of(2026,10,5,12,0);
    private final String marker="SI-"+UUID.randomUUID().toString().substring(0,18);
    private final long owner=8_100_000_000L+Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),100_000_000L);
    private long nextEvent=owner*100;
    private JdbcTemplate jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void actualGroupsAndLegacyRowsAreUniqueFilteredAndHaveWholePeriodTotals() throws Exception {
        String url=System.getenv().getOrDefault("DIRECT_REFERRAL_MYSQL_URL",
            "jdbc:mysql://127.0.0.1:33335/direct_referral_acceptance_20261005?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:33335/direct_referral_acceptance_20261005?");
        var driver=new DriverManagerDataSource(url,"root","");
        var dataSource=new SingleConnectionDataSource(driver.getConnection(),true);
        try {
            jdbc=new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(33335);
            assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("direct_referral_acceptance_20261005");
            user(owner); user(owner+1); user(owner+2);
            long usdt=event("direct_purchase",1,"120","0","UNLOCKED");
            long nex=event("direct_purchase",1,"0","50","UNLOCKED");
            group("L1","direct_purchase",1,"120","50","UNLOCKED","PRODUCTION","",owner,AT,usdt,nex);
            group("L2","network",2,"30","300","COOLING","PRODUCTION","",owner,AT,null,null);
            event("network",3,"4","0","UNLOCKED"); event("network",3,"0","40","UNLOCKED");
            event("binary",1,"900","0","REVERSED"); event("binary",1,"700","0","RECOVERY_PENDING");
            group("WAIT","direct_purchase",1,"0","0","WAITING_CALCULATION","PRODUCTION","",owner,AT,null,null);
            group("REVERSED","direct_purchase",1,"99","99","REVERSED","PRODUCTION","",owner,AT,null,null);
            group("DEBT","direct_purchase",1,"88","88","RECOVERY_PENDING","PRODUCTION","",owner,AT,null,null);
            jdbc.update("UPDATE nx_direct_referral_settlement SET recovery_pending_usdt=42,recovery_pending_nex=88 WHERE settlement_no=?",marker+"-DEBT");
            group("DEVICE","direct_device_earning",1,"1000","1000","UNLOCKED","PRODUCTION","",owner,AT,null,null);
            group("ENV","direct_purchase",1,"1000","1000","UNLOCKED","SANDBOX","",owner,AT,null,null);
            group("RUN","direct_purchase",1,"1000","1000","UNLOCKED","PRODUCTION","other-run",owner,AT,null,null);
            group("OWNER","direct_purchase",1,"1000","1000","UNLOCKED","PRODUCTION","",owner+2,AT,null,null);
            group("FUTURE","direct_purchase",1,"1000","1000","UNLOCKED","PRODUCTION","",owner,AT.plusDays(2),null,null);
            group("L8","network",8,"1000","1000","UNLOCKED","PRODUCTION","",owner,AT,null,null);
            var cfg=new Configuration(new Environment("seven-layer-insights",new JdbcTransactionFactory(),dataSource));
            cfg.setMapUnderscoreToCamelCase(true);cfg.addMapper(AppTeamInsightsMapper.class);
            try(var session=new MybatisSqlSessionFactoryBuilder().build(cfg).openSession(true)) {
                var service=new AppTeamInsightsService(session.getMapper(AppTeamInsightsMapper.class),
                    mock(LeadershipPoolConfigGuard.class),mock(PlatformConfigFacade.class),new MockEnvironment());
                var all=service.unilevel(owner,"all",1,100,"2026-10-05T08:00:00Z","all",2).getData();
                assertThat(all).containsEntry("totalRows",7L);
                var summary=(Map<String,Object>)all.get("summary");
                money(summary,"amountUSDT","154");money(summary,"amountNEX","390");
                money(summary,"creditedUSDT","124");money(summary,"creditedNEX","90");
                money(summary,"pendingUSDT","30");money(summary,"pendingNEX","300");
                var events=(List<Map<String,Object>>)all.get("events");
                assertThat(events).hasSize(7);
                assertThat(events.stream().map(row->row.get("id"))).doesNotHaveDuplicates();
                assertThat(events).filteredOn(row->(marker+"-L1").equals(row.get("id"))).hasSize(1);
                var waiting=events.stream().filter(row->(marker+"-WAIT").equals(row.get("id"))).findFirst().orElseThrow();
                assertThat(waiting).containsEntry("status","waiting_calculation").containsEntry("nexUsdtPrice",null);
                var debt=events.stream().filter(row->(marker+"-DEBT").equals(row.get("id"))).findFirst().orElseThrow();
                money(debt,"recoveryPendingUSDT","42");
                var direct=service.unilevel(owner,"all",2,1,"2026-10-05T08:00:00Z","direct",2).getData();
                assertThat(direct).containsEntry("totalRows",4L);
                money((Map<String,Object>)direct.get("summary"),"amountUSDT","120");
                assertThat((List<Map<String,Object>>)direct.get("events")).hasSize(1)
                    .allSatisfy(row->assertThat(row).containsEntry("layer",1));
                var extended=service.unilevel(owner,"all",1,100,"2026-10-05T08:00:00Z","extended",2).getData();
                assertThat(extended).containsEntry("totalRows",3L);
                money((Map<String,Object>)extended.get("summary"),"amountUSDT","34");
                var commissions=service.commissions(owner,1,100,"2026-10-05T08:00:00Z").getData();
                money((Map<String,Object>)commissions.get("aggregate"),"totalUSDT","124");
                money((Map<String,Object>)commissions.get("aggregate"),"totalNEX","90");
                assertThat(commissions).containsEntry("totalRows",6L);
                long cancelledUsdt=event("network",4,"15","0","RECOVERY_PENDING");
                long validNex=event("network",4,"0","90","UNLOCKED");
                group("SINGLE","network",4,"15","90","RECOVERY_PENDING","PRODUCTION","",owner,AT,cancelledUsdt,validNex);
                jdbc.update("UPDATE nx_direct_referral_settlement SET cancelled_usdt=15,credited_usdt_at=?,credited_nex_at=?,recovery_pending_usdt=5 WHERE settlement_no=?",AT,AT,marker+"-SINGLE");
                // JDBC fixture writes bypass this mapper session; the next read represents a fresh HTTP request.
                session.clearCache();
                var partial=service.unilevel(owner,"all",1,100,"2026-10-05T08:00:00Z","all",2).getData();
                assertThat(partial).containsEntry("totalRows",8L);
                var partialSummary=(Map<String,Object>)partial.get("summary");
                money(partialSummary,"amountUSDT","154");money(partialSummary,"amountNEX","480");
                money(partialSummary,"creditedUSDT","124");money(partialSummary,"creditedNEX","180");
                money(partialSummary,"pendingUSDT","30");money(partialSummary,"pendingNEX","300");
                var partialRow=((List<Map<String,Object>>)partial.get("events")).stream()
                    .filter(row->(marker+"-SINGLE").equals(row.get("id"))).findFirst().orElseThrow();
                assertThat(partialRow).containsEntry("kind","unilevel")
                    .containsEntry("statusUSDT","recovery_pending").containsEntry("statusNEX","unlocked");
                money(partialRow,"amountUSDT","15");money(partialRow,"amountNEX","90");
                var netCommissions=service.commissions(owner,1,100,"2026-10-05T08:00:00Z").getData();
                money((Map<String,Object>)netCommissions.get("aggregate"),"totalUSDT","124");
                money((Map<String,Object>)netCommissions.get("aggregate"),"totalNEX","180");
                var directory=Path.of(System.getenv().getOrDefault("DIRECT_REFERRAL_EVIDENCE_DIR","target/seven-layer-runtime"));
                Files.createDirectories(directory);
                new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter()
                    .writeValue(directory.resolve("seven-layer-insights.json").toFile(),Map.of("passed",true,
                        "database","direct_referral_acceptance_20261005","port",33335,"marker",marker,
                        "all",all,"direct",direct,"extended",extended,"commissions",commissions,
                        "singleAssetReversal",partial,"netCommissions",netCommissions));
            }
        } finally { dataSource.destroy(); }
    }

    private void user(long id) {
        jdbc.update("INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,sandbox) VALUES(?,'00',?,'127.0.0.1','fixture','Projection',?,0)",id,String.valueOf(id),String.valueOf(id));
    }
    private long event(String kind,int layer,String usdt,String nex,String state) {
        long id=nextEvent++;
        jdbc.update("INSERT INTO nx_commission_event(id,user_id,commission_type,source_user_id,source_user_name,layer_no,order_no,order_amount_usd,amount_usdt,amount_nex,currency,status,created_at,unlock_at) VALUES(?,?,?,?,'Projection',?,?,1000,?,?,?,?,?,?)",
            id,owner,kind,owner+1,layer,marker+"-ORDER-"+kind+"-"+state,new BigDecimal(usdt),new BigDecimal(nex),new BigDecimal(usdt).signum()>0?"USDT":"NEX",state,AT,AT.plusDays(30));
        return id;
    }
    private void group(String suffix,String kind,int layer,String usdt,String nex,String state,String env,String run,long recipient,LocalDateTime at,Long usdtEvent,Long nexEvent) {
        jdbc.update("INSERT INTO nx_direct_referral_settlement(settlement_no,source_environment,run_id,source_type,source_ref,source_user_id,beneficiary_user_id,source_user_name,source_occurred_at,policy_version,policy_snapshot,basis_usdt,nex_usdt_price,amount_usdt,amount_nex,usdt_event_id,nex_event_id,status,release_at,created_at,layer_no) VALUES(?,?,?,?,?,?,?,'Projection',?,0,'{}',1000,?,?,?,?,?,?,?,?,?)",
            marker+"-"+suffix,env,run,kind,marker+"-"+suffix,owner+1,recipient,at,
            "WAITING_CALCULATION".equals(state)?null:new BigDecimal("0.1"),new BigDecimal(usdt),new BigDecimal(nex),usdtEvent,nexEvent,state,at.plusDays(30),at,layer);
    }
    private static void money(Map<String,Object> value,String key,String expected) {
        assertThat((BigDecimal)value.get(key)).as(key).isEqualByComparingTo(expected);
    }
}
