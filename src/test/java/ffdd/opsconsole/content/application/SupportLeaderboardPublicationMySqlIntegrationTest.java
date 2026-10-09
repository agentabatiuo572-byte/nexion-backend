package ffdd.opsconsole.content.application;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariDataSource;
import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.content.domain.SupportLeaderboard.*;
import ffdd.opsconsole.content.mapper.SupportLeaderboardPublicationMapper;
import ffdd.opsconsole.shared.config.MybatisPlusConfig;
import ffdd.opsconsole.shared.exception.BizException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Real publication SQL/transactions; aggregate and qualification values are synthetic fixtures, not financial-source capture. */
class SupportLeaderboardPublicationMySqlIntegrationTest {
    private static final String SERVER_UUID="3556ddae-c1a1-11f1-8853-a40c6626953d";
    private static final String PUBLICATIONS="nx_support_leaderboard_publication", POINTERS="nx_support_leaderboard_latest";
    private final String marker="slb-native-"+UUID.randomUUID().toString().replace("-","");
    private final long group=1_000_000_000_000L+(UUID.randomUUID().getMostSignificantBits() & 0x3fffffffffffL);
    private final long memberA=group+1,memberB=group+2;
    private final Set<String> streams=new LinkedHashSet<>();
    private final Set<Long> publicationConnections=ConcurrentHashMap.newKeySet();
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    private HikariDataSource dataSource,outsideDataSource;
    private JdbcTemplate jdbc,outside;
    private TransactionTemplate rr;
    private SupportLeaderboardPublicationService service;
    private Map<String,Long> beforeCounts;
    private Map<String,List<String>> beforeHashes;
    private List<String> beforeGrants;
    private volatile String fault;
    private volatile CountDownLatch racing;
    private ExecutorService publicationWorkers;
    private Instant now;

