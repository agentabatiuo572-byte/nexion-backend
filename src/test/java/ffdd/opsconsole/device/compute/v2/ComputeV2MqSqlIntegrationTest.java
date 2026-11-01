package ffdd.opsconsole.device.compute.v2;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event.*;
import ffdd.opsconsole.finance.compute.v2.ComputeV2SettlementMySqlIntegrationTest;
import ffdd.opsconsole.shared.rocketmq.RocketMqAclProperties;
import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual isolated Redis + RocketMQ 5.5.0 + MySQL sink. This verifies transport/settlement, not model execution. */
@EnabledIfEnvironmentVariable(named="COMPUTE_V2_MQ_IT",matches="true")
@EnabledIfEnvironmentVariable(named="COMPUTE_V2_MYSQL_IT",matches="true")
class ComputeV2MqSqlIntegrationTest {
    private static Process nameserver;
    private static Process broker;
    private static Path directory;
    private static Path brokerConfig;
    private static int namePort;
    private static int brokerPort;
    private static final String INSTALL = "D:/software/rocketMQ";
    private ComputeV2SettlementMySqlIntegrationTest sql;
    private ComputeV2MessageLog messageLog;

    @BeforeAll @Timeout(60) static void dependencies() throws Exception {
        ComputeV2RedisIntegrationTest.startRedis();
        directory = Files.createTempDirectory(Path.of("target"),"compute-v2-mq-").toAbsolutePath();
        namePort = port(); brokerPort = port();
        Path nameConfig = directory.resolve("nameserver.properties");
        Files.writeString(nameConfig,"listenPort="+namePort+"\nbindAddress=127.0.0.1\nserverWorkerThreads=2\nserverSelectorThreads=1\n");
        nameserver = launch("org.apache.rocketmq.namesrv.NamesrvStartup",nameConfig,"nameserver",128);
        ready(nameserver,"Name Server boot success");
        brokerConfig = directory.resolve("broker.properties");
        Files.writeString(brokerConfig,"""
                brokerClusterName=COMPUTE_V2_ISOLATED
                brokerName=compute-v2-isolated
                brokerId=0
                brokerRole=ASYNC_MASTER
                bindAddress=127.0.0.1
                brokerIP1=127.0.0.1
                autoCreateTopicEnable=false
                autoCreateSubscriptionGroup=true
                enableSingleTopicRegister=true
                flushDiskType=SYNC_FLUSH
                cleanFileForciblyEnable=false
                fileReservedTime=168
                mappedFileSizeCommitLog=1048576
                mappedFileSizeConsumeQueue=300000
                messageIndexEnable=false
                timerWheelEnable=false
                enableTimerWheel=false
                serverWorkerThreads=2
                serverSelectorThreads=1
                sendMessageThreadPoolNums=2
                pullMessageThreadPoolNums=2
                queryMessageThreadPoolNums=1
                adminBrokerThreadPoolNums=1
                clientManageThreadPoolNums=1
                consumerManageThreadPoolNums=1
                heartbeatThreadPoolNums=1
                endTransactionThreadPoolNums=1
                flushConsumerOffsetInterval=1000
                """+"listenPort="+brokerPort+"\nhaListenPort="+(brokerPort+1)+"\nnamesrvAddr=127.0.0.1:"+namePort
                +"\nstorePathRootDir="+directory.resolve("store").toString().replace('\\','/')+"\n");
        broker = launch("org.apache.rocketmq.broker.BrokerStartup",brokerConfig,"broker",256);
        ready(broker,"boot success");
    }
    @AfterAll static void dependenciesClosed() throws Exception {
        stop(broker); stop(nameserver); ComputeV2RedisIntegrationTest.stopRedis();
    }
    @BeforeEach void sql() throws Exception { sql = new ComputeV2SettlementMySqlIntegrationTest(); sql.fixture(); }
    @AfterEach void cleanup() throws Exception { if (messageLog != null) messageLog.close(); if (sql != null) sql.cleanup(); }

