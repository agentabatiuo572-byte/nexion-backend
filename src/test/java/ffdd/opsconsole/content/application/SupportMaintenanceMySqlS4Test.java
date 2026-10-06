package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.dto.SupportBindingRequest;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportMaintenanceMapper;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Uses only fresh random fixtures in the explicitly authorized isolated database. */
@org.springframework.context.annotation.Import(SupportIsolatedRuntime.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties="server.port=${S4_HTTP_PORT:18129}")
@EnabledIfEnvironmentVariable(named="S4_EVIDENCE_DIR",matches=".+")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SupportMaintenanceMySqlS4Test {
    @org.springframework.test.context.DynamicPropertySource static void coreBoundary(org.springframework.test.context.DynamicPropertyRegistry registry) {
        if("true".equals(System.getenv("CS_ENHANCE_CORE_ENABLED")))SupportEnhancementPreparationTest.isolatedBoundary(registry);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate actorRedis;
    private SupportFixtureActors actorEvidence;
    private SupportFixtureActors fixtureActors() {
        if (actorEvidence == null) actorEvidence = new SupportFixtureActors(jdbc, actorRedis, json, transactions, run, getClass().getSimpleName());
        return actorEvidence;
    }
    @Autowired DataSource dataSource;
    @Autowired SupportBindingService bindings;
    @Autowired SupportBindingMapper bindingMapper;
    @Autowired SupportMaintenanceMapper mapper;
    @Autowired SupportOwnershipService ownership;
    @Autowired SupportMaintenanceService maintenance;
    @Autowired SupportActivityService activity;
    @Autowired SupportHumanMessageService human;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    private String run;
    private long boss,g1,g2,customer;
    private String conversation;

    @BeforeEach void fixture() throws Exception {
        run="s4m_"+UUID.randomUUID().toString().replace("-","").substring(0,10);
        fixtureActors().assertBusinessEntry();
        try(var connection=dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).contains("127.0.0.1:"+SupportRuntimeTarget.current().databasePort()+"/"+SupportIsolatedRuntime.database());
            assertThat(connection.getCatalog()).isEqualTo(SupportIsolatedRuntime.database());
        }
        boss=admin("boss","SUPER_ADMIN","MANAGER");g1=admin("g1","SUPPORT","DEDICATED");g2=admin("g2","SUPPORT","DEDICATED");
        customer=tx(()->{
            String referral=UUID.randomUUID().toString().replace("-","").substring(0,20);
            jdbc.update("INSERT INTO nx_user(country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox) VALUES('+86',?,'127.0.0.1','NO_LOGIN',?,?,'ACTIVE',0)",
                    "198"+String.format("%08d",Math.abs((long)referral.hashCode())%100000000),run,referral);
            long id=jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,referral);
            bindings.register(id,null);return id;
        });
        transfer(g1);
        conversation=run+"_conversation";
        jdbc.update("INSERT INTO nx_conversation(conversation_no,user_id,conversation_type,status,last_message,created_at,updated_at) VALUES(?,?,'support','OPEN','fixture',NOW(),NOW())",conversation,customer);
        as(g1);
    }
    @AfterEach void clearActor(){try {if(actorEvidence!=null)actorEvidence.cleanupAll(Set.of());} finally {SecurityContextHolder.clearContext();}}

    @Test void executionReplayStopResumeTransferAndRollbackPersistCorrectly() throws Exception {
        String old=UUID.randomUUID().toString();login(old);
        long first=send(g1,"first");long firstCycle=mapper.openCycle(customer).id();long baseline=mapper.openCycle(customer).baselineActivitySeq();
        tx(()->{maintenance.executed(customer,bindingMapper.current(customer),first,"replay-message");return null;});
        send(g1,"second");assertThat(mapper.executionCount(customer)).isEqualTo(2);
        assertThat(mapper.openCycle(customer).baselineActivitySeq()).isEqualTo(baseline);
        login(old);assertThat(mapper.openCycle(customer)).isNotNull();
        login(UUID.randomUUID().toString());assertThat(mapper.openCycle(customer)).isNull();
        assertThat(status(firstCycle)).isEqualTo("SUCCEEDED");
        send(g1,"after-success");assertThat(mapper.openCycle(customer).id()).isNotEqualTo(firstCycle);
        String stopKey=UUID.randomUUID().toString();long assignment=bindingMapper.current(customer).id();
        maintenance.change(customer,false,"Customer requests pause",assignment,1L,stopKey);
        var replay=maintenance.change(customer,false,"Customer requests pause",assignment,1L,stopKey).getData();
        assertThat(replay.get("enabled")).isEqualTo(false);
        assertThat(((Number)replay.get("version")).longValue()).isEqualTo(2);
        assertThatThrownBy(()->maintenance.change(customer,true,"Changed payload attempt",assignment,1L,stopKey)).isInstanceOf(RuntimeException.class);
        login(UUID.randomUUID().toString());assertThat(successes()).isEqualTo(1);
        assertThatThrownBy(()->send(g1,"stopped-send")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no=?",Long.class,conversation)).isEqualTo(3);
        maintenance.change(customer,true,"Customer requests resume",assignment,2L,UUID.randomUUID().toString());
        assertThat(mapper.openCycle(customer)).isNull();
        send(g1,"transfer-open");long transferCycle=mapper.openCycle(customer).id();transfer(g2);
        assertThat(status(transferCycle)).isEqualTo("TRANSFERRED");
        login(UUID.randomUUID().toString());assertThat(successes()).isEqualTo(1);
        assertThatThrownBy(()->send(g1,"revoked-send")).isInstanceOf(RuntimeException.class);
        as(g2);long executions=mapper.executionCount(customer);
        assertThatThrownBy(()->tx(()->{send(g2,"rollback-message");activity.interactiveLogin(customer,UUID.randomUUID().toString());throw new IllegalStateException("force rollback");}))
                .hasMessage("force rollback");
        assertThat(mapper.executionCount(customer)).isEqualTo(executions);
        assertThat(mapper.openCycle(customer)).isNull();
        evidence("sequence",Map.of("customerId",customer,"messageReplay",true,"stopResume",true,"transfer",true,"atomicRollback",true));
    }

    @Test void customerLockLinearizesActivityAndStopInBothOrders() throws Exception {
        for(boolean activityFirst:List.of(true,false)) {
            as(g1);var pref=mapper.preference(customer);
            if(pref!=null && !pref.enabled()) maintenance.change(customer,true,"Resume next concurrency run",bindingMapper.current(customer).id(),pref.version(),UUID.randomUUID().toString());
            send(g1,"race-"+activityFirst);long cycle=mapper.openCycle(customer).id();
            Runnable login=()->login(UUID.randomUUID().toString());
            Runnable stop=()->{as(g1);var p=mapper.preference(customer);maintenance.change(customer,false,"Concurrent customer pause",bindingMapper.current(customer).id(),p==null?1L:p.version(),UUID.randomUUID().toString());};
            ordered(activityFirst?login:stop,activityFirst?stop:login);
            assertThat(status(cycle)).isEqualTo(activityFirst?"SUCCEEDED":"STOPPED");
        }
        evidence("activity-stop-lock",Map.of("bothOrders",true));
    }

    @Test void customerLockLinearizesSendAndTransferInBothOrders() throws Exception {
        for(boolean sendFirst:List.of(true,false)) {
            transfer(g1);long before=mapper.executionCount(customer);
            Runnable send=()->{try{send(g1,"transfer-race-"+sendFirst);}catch(RuntimeException failure){if(sendFirst)throw failure;}};
            Runnable transfer=()->transfer(g2);
            ordered(sendFirst?send:transfer,sendFirst?transfer:send);
            assertThat(mapper.executionCount(customer)).isEqualTo(before+(sendFirst?1:0));
            assertThat(mapper.openCycle(customer)).isNull();
        }
        evidence("send-transfer-lock",Map.of("bothOrders",true));
    }

    @Test void captureFenceWaitsForCommitAndGapInvalidatesOldCoverage() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var pending=pool.submit(()->tx(()->{activity.interactiveLogin(customer,UUID.randomUUID().toString());held.countDown();await(release);return null;}));
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
            var checkpoint=pool.submit(activity::checkpoint);Thread.sleep(150);assertThat(checkpoint.isDone()).isFalse();
            release.countDown();pending.get(10,TimeUnit.SECONDS);var fence=checkpoint.get(10,TimeUnit.SECONDS);
            assertThat(mapper.activity(customer).lastEffectiveAt()).isBeforeOrEqualTo(fence.observedThroughAt());
            login(UUID.randomUUID().toString());assertThat(mapper.activity(customer).lastEffectiveAt()).isAfter(fence.observedThroughAt());
            var original=mapper.checkpointFence();
            try {
                jdbc.update("UPDATE nx_support_activity_coverage SET coverage_start_at=UTC_TIMESTAMP(6),observed_through_at=UTC_TIMESTAMP(6) WHERE id=1");
                var gap=activity.checkpoint();
                assertThat(gap.covers(gap.observedThroughAt().minusDays(7),gap.observedThroughAt())).isFalse();
                jdbc.update("UPDATE nx_support_activity_coverage SET coverage_start_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 8 DAY) WHERE id=1");
                var recovered=activity.checkpoint();
                assertThat(recovered.covers(recovered.observedThroughAt().minusDays(7),recovered.observedThroughAt())).isTrue();
                assertThat(recovered.covers(recovered.observedThroughAt().minusDays(9),recovered.observedThroughAt())).isFalse();
                assertThat(recovered.covers(recovered.observedThroughAt().minusDays(7),recovered.observedThroughAt().plusSeconds(1))).isFalse();
            } finally {jdbc.update("UPDATE nx_support_activity_coverage SET coverage_start_at=? WHERE id=1",original.coverageStartAt());}
        } finally {release.countDown();pool.shutdownNow();}
        evidence("coverage-fence",Map.of("blocksUncommittedCapture",true,"postFenceStrictlyLater",true,"gapRecoveryBoundary",true));
    }

    private long send(long agent,String label) {
        return tx(()->{as(agent);String client=run+"_"+label;var assignment=bindingMapper.current(customer);
            var prepared=human.prepare(customer,"ADMIN",agent,client,"runtime-send",label,client,"TEXT","MAINTENANCE",null,assignment.id());
            jdbc.update("INSERT INTO nx_conversation_message(conversation_id,conversation_no,sender_id,sender_type,sender_name,content,created_at,updated_at) SELECT id,conversation_no,?,'agent','runtime',?,NOW(),NOW() FROM nx_conversation WHERE conversation_no=?",agent,label,conversation);
            long message=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);human.committed(prepared,message,client);return message;
        });
    }
    private void login(String source){tx(()->{activity.interactiveLogin(customer,source);return null;});}
    private void transfer(long target){as(boss);var old=bindingMapper.current(customer);assertThat(bindings.transfer(UUID.randomUUID().toString(),new SupportBindingRequest(target,List.of(new SupportBindingRequest.Customer(customer,old==null?null:old.id(),old==null?bindingMapper.poolVersion(customer):old.version())),"Runtime formal customer handover")).getCode()).isZero();}
    private long admin(String label,String role,String seat){
        return fixtureActors().createSql(run+label,"NO_LOGIN",label,role,seat);
    }
    private void as(long actor){var auth=new UsernamePasswordAuthenticationToken(String.valueOf(actor),null,List.of(new SimpleGrantedAuthority("service_m3_write")));auth.setDetails(Map.of("subjectType","ADMIN","username",run));SecurityContextHolder.getContext().setAuthentication(auth);}
    private <T>T tx(java.util.function.Supplier<T> action){return new TransactionTemplate(transactions).execute(status->action.get());}
    private String status(long id){return jdbc.queryForObject("SELECT status FROM nx_support_maintenance_cycle WHERE id=?",String.class,id);}
    private long successes(){return jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_maintenance_cycle WHERE customer_id=? AND status='SUCCEEDED'",Long.class,customer);}
    private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("runtime latch timeout");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new RuntimeException(ex);}}
    private void ordered(Runnable first,Runnable second)throws Exception{var pool=Executors.newFixedThreadPool(2);var held=new CountDownLatch(1);var release=new CountDownLatch(1);try{var one=pool.submit(()->tx(()->{ownership.lockCustomer(customer);held.countDown();await(release);first.run();return null;}));assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();var two=pool.submit(second);Thread.sleep(150);assertThat(two.isDone()).isFalse();release.countDown();one.get(15,TimeUnit.SECONDS);two.get(15,TimeUnit.SECONDS);}finally{release.countDown();pool.shutdownNow();}}
    private void evidence(String name,Map<String,Object> data)throws Exception{Files.writeString(Path.of(System.getenv("S4_EVIDENCE_DIR"),"maintenance-"+name+".json"),json.writeValueAsString(Map.of("checkedAt",java.time.Instant.now().toString(),"checks",data)));}
}
