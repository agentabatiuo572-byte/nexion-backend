package ffdd.opsconsole.device.compute.v2;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Starts and destroys only an isolated Redis process owned by this test. No existing Redis data is touched. */
@Timeout(60)
@EnabledIfEnvironmentVariable(named="COMPUTE_V2_REDIS_IT",matches="true")
class ComputeV2RedisIntegrationTest {
    private static Process process;
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private ComputeV2Properties config;
    private ComputeV2RedisStore store;
    private static final Instant NOW = Instant.parse("2026-11-01T01:00:00Z");
    private static final String HASH = "a".repeat(64);

    @BeforeAll static void startRedis() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path directory = Files.createTempDirectory(Path.of("target"),"compute-v2-redis-");
        process = new ProcessBuilder("D:/software/Redis-8.6.1/redis-server.exe","--bind","127.0.0.1",
                "--port",Integer.toString(port),"--save","","--appendonly","no","--dir",directory.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
        BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream()));
        assertTrue(output.lines().anyMatch(line -> line.contains("Ready to accept connections")));
        connections = new LettuceConnectionFactory("127.0.0.1",port); connections.afterPropertiesSet(); connections.start();
        redis = new StringRedisTemplate(connections); redis.afterPropertiesSet();
        assertEquals("PONG",connections.getConnection().ping());
    }
    @AfterAll static void stopRedis() throws Exception {
        if (connections != null) connections.destroy();
        if (process != null) { process.destroy(); process.waitFor(10,TimeUnit.SECONDS); assertFalse(process.isAlive()); }
    }
    @BeforeEach void reset() {
        connections.getConnection().serverCommands().flushDb();
        config = new ComputeV2Properties(); config.setDeploymentScope("REDIS-INTEGRATION");
        store = new ComputeV2RedisStore(redis,JsonMapper.builder().addModule(new JavaTimeModule()).build(),config);
        store.ready(true); store.execute("initialize",Long.toString(NOW.toEpochMilli()/60000));
    }
    @Test void sixThousandManagedDevicesAndSixRealPermits() {
        for (int device = 1; device <= 6000; device++) {
            reserve(device,"T"+device,"R"+device);
            store.apply(event(device,"T"+device,1,Kind.ISSUED,task("R"+device),null),NOW);
        }
        assertEquals(6000L,redis.opsForHash().size(store.key("devices")));
        assertThrows(RuntimeException.class,() -> reserve(6001,"OVER","OVER"));
        for (int device = 1; device <= 6; device++) {
            String taskNo = store.sorted("queue").iterator().next();
            long actualDevice = Long.parseLong(taskNo.substring(1));
            var started = event(actualDevice,taskNo,1,Kind.STARTED,task("R"+actualDevice).withStarted(NOW,NOW.plusSeconds(120),NOW.plusSeconds(120)),null);
            assertFalse(store.execute("start",taskNo,"1","executor-"+device,Long.toString(NOW.plusSeconds(120).toEpochMilli()),store.encode(started),"6",Long.toString(NOW.toEpochMilli()),"300000","30000","1").isEmpty());
            store.apply(started,NOW);
        }
        assertEquals("",store.execute("start","T7","1","executor-7",Long.toString(NOW.plusSeconds(120).toEpochMilli()),"{}","6",Long.toString(NOW.toEpochMilli()),"300000","30000","1"));
        assertEquals(6L,redis.opsForSet().size(store.key("permits")));
        assertEquals("QUEUED",store.field("states","T7"));
    }
    @Test void duplicateCompletionIsExactOnceAndOldGenerationCannotChangeCurrentTask() throws Exception {
        reserve(1,"T1","R1"); var task = task("R1").withStarted(NOW,NOW.plusSeconds(120),NOW.plusSeconds(120));
        store.apply(event(1,"T1",1,Kind.ISSUED,task,null),NOW);
        var start = event(1,"T1",1,Kind.STARTED,task,null);
        store.execute("start","T1","1","executor",Long.toString(NOW.plusSeconds(120).toEpochMilli()),store.encode(start),"6",Long.toString(NOW.toEpochMilli()),"300000","30000","1"); store.apply(start,NOW);
        var complete = event(1,"T1",1,Kind.VERIFIED,task,proof(0));
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try { for (var result : workers.invokeAll(Collections.nCopies(100,() -> { store.apply(complete,NOW); return true; }))) assertEquals(true,result.get()); }
        finally { workers.shutdownNow(); }
        assertEquals("1234567",store.field("pending-usdt","10"));
        assertEquals(3,store.entries("pending").size());
        assertThrows(RuntimeException.class,() -> reserve(1,"T2","R2")); // Process exit is required even after completion.
        var exit = event(1,"T1",1,Kind.EXITED,task,null);
        store.execute("stage","T1","1",exit.eventId(),store.encode(exit),"EXITED",Long.toString(NOW.toEpochMilli()),"","","604800"); store.apply(exit,NOW);
        assertEquals("2",reserve(1,"T2","R2").get(1).toString());
        assertThrows(RuntimeException.class,() -> store.execute("stage","T2","1","old","{}","FAILED",Long.toString(NOW.toEpochMilli()),"","","604800"));
        store.apply(complete,NOW);
        assertEquals("T2",store.field("devices","1")); assertEquals("ISSUING",store.field("states","T2"));
    }
    @Test void minuteFreezeIsImmutableAndLateEventFormsSupplement() throws Exception {
        var a = event(1,"T1",1,Kind.ISSUED,task("R1"),null);
        store.restore(a); store.apply(a,NOW);
        String window = store.execute("freeze",Long.toString(NOW.getEpochSecond()+60));
        var frozen = Map.copyOf(store.entries("frozen:"+window));
        var late = event(2,"T2",1,Kind.ISSUED,task("R2"),null);
        store.restore(late); store.apply(late,NOW.plusSeconds(61));
        assertEquals(frozen,store.entries("frozen:"+window));
        assertTrue(store.entries("active:10").containsKey(late.eventId()));
        store.markPersisted(List.of(a)); assertTrue(store.persisted(a.eventId()));
        store.apply(a,NOW.plusSeconds(62)); assertFalse(store.entries("active:10").containsKey(a.eventId()));
    }
    @Test void expiredLeaseDoesNotReleaseActualExecutionPermitAndBlocksLateProof() {
        reserve(1,"T1","R1"); var task = task("R1").withStarted(NOW,NOW.plusSeconds(120),NOW.plusSeconds(120));
        store.apply(event(1,"T1",1,Kind.ISSUED,task,null),NOW);
        var start = event(1,"T1",1,Kind.STARTED,task,null);
        store.execute("start","T1","1","executor",Long.toString(NOW.plusSeconds(120).toEpochMilli()),store.encode(start),"6",Long.toString(NOW.toEpochMilli()),"300000","30000","1"); store.apply(start,NOW);
        var fenced = event(1,"T1",1,Kind.FENCED,task,null);
        store.execute("stage","T1","1",fenced.eventId(),store.encode(fenced),"FENCED",Long.toString(NOW.plusSeconds(121).toEpochMilli()),"","","604800"); store.apply(fenced,NOW.plusSeconds(121));
        assertEquals(1L,redis.opsForSet().size(store.key("permits")));
        var verified = event(1,"T1",1,Kind.VERIFIED,task,proof(0));
        assertThrows(RuntimeException.class,() -> store.execute("stage","T1","1",verified.eventId(),store.encode(verified),"VERIFIED",Long.toString(NOW.plusSeconds(121).toEpochMilli()),"","","604800"));
        assertEquals("RECOVERING",store.field("states","T1"));
    }
    @Test void failedVerificationDoesNotConsumeDailyEntitlementAndDuplicateDailyIsZero() {
        var task = task("R1").withStarted(NOW,NOW.plusSeconds(120),NOW.plusSeconds(120));
        reserve(1,"T1","R1"); store.apply(event(1,"T1",1,Kind.ISSUED,task,null),NOW);
        var complete = event(1,"T1",1,Kind.VERIFIED,task,proof(3000000));
        String daily = complete.evidence().dailyEntitlementKey();
        assertThrows(RuntimeException.class,() -> store.execute("stage","T1","1",complete.eventId(),store.encode(complete),"VERIFIED",Long.toString(NOW.toEpochMilli()),daily,"zero","604800"));
        assertNull(redis.opsForValue().get(store.key("daily:"+daily)));
        var started = event(1,"T1",1,Kind.STARTED,task,null);
        store.execute("start","T1","1","executor",Long.toString(NOW.plusSeconds(120).toEpochMilli()),store.encode(started),"6",Long.toString(NOW.toEpochMilli()),"300000","30000","1"); store.apply(started,NOW);
        store.execute("verify","T1","1",HASH,Long.toString(NOW.toEpochMilli()));
        redis.opsForValue().set(store.key("daily:"+daily),"PRIOR_DEVICE_DAILY_EVENT");
        var zero = event(1,"T1",1,Kind.VERIFIED,task,proof(0));
        assertEquals(store.encode(zero),store.execute("stage","T1","1",complete.eventId(),store.encode(complete),"VERIFIED",Long.toString(NOW.toEpochMilli()),daily,store.encode(zero),"604800"));
    }
    @Test void closedWindowAndConcurrentCompletionLoseNoEvent() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8); CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> actions = new ArrayList<>();
        for (int i = 1; i <= 64; i++) {
            int device = i;
            actions.add(() -> { start.await(); store.apply(event(device,"T"+device,1,Kind.ISSUED,task("R"+device),null),NOW); return true; });
        }
        actions.add(() -> { start.await(); store.execute("freeze",Long.toString(NOW.getEpochSecond()+60)); return true; });
        try {
            var submitted = actions.stream().map(workers::submit).toList(); start.countDown();
            for (var future : submitted) assertTrue(future.get());
        } finally { workers.shutdownNow(); }
        int frozenCount = store.sorted("frozen-index").stream().mapToInt(window -> store.entries("frozen:"+window).size()).sum();
        assertEquals(64,frozenCount+store.entries("active:10").size());
        assertEquals(64,store.entries("pending").size());
        var snapshots = new HashMap<String,Map<Object,Object>>();
        for (String window : store.sorted("frozen-index")) snapshots.put(window,Map.copyOf(store.entries("frozen:"+window)));
        store.apply(event(100,"late",1,Kind.ISSUED,task("R-late"),null),NOW.plusSeconds(62));
        snapshots.forEach((window,events) -> assertEquals(events,store.entries("frozen:"+window)));
    }
    @Test void redisLossBlocksAdmissionAndDurableReplayRestoresDailyAndActualPermit() {
        reserve(1,"T1","R1"); var task = task("R1").withStarted(NOW,NOW.plusSeconds(120),NOW.plusSeconds(120));
        var issued = event(1,"T1",1,Kind.ISSUED,task,null); var started = event(1,"T1",1,Kind.STARTED,task,null);
        connections.getConnection().serverCommands().flushDb();
        assertFalse(store.ready()); assertThrows(RuntimeException.class,() -> reserve(2,"T2","R2"));
        assertThrows(RuntimeException.class,() -> store.reserve("summary","10"));
        store.restorePersisted(issued); store.restorePersisted(started);
        assertEquals("RECOVERING",store.field("states","T1")); assertEquals(1L,redis.opsForSet().size(store.key("permits")));
        var complete = event(1,"T1",1,Kind.VERIFIED,task,proof(3000000));
        store.apply(complete,NOW.plusSeconds(31));
        assertEquals("COMPLETED",store.field("states","T1")); assertEquals("1234567",store.field("pending-usdt","10"));
        assertEquals(complete.eventId(),redis.opsForValue().get(store.key("daily:"+complete.evidence().dailyEntitlementKey())));
        assertEquals(1L,redis.opsForSet().size(store.key("permits"))); assertFalse(store.ready());
        store.markPersisted(List.of(complete));
        assertEquals("0",store.field("pending-usdt","10")); assertEquals("0",store.field("pending-nex-completions","10"));
        store.execute("initialize",Long.toString(NOW.getEpochSecond())); store.ready(true);
        assertEquals(List.of("0","0","0","0"),store.reserve("summary","10"));
    }
    @Test void duplicateBusinessIdWithChangedPayloadIsRejectedAndValidationIsSingleOwner() {
        reserve(1,"T1","R1"); var issued = event(1,"T1",1,Kind.ISSUED,task("R1"),null); store.apply(issued,NOW);
        var changed = new ComputeV2Event(2,issued.eventId(),issued.kind(),issued.deploymentScope(),issued.sourceEnvironment(),issued.runId(),
                issued.userId(),issued.deviceId(),issued.instanceNo(),issued.taskNo(),issued.generation(),issued.occurredAt(),issued.task(),null,"changed");
        assertThrows(RuntimeException.class,() -> store.apply(changed,NOW));
        var start = event(1,"T1",1,Kind.STARTED,task("R1").withStarted(NOW,NOW.plusSeconds(120),NOW.plusSeconds(120)),null);
        store.execute("start","T1","1","executor",Long.toString(NOW.plusSeconds(120).toEpochMilli()),store.encode(start),"6",Long.toString(NOW.toEpochMilli()),"300000","30000","1"); store.apply(start,NOW);
        assertEquals("1",store.execute("verify","T1","1",HASH,Long.toString(NOW.toEpochMilli())));
        assertEquals("0",store.execute("verify","T1","1",HASH,Long.toString(NOW.toEpochMilli())));
        assertThrows(RuntimeException.class,() -> store.execute("verify","T1","1","b".repeat(64),Long.toString(NOW.toEpochMilli())));
    }
    @Test void thousandAccountsHaveSixtyStableSecondOffsetsAndSealWithoutOneThousandWalletBurst() {
        for (int user = 1; user <= 1000; user++) {
            var base = event(user,"TU"+user,1,Kind.ISSUED,task("RU"+user),null);
            var account = new ComputeV2Event(2,base.eventId(),base.kind(),base.deploymentScope(),base.sourceEnvironment(),base.runId(),
                    user,base.deviceId(),base.instanceNo(),base.taskNo(),1,NOW,base.task(),null,null);
            store.apply(account,NOW);
        }
        assertEquals(1000L,redis.opsForZSet().size(store.key("active-index")));
        store.execute("freeze",Long.toString(NOW.getEpochSecond()+1));
        assertEquals(17L,redis.opsForZSet().size(store.key("frozen-index")));
        assertEquals(983L,redis.opsForZSet().size(store.key("active-index")));
        for (int i=0;i<5;i++) store.execute("freeze",Long.toString(NOW.getEpochSecond()+60));
        assertEquals(1000L,redis.opsForZSet().size(store.key("frozen-index")));
        assertEquals(0L,redis.opsForZSet().size(store.key("active-index")));
        Set<Double> endings = new HashSet<>();
        for (String window : store.sorted("frozen-index")) endings.add(store.score("frozen-index",window));
        assertEquals(60,endings.size());
        assertEquals(ComputeV2RedisStore.windowEnd(7,NOW).plusSeconds(60),ComputeV2RedisStore.windowEnd(7,NOW.plusSeconds(60)));
    }
    @Test void accountPendingIndexAndMoneyCountsRemainOneAtomicSnapshotDuringSqlAcknowledgements() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i=1;i<=64;i++) {
                int device = i;
                futures.add(workers.submit(() -> {
                    var completion = event(device,"P"+device,1,Kind.VERIFIED,
                            task("RP"+device).withStarted(NOW,NOW.plusSeconds(120),NOW.plusSeconds(120)),proof(0));
                    store.apply(completion,NOW.plusSeconds(30));
                    store.markPersisted(List.of(completion));
                }));
            }
            do {
                var snapshot = store.reserve("summary","10");
                long count = Long.parseLong(snapshot.get(2).toString());
                assertEquals(count,snapshot.size()-4);
                assertEquals(count*1234567,Long.parseLong(snapshot.get(0).toString()));
                assertEquals("0",snapshot.get(1)); assertEquals("0",snapshot.get(3));
            } while (futures.stream().anyMatch(future -> !future.isDone()));
            for (var future : futures) future.get();
            assertEquals(List.of("0","0","0","0"),store.reserve("summary","10"));
        } finally { workers.shutdownNow(); }
    }
    @Test void fiveMinuteUnconfirmedSendsStopAdmissionAndOutgoingHasHardBound() {
        assertEquals("original",store.execute("outgoing","one","original",Long.toString(NOW.minusSeconds(301).toEpochMilli()),"1"));
        assertEquals("original",store.execute("outgoing","one","changed",Long.toString(NOW.toEpochMilli()),"1"));
        assertThrows(RuntimeException.class,() -> store.execute("outgoing","two","second",Long.toString(NOW.toEpochMilli()),"1"));
        assertThrows(RuntimeException.class,() -> reserve(1,"T1","R1"));
        assertTrue(store.entries("devices").isEmpty());
    }
    @Test void queuedCancellationAndExecutionAcquisitionRaceKeepsReservedPermit() throws Exception {
        reserve(1,"T1","R1"); store.apply(event(1,"T1",1,Kind.ISSUED,task("R1"),null),NOW);
        var started = event(1,"T1",1,Kind.STARTED,task("R1").withStarted(NOW,NOW.plusSeconds(120),NOW.plusSeconds(120)),null);
        var failed = event(1,"T1",1,Kind.FAILED,task("R1"),null);
        ExecutorService workers = Executors.newFixedThreadPool(2); CountDownLatch go = new CountDownLatch(1);
        try {
            var start = workers.submit(() -> { go.await(); return store.execute("start","T1","1","executor",Long.toString(NOW.plusSeconds(120).toEpochMilli()),store.encode(started),"6",Long.toString(NOW.toEpochMilli()),"300000","30000","1"); });
            var cancel = workers.submit(() -> { go.await(); return store.execute("cancel","T1","1",failed.eventId(),store.encode(failed)); });
            go.countDown(); String cancellation = cancel.get();
            if (cancellation.isEmpty()) {
                assertFalse(start.get().isEmpty()); assertEquals("STARTING",store.field("states","T1"));
                assertEquals(1L,redis.opsForSet().size(store.key("permits")));
                assertEquals("",store.execute("cancel","T1","1",failed.eventId(),store.encode(failed)));
            } else {
                var failure = assertThrows(ExecutionException.class,start::get);
                Throwable cause = failure.getCause(); while (cause.getCause()!=null) cause = cause.getCause();
                assertTrue(cause.getMessage().contains("NOT_QUEUED"));
                assertEquals("FAILING",store.field("states","T1")); assertEquals(0L,redis.opsForSet().size(store.key("permits")));
                assertEquals(cancellation,store.execute("cancel","T1","1",failed.eventId(),"changed"));
            }
        } finally { workers.shutdownNow(); }
    }
    @Test void delayedPrunedOldGenerationCannotRequeueOrReserveAnExecutionAheadOfCurrentWork() {
        reserve(1,"OLD","R-old");
        var oldIssued = event(1,"OLD",1,Kind.ISSUED,task("R-old"),null);
        var oldStarted = event(1,"OLD",1,Kind.STARTED,task("R-old").withStarted(NOW,NOW.plusSeconds(120),NOW.plusSeconds(120)),null);
        store.restorePersisted(oldIssued); store.restorePersisted(oldStarted);
        store.restorePersisted(event(1,"OLD",1,Kind.FAILED,oldStarted.task(),null));
        store.restorePersisted(event(1,"OLD",1,Kind.EXITED,oldStarted.task(),null));
        reserve(1,"CURRENT","R-current");
        var current = event(1,"CURRENT",2,Kind.ISSUED,task("R-current"),null); store.apply(current,NOW);
        assertNull(store.field("generation","OLD"));
        redis.delete(store.key("seen:"+oldIssued.eventId())); redis.delete(store.key("seen:"+oldStarted.eventId()));
        store.restore(oldIssued); store.apply(oldIssued,NOW.plusSeconds(1));
        store.restore(oldStarted); store.apply(oldStarted,NOW.plusSeconds(1));
        assertEquals("CURRENT",store.field("devices","1"));
        assertEquals(Set.of("CURRENT"),store.sorted("queue"));
        assertEquals(0L,redis.opsForSet().size(store.key("permits")));
        var started = event(1,"CURRENT",2,Kind.STARTED,task("R-current").withStarted(NOW,NOW.plusSeconds(120),NOW.plusSeconds(120)),null);
        assertFalse(store.execute("start","CURRENT","2","executor",Long.toString(NOW.plusSeconds(120).toEpochMilli()),store.encode(started),"6",Long.toString(NOW.toEpochMilli()),"300000","30000","1").isEmpty());
        assertEquals(1L,redis.opsForSet().size(store.key("permits")));
    }
    @Test void pausedOrFiveMinuteBacklogStopsNewExecutionButAllowsOriginalLeaseRenewal() {
        reserve(1,"T1","R1"); store.apply(event(1,"T1",1,Kind.ISSUED,task("R1"),null),NOW);
        var start = event(1,"T1",1,Kind.STARTED,task("R1").withStarted(NOW,NOW.plusSeconds(120),NOW.plusSeconds(120)),null);
        assertEquals("0",store.execute("admission",Long.toString(NOW.toEpochMilli()),"300000","30000","0"));
        assertThrows(RuntimeException.class,() -> store.execute("start","T1","1","executor",Long.toString(NOW.plusSeconds(120).toEpochMilli()),store.encode(start),"6",
                Long.toString(NOW.toEpochMilli()),"300000","30000","0"));
        var backlog = event(2,"BACKLOG",1,Kind.ISSUED,task("R-backlog"),null); store.apply(backlog,NOW.minusSeconds(301));
        assertEquals("0",store.execute("admission",Long.toString(NOW.toEpochMilli()),"300000","30000","1"));
        assertThrows(RuntimeException.class,() -> store.execute("start","T1","1","executor",Long.toString(NOW.plusSeconds(120).toEpochMilli()),store.encode(start),"6",
                Long.toString(NOW.toEpochMilli()),"300000","30000","1"));
        assertEquals("QUEUED",store.field("states","T1")); assertEquals(0L,redis.opsForSet().size(store.key("permits")));
        store.markPersisted(List.of(backlog));
        assertEquals("1",store.execute("admission",Long.toString(NOW.toEpochMilli()),"300000","30000","1"));
        assertEquals(store.encode(start),store.execute("start","T1","1","executor",Long.toString(NOW.plusSeconds(120).toEpochMilli()),store.encode(start),"6",
                Long.toString(NOW.toEpochMilli()),"300000","30000","1")); store.apply(start,NOW);
        assertEquals("1",store.execute("renew","T1","1","executor",Long.toString(NOW.toEpochMilli()),Long.toString(NOW.plusSeconds(120).toEpochMilli())));
        assertEquals(store.encode(start),store.execute("start","T1","1","executor",Long.toString(NOW.plusSeconds(120).toEpochMilli()),store.encode(start),"6",
                Long.toString(NOW.toEpochMilli()),"300000","30000","0"));
    }
    @Test void postedTurnoverReleasesLargeRequestJsonAndKeepsOnlyBoundedHotDedup() throws Exception {
        config.setPostedDedupSeconds(1);
        String payload="x".repeat(16000);
        for(int n=1;n<=500;n++) {
            String taskNo="TURN"+n,request="RT"+n;
            reserve(1,taskNo,request);
            var issued=event(1,taskNo,n,Kind.ISSUED,task(request),null);
            store.field("issued-events",taskNo,store.encode(issued));store.cache("request-event:10:1:"+request,payload);
            var failed=event(1,taskNo,n,Kind.FAILED,task(request),null);
            store.apply(issued,NOW);store.apply(failed,NOW);store.markPersisted(List.of(issued,failed));
        }
        assertEquals(1,store.entries("issued-events").size());
        assertEquals(1,store.entries("task-requests").size());assertEquals(1,store.entries("posted-requests").size());
        assertEquals(Set.of(store.key("request-event:10:1:RT500")),redis.keys(store.key("request-event:*")));
        assertTrue(redis.getExpire(store.key("seen:TURN500:500:ISSUED"))<=1);
        store.execute("retire","1","1");
        assertTrue(redis.keys(store.key("request-event:*")).isEmpty());
        assertTrue(store.entries("snapshots").isEmpty());assertTrue(store.entries("lifecycle-events").isEmpty());
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!redis.keys(store.key("request:*")).isEmpty() && System.nanoTime()<deadline) Thread.sleep(20);
        assertTrue(redis.keys(store.key("request:*")).isEmpty());assertTrue(redis.keys(store.key("seen:*")).isEmpty());
    }
    @Test void unpostedEvidenceSurvivesPruneAttemptsAndMissingHotMarkerCannotDoublePendingMoney() {
        reserve(1,"PENDING","RP");
        var issued=event(1,"PENDING",1,Kind.ISSUED,task("RP"),null);
        store.field("issued-events","PENDING",store.encode(issued));store.cache("request-event:10:1:RP",store.encode(issued));
        store.apply(issued,NOW);
        var failed=event(1,"PENDING",1,Kind.FAILED,task("RP"),null);store.apply(failed,NOW);store.execute("retire","1","600");
        assertEquals("0",store.execute("prune","PENDING","600"));
        assertNotNull(store.value("request-event:10:1:RP"));assertEquals(-1L,redis.getExpire(store.key("seen:"+issued.eventId())));
        redis.delete(store.key("seen:"+issued.eventId()));store.apply(issued,NOW.plusSeconds(100));
        assertEquals("2",store.field("pending-count","PENDING"));assertEquals(2,store.entries("pending").size());
        store.markPersisted(List.of(issued,failed));
        assertNull(store.value("request-event:10:1:RP"));assertNull(store.field("generation","PENDING"));
        store.field("device-generation","1","2");
        store.restore(issued);store.apply(issued,NOW);store.markPersisted(List.of(issued));
        assertNull(store.field("generation","PENDING"));assertTrue(store.sorted("queue").isEmpty());
    }
    @Test @EnabledIfEnvironmentVariable(named="COMPUTE_V2_MYSQL_IT",matches="true")
    void prunedAndColdNamespaceRequestRetriesReadOriginalSqlWithoutPublishingOrApplying() throws Exception {
        var sql=new ffdd.opsconsole.finance.compute.v2.ComputeV2SettlementMySqlIntegrationTest();sql.fixture();
        try {
            config.setDeploymentScope("TEST");store=new ComputeV2RedisStore(redis,JsonMapper.builder().addModule(new JavaTimeModule()).build(),config);
            store.ready(true);store.execute("initialize",Long.toString(NOW.getEpochSecond()),"0");
            var template=sql.completion(1,1234567,0,NOW.plusSeconds(30));var t=template.task();
            var frozen=new TaskSnapshot(t.requestId(),t.taskConfigId(),t.taskName(),t.taskClass(),t.modelName(),t.clientName(),t.configVersion(),
                    t.expectedSeconds(),t.minSeconds(),t.maxSeconds(),t.requiredSeconds(),t.executionTimeoutSec(),0,NOW,null,NOW.plusSeconds(45),NOW.plusSeconds(420),
                    t.rewardUsdtMicros(),t.rawQuoteMicros(),t.e3Multiplier(),t.walletId(),t.walletIncarnation(),t.device(),t.inputSha256(),t.modelSha256(),t.inputArtifactRef());
            String taskNo=template.taskNo(),request="7:11:"+frozen.requestId();
            store.reserve("reserve",request,"11",taskNo,"7",Long.toString(NOW.toEpochMilli()),"6000","300000","30000",store.encode(frozen));
            var issued=new ComputeV2Event(2,ComputeV2Event.eventId(taskNo,1,Kind.ISSUED),Kind.ISSUED,"TEST","PRODUCTION","",7,11,"DEVICE-11",taskNo,1,NOW,frozen,null,null);
            var failed=new ComputeV2Event(2,ComputeV2Event.eventId(taskNo,1,Kind.FAILED),Kind.FAILED,"TEST","PRODUCTION","",7,11,"DEVICE-11",taskNo,1,NOW,frozen,null,"CANCELLED");
            store.field("issued-events",taskNo,store.encode(issued));store.cache("request-event:"+request,store.encode(issued));
            store.apply(issued,NOW);store.apply(failed,NOW);
            sql.sink().persistBatch(new ComputeV2Persistence.SealedBatch("HISTORY-CACHE",NOW,NOW.plusSeconds(60),List.of(issued,failed)));
            store.markPersisted(List.of(issued,failed));
            var mq=mock(ComputeV2MessageLog.class);var runtime=new ComputeV2Runtime(store,mq,config,Clock.fixed(NOW,ZoneOffset.UTC),sql.sink());
            runtime.retire(7,11);assertNull(store.value("request-event:"+request));assertNotNull(store.value("request:"+request));
            assertEquals(issued,runtime.issue(7,11,"DEVICE-11",frozen));assertTrue(store.entries("devices").isEmpty());
            store.delete("request:"+request);
            runtime=new ComputeV2Runtime(store,mq,config,Clock.fixed(NOW.plusSeconds(601),ZoneOffset.UTC),sql.sink());
            assertEquals(issued,runtime.issue(7,11,"DEVICE-11",frozen));
            redis.delete(redis.keys(store.key("*")));store.ready(true);store.execute("initialize","0",Long.toString(NOW.plusSeconds(50).toEpochMilli()));
            runtime=new ComputeV2Runtime(store,mq,config,Clock.fixed(NOW.plusSeconds(51),ZoneOffset.UTC),sql.sink());
            assertEquals(issued,runtime.issue(7,11,"DEVICE-11",frozen));
            var unavailable=new ComputeV2Runtime(store,mq,config,Clock.fixed(NOW.plusSeconds(51),ZoneOffset.UTC));
            assertEquals("COMPUTE_V2_REQUEST_HISTORY_UNAVAILABLE",assertThrows(IllegalStateException.class,()->unavailable.issue(7,11,"DEVICE-11",frozen)).getMessage());
            assertTrue(store.entries("pending").isEmpty());assertTrue(store.entries("devices").isEmpty());verifyNoInteractions(mq);
            assertEquals(2,sql.jdbc().queryForObject("SELECT COUNT(*) FROM nx_compute_v2_event",Integer.class));
            assertEquals(0,sql.jdbc().queryForObject("SELECT COUNT(*) FROM nx_compute_receipt",Integer.class));
        } finally { sql.cleanup(); }
    }
    static StringRedisTemplate redisTemplate() { return redis; }
    private List<?> reserve(int device,String taskNo,String request) {
        return store.reserve("reserve","10:"+device+":"+request,Integer.toString(device),taskNo,"10",Long.toString(NOW.toEpochMilli()),"6000","300000","30000",store.encode(task(request)),
                Integer.toString(config.getDedupRetentionSeconds()),"INSTANCE-"+device,"1",Integer.toString(config.getPostedDedupSeconds()));
    }
    static TaskSnapshot task(String request) {
        return new TaskSnapshot(request,"CONFIG","Actual task","EMBEDDING","MODEL","BGE",1,30,30,30,30,0,NOW,null,
                NOW.plusSeconds(120),NOW.plusSeconds(120),1234567,1234567,BigDecimal.ONE,100,"00000000-0000-0000-0000-000000000001",
                new DeviceSnapshot("Cloud device","CLOUD_SHARE","cloud-share","GPU",1,BigDecimal.ONE,8),HASH,HASH,"compute/input/object");
    }
    private static Verified proof(long nex) {
        return new Verified(HASH,HASH,HASH,NOW.plusSeconds(30),NOW.plusSeconds(30),"compute/result/object",100,nex,
                nex == 0 ? null : "CLOUD_SHARE_DAILY:1:2026-11-01",LocalDate.parse("2026-11-01"));
    }
    static ComputeV2Event event(long device,String task,long gen,Kind kind,TaskSnapshot snapshot,Verified proof) {
        return new ComputeV2Event(2,ComputeV2Event.eventId(task,gen,kind),kind,"REDIS-INTEGRATION","PRODUCTION","",10,device,"INSTANCE-"+device,
                task,gen,proof == null ? NOW : proof.verifiedAt(),snapshot,proof,null);
    }
}
