package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.dto.SupportBindingRequest;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.realtime.ConversationSocketCommands;
import ffdd.opsconsole.onboarding.application.OnboardingCalibrationService;
import ffdd.opsconsole.shared.security.AdminSessionRegistry;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Real HTTP and two MySQL connections: the replay transaction keeps a pre-commit RR snapshot. */
@org.springframework.context.annotation.Import(SupportIsolatedRuntime.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={"server.port=${S4_HTTP_PORT:18129}",
    "nexion.support.attachments.allowed-mime-types=image/png,image/jpeg","nexion.support.attachments.max-bytes=1048576",
    "nexion.support.attachments.max-pixels=1000000","nexion.support.attachments.ttl-seconds=300"})
@EnabledIfEnvironmentVariable(named="S4_EVIDENCE_DIR",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportMessageReplayMySqlS4Test {
    @org.springframework.test.context.DynamicPropertySource static void coreBoundary(org.springframework.test.context.DynamicPropertyRegistry registry) {
        if("true".equals(System.getenv("CS_ENHANCE_CORE_ENABLED")))SupportEnhancementPreparationTest.isolatedBoundary(registry);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SupportBindingService bindings;
    @Autowired SupportBindingMapper assignments;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JwtTokenProvider tokens;
    @Autowired AdminSessionRegistry sessions;
    @Autowired OnboardingCalibrationService onboarding;
    @SpyBean(proxyTargetAware=true) SupportOwnershipService ownership;
    @SpyBean(proxyTargetAware=true) ConversationSocketCommands socketCommands;

    private final HttpClient client=HttpClient.newHttpClient();
    private final Map<String,Object> evidence=new LinkedHashMap<>();
    private final AtomicReference<Gate> gate=new AtomicReference<>();
    private final AtomicReference<MutexGate> mutexGate=new AtomicReference<>();
    private final ThreadLocal<String> socketCommandKey=new ThreadLocal<>();
    private long customer,agent,boss;
    private String adminToken,appToken;

    enum Route { APP_CREATE,APP_REPLY,ADMIN_INITIATE,ADMIN_REPLY,APP_CREATE_TRANSFER,ADMIN_WS_INITIATE,ADMIN_WS_REPLY }

    private static final class Gate {
        final String retryKey,firstKey,clientId;
        final long customer;
        final CountDownLatch snapshotReady=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicBoolean intercepted=new AtomicBoolean();
        final AtomicLong retryConnection=new AtomicLong(),firstConnection=new AtomicLong();
        final AtomicReference<Throwable> setupFailure=new AtomicReference<>();
        Gate(String retryKey,String firstKey,String clientId,long customer) {
            this.retryKey=retryKey;this.firstKey=firstKey;this.clientId=clientId;this.customer=customer;
        }
    }

    private static final class MutexGate {
        final String firstKey,secondKey;
        final long customer;
        final CountDownLatch firstLocked=new CountDownLatch(1),secondAtCustomer=new CountDownLatch(1),
                secondLocked=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicBoolean firstIntercepted=new AtomicBoolean(),secondIntercepted=new AtomicBoolean();
        final AtomicLong firstConnection=new AtomicLong(),secondConnection=new AtomicLong();
        MutexGate(String firstKey,String secondKey,long customer) {
            this.firstKey=firstKey;this.secondKey=secondKey;this.customer=customer;
        }
    }

    @BeforeEach void fixture() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo(SupportIsolatedRuntime.database());
        assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(33329);
        assertThat(System.getenv("S3_FIXTURE_PASSWORD")!=null && !System.getenv("S3_FIXTURE_PASSWORD").isBlank())
                .as("The isolated fixture password must be configured").isTrue();
        boss=admin("SUPER_ADMIN","MANAGER");agent=admin("SUPPORT","DEDICATED");
        as(boss);
        customer=new TransactionTemplate(transactions).execute(status->{
            String ref=UUID.randomUUID().toString().replace("-","").substring(0,20);
            String phone="198"+String.format("%08d",Math.abs((long)ref.hashCode())%100000000);
            jdbc.update("INSERT INTO nx_user(country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox) VALUES('+86',?,'127.0.0.1',?,?,?,'ACTIVE',0)",
                    phone,passwordHash(),"S4 replay "+ref,ref);
            long id=jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,ref);
            bindings.register(id,null);return id;
        });
        var assignment=assignments.current(customer);
        assertThat(bindings.transfer(key(),new SupportBindingRequest(agent,List.of(new SupportBindingRequest.Customer(customer,
                assignment==null?null:assignment.id(),assignment==null?assignments.poolVersion(customer):assignment.version())),
                "Isolated S4 replay race fixture")).getCode()).isZero();
        String name=jdbc.queryForObject("SELECT username FROM nx_admin WHERE id=?",String.class,agent);
        adminToken=tokens.createToken(agent,"ADMIN",name,List.of(),sessions.createSession(agent,name));
        appToken=userToken();
        SecurityContextHolder.clearContext();

        doAnswer(invocation->{
            JsonNode frame=invocation.getArgument(2);
            socketCommandKey.set(frame.path("idempotencyKey").asText(null));
            try {return invocation.callRealMethod();}
            finally {socketCommandKey.remove();}
        }).when(socketCommands).execute(any(),any(),any());

        // The latch is inside the HTTP action's actual transaction, not an unrelated test-side transaction.
        // It runs before the customer mutex, so the first request can commit on a second connection.
        doAnswer(invocation->{
            Gate current=gate.get();
            var attributes=RequestContextHolder.getRequestAttributes();
            String command=socketCommandKey.get();
            if(command==null && attributes instanceof ServletRequestAttributes servlet)
                command=servlet.getRequest().getHeader("Idempotency-Key");
            if(current!=null && Objects.equals(invocation.getArgument(0),current.customer)) {
                if(current.firstKey.equals(command))
                    current.firstConnection.compareAndSet(0,jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                if(current.retryKey.equals(command) && current.intercepted.compareAndSet(false,true)) {
                    try {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                        assertThat(jdbc.execute((ConnectionCallback<Integer>)Connection::getTransactionIsolation))
                                .as("Regression must exercise the real RR action transaction").isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                        current.retryConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_human_message WHERE customer_id=? AND client_message_id=?",
                                Long.class,current.customer,current.clientId)).isZero();
                    } catch(Throwable failure) {
                        current.setupFailure.set(failure);throw failure;
                    } finally { current.snapshotReady.countDown(); }
                    assertThat(current.release.await(25,TimeUnit.SECONDS)).as("First message must commit before replay continues").isTrue();
                    assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class)).isEqualTo(current.retryConnection.get());
                    // This ordinary read MUST remain stale. FOR SHARE in the production dedup path must see the winner.
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_human_message WHERE customer_id=? AND client_message_id=?",
                            Long.class,current.customer,current.clientId)).as("RR snapshot was retained across the competing commit").isZero();
                }
            }
            MutexGate mutex=mutexGate.get();
            boolean first=false,second=false;
            if(mutex!=null && Objects.equals(invocation.getArgument(0),mutex.customer)) {
                first=mutex.firstKey.equals(command) && mutex.firstIntercepted.compareAndSet(false,true);
                second=mutex.secondKey.equals(command) && mutex.secondIntercepted.compareAndSet(false,true);
                if(first)mutex.firstConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                if(second) {
                    mutex.secondConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                    mutex.secondAtCustomer.countDown();
                }
            }
            Object result=invocation.callRealMethod();
            if(first) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                mutex.firstLocked.countDown();
                assertThat(mutex.release.await(25,TimeUnit.SECONDS)).as("Release the first real customer lock holder").isTrue();
            }
            if(second)mutex.secondLocked.countDown();
            return result;
        }).when(ownership).lockCustomer(any());
    }

    @AfterEach void release() {
        Gate current=gate.getAndSet(null);if(current!=null)current.release.countDown();
        MutexGate mutex=mutexGate.getAndSet(null);if(mutex!=null)mutex.release.countDown();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name="readFirst={0}: read and reply serialize at customer before header")
    @ValueSource(booleans={true,false})
    void readAndReplyUseCustomerFirstMutex(boolean readFirst) throws Exception {
        String no=ok(http("POST","/api/app/support/conversations",appToken,
                Map.of("conversationType","support","openingText","Read and reply mutex seed"),key()))
                .path("conversation").path("conversationNo").asText();
        String seedClient=key(),seedText="First agent message for read mutex";
        JsonNode initialHeader=ok(http("GET","/api/app/support/conversations/"+no,appToken,null,null)).path("conversation");
        ok(http("POST","/api/admin/content/conversations/"+no+"/replies",adminToken,
                Map.of("body",seedText,"kind","TEXT","intent","SERVICE","clientMessageId",seedClient,
                        "expectedAssignmentId",assignments.current(customer).id(),"expectedStatus",initialHeader.path("status").asText(),
                        "expectedVersion",initialHeader.path("version").asLong(),"reason","Seed the read receipt regression"),key()));
        long seedMessage=jdbc.queryForObject("SELECT message_id FROM nx_support_human_message WHERE customer_id=? AND client_message_id=?",
                Long.class,customer,seedClient);
        JsonNode before=ok(http("GET","/api/app/support/conversations/"+no,appToken,null,null)).path("conversation");
        assertThat(before.path("unreadCount").asInt()).isEqualTo(1);
        long countBefore=messageCount();
        String readKey=key(),replyKey=key(),replyClient=key(),text="Concurrent reply remains unread "+key();
        String readPath=readFirst?"/api/app/support/conversations/"+no+"/read":"/content/app/conversations/"+no+"/receipts/read";
        var readBody=Map.of("lastSeenMessageId",seedMessage,"expectedStatus",before.path("status").asText(),
                "expectedVersion",before.path("version").asLong());
        var replyBody=Map.of("body",text,"kind","TEXT","intent","SERVICE","clientMessageId",replyClient,
                "expectedAssignmentId",assignments.current(customer).id(),"expectedStatus",before.path("status").asText(),
                "expectedVersion",before.path("version").asLong(),"reason","Concurrent reply and read mutex regression");
        Callable<JsonNode> read=()->http("POST",readPath,appToken,readBody,readKey);
        Callable<JsonNode> reply=()->http("POST","/api/admin/content/conversations/"+no+"/replies",adminToken,replyBody,replyKey);
        MutexGate mutex=new MutexGate(readFirst?readKey:replyKey,readFirst?replyKey:readKey,customer);
        mutexGate.set(mutex);
        var sample=new LinkedHashMap<String,Object>();sample.put("passed",false);
        evidence.put(readFirst?"MUTEX_READ_FIRST":"MUTEX_REPLY_FIRST",sample);
        ExecutorService executor=Executors.newFixedThreadPool(2);
        Future<JsonNode> first=executor.submit(readFirst?read:reply),second=null;
        try {
            assertThat(mutex.firstLocked.await(15,TimeUnit.SECONDS)).as("First HTTP action acquired the actual customer row mutex").isTrue();
            second=executor.submit(readFirst?reply:read);
            assertThat(mutex.secondAtCustomer.await(15,TimeUnit.SECONDS)).as("Second HTTP action reached customer lock before any header lock").isTrue();
            assertThat(mutex.secondLocked.await(300,TimeUnit.MILLISECONDS)).as("Second action is blocked inside customer FOR UPDATE").isFalse();
            assertThat(second.isDone()).isFalse();
            assertThat(mutex.firstConnection.get()).isPositive().isNotEqualTo(mutex.secondConnection.get());
            // A third real transaction can lock the header while both requests stop at the customer layer.
            // This fails immediately if the waiting read/reply took header first; no performance_schema grants needed.
            Long headerId=new TransactionTemplate(transactions).execute(status->jdbc.queryForObject(
                    "SELECT id FROM nx_conversation WHERE conversation_no=? AND is_deleted=0 FOR UPDATE NOWAIT",Long.class,no));
            assertThat(headerId).isPositive();
            sample.put("headerAvailableWhileCustomerBlocked",true);
            sample.put("firstConnection",mutex.firstConnection.get());sample.put("secondConnection",mutex.secondConnection.get());
            mutex.release.countDown();
            JsonNode firstResult=first.get(15,TimeUnit.SECONDS),secondResult=second.get(15,TimeUnit.SECONDS);
            assertThat(mutex.secondLocked.getCount()).isZero();
            JsonNode readResult=readFirst?firstResult:secondResult,replyResult=readFirst?secondResult:firstResult;
            assertThat(replyResult.path("code").asInt(-1)).as("Reply response has no deadlock/500").isZero();
            if(readFirst)assertThat(readResult.path("code").asInt(-1)).isZero();
            else assertThat(readResult.path("code").asInt(-1)).as("Old read version may conflict but cannot deadlock").isIn(0,409);
            long replyMessage=jdbc.queryForObject("SELECT message_id FROM nx_support_human_message WHERE customer_id=? AND client_message_id=?",
                    Long.class,customer,replyClient);
            assertThat(messageCount()).isEqualTo(countBefore+1);
            JsonNode refreshed=ok(http("GET","/api/app/support/conversations/"+no,appToken,null,null));
            assertContainsCommittedMessage(refreshed,seedMessage,seedText,seedClient);
            assertContainsCommittedMessage(refreshed,replyMessage,text,replyClient);
            boolean readSucceeded=readResult.path("code").asInt()==0;
            assertThat(refreshed.path("conversation").path("unreadCount").asInt()).isEqualTo(readSucceeded?1:2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message_receipt WHERE message_id=? AND receipt_status='read'",
                    Long.class,seedMessage)).isEqualTo(readSucceeded?1:0);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message_receipt WHERE message_id=? AND receipt_status='read'",
                    Long.class,replyMessage)).isZero();
            // Refresh the version and explicitly read the new message; both receipts persist on the next GET.
            JsonNode currentHeader=refreshed.path("conversation");
            ok(http("POST","/api/app/support/conversations/"+no+"/read",appToken,
                    Map.of("lastSeenMessageId",replyMessage,"expectedStatus",currentHeader.path("status").asText(),
                            "expectedVersion",currentHeader.path("version").asLong()),key()));
            JsonNode afterRead=ok(http("GET","/api/app/support/conversations/"+no,appToken,null,null));
            assertThat(afterRead.path("conversation").path("unreadCount").asInt()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message_receipt WHERE message_id IN (?,?) AND receipt_status='read'",
                    Long.class,seedMessage,replyMessage)).isEqualTo(2);
            sample.put("readCode",readResult.path("code").asInt());sample.put("replyCode",replyResult.path("code").asInt());
            sample.put("readPath",readPath);sample.put("finalUnread",0);sample.put("passed",true);
        } finally {
            mutex.release.countDown();mutexGate.set(null);executor.shutdown();
            if(!executor.awaitTermination(40,TimeUnit.SECONDS)){first.cancel(true);if(second!=null)second.cancel(true);executor.shutdownNow();}
        }
    }

    @AfterAll void writeEvidence() throws Exception {
        Path directory=Path.of(System.getenv("S4_EVIDENCE_DIR"));Files.createDirectories(directory);
        Files.writeString(directory.resolve("message-replay-mysql-evidence.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("at",Instant.now().toString(),"scenarios",evidence)));
    }

    @ParameterizedTest(name="{0}: stale RR retry returns the committed message")
    @EnumSource(Route.class)
    void clientMessageReplayRetainsCommittedResponseUnderOldSnapshot(Route route) throws Exception {
        boolean app=route.name().startsWith("APP");
        boolean create=!route.name().endsWith("REPLY");
        boolean transfer=route==Route.APP_CREATE_TRANSFER;
        boolean socket=route.name().startsWith("ADMIN_WS");
        String no=null;
        if(!create) no=ok(http("POST","/api/app/support/conversations",appToken,
                Map.of("conversationType","support","openingText","Seed thread for replay regression"),key())).path("conversation").path("conversationNo").asText();
        long messagesBefore=messageCount();
        String text="Durable replay message "+key(),clientId=key(),firstKey=key(),retryKey=key();
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("clientMessageId",clientId);payload.put("kind","TEXT");
        if(!transfer)payload.put("expectedAssignmentId",assignments.current(customer).id());
        if(create) {
            payload.put("conversationType","support");payload.put("openingText",text);
            if(!app){payload.put("userId",customer);payload.put("reason","Explicit first contact replay regression");payload.put("intent","SERVICE");}
        } else {
            var header=ok(http("GET","/api/app/support/conversations/"+no,appToken,null,null)).path("conversation");
            payload.put("body",text);payload.put("expectedStatus",header.path("status").asText());payload.put("expectedVersion",header.path("version").asLong());
            if(!app){payload.put("intent","SERVICE");payload.put("reason","Explicit reply replay regression");}
        }
        String path=(app?"/api/app/support/conversations":"/api/admin/content/conversations")+(create?"":"/"+no+"/replies");
        String actorToken=app?appToken:adminToken;
        String existingNo=no;
        Gate current=new Gate(retryKey,firstKey,clientId,customer);gate.set(current);
        var sample=new LinkedHashMap<String,Object>();sample.put("passed",false);evidence.put(route.name(),sample);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        Future<JsonNode> retry=executor.submit(()->socket
                ?socketCommand(actorToken,create?"create":"reply",existingNo,payload,retryKey)
                :http("POST",path,actorToken,payload,retryKey));
        try {
            assertThat(current.snapshotReady.await(20,TimeUnit.SECONDS)).as("Replay reached the real customer lock transaction").isTrue();
            assertThat(current.setupFailure.get()).as("Snapshot instrumentation succeeded").isNull();
            JsonNode firstEnvelope=http("POST",path,actorToken,payload,firstKey);
            sample.put("firstCode",firstEnvelope.path("code").asInt(-1));
            JsonNode first=ok(firstEnvelope);
            long messageId=jdbc.queryForObject("SELECT message_id FROM nx_support_human_message WHERE customer_id=? AND client_message_id=?",
                    Long.class,customer,clientId);
            assertThat(current.firstConnection.get()).isPositive().isNotEqualTo(current.retryConnection.get());
            sample.put("firstConnection",current.firstConnection.get());sample.put("retryConnection",current.retryConnection.get());
            sample.put("messageId",messageId);sample.put("snapshotBeforeCommit",true);
            sample.put("transport",socket?"WS_RETRY_HTTP_FIRST":"HTTP");
            String committedNo=(app?first.path("conversation"):first).path("conversationNo").asText();
            String latestText=text,followClient=null;
            long latestVersion=(app?first.path("conversation"):first).path("version").asLong();
            long latestMessageId=messageId,newAgent=agent;
            if(transfer) {
                newAgent=admin("SUPPORT","DEDICATED");
                as(boss);
                try {
                    var currentAssignment=assignments.current(customer);
                    assertThat(bindings.transfer(key(),new SupportBindingRequest(newAgent,List.of(new SupportBindingRequest.Customer(
                            customer,currentAssignment.id(),currentAssignment.version())),"Replay spans an explicit advisor handover")).getCode()).isZero();
                } finally {SecurityContextHolder.clearContext();}
                String newToken=adminToken(newAgent);
                var header=ok(http("GET","/api/admin/content/conversations/"+committedNo,newToken,null,null)).path("conversation");
                latestText="New advisor follow-up "+key();followClient=key();
                var follow=ok(http("POST","/api/admin/content/conversations/"+committedNo+"/replies",newToken,
                        Map.of("body",latestText,"kind","TEXT","intent","SERVICE","clientMessageId",followClient,
                                "expectedAssignmentId",assignments.current(customer).id(),"expectedStatus",header.path("status").asText(),
                                "expectedVersion",header.path("version").asLong(),"reason","Reply after explicit advisor handover"),key()));
                latestVersion=follow.path("version").asLong();
                latestMessageId=jdbc.queryForObject("SELECT message_id FROM nx_support_human_message WHERE customer_id=? AND client_message_id=?",
                        Long.class,customer,followClient);
                sample.put("newAgent",newAgent);sample.put("followMessageId",latestMessageId);
            }
            current.release.countDown();
            JsonNode retryEnvelope=retry.get(35,TimeUnit.SECONDS);
            sample.put("retryCode",retryEnvelope.path("code").asInt(-1));
            JsonNode restored=ok(retryEnvelope);
            JsonNode restoredHeader=app?restored.path("conversation"):restored;
            assertThat(restoredHeader.path("conversationNo").asText()).isEqualTo(committedNo);
            assertThat(restoredHeader.path("lastMessage").asText()).isEqualTo(latestText);
            assertThat(restoredHeader.path("version").asLong()).isEqualTo(latestVersion);
            if(app) assertContainsCommittedMessage(restored,messageId,text,clientId);
            else assertThat(restoredHeader.path("lastPublicMessageId").asLong()).isEqualTo(messageId);
            if(transfer) {
                assertThat(restoredHeader.path("ownerAgentId").asText()).isEqualTo(String.valueOf(newAgent));
                assertThat(restoredHeader.path("unreadCount").asInt()).isEqualTo(1);
                assertContainsCommittedMessage(restored,latestMessageId,latestText,followClient);
                sample.put("unreadCount",1);sample.put("ownerIsCurrent",true);
            }
            assertThat(messageCount()).isEqualTo(messagesBefore+(transfer?2:1));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_human_message WHERE customer_id=? AND client_message_id=?",
                    Long.class,customer,clientId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation WHERE user_id=? AND is_deleted=0",Long.class,customer)).isEqualTo(1);
            // A fresh request confirms durable content; it does not substitute for the assertions on replay above.
            assertContainsCommittedMessage(ok(http("GET","/api/app/support/conversations/"+committedNo,appToken,null,null)),messageId,text,clientId);
            var different=new LinkedHashMap<>(payload);different.put(create?"openingText":"body",text+" changed");
            JsonNode mismatch=http("POST",path,actorToken,different,key());
            assertThat(mismatch.path("code").asInt()).isEqualTo(409);
            assertThat(messageCount()).isEqualTo(messagesBefore+(transfer?2:1));
            sample.put("wrongContentCode",409);sample.put("conversationNo",committedNo);sample.put("passed",true);
        } finally {
            current.release.countDown();gate.set(null);
            executor.shutdown();
            if(!executor.awaitTermination(40,TimeUnit.SECONDS)){retry.cancel(true);executor.shutdownNow();}
        }
    }

    private void assertContainsCommittedMessage(JsonNode detail,long messageId,String text,String clientId) {
        JsonNode found=null;
        for(JsonNode message:detail.path("messages"))if(message.path("id").asLong()==messageId)found=message;
        assertThat(found).as("Recovered response contains the committed message, not just an old conversation header").isNotNull();
        assertThat(found.path("content").asText()).isEqualTo(text);
        assertThat(found.path("clientMessageId").asText()).isEqualTo(clientId);
    }

    private long messageCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message m JOIN nx_conversation c ON c.conversation_no=m.conversation_no WHERE c.user_id=? AND m.is_deleted=0",Long.class,customer);}
    private String key(){return "s4-replay-"+UUID.randomUUID();}
    private String passwordHash(){return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(System.getenv("S3_FIXTURE_PASSWORD"));}
    private long admin(String role,String seat) {
        String name="s4_replay_"+UUID.randomUUID().toString().substring(0,8);
        jdbc.update("INSERT INTO nx_admin(username,password_hash,nickname,super_admin,status) VALUES(?,?,?,?,1)",name,passwordHash(),name,"SUPER_ADMIN".equals(role)?1:0);
        long id=jdbc.queryForObject("SELECT id FROM nx_admin WHERE username=?",Long.class,name);
        jdbc.update("INSERT INTO nx_admin_role_relation(admin_id,role_id) SELECT ?,id FROM nx_admin_role WHERE role_code=? AND is_deleted=0",id,role);
        jdbc.update("INSERT INTO nx_support_agent_profile(admin_id,seat_type,position,service_types,tags,max_concurrent,enabled,transferable,busy) VALUES(?,?,?,'support,advisor','',0,1,1,0)",id,seat,seat);
        return id;
    }
    private String adminToken(long id) {
        String name=jdbc.queryForObject("SELECT username FROM nx_admin WHERE id=?",String.class,id);
        return tokens.createToken(id,"ADMIN",name,List.of(),sessions.createSession(id,name));
    }
    private void as(long actor) {
        var authentication=new UsernamePasswordAuthenticationToken(String.valueOf(actor),null,
                List.of(new SimpleGrantedAuthority("service_m3_write"),new SimpleGrantedAuthority("service_m3_read")));
        authentication.setDetails(Map.of("subjectType","ADMIN","username","s4-replay-fixture"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
    private String userToken() throws Exception {
        assertThat(onboarding.defer(customer,new OnboardingCalibrationService.ActionRequest("s4-replay-device-"+customer,0,key())).getCode()).isZero();
        String session=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,expires_at,last_active_at) VALUES(?,?,?,DATE_ADD(NOW(),INTERVAL 1 DAY),NOW())",customer,session,session);
        String token=tokens.createUserToken(customer,"s4-replay-customer",List.of(),session,Duration.ofHours(8),UserAuthEnvironment.PRODUCTION);
        var terms=ok(http("GET","/api/legal/terms/current?locale=en&jurisdiction=GLOBAL",token,null,null));
        ok(http("POST","/api/legal/terms/acknowledgment",token,Map.of("locale","en","jurisdiction","GLOBAL","version",terms.path("version").asText(),
                "confirmed",true,"idempotencyKey",key(),"runId",""),null));return token;
    }
    private JsonNode http(String method,String path,String token,Object body,String command) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+SupportIsolatedRuntime.port()+path)).timeout(Duration.ofSeconds(40)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(command!=null)request.header("Idempotency-Key",command);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return json.readTree(client.send(request.build(),HttpResponse.BodyHandlers.ofString()).body());
    }
    private JsonNode socketCommand(String token,String operation,String no,Map<String,Object> body,String command) throws Exception {
        JsonNode ticket=ok(http("POST","/api/admin/content/conversations/realtime-ticket",token,null,null));
        try(var probe=new SocketProbe()) {
            probe.socket=client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+SupportIsolatedRuntime.port()+"/ws/conversations"),probe)
                    .get(10,TimeUnit.SECONDS);
            probe.send(Map.of("type","auth","ticket",ticket.path("ticket").asText()));
            probe.await("ready",null,10);
            var frame=new LinkedHashMap<String,Object>();
            frame.put("type","command");frame.put("requestId",command);frame.put("operation",operation);
            frame.put("idempotencyKey",command);frame.put("body",body);
            if(no!=null)frame.put("conversationNo",no);
            probe.send(frame);
            return probe.await("ack",command,35).path("result");
        }
    }
    private final class SocketProbe implements WebSocket.Listener,AutoCloseable {
        WebSocket socket;
        final BlockingQueue<String> frames=new LinkedBlockingQueue<>();
        final StringBuilder buffer=new StringBuilder();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        @Override public void onOpen(WebSocket ws){ws.request(1);}
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last) {
            buffer.append(data);if(last){frames.add(buffer.toString());buffer.setLength(0);}ws.request(1);return null;
        }
        @Override public void onError(WebSocket ws,Throwable error){failure.set(error);}
        void send(Object frame) throws Exception {socket.sendText(json.writeValueAsString(frame),true).get(5,TimeUnit.SECONDS);}
        JsonNode await(String type,String requestId,int seconds) throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
            while(System.nanoTime()<end) {
                assertThat(failure.get()).as("Real WebSocket transport remains available").isNull();
                String raw=frames.poll(200,TimeUnit.MILLISECONDS);
                if(raw==null)continue;
                JsonNode frame=json.readTree(raw);
                if(type.equals(frame.path("type").asText())
                        && (requestId==null || requestId.equals(frame.path("requestId").asText())))return frame;
                if("error".equals(frame.path("type").asText()))throw new AssertionError("Socket rejected frame with code "+frame.path("code").asInt());
            }
            throw new AssertionError("Missing socket event "+type);
        }
        @Override public void close(){if(socket!=null)socket.abort();}
    }
    private JsonNode ok(JsonNode envelope) {
        assertThat(envelope.path("code").asInt(-1)).as("HTTP result: %s",envelope.path("message").asText()).isZero();
        return envelope.path("data");
    }
}
