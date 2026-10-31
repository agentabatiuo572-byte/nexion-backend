package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.mapper.AppWalletBillsMapper;
import ffdd.opsconsole.market.mapper.NexMarketMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.team.dto.VRankBootstrapRequest;
import ffdd.opsconsole.team.infrastructure.MybatisTeamCommissionRepository;
import ffdd.opsconsole.team.mapper.AppTeamInsightsMapper;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import ffdd.opsconsole.team.web.AppVRankController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real, disposable MySQL; table definitions are copied from the repository SQL. */
@EnabledIfEnvironmentVariable(named="NEXION_DATA_MYSQL_RUNTIME", matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataBootstrapMySqlIntegrationTest {
    private final String database="outstanding_data_"+UUID.randomUUID().toString().replace("-","");
    private final int port=Integer.parseInt(System.getenv().getOrDefault("NEXION_DATA_MYSQL_PORT","33343"));
    private final Path evidence=Path.of(System.getenv().getOrDefault("NEXION_DATA_EVIDENCE_DIR","target/data-bootstrap-runtime"));
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private SqlSessionTemplate sessions;

    @BeforeAll void schema() throws Exception {
        Files.createDirectories(evidence);
        mysql("CREATE DATABASE "+database+" CHARACTER SET utf8mb4;",false);
        StringBuilder ddl=new StringBuilder("SET FOREIGN_KEY_CHECKS=0;\n");
        var names=new HashSet<String>();
        List<Path> sources=new ArrayList<>(); sources.add(Path.of("scripts/schema.sql"));
        try(var files=Files.list(Path.of("scripts/migrations"))) { sources.addAll(files.filter(p->p.toString().endsWith(".sql")&&!p.getFileName().toString().startsWith("20261101")).sorted().toList()); }
        var pattern=Pattern.compile("(?ms)^CREATE TABLE IF NOT EXISTS (nx_\\w+)\\s*\\(.*?\\) ENGINE=[^\\r\\n]+;");
        for(Path source:sources) {
            var matcher=pattern.matcher(Files.readString(source));
            while(matcher.find()) if(names.add(matcher.group(1))) ddl.append(matcher.group()).append('\n');
        }
        ddl.append("SET FOREIGN_KEY_CHECKS=1;\n");
        mysql(ddl.toString(),true);
        var dataSource=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:"+port+"/"+database+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
        jdbc=new JdbcTemplate(dataSource);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Configuration cfg=new Configuration(new Environment("real-mysql",new SpringManagedTransactionFactory(),dataSource));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(TeamCommissionMapper.class);cfg.addMapper(AppTeamInsightsMapper.class);
        cfg.addMapper(AppWalletBillsMapper.class);cfg.addMapper(NexMarketMapper.class);
        cfg.addMapper(ffdd.opsconsole.content.mapper.SupportLeaderboardPublicationMapper.class);
        sessions=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(cfg));
        mysql(Files.readString(Path.of("scripts/migrations/20261101_vrank_bootstrap.sql")),true);
        Files.writeString(evidence.resolve("database.txt"),database+" port="+port+"\n");
        Files.writeString(evidence.resolve("structured-asset-inventory.json"),new ObjectMapper().writeValueAsString(
                jdbc.queryForList("SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME IN ('asset','currency','from_asset','to_asset','payment_asset','reward_type','rank_currency','metric_code') ORDER BY TABLE_NAME,ORDINAL_POSITION")));
    }

    @AfterEach void reset() {
        A2ReplayContext.exitReplay();
        jdbc.update("DELETE FROM nx_support_leaderboard_latest");
        for(String table:List.of("nx_earnings_source_recovery","nx_earnings_release_entry","nx_wallet_asset_adjustment","nx_support_leaderboard_publication",
                "nx_support_payment_attribution","nx_earning_event","nx_commission_event","nx_price_index","nx_wallet_ledger","nx_v_rank_config","nx_v_rank_reward_rule")) jdbc.update("DELETE FROM "+table);
        jdbc.execute("DROP TABLE IF EXISTS nx_uvt_legacy_price_archive");
        for(String index:jdbc.queryForList("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_price_index' AND INDEX_NAME='test_uvt_quote'",String.class))
            jdbc.execute("ALTER TABLE nx_price_index DROP INDEX "+index);
    }

    @Test void oldAssetsReadThroughActualWalletAndMarketMappersAndRepeatPreservesExactFacts() throws Exception {
        legacyLedger("old", "NEX", "12.345678");
        jdbc.update("INSERT INTO nx_price_index(metric_code,metric_label,unit_label,price_usdt,sampled_at) VALUES('NEX_USDT','NEX price','USDT/NEX',2.125,'2026-10-01 00:00:00')");
        var before=jdbc.queryForMap("SELECT id,biz_no,amount,balance_after,created_at,updated_at FROM nx_wallet_ledger WHERE biz_no='old'");
        assertThat(sessions.getMapper(NexMarketMapper.class).latestNexUsdtPrice()).isNull();
        migration();
        assertThat(sessions.getMapper(NexMarketMapper.class).latestNexUsdtPrice()).isEqualByComparingTo("2.125");
        var rows=sessions.getMapper(AppWalletBillsMapper.class).rowsFiltered(1L,20,0,"UVT",null,null);
        assertThat(rows).hasSize(1);assertThat(rows.get(0).amount()).isEqualByComparingTo("12.345678");
        assertThat(jdbc.queryForMap("SELECT id,biz_no,amount,balance_after,created_at,updated_at FROM nx_wallet_ledger WHERE biz_no='old'")).isEqualTo(before);
        var first=jdbc.queryForList("SELECT * FROM nx_price_index");
        migration();
        assertThat(jdbc.queryForList("SELECT * FROM nx_price_index")).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT metric_label FROM nx_price_index",String.class)).isEqualTo("UVT price");
        Files.writeString(evidence.resolve("legacy-wallet-market.json"),new ObjectMapper().findAndRegisterModules().writeValueAsString(Map.of("walletBefore",before,"walletAfter",rows,"prices",first,"repeatEqual",true)));
    }

    @Test void priceUniqueConflictsArchiveEqualAndUnequalLegacyQuotesWithoutOverwritingTarget() throws Exception {
        jdbc.execute("ALTER TABLE nx_price_index ADD UNIQUE KEY test_uvt_quote(metric_code,sampled_at)");
        for(int n=1;n<=2;n++) {
            jdbc.update("INSERT INTO nx_price_index(metric_code,metric_label,unit_label,price_usdt,sampled_at) VALUES('NEX_USDT','NEX','NEX',?,?)",n,"2026-10-0"+n+" 00:00:00");
            jdbc.update("INSERT INTO nx_price_index(metric_code,metric_label,unit_label,price_usdt,sampled_at) VALUES('UVT_USDT','operator label','operator unit',?,?)",n==1?1:9,"2026-10-0"+n+" 00:00:00");
        }
        var target=jdbc.queryForList("SELECT * FROM nx_price_index WHERE metric_code='UVT_USDT' ORDER BY id");
        migration();migration();
        assertThat(jdbc.queryForList("SELECT * FROM nx_price_index ORDER BY id")).isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_uvt_legacy_price_archive",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT SUM(CAST(JSON_UNQUOTE(original_row->'$.price_usdt') AS DECIMAL(18,8))) FROM nx_uvt_legacy_price_archive",BigDecimal.class)).isEqualByComparingTo("3");
        jdbc.execute("ALTER TABLE nx_price_index DROP INDEX test_uvt_quote");
    }

    @Test void collidingFinancialKeysRollbackEveryRenameAndPreserveBothPostings() throws Exception {
        legacyLedger("same-business", "NEX", "10");legacyLedger("same-business", "UVT", "11");
        jdbc.update("INSERT INTO nx_price_index(metric_code,metric_label,unit_label,price_usdt) VALUES('NEX_USDT','NEX','NEX',2)");
        var before=jdbc.queryForList("SELECT * FROM nx_wallet_ledger ORDER BY id");
        assertThatThrownBy(this::migration).hasMessageContaining("UVT_LEGACY_MIGRATION_CONFLICT");
        assertThat(jdbc.queryForList("SELECT * FROM nx_wallet_ledger ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT metric_code FROM nx_price_index",String.class)).isEqualTo("NEX_USDT");
    }

    @Test void originalBugTablesAndRewardHistoryRetainEveryNonCodeFieldIncludingRawPayloadHashes() throws Exception {
        jdbc.update("INSERT INTO nx_earnings_release_entry(entry_no,user_id,source_type,source_ref,asset,amount,bucket,idempotency_key) VALUES('entry-old',1,'TASK','old-task','NEX',7.123456,'withdrawable','old-idem')");
        jdbc.update("""
                INSERT INTO nx_earnings_source_recovery(recovery_no,entry_no,user_id,source_type,source_ref,asset,source_environment,
                  requested,recovered,outstanding,cumulative_recovered,remaining_entitlement,bucket,balance_before,balance_after,
                  debit_counter_before,evidence_status,reason,actor)
                VALUES('recovery-old','entry-old',1,'TASK','old-task','NEX','PRODUCTION',1,0,1,0,7.123456,'withdrawable',20,20,0,'LEGACY_UNPROVEN','old reason','old actor')
                """);
        jdbc.update("""
                INSERT INTO nx_wallet_asset_adjustment(adjustment_no,user_id,asset,direction,amount,amount_usd,reason_code,reason,evidence_ref,idempotency_key,maker)
                VALUES('adjustment-old',1,'NEX','IN',5.987654,10,'TEST_FIXTURE','old reason','old-evidence','old-adjustment-idem','old maker')
                """);
        jdbc.update("""
                INSERT INTO nx_support_leaderboard_publication(stream_key,board,reference_month,currency,rank_currency,scope,groups_key,definition_version,source_version,view_version,comparison_key,evaluated_at,published_at,state,payload,payload_hash)
                VALUES(REPEAT('a',64),'EARNINGS','2026-10','NEX','NEX','GLOBAL','[]','old-definition','old-source','old-view',REPEAT('b',64),NOW(6),NOW(6),'COMPLETE','{"currency":"NEX"}',SHA2('{"currency":"NEX"}',256))
                """);
        jdbc.update("INSERT INTO nx_v_rank_reward_rule(reward_id,rank_code,reward_type,amount) VALUES('old-rule','V1','nex',17.25)");
        var columns=Map.of("nx_earnings_release_entry",List.of("asset"),"nx_earnings_source_recovery",List.of("asset"),
                "nx_wallet_asset_adjustment",List.of("asset"),"nx_support_leaderboard_publication",List.of("currency","rank_currency"),
                "nx_v_rank_reward_rule",List.of("reward_type"));
        var expected=new LinkedHashMap<String,List<Map<String,Object>>>();
        columns.forEach((table,fields)-> {
            var rows=jdbc.queryForList("SELECT * FROM "+table);
            for(var row:rows) for(String field:fields) row.put(field,field.equals("reward_type")?"uvt":"UVT");
            expected.put(table,rows);
        });
        migration();migration();
        expected.forEach((table,rows)->assertThat(jdbc.queryForList("SELECT * FROM "+table)).as(table).isEqualTo(rows));
        Files.writeString(evidence.resolve("legacy-original-tables.json"),new ObjectMapper().findAndRegisterModules().writeValueAsString(expected));
    }

    @Test void migratedImmutablePublicationIsReadByActualConsumerAndCorruptionStillFails() throws Exception {
        var now=java.time.Instant.parse("2026-10-09T01:00:00.123456Z");
        var oldContext=new ffdd.opsconsole.content.domain.SupportLeaderboard.Context(
                ffdd.opsconsole.content.domain.SupportLeaderboard.Board.netContribution,java.time.YearMonth.of(2026,10),java.time.YearMonth.of(2026,10),
                "NEX",ffdd.opsconsole.content.domain.SupportLeaderboard.Scope.all,Set.of(),ffdd.opsconsole.content.domain.SupportLeaderboard.NET_DEFINITION,now.minusSeconds(86400));
        var count=new ffdd.opsconsole.content.domain.SupportLeaderboard.Count(1L,ffdd.opsconsole.content.domain.SupportLeaderboard.Coverage.COMPLETE,ffdd.opsconsole.content.domain.SupportLeaderboard.Reason.NONE);
        var amount=new ffdd.opsconsole.content.domain.SupportLeaderboard.Amount(new BigDecimal("12.345678"),"NEX",oldContext.referenceMonth(),ffdd.opsconsole.content.domain.SupportLeaderboard.AmountKind.NET_CONTRIBUTION,
                ffdd.opsconsole.content.domain.SupportLeaderboard.Coverage.COMPLETE,ffdd.opsconsole.content.domain.SupportLeaderboard.Reason.NONE,"legacy-money-source");
        var snapshot=ffdd.opsconsole.content.domain.SupportLeaderboard.calculate(oldContext,"legacy-source",ffdd.opsconsole.content.domain.SupportLeaderboard.Coverage.COMPLETE,
                List.of(new ffdd.opsconsole.content.domain.SupportLeaderboard.Candidate(7,"Legacy agent",null,"Group",ffdd.opsconsole.content.domain.SupportLeaderboard.Qualification.ACTIVE,count,count,amount,null)));
        var json=new ObjectMapper().findAndRegisterModules();String payload=json.writeValueAsString(snapshot);
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String stream=ffdd.opsconsole.content.application.SupportLeaderboardPublicationService.streamKey(oldContext);
        jdbc.update("""
                INSERT INTO nx_support_leaderboard_publication(stream_key,board,rank_month,reference_month,currency,rank_currency,scope,groups_key,definition_version,source_version,view_version,comparison_key,evaluated_at,published_at,state,payload,payload_hash)
                VALUES(?,'netContribution','2026-10','2026-10','NEX','NEX','all','',?,?,?,?,?,?,'COMPLETE',?,?)
                """,stream,oldContext.definitionVersion(),snapshot.sourceVersion(),snapshot.viewVersion(),snapshot.comparisonKey(),
                java.time.LocalDateTime.ofInstant(oldContext.evaluatedAt(),java.time.ZoneOffset.UTC),java.time.LocalDateTime.ofInstant(oldContext.evaluatedAt().plusSeconds(1),java.time.ZoneOffset.UTC),payload,hash);
        long id=jdbc.queryForObject("SELECT id FROM nx_support_leaderboard_publication WHERE stream_key=?",Long.class,stream);
        jdbc.update("INSERT INTO nx_support_leaderboard_latest(stream_key,publication_id) VALUES(?,?)",stream,id);
        var service=new ffdd.opsconsole.content.application.SupportLeaderboardPublicationService(sessions.getMapper(ffdd.opsconsole.content.mapper.SupportLeaderboardPublicationMapper.class));
        var current=new ffdd.opsconsole.content.domain.SupportLeaderboard.Context(oldContext.board(),oldContext.rankMonth(),oldContext.referenceMonth(),"UVT",oldContext.scope(),oldContext.approvedGroupIds(),oldContext.definitionVersion(),now);
        var read=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));read.setReadOnly(true);read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(read.execute(s->service.latest(oldContext,snapshot.viewVersion())).snapshot()).isEqualTo(snapshot);
        assertThatThrownBy(()->read.execute(s->service.latest(current,snapshot.viewVersion()))).hasMessage("SUPPORT_LEADERBOARD_PUBLICATION_UNAVAILABLE");
        migration();
        assertThat(read.execute(s->service.latest(current,snapshot.viewVersion())).snapshot()).isEqualTo(snapshot);
        var previousDay=read.execute(s->service.previousBusinessDay(current));assertThat(previousDay).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT currency FROM nx_support_leaderboard_publication WHERE id=?",String.class,id)).isEqualTo("UVT");
        assertThat(jdbc.queryForObject("SELECT payload FROM nx_support_leaderboard_publication WHERE id=?",String.class,id)).isEqualTo(payload);
        assertThat(jdbc.queryForObject("SELECT payload_hash FROM nx_support_leaderboard_publication WHERE id=?",String.class,id)).isEqualTo(hash);
        jdbc.update("UPDATE nx_support_leaderboard_publication SET payload=? WHERE id=?",payload.replace("legacy-source","tampered-source"),id);
        assertThatThrownBy(()->read.execute(s->service.latest(current,snapshot.viewVersion()))).hasMessage("SUPPORT_LEADERBOARD_PUBLICATION_UNAVAILABLE");
        Files.writeString(evidence.resolve("immutable-publication-consumer.json"),json.writeValueAsString(Map.of("legacyVersion",snapshot.viewVersion(),"rawHash",hash,"migratedColumn","UVT","rawContext","NEX","consumerRead",true,"tamperRejected",true)));
    }

    @Test void migratedAttributionOriginalNexProofPassesBothActualConsumersButOtherCurrencyAndAmountDoNot() throws Exception {
        var json=new ObjectMapper();var at=java.time.LocalDateTime.parse("2026-10-08T10:00:00");var captured=java.time.LocalDateTime.parse("2026-10-08T02:00:01");
        var source=new LinkedHashMap<String,Object>();source.put("factId","DEPOSIT:501");source.put("customerId",1);source.put("ledgerId",501);
        source.put("kind","DEPOSIT");source.put("source","VIETQR");source.put("currency","NEX");source.put("amount",new BigDecimal("12.345678"));
        source.put("succeededAt",at.toString());source.put("businessZone","Asia/Shanghai");source.put("fractionalSecondDigits",0);
        var layer=new LinkedHashMap<String,Object>();layer.put("status","UNASSIGNED");layer.put("id",null);
        var evidence=new LinkedHashMap<String,Object>();evidence.put("schemaVersion","support-payment-attribution-v1");evidence.put("captureMode","NEW_SUCCESS");
        evidence.put("captureZone","UTC");evidence.put("captureDbUtc",captured.toString());evidence.put("sourcePartition",null);
        evidence.put("agent",layer);evidence.put("group",layer);evidence.put("owner",layer);
        String raw=json.writeValueAsString(source),evidenceJson=json.writeValueAsString(evidence);
        jdbc.update("""
                INSERT INTO nx_support_payment_attribution(fact_id,customer_id,kind,source,ledger_id,source_business_id,currency,amount,succeeded_at,source_business_zone,success_time_field,fractional_second_digits,
                  capture_db_utc,agent_status,group_status,owner_status,capture_mode,capture_schema_version,source_fact_json,attribution_evidence_json)
                VALUES('DEPOSIT:501',1,'DEPOSIT','VIETQR',501,'business501','NEX',12.345678,?,'Asia/Shanghai','nx_wallet_ledger.created_at',0,?,'UNASSIGNED','UNASSIGNED','UNASSIGNED','NEW_SUCCESS','support-payment-attribution-v1',?,?)
                """,at,captured,raw,evidenceJson);
        migration();
        var proof=jdbc.queryForObject("SELECT fact_id,source_partition,capture_db_utc,source_fact_json,attribution_evidence_json FROM nx_support_payment_attribution",(r,n)->
                new ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionProof(r.getString(1),r.getString(2),r.getObject(3,java.time.LocalDateTime.class),r.getString(4),r.getString(5)));
        var row=new ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionRow("DEPOSIT:501",1L,"DEPOSIT","VIETQR",501L,"business501",null,null,null,
                jdbc.queryForObject("SELECT currency FROM nx_support_payment_attribution",String.class),new BigDecimal("12.345678"),at,"Asia/Shanghai","nx_wallet_ledger.created_at",0,"NEW_SUCCESS","support-payment-attribution-v1",null,null,null,"UNASSIGNED","UNASSIGNED","UNASSIGNED");
        var fact=new ffdd.opsconsole.finance.facade.SupportPaymentFacts.Fact("DEPOSIT:501",ffdd.opsconsole.finance.facade.SupportPaymentFacts.Kind.DEPOSIT,
                ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source.VIETQR,List.of("nx_wallet_ledger:501"),1,501,"business501",null,null,null,"UVT",new BigDecimal("12.345678"),at,"nx_wallet_ledger.created_at",0,null,at,null,null,ffdd.opsconsole.finance.facade.SupportPaymentFacts.Status.UNKNOWN);
        for(String[] consumer:new String[][]{{"ffdd.opsconsole.content.application.SupportLeaderboardSourceService","savedProof"},{"ffdd.opsconsole.content.application.SupportAnalyticsService","netProof"}}) {
            Class<?> proofType=consumer[1].equals("savedProof")?ffdd.opsconsole.content.mapper.SupportLeaderboardMapper.AttributionProof.class:proof.getClass();
            var constructor=proofType.getConstructor(String.class,String.class,java.time.LocalDateTime.class,String.class,String.class);
            Object currentProof=constructor.newInstance(proof.factId(),proof.sourcePartition(),proof.captureDbUtc(),proof.sourceFactJson(),proof.attributionEvidenceJson());
            var method=Class.forName(consumer[0]).getDeclaredMethod(consumer[1],proofType,row.getClass(),fact.getClass());method.setAccessible(true);
            assertThat((Boolean)method.invoke(null,currentProof,row,fact)).as(consumer[0]).isTrue();
            for(String corrupted:List.of(proof.sourceFactJson().replace("NEX","USDT"),proof.sourceFactJson().replace("12.345678","12.345679"))) {
                var altered=constructor.newInstance(proof.factId(),proof.sourcePartition(),proof.captureDbUtc(),corrupted,proof.attributionEvidenceJson());
                assertThat((Boolean)method.invoke(null,altered,row,fact)).as(consumer[0]).isFalse();
            }
        }
        assertThat(json.readTree(proof.sourceFactJson())).isEqualTo(json.readTree(raw));
        Files.writeString(this.evidence.resolve("immutable-attribution-consumers.json"),json.writeValueAsString(Map.of("typedCurrency",row.currency(),"originalCurrency",json.readTree(proof.sourceFactJson()).path("currency").asText(),"bothConsumers",true,"currencyAndAmountTamperRejected",true)));
    }

    @Test void approvedBootstrapReturnsNineActualRanksAndExistingConfigurationCannotBeOverwritten() throws Exception {
        var audit=mock(AuditLogService.class);
        var service=new VRankBootstrapService(jdbc,audit);
        var context=new AuditReplayContext("checker","approved first configuration","vrank-initialize");
        var params=params();
        assertThatThrownBy(()->service.initialize(params,context)).hasMessage("A2_CONFIRMATION_REQUIRED");
        A2ReplayContext.enterReplay("owned-test-operation");
        transaction.execute(status->service.initialize(params,context));
        var controller=new AppVRankController(new MybatisTeamCommissionRepository(sessions.getMapper(TeamCommissionMapper.class)),
                mock(ffdd.opsconsole.team.domain.VRankPerformanceRepository.class),sessions.getMapper(AppTeamInsightsMapper.class),
                new org.springframework.mock.env.MockEnvironment(),null,null);
        var data=controller.ranks().getData();
        assertThat((List<?>)data.get("ranks")).hasSize(9);
        assertThat(jdbc.queryForList("SELECT rank_code FROM nx_v_rank_config ORDER BY sort_order",String.class)).containsExactly("V0","V1","V2","V3","V4","V5","V6","V7","V8");
        assertThatThrownBy(()->transaction.execute(status->service.initialize(params,context))).hasMessage("VRANK_BOOTSTRAP_REQUIRES_EMPTY_CONFIGURATION");
        verify(audit,times(1)).recordRequired(any());
        Files.writeString(evidence.resolve("fresh-vranks.json"),new ObjectMapper().writeValueAsString(data));
    }

    @Test void requiredAuditFailureRollsBackAllNineRanksAndAllowsApprovedRetry() {
        var audit=mock(AuditLogService.class);doThrow(new IllegalStateException("audit unavailable")).when(audit).recordRequired(any());
        var service=new VRankBootstrapService(jdbc,audit);
        A2ReplayContext.enterReplay("owned-test-operation");
        assertThatThrownBy(()->transaction.execute(status->service.initialize(params(),new AuditReplayContext("checker","reason","key")))).hasMessage("audit unavailable");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_v_rank_config",Integer.class)).isZero();
        org.mockito.Mockito.reset(audit);
        transaction.execute(status->service.initialize(params(),new AuditReplayContext("checker","reason","key")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_v_rank_config",Integer.class)).isEqualTo(9);
    }

    public static Map<String,Object> params() {
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<9;i++) {
            var row=new LinkedHashMap<String,Object>();row.put("rankCode","V"+i);row.put("titleCn","等级"+i);row.put("titleEn","Rank "+i);
            row.put("selfComputePoints",i*100L);row.put("directRefs",0);row.put("teamComputePoints",0L);
            row.put("requiredDownlineRank",null);row.put("requiredDownlineCount",0);row.put("unilevelDepth","0");
            row.put("peerBonusRate",BigDecimal.ZERO);row.put("leadershipVotes",0);row.put("rewards",List.of());rows.add(row);
        }
        return Map.of("ranks",rows);
    }
    private void legacyLedger(String biz,String asset,String amount) {
        jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status) VALUES(1,?,'TASK_REWARD',?,'IN',?,20,'SUCCESS')",biz,asset,new BigDecimal(amount));
    }
    private void migration() throws Exception {mysql(Files.readString(Path.of("scripts/migrations/20261101_uvt_legacy_asset_migration.sql")),true);}
    private void mysql(String sql,boolean withDatabase) throws Exception {
        var command=new ArrayList<>(List.of(System.getenv().getOrDefault("NEXION_DATA_MYSQL","D:/software/MySQL/MySQL Server 8.0/bin/mysql.exe"),"--no-defaults","--host=127.0.0.1","--port="+port,"--user=root","--default-character-set=utf8mb4","--binary-mode"));
        if(withDatabase)command.add("--database="+database);
        Path input=Files.createTempFile(evidence,"mysql-",".sql"),output=input.resolveSibling(input.getFileName()+".log");
        Files.writeString(input,sql);
        var process=new ProcessBuilder(command).redirectInput(input.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertThat(process.waitFor()).as(Files.readString(output)).isZero();
    }
}