    @BeforeEach void requireExclusiveResourceAndRealSpringMyBatis() throws Exception {
        // Missing acceptance settings fail this candidate; no skipped test can count as native evidence.
        assertThat(required("SUPPORT_CAPTURE_MYSQL_ENABLED")).isEqualTo("true");
        var target=SupportRuntimeTarget.select(Map.of("SUPPORT_RUNTIME_TARGET","analytics-20261007"));
        String url=required("NEXION_DB_URL"),username=required("NEXION_DB_USERNAME"),password=required("NEXION_DB_PASSWORD");
        if(!url.startsWith(target.jdbcPrefix()) || !username.equals(target.username()))
            throw new IllegalStateException("Refusing non-allowlisted publication database before writes");
        Path proofPath=Path.of(required("SUPPORT_CAPTURE_OWNERSHIP")).toAbsolutePath().normalize();
        byte[] bytes=Files.readAllBytes(proofPath);var proof=json.readTree(bytes);
        assertThat(proof.path("databaseIdentity").path("serverUuid").asText()).isEqualTo(SERVER_UUID);
        var context=json.createObjectNode();context.put("schemaVersion",2).put("ownershipMode","EXCLUSIVE_ANALYTICS");
        context.set("resourceIdentity",proof.path("resourceIdentity").deepCopy());
        context.putObject("resourceOwnership").put("path",proofPath.toString()).put("sha256",hash(bytes));
        SupportExclusiveRuntimeOwnership.validate(context,target);
        dataSource=pool(url,username,password,6);outsideDataSource=pool(url,username,password,2);
        jdbc=new JdbcTemplate(dataSource);outside=new JdbcTemplate(outsideDataSource);
        SupportExclusiveRuntimeOwnership.requireActual(context,target,jdbc);
        SupportExclusiveRuntimeOwnership.requireActual(context,target,outside);
        assertThat(jdbc.queryForObject("SELECT @@server_uuid",String.class)).isEqualTo(SERVER_UUID);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("cs_analytics_20261007");
        assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(33337);
        for(String table:List.of(PUBLICATIONS,POINTERS))outside.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);
        assertThat(outside.queryForObject("SELECT COUNT(*) FROM "+PUBLICATIONS+" WHERE groups_key=?",Long.class,Long.toString(group))).isZero();
        now=outside.queryForObject("SELECT UTC_TIMESTAMP(6)",LocalDateTime.class).toInstant(ZoneOffset.UTC).minusSeconds(2);
        var configuration=new Configuration(new Environment("publication-native",new SpringManagedTransactionFactory(),dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultStatementTimeout(15);
        assertThat(configuration.getLocalCacheScope()).isEqualTo(LocalCacheScope.SESSION);
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());
        configuration.addMapper(SupportLeaderboardPublicationMapper.class);
        var template=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var actual=template.getMapper(SupportLeaderboardPublicationMapper.class);
        var intercepted=(SupportLeaderboardPublicationMapper)Proxy.newProxyInstance(actual.getClass().getClassLoader(),
            new Class<?>[]{SupportLeaderboardPublicationMapper.class},(proxy,method,args)->{
                if(method.getName().equals("ensurePointer")) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                    jdbc.execute((ConnectionCallback<Void>)c->{assertThat(c.getAutoCommit()).isFalse();assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);return null;});
                    publicationConnections.add(connectionId(jdbc));
                    CountDownLatch latch=racing;
                    if(latch!=null){latch.countDown();if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Concurrent publication barrier timed out");}
                }
                if(method.getName().equals("advance") && "CAS".equals(fault))return 0;
                try {
                    Object result=method.invoke(actual,args);
                    if(method.getName().equals("insert") && "INSERT".equals(fault))
                        throw new IllegalStateException("FIXTURE_FAILURE_AFTER_REAL_PUBLICATION_INSERT");
                    return result;
                } catch(InvocationTargetException ex){throw ex.getCause();}
            });
        var manager=new DataSourceTransactionManager(dataSource);
        rr=new TransactionTemplate(manager);rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);rr.setTimeout(30);
        var proxy=new ProxyFactory(new SupportLeaderboardPublicationService(intercepted));proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        service=(SupportLeaderboardPublicationService)proxy.getProxy();
        beforeCounts=counts();beforeHashes=tableHashes();beforeGrants=outside.queryForList("SHOW GRANTS",String.class).stream().sorted().toList();
    }

    @Test void realInsertFailureAndPointerCasFailureRollbackOnlyTheirNewVersion() {
        Snapshot first=aggregateFixture(context(Board.customers,"USDT",now,marker),20,10,Coverage.COMPLETE);
        Publication initial=publish(first);var rows=ownedRows();
        Snapshot correction=aggregateFixture(context(Board.customers,"USDT",now.plusMillis(1),marker),10,20,Coverage.COMPLETE);
        try {
            fault="INSERT";
            assertThatThrownBy(()->publish(correction)).hasMessage("FIXTURE_FAILURE_AFTER_REAL_PUBLICATION_INSERT");
            assertThat(ownedRows()).isEqualTo(rows);assertPointer(first,initial.publicationId());
            fault="CAS";deny(503,()->publish(correction));
            assertThat(ownedRows()).isEqualTo(rows);assertPointer(first,initial.publicationId());
            Snapshot emptyStream=aggregateFixture(context(Board.customers,"USD",now,marker),20,10,Coverage.COMPLETE);
            deny(503,()->publish(emptyStream));
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM "+POINTERS+" WHERE stream_key=?",Long.class,key(emptyStream))).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM "+PUBLICATIONS+" WHERE stream_key=?",Long.class,key(emptyStream))).isZero();
        } finally {fault=null;}
    }

    @Test void concurrentFirstPublishersCommitOneImmutableVersionAndReplayItsSameId() throws Exception {
        Snapshot snapshot=aggregateFixture(context(Board.customers,"USDT",now,marker),20,10,Coverage.COMPLETE);
        streams.add(key(snapshot));racing=new CountDownLatch(2);var workers=Executors.newFixedThreadPool(2);publicationWorkers=workers;
        try {
            var one=workers.submit(()->service.publish(snapshot));var two=workers.submit(()->service.publish(snapshot));
            Publication a=one.get(25,TimeUnit.SECONDS),b=two.get(25,TimeUnit.SECONDS);
            assertThat(a).isEqualTo(b);assertThat(publicationConnections).hasSize(2);assertPointer(snapshot,a.publicationId());
            assertThat(ownedRows()).hasSize(1);racing=null;
            var rows=ownedRows();assertThat(publish(snapshot)).isEqualTo(a);assertThat(ownedRows()).isEqualTo(rows);
        } finally {
            racing=null;workers.shutdown();
            if(!workers.awaitTermination(35,TimeUnit.SECONDS)){workers.shutdownNow();assertThat(workers.awaitTermination(15,TimeUnit.SECONDS)).isTrue();}
        }
    }

    @Test void lateEvaluationRejects409WhileLatestAndEarlierReplayRemainCommitted() {
        Snapshot older=aggregateFixture(context(Board.customers,"USDT",now,marker),20,10,Coverage.COMPLETE);
        Publication a=publish(older);
        Snapshot newest=aggregateFixture(context(Board.customers,"USDT",now.plusMillis(2),marker),10,20,Coverage.COMPLETE);
        Publication b=publish(newest);var rows=ownedRows();
        Snapshot late=aggregateFixture(context(Board.customers,"USDT",now.plusMillis(1),marker),30,10,Coverage.COMPLETE);
        deny(409,()->publish(late));assertPointer(newest,b.publicationId());assertThat(ownedRows()).isEqualTo(rows);
        assertThat(publish(older)).isEqualTo(a);assertPointer(newest,b.publicationId());assertThat(ownedRows()).isEqualTo(rows);
        rr.executeWithoutResult(tx->{tx.setRollbackOnly();assertThat(service.latest(newest.context(),newest.viewVersion())).isEqualTo(b);
            deny(409,()->service.latest(newest.context(),older.viewVersion()));
            deny(409,()->service.latest(context(Board.customers,"USDT",now,marker+"-different"),null));});
    }

    @Test void outerRepeatableReadCannotRereadRequiresNewCommitButReturnedPublicationSurvivesOuterRollback() {
        Snapshot snapshot=aggregateFixture(context(Board.customers,"USDT",now,marker),20,10,Coverage.COMPLETE);
        Publication[] supplied=new Publication[1];long[] outerConnection=new long[1];
        rr.executeWithoutResult(tx->{
            tx.setRollbackOnly();outerConnection[0]=connectionId(jdbc);
            deny(503,()->service.latest(snapshot.context(),null));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+PUBLICATIONS+" WHERE stream_key=?",Long.class,key(snapshot))).isZero();
            supplied[0]=publish(snapshot);
            assertThat(publicationConnections).doesNotContain(outerConnection[0]);
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM "+PUBLICATIONS+" WHERE id=?",Long.class,supplied[0].publicationId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+PUBLICATIONS+" WHERE stream_key=?",Long.class,key(snapshot))).isZero();
            deny(503,()->service.latest(snapshot.context(),null));
            assertThat(SupportLeaderboard.page(supplied[0].snapshot(),null,1,20,null,memberA).rows()).hasSize(2);
            assertThat(connectionId(jdbc)).isEqualTo(outerConnection[0]);
        });
        assertPointer(snapshot,supplied[0].publicationId());
        Publication reread=rr.execute(tx->service.latest(snapshot.context(),snapshot.viewVersion()));
        assertThat(reread).isEqualTo(supplied[0]);
    }

    @Test void databaseUtcMicrosecondClockAndDecodedPayloadRetainEveryReplayBinding() throws Exception {
        Snapshot snapshot=aggregateFixture(context(Board.deposit,"USDT",now,marker),20,10,Coverage.COMPLETE);
        Instant before=outside.queryForObject("SELECT UTC_TIMESTAMP(6)",LocalDateTime.class).toInstant(ZoneOffset.UTC);
        Publication p=publish(snapshot);
        Instant after=outside.queryForObject("SELECT UTC_TIMESTAMP(6)",LocalDateTime.class).toInstant(ZoneOffset.UTC);
        assertThat(p.publishedAt()).isBetween(before,after).isAfterOrEqualTo(now);
        assertThat(p.publishedAt().getNano()%1000).isZero();assertThat(jdbc.queryForObject("SELECT @@session.time_zone",String.class)).isEqualTo("+08:00");
        var stored=outside.queryForMap("SELECT * FROM "+PUBLICATIONS+" WHERE id=?",p.publicationId());
        String payload=(String)stored.get("payload");assertThat(json.readValue(payload,Snapshot.class)).isEqualTo(snapshot);
        assertThat(stored).containsEntry("stream_key",key(snapshot)).containsEntry("board","deposit")
            .containsEntry("rank_month",snapshot.context().rankMonth().toString()).containsEntry("reference_month",snapshot.context().referenceMonth().toString())
            .containsEntry("currency","USDT").containsEntry("rank_currency","USDT").containsEntry("scope","ownGroup")
            .containsEntry("groups_key",Long.toString(group)).containsEntry("definition_version",marker)
            .containsEntry("source_version",snapshot.sourceVersion()).containsEntry("view_version",snapshot.viewVersion())
            .containsEntry("comparison_key",snapshot.comparisonKey()).containsEntry("state","COMPLETE")
            .containsEntry("payload_hash",hash(payload.getBytes(StandardCharsets.UTF_8)));
        assertThat(outside.queryForObject("SELECT evaluated_at FROM "+PUBLICATIONS+" WHERE id=?",LocalDateTime.class,p.publicationId())).isEqualTo(LocalDateTime.ofInstant(now,ZoneOffset.UTC));
        assertThat(outside.queryForObject("SELECT published_at FROM "+PUBLICATIONS+" WHERE id=?",LocalDateTime.class,p.publicationId())).isEqualTo(LocalDateTime.ofInstant(p.publishedAt(),ZoneOffset.UTC));
        assertThat(publish(snapshot)).isEqualTo(p);
        Publication replay=rr.execute(tx->service.latest(snapshot.context(),snapshot.viewVersion()));
        assertThat(replay.snapshot()).isEqualTo(snapshot);
    }

    @Test void previousDayUsesCompleteOwnedScopeBoardPeriodCurrencyAndLastTimeThenIdWithoutRewritingYesterday() throws Exception {
        LocalDate today=now.atZone(SupportLeaderboard.BUSINESS_ZONE).toLocalDate();
        Instant from=today.minusDays(1).atStartOfDay(SupportLeaderboard.BUSINESS_ZONE).toInstant(),tieAt=from.plusSeconds(7200);
        Snapshot oldDefinition=aggregateFixture(context(Board.customers,"USDT",from.plusSeconds(100),marker+"-old"),20,10,Coverage.COMPLETE);
        Publication old=historicalAggregateFixture(oldDefinition,from.plusSeconds(200));
        Snapshot firstTie=aggregateFixture(context(Board.customers,"USD",from.plusSeconds(300),marker),20,10,Coverage.COMPLETE);
        Publication first=historicalAggregateFixture(firstTie,tieAt);
        Snapshot lastTie=aggregateFixture(context(Board.customers,"USDT",from.plusSeconds(400),marker),10,20,Coverage.COMPLETE);
        Publication last=historicalAggregateFixture(lastTie,tieAt);
        historicalAggregateFixture(aggregateFixture(context(Board.customers,"USD",from.plusSeconds(500),marker),30,10,Coverage.PARTIAL),tieAt.plusSeconds(100));
        Publication amountBaseline=historicalAggregateFixture(aggregateFixture(context(Board.deposit,"USDT",from.plusSeconds(500),marker),30,10,Coverage.COMPLETE),tieAt.plusSeconds(100));
        YearMonth historicalMonth=amountBaseline.snapshot().context().rankMonth().minusMonths(1);
        Context priorPeriod=new Context(Board.deposit,historicalMonth,historicalMonth,"USDT",Scope.ownGroup,Set.of(group),marker,from.plusSeconds(700));
        historicalAggregateFixture(aggregateFixture(priorPeriod,30,10,Coverage.COMPLETE),tieAt.plusSeconds(200));
        Context managed=new Context(Board.customers,null,YearMonth.from(from.atZone(SupportLeaderboard.BUSINESS_ZONE)),"USDT",Scope.managedGroups,Set.of(group),marker,from.plusSeconds(600));
        historicalAggregateFixture(aggregateFixture(managed,30,10,Coverage.COMPLETE),tieAt.plusSeconds(100));
        Context other=new Context(Board.customers,null,YearMonth.from(from.atZone(SupportLeaderboard.BUSINESS_ZONE)),"USDT",Scope.ownGroup,Set.of(group+10),marker,from.plusSeconds(600));
        historicalAggregateFixture(aggregateFixture(other,30,10,Coverage.COMPLETE),tieAt.plusSeconds(100));
        Snapshot current=aggregateFixture(context(Board.customers,"USDT",now,marker),20,10,Coverage.COMPLETE);
        List<Publication> baselines=rr.execute(tx->service.previousBusinessDay(current.context()));
        assertThat(baselines).extracting(Publication::publicationId).containsExactly(old.publicationId(),first.publicationId(),last.publicationId());
        Snapshot moved=SupportLeaderboard.withMovement(current,baselines);
        Row row=moved.rows().stream().filter(r->r.agentId()==memberA).findFirst().orElseThrow();
        assertThat(row.movement()).isEqualTo(new Movement(MovementKind.UP,1,2,last.snapshot().context().evaluatedAt(),last.snapshot().viewVersion(),Reason.NONE));
        Snapshot changed=aggregateFixture(context(Board.customers,"USDT",now,marker+"-different"),20,10,Coverage.COMPLETE);
        assertThat(SupportLeaderboard.withMovement(changed,rr.execute(tx->service.previousBusinessDay(changed.context()))).rows())
            .allSatisfy(r->assertThat(r.movement().reason()).isEqualTo(Reason.DEFINITION_CHANGED));
        var yesterday=outside.queryForList("SELECT * FROM "+PUBLICATIONS+" WHERE groups_key=? AND published_at<? ORDER BY id",Long.toString(group),LocalDateTime.ofInstant(today.atStartOfDay(SupportLeaderboard.BUSINESS_ZONE).toInstant(),ZoneOffset.UTC));
        publish(moved);
        publish(aggregateFixture(context(Board.customers,"USDT",now.plusMillis(1),marker),30,10,Coverage.COMPLETE));
        List<Publication> unchanged=rr.execute(tx->service.previousBusinessDay(current.context()));
        assertThat(unchanged).isEqualTo(baselines);
        assertThat(outside.queryForList("SELECT * FROM "+PUBLICATIONS+" WHERE groups_key=? AND published_at<? ORDER BY id",Long.toString(group),LocalDateTime.ofInstant(today.atStartOfDay(SupportLeaderboard.BUSINESS_ZONE).toInstant(),ZoneOffset.UTC))).isEqualTo(yesterday);
        // Amount boards retain currency and period, unlike count-board reference currency/month.
        Context amountCurrent=context(Board.deposit,"USDT",now,marker);
        List<Publication> amounts=rr.execute(tx->service.previousBusinessDay(amountCurrent));
        assertThat(amounts).extracting(Publication::publicationId).containsExactlyElementsOf(amountCurrent.rankMonth().equals(amountBaseline.snapshot().context().rankMonth())?List.of(amountBaseline.publicationId()):List.of());
        List<Publication> otherCurrency=rr.execute(tx->service.previousBusinessDay(context(Board.deposit,"USD",now,marker)));
        assertThat(otherCurrency).isEmpty();
        // Precise owned history across a month boundary proves count reference-month independence even mid-month.
        Instant boundary=today.withDayOfMonth(1).atTime(12,0).atZone(SupportLeaderboard.BUSINESS_ZONE).toInstant();
        Instant prior=boundary.minusSeconds(13*3600);
        Publication priorReference=historicalAggregateFixture(aggregateFixture(context(Board.customers,"USD",prior,marker),10,20,Coverage.COMPLETE),prior.plusSeconds(60));
        Snapshot boundaryCurrent=aggregateFixture(context(Board.customers,"USDT",boundary,marker),20,10,Coverage.COMPLETE);
        List<Publication> boundaryHistory=rr.execute(tx->service.previousBusinessDay(boundaryCurrent.context()));
        assertThat(boundaryHistory).extracting(Publication::publicationId).contains(priorReference.publicationId());
        assertThat(priorReference.snapshot().context().referenceMonth()).isNotEqualTo(boundaryCurrent.context().referenceMonth());
        assertThat(SupportLeaderboard.withMovement(boundaryCurrent,boundaryHistory).rows()).allSatisfy(r->assertThat(r.movement().baselineVersion()).isEqualTo(priorReference.snapshot().viewVersion()));
    }

    @AfterEach void exactOwnedCommittedCleanupAndIndependentFullTableReadbackAlwaysClosePools() throws Throwable {
        Throwable failure=null;
        try {
            if(publicationWorkers!=null && !publicationWorkers.isTerminated())
                throw new IllegalStateException("Native writers still active; preserving owned publication fixtures");
            if(beforeCounts!=null)cleanupOwned();
        }catch(Throwable ex){failure=ex;}
        try {
            if(beforeCounts!=null && (publicationWorkers==null || publicationWorkers.isTerminated())){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(counts()).isEqualTo(beforeCounts);assertThat(tableHashes()).isEqualTo(beforeHashes);
                assertThat(outside.queryForList("SHOW GRANTS",String.class).stream().sorted().toList()).isEqualTo(beforeGrants);}
        }catch(Throwable ex){if(failure==null)failure=ex;else failure.addSuppressed(ex);}
        try {if(outsideDataSource!=null)outsideDataSource.close();}finally{if(dataSource!=null)dataSource.close();}
        if(failure!=null)throw failure;
    }

    private Context context(Board board,String currency,Instant at,String definition) {
        YearMonth month=YearMonth.from(at.atZone(SupportLeaderboard.BUSINESS_ZONE));
        return new Context(board,board==Board.customers?null:month,month,currency,Scope.ownGroup,Set.of(group),definition,at.truncatedTo(ChronoUnit.MICROS));
    }
    private Snapshot aggregateFixture(Context c,long a,long b,Coverage coverage) {
        // Opaque aggregate members are not nx_user rows; qualification proof is declared synthetic, never a real birth fact.
        var rows=new ArrayList<Candidate>();
        for(long[] pair:List.of(new long[]{memberA,a},new long[]{memberB,b}))rows.add(new Candidate(pair[0],marker+pair[0],null,marker,
            Qualification.ACTIVE,new Count(pair[1],Coverage.COMPLETE,Reason.NONE),new Count(pair[1],Coverage.COMPLETE,Reason.NONE),
            new Amount(BigDecimal.valueOf(pair[1]),c.currency(),c.referenceMonth(),c.board()==Board.purchase?AmountKind.PURCHASE:AmountKind.DEPOSIT,Coverage.COMPLETE,Reason.NONE,marker+"-amount"),c.evaluatedAt().minusSeconds(86400)));
        return SupportLeaderboard.calculate(c,marker+"-aggregate",coverage,rows);
    }
    private Publication publish(Snapshot snapshot){streams.add(key(snapshot));return service.publish(snapshot);}
    private String key(Snapshot snapshot){return SupportLeaderboardPublicationService.streamKey(snapshot.context());}
    private Publication historicalAggregateFixture(Snapshot snapshot,Instant publishedAt) throws Exception {
        String stream=key(snapshot),payload=json.writeValueAsString(snapshot);streams.add(stream);Context c=snapshot.context();
        assertThat(outside.update("INSERT INTO "+PUBLICATIONS+" (stream_key,board,rank_month,reference_month,currency,rank_currency,scope,groups_key,definition_version,source_version,view_version,comparison_key,evaluated_at,published_at,state,payload,payload_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            stream,c.board().name(),c.rankMonth()==null?null:c.rankMonth().toString(),c.referenceMonth().toString(),c.currency(),c.rankCurrency(),c.scope().name(),String.join(",",c.approvedGroupIds().stream().sorted().map(Object::toString).toList()),c.definitionVersion(),snapshot.sourceVersion(),snapshot.viewVersion(),snapshot.comparisonKey(),LocalDateTime.ofInstant(c.evaluatedAt(),ZoneOffset.UTC),LocalDateTime.ofInstant(publishedAt,ZoneOffset.UTC),snapshot.state().name(),payload,hash(payload.getBytes(StandardCharsets.UTF_8)))).isEqualTo(1);
        long id=outside.queryForObject("SELECT id FROM "+PUBLICATIONS+" WHERE stream_key=? AND view_version=? AND source_version=?",Long.class,stream,snapshot.viewVersion(),snapshot.sourceVersion());
        return new Publication(id,publishedAt,snapshot);
    }
    private List<Map<String,Object>> ownedRows(){return outside.queryForList("SELECT * FROM "+PUBLICATIONS+" WHERE groups_key IN (?,?) ORDER BY id",Long.toString(group),Long.toString(group+10));}
    private void assertPointer(Snapshot snapshot,long id){assertThat(outside.queryForObject("SELECT publication_id FROM "+POINTERS+" WHERE stream_key=?",Long.class,key(snapshot))).isEqualTo(id);}
    private void cleanupOwned() {
        var writer=new TransactionTemplate(new DataSourceTransactionManager(outsideDataSource));
        writer.executeWithoutResult(tx->{
            for(String stream:streams){
                var rows=outside.queryForList("SELECT * FROM "+PUBLICATIONS+" WHERE stream_key=?",stream);
                for(var row:rows){assertThat(String.valueOf(row.get("definition_version"))).startsWith(marker);assertThat(row.get("source_version")).isEqualTo(marker+"-aggregate");
                    assertThat(row.get("payload_hash")).isEqualTo(hash(String.valueOf(row.get("payload")).getBytes(StandardCharsets.UTF_8)));}
                Long pointer=outside.query("SELECT publication_id FROM "+POINTERS+" WHERE stream_key=?",rs->rs.next()?(Long)rs.getObject(1):null,stream);
                if(pointer!=null)assertThat(rows.stream().map(row->((Number)row.get("id")).longValue()).toList()).contains(pointer);
                outside.update("DELETE FROM "+POINTERS+" WHERE stream_key=? AND publication_id <=> ?",stream,pointer);
                for(var row:rows)assertThat(outside.update("DELETE FROM "+PUBLICATIONS+" WHERE id=? AND stream_key=? AND source_version=? AND view_version=? AND payload_hash=?",row.get("id"),stream,row.get("source_version"),row.get("view_version"),row.get("payload_hash"))).isEqualTo(1);
            }
        });
    }
    private Map<String,Long> counts(){var counts=new TreeMap<String,Long>();for(String table:tables())counts.put(table,outside.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Long.class));return counts;}
    private Map<String,List<String>> tableHashes(){var hashes=new TreeMap<String,List<String>>();for(String table:tables())hashes.put(table,outside.queryForList("SELECT * FROM `"+table+"`").stream().map(row->{try{return hash(json.writeValueAsBytes(row));}catch(Exception ex){throw new IllegalStateException("Cannot fingerprint protected row",ex);}}).sorted().toList());return hashes;}
    private List<String> tables(){var tables=outside.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name",String.class);assertThat(tables).allMatch(name->name.matches("[A-Za-z0-9_]+"));return tables;}
    private static long connectionId(JdbcTemplate source){return source.queryForObject("SELECT CONNECTION_ID()",Long.class);}
    private static void deny(int code,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,ex->assertThat(ex.getCode()).isEqualTo(code));}
    private static HikariDataSource pool(String url,String username,String password,int size){var pool=new HikariDataSource();pool.setJdbcUrl(url);pool.setUsername(username);pool.setPassword(password);pool.setMaximumPoolSize(size);pool.setMinimumIdle(0);pool.setConnectionTimeout(10000);pool.setConnectionInitSql("SET time_zone = '+08:00'");return pool;}
    private static String required(String name){String value=System.getProperty(name);if(value==null||value.isBlank())value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("NOT_RUN: missing isolated publication acceptance setting "+name);return value;}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception ex){throw new IllegalStateException(ex);}}
}