    @Test @Timeout(120) void durableLifecycleSurvivesConsumerRestartAndSqlFailureWithoutOffsetOrDoubleCredit() throws Exception {
        var config = new ComputeV2Properties(); config.setDeploymentScope("TEST"); config.setNamesrvAddr("127.0.0.1:"+namePort);
        config.setStrictDurability(true);
        config.setTopic("compute-v2-it-"+UUID.randomUUID().toString().replace("-",""));
        config.setConsumerGroup("compute-v2-it-c-"+UUID.randomUUID().toString().replace("-",""));
        var admin = new DefaultMQAdminExt(); admin.setInstanceName("isolated-it-admin-"+UUID.randomUUID());
        admin.setNamesrvAddr(config.getNamesrvAddr()); admin.setVipChannelEnabled(false); admin.start();
        try {
            long registrationDeadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            boolean registered;
            do {
                registered = admin.examineBrokerClusterInfo().getBrokerAddrTable().containsKey("compute-v2-isolated");
            } while (!registered && System.nanoTime()<registrationDeadline);
            assertTrue(registered,"Broker did not register its actual loopback route");
            admin.createAndUpdateTopicConfig("127.0.0.1:"+brokerPort,new TopicConfig(config.getTopic(),32,32,6));
            var store = new ComputeV2RedisStore(ComputeV2RedisIntegrationTest.redisTemplate(),JsonMapper.builder().addModule(new JavaTimeModule()).build(),config);
            var clock = new MutableClock(Instant.parse("2026-11-01T01:00:01Z"));
            messageLog = new ComputeV2MessageLog(config,store,new RocketMqAclProperties()); messageLog.start();
            var checkpoint = new ComputeV2Checkpoint(messageLog,store,sql.sink(),config,clock); checkpoint.initialize();
            pump(checkpoint,store::ready);
            var template = sql.completion(1,1234567,0,clock.instant().plusSeconds(30)).task();
            var frozen = new TaskSnapshot(template.requestId(),template.taskConfigId(),template.taskName(),template.taskClass(),template.modelName(),
                    template.clientName(),template.configVersion(),template.expectedSeconds(),template.minSeconds(),template.maxSeconds(),template.requiredSeconds(),
                    template.executionTimeoutSec(),0,clock.instant(),null,clock.instant().plusSeconds(45),clock.instant().plusSeconds(420),
                    template.rewardUsdtMicros(),template.rawQuoteMicros(),template.e3Multiplier(),template.walletId(),template.walletIncarnation(),
                    template.device(),template.inputSha256(),template.modelSha256(),template.inputArtifactRef());
            var runtime = new ComputeV2Runtime(store,messageLog,config,clock,sql.sink());
            var issued = runtime.issue(7,11,"DEVICE-11",frozen);
            assertEquals(issued,runtime.issue(7,11,"DEVICE-11",frozen));
            var resourceQueue=messageLog.producer().fetchPublishMessageQueues(config.getTopic()).stream().filter(q->q.getQueueId()==7).findFirst().orElseThrow();
            long beforeReservation=messageLog.producer().maxOffset(resourceQueue);
            sql.jdbc().execute("CREATE TABLE IF NOT EXISTS nx_test_cloud_resource_guard(id INT PRIMARY KEY)");
            sql.jdbc().execute("INSERT IGNORE INTO nx_test_cloud_resource_guard VALUES(1)");
            var resourceTransaction=new TransactionTemplate(new DataSourceTransactionManager(sql.jdbc().getDataSource()));
            var reserved=resourceTransaction.execute(status->{
                assertEquals(1,sql.jdbc().queryForObject("SELECT id FROM nx_test_cloud_resource_guard WHERE id=1 FOR UPDATE",Integer.class));
                return runtime.reserveExecution(7,11,"executor-one").orElseThrow();
            });
            assertEquals(beforeReservation,messageLog.producer().maxOffset(resourceQueue));
            assertEquals(1,runtime.activeExecutionCount());assertEquals("STARTING",runtime.view(7,11).orElseThrow().status());
            var started=runtime.confirmExecution(reserved);
            assertEquals(started,runtime.acquireExecution(7,11,"executor-one").orElseThrow());
            assertEquals(issued.taskNo(),runtime.forExecutor("executor-one").orElseThrow().event().taskNo());
            assertTrue(runtime.beginVerification(7,11,issued.generation(),"e".repeat(64)));
            assertFalse(runtime.beginVerification(7,11,issued.generation(),"e".repeat(64)));
            sql.jdbc().execute("CREATE TRIGGER mq_it_failure BEFORE INSERT ON nx_compute_v2_event FOR EACH ROW BEGIN IF NEW.user_id=7 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='INJECTED_SQL_FAILURE'; END IF; END");
            clock.at = clock.at.plusSeconds(30);
            var complete = runtime.verifiedComplete(7,11,issued.generation(),sql.completion(1,1234567,0,clock.instant()).evidence());
            sql.jdbc().update("INSERT INTO nx_user VALUES(8,'ACTIVE',0,0,'2026-01-01')");
            sql.jdbc().update("INSERT INTO nx_user_device VALUES(12,8,'DEVICE-12','PRODUCTION','',0,'ACTIVE',NULL,0)");
            String healthyWallet = "55555555-5555-5555-5555-555555555555";
            sql.jdbc().update("INSERT INTO nx_user_wallet(id,user_id,fund_incarnation) VALUES(2,8,?)",healthyWallet);
            var t = complete.task();
            var healthyTask = new TaskSnapshot("healthy-request",t.taskConfigId(),t.taskName(),t.taskClass(),t.modelName(),t.clientName(),t.configVersion(),
                    t.expectedSeconds(),t.minSeconds(),t.maxSeconds(),t.requiredSeconds(),t.executionTimeoutSec(),0,t.issuedAt(),t.startedAt(),t.leaseExpiresAt(),t.proofExpiresAt(),
                    77,77,t.e3Multiplier(),2,healthyWallet,t.device(),t.inputSha256(),t.modelSha256(),t.inputArtifactRef());
            String healthyNo = "CV2-"+"F".repeat(32);
            var p = complete.evidence();
            var healthyProof = new Verified("f".repeat(64),p.inputSha256(),p.resultSha256(),p.verifiedAt(),p.completedAt(),"result/healthy",p.artifactBytes(),0,null,p.utcDay());
            var healthy = new ComputeV2Event(2,ComputeV2Event.eventId(healthyNo,1,Kind.VERIFIED),Kind.VERIFIED,"TEST","PRODUCTION","",8,12,"DEVICE-12",
                    healthyNo,1,clock.instant(),healthyTask,healthyProof,null);
            messageLog.send(healthy); store.apply(healthy,clock.instant());
            assertEquals(1234567,runtime.pendingSummary(7).pendingUsdtMicros());
            var queue = messageLog.producer().fetchPublishMessageQueues(config.getTopic()).stream().filter(q -> q.getQueueId()==7).findFirst().orElseThrow();
            pump(checkpoint,() -> store.entries("pending").size() == 4);
            Long before = messageLog.consumer().committed(queue);
            assertTrue(before == null || before <= 0);
            clock.at = clock.at.plusSeconds(60); checkpoint.tick();
            assertEquals(0,sql.jdbc().queryForObject("SELECT COUNT(*) FROM nx_compute_v2_checkpoint WHERE user_id=7",Integer.class));
            assertEquals(0,sql.jdbc().queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE id=1",java.math.BigDecimal.class).signum());
            assertEquals(new java.math.BigDecimal("0.000077"),sql.jdbc().queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE id=2",java.math.BigDecimal.class));
            assertTrue(runtime.settlementBlock(complete.taskNo(),complete.generation()).isEmpty());
            var healthyQueue = messageLog.producer().fetchPublishMessageQueues(config.getTopic()).stream().filter(q -> q.getQueueId()==8).findFirst().orElseThrow();
            var first = checkpoint;
            pump(first,() -> {try {Long offset=messageLog.consumer().committed(healthyQueue);return offset!=null&&offset>=1;}catch(Exception ex){throw new IllegalStateException(ex);}});
            Long failed = messageLog.consumer().committed(queue); assertTrue(failed == null || failed <= 0);
            messageLog.close();
            messageLog = new ComputeV2MessageLog(config,store,new RocketMqAclProperties()); messageLog.start();
            checkpoint = new ComputeV2Checkpoint(messageLog,store,sql.sink(),config,clock); checkpoint.initialize();
            var restarted = checkpoint; pump(restarted,store::ready);
            assertEquals(1234567,runtime.pendingSummary(7).pendingUsdtMicros());
            sql.jdbc().execute("DROP TRIGGER mq_it_failure");
            sql.jdbc().update("UPDATE nx_user SET status='FROZEN' WHERE id=7");
            pump(restarted,() -> runtime.settlementBlock(complete.taskNo(),complete.generation()).isPresent());
            assertEquals("COMPUTE_V2_ACCOUNT_NOT_ACTIVE",runtime.settlementBlock(complete.taskNo(),complete.generation()).orElseThrow());
            assertEquals(0,sql.jdbc().queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE id=1",java.math.BigDecimal.class).signum());
            sql.jdbc().update("UPDATE nx_user SET status='ACTIVE' WHERE id=7");
            pump(restarted,() -> sql.jdbc().queryForObject("SELECT COUNT(*) FROM nx_compute_receipt WHERE user_id=7",Integer.class) == 1);
            assertEquals(new java.math.BigDecimal("1.234567"),sql.jdbc().queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE id=1",java.math.BigDecimal.class));
            assertEquals(0,runtime.pendingSummary(7).pendingUsdtMicros());
            assertTrue(runtime.settlementBlock(complete.taskNo(),complete.generation()).isEmpty());
            store.delete("seen:"+complete.eventId()); // Expired POSTED hot cache must resolve SQL before any Redis money reapplication.
            messageLog.send(complete);
            pump(restarted,() -> {
                try { Long offset = messageLog.consumer().committed(queue); return offset != null && offset >= 6; }
                catch (Exception ex) { throw new IllegalStateException(ex); }
            });
            assertEquals(2,sql.jdbc().queryForObject("SELECT COUNT(*) FROM nx_compute_receipt",Integer.class));
            assertEquals(0,runtime.pendingSummary(7).pendingTaskCount());
            assertTrue(store.persisted(complete.eventId()));
            Properties unsafe = new Properties(); unsafe.setProperty("flushDiskType","ASYNC_FLUSH");
            admin.updateBrokerConfig("127.0.0.1:"+brokerPort,unsafe);
            assertThrows(IllegalStateException.class,() -> messageLog.verifyDurability());
            assertThrows(IllegalStateException.class,() -> messageLog.send(complete));
            config.setStrictDurability(false); messageLog.verifyDurability(); messageLog.send(complete);
            config.setStrictDurability(true);
            unsafe.setProperty("flushDiskType","SYNC_FLUSH"); admin.updateBrokerConfig("127.0.0.1:"+brokerPort,unsafe); messageLog.verifyDurability();
        } finally { admin.shutdown(); }
    }
    private static void pump(ComputeV2Checkpoint checkpoint,BooleanSupplier done) {
        long end = System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        do { checkpoint.tick(); } while (!done.getAsBoolean() && System.nanoTime()<end);
        assertTrue(done.getAsBoolean(),"Observed broker/checkpoint condition did not converge");
    }
    private static int port() throws Exception { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static Process launch(String main,Path config,String name,int heap) throws Exception {
        var command = new ArrayList<>(List.of("D:/software/Java/jdk-17/bin/java.exe","-Xms"+heap+"m","-Xmx"+heap+"m",
                "-XX:MaxDirectMemorySize=128m","-Duser.home="+directory,"-Drocketmq.home.dir="+INSTALL,
                "-Drocketmq.log.root="+directory.resolve("logs"),"-cp",INSTALL+"/conf;"+INSTALL+"/lib/*",main,"-c",config.toString()));
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }
    private static void ready(Process process,String marker) throws Exception {
        var output = new BufferedReader(new InputStreamReader(process.getInputStream()));
        assertTrue(output.lines().peek(line -> { try { Files.writeString(directory.resolve("startup.log"),line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
                    catch (IOException ex) { throw new UncheckedIOException(ex); } }).anyMatch(line -> line.contains(marker)));
    }
    private static void stop(Process process) throws Exception {
        if (process != null) { process.destroy(); process.waitFor(20,TimeUnit.SECONDS); assertFalse(process.isAlive()); }
    }
    private static class MutableClock extends Clock {
        Instant at; MutableClock(Instant at) { this.at = at; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return at; }
    }
}
