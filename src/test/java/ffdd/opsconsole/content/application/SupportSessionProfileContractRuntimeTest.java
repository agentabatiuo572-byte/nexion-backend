package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.NexionOpsConsoleApplication;
import ffdd.opsconsole.content.mapper.ConversationTimeoutPolicyMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** Owned real HTTP/SQL contract facets. Missing failure/session producers are explicitly partial. */
@EnabledIfEnvironmentVariable(named="CS_ANALYTICS_SESSION_CONTRACT_ENABLED",matches="true")
@SpringBootTest(classes=NexionOpsConsoleApplication.class,webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({SupportEnhancementPreparationTest.IsolatedConfiguration.class,SupportObjectEvidenceLedger.Configuration.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportSessionProfileContractRuntimeTest extends SupportOwnedContractFixture {
    @Autowired ConversationTimeoutPolicyMapper timeoutMapper;
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) {SupportEnhancementPreparationTest.isolatedBoundary(r);}

    @Test void messageRecoveryAndRealReadReceiptsStaySeparateAndOldScopeIsRevoked() throws Exception {
        long customer=ownedCustomer(first);
        String owner=token(first),client=userToken(customer);
        var created=ok(http("POST","/api/app/support/conversations",client,Map.of("conversationType","support","openingText","Owned native pending customer question"),key()));
        String no=created.path("conversation").path("conversationNo").asText();
        assertThat(no).isNotBlank();
        var conversation=jdbc.queryForMap("SELECT id,user_id,status,version,last_message_at FROM nx_conversation WHERE conversation_no=?",no);
        assertThat(((Number)conversation.get("user_id")).longValue()).isEqualTo(customer);
        long userMessage=jdbc.queryForObject("SELECT MAX(id) FROM nx_conversation_message WHERE conversation_no=? AND sender_type='user' AND is_deleted=0",Long.class,no);
        long pending=bindingMapper.pendingReplies(no);assertThat(pending).isPositive();
        long executions=executions(customer);
        var current=ok(http("GET","/api/admin/content/conversations/"+no,owner,null,null)).path("conversation");
        var read=Map.of("lastSeenMessageId",userMessage,"expectedStatus",current.path("status").asText(),"expectedVersion",current.path("version").asLong());
        ok(http("POST","/api/admin/content/conversations/"+no+"/read",owner,read,null));
        assertThat(jdbc.queryForObject("SELECT receipt_status FROM nx_conversation_message_receipt WHERE message_id=? AND conversation_no=?",String.class,userMessage,no)).isEqualTo("read");
        assertThat(bindingMapper.pendingReplies(no)).isEqualTo(pending);
        assertThat(executions(customer)).isEqualTo(executions);
        assertThat(jdbc.queryForMap("SELECT id,user_id,status,version,last_message_at FROM nx_conversation WHERE conversation_no=?",no)).isEqualTo(conversation);
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            var candidate=timeoutMapper.lockCandidate(no);assertThat(candidate).isNotNull();assertThat(candidate.hasValidPolicy()).isTrue();
            assertThat(timeoutMapper.pendingRepliesCurrent(no)).contains(userMessage);
            var future=candidate.lastActivityAt().plusMinutes(candidate.closeMinutes()+1L);
            assertThat(timeoutMapper.selectDueCloseCandidates(future,100000)).extracting(ffdd.opsconsole.content.domain.ConversationIdleCandidate::conversationNo).doesNotContain(no);
        });
        observed("read-vs-reply","Actual administrator receipt writes read but leaves pending reply, maintenance executions and last effective message unchanged; real timeout SQL excludes pending owned conversation");

        String command=key(),messageId=key();
        String recovery="/api/admin/content/support-workbench/commands/"+command;
        assertCode(http("GET",recovery,owner,null,null),404);
        var request=reply(no,owner,"Owned first accepted reply",messageId,customer);
        // Ignoring a returned acknowledgment tests subsequent lookup, not physical network disconnection.
        ok(http("POST","/api/admin/content/conversations/"+no+"/replies",owner,request,command));
        JsonNode recovered=ok(http("GET",recovery,owner,null,null));
        assertThat(recovered.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(recovered.path("resultType").asText()).isEqualTo("M3_CONVERSATION_REPLY");
        long firstMessage=jdbc.queryForObject("SELECT message_id FROM nx_support_human_message WHERE actor_type='ADMIN' AND actor_id=? AND client_message_id=?",Long.class,first,messageId);
        var firstVisible=message(ok(http("GET","/api/admin/content/conversations/"+no,owner,null,null)),firstMessage);
        assertThat(firstVisible.path("senderId").asLong()).isEqualTo(first);
        assertThat(firstVisible.path("authorConfidence").asText()).isEqualTo("VERIFIED");
        assertThat(firstVisible.path("receiptStatus").asText()).isEqualTo("sent");
        assertThat(firstVisible.path("senderAvatar").isNull()).isTrue(); // This actor has no stored avatar; never invent one.
        assertThat(firstVisible.path("clientMessageId").asText()).isEqualTo(messageId);
        ok(http("POST","/api/admin/content/conversations/"+no+"/replies",owner,request,command));
        ok(http("POST","/api/admin/content/conversations/"+no+"/replies",owner,request,key()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_human_message WHERE actor_type='ADMIN' AND actor_id=? AND client_message_id=?",Long.class,first,messageId)).isEqualTo(1L);
        var collision=new LinkedHashMap<>(request);collision.put("body","Changed text under the same client id");
        assertCode(http("POST","/api/admin/content/conversations/"+no+"/replies",owner,collision,key()),409);
        String secondId=key();ok(http("POST","/api/admin/content/conversations/"+no+"/replies",owner,reply(no,owner,"Owned second accepted reply",secondId,customer),key()));
        long secondMessage=jdbc.queryForObject("SELECT message_id FROM nx_support_human_message WHERE actor_id=? AND client_message_id=?",Long.class,first,secondId);
        markClientRead(no,client,firstMessage);
        assertThat(message(ok(http("GET","/api/admin/content/conversations/"+no,owner,null,null)),firstMessage).path("receiptStatus").asText()).isEqualTo("read");
        assertThat(message(ok(http("GET","/api/admin/content/conversations/"+no,owner,null,null)),secondMessage).path("receiptStatus").asText()).isEqualTo("sent");
        markClientRead(no,client,secondMessage);
        var receipts=jdbc.queryForList("SELECT * FROM nx_conversation_message_receipt WHERE conversation_no=? ORDER BY message_id",no);
        markClientRead(no,client,firstMessage);
        assertThat(jdbc.queryForList("SELECT * FROM nx_conversation_message_receipt WHERE conversation_no=? ORDER BY message_id",no)).isEqualTo(receipts);
        assertThat(bindingMapper.pendingReplies(no)).isEqualTo(pending);
        assertThat(executions(customer)).isEqualTo(executions);
        observed("send-read-replay","Real successful command recovery, same-key and same-client replay, changed-payload 409 and SQL single message; exact App read receipt and old cursor cannot regress newer read state");

        long foreignOwner=admin("outside_owner","SUPPORT","MANAGER"),outside=admin("outside_agent","SUPPORT","DEDICATED");
        createSupportGroup(foreignOwner,List.of(outside),"outside");
        transfer(outside,customer);
        var bound=bindingMapper.current(customer);assertThat(bound.agentAdminId()).isEqualTo(outside);
        assertHidden(http("GET","/api/admin/content/conversations/"+no,owner,null,null));
        assertHidden(http("GET",recovery,owner,null,null));
        assertHidden(http("POST","/api/admin/content/conversations/"+no+"/replies",owner,request,command));
        assertHidden(http("GET","/api/admin/content/support-workbench/customers/"+customer+"/360",owner,null,null));
        assertHidden(http("GET","/api/admin/content/support-workbench/customers/"+customer+"/devices",owner,null,null));
        assertHidden(http("GET","/api/admin/content/support-workbench/customers/"+customer+"/flows",owner,null,null));
        assertThat(ok(http("GET","/api/admin/content/conversations/"+no,token(outside),null,null)).path("messages").size()).isGreaterThanOrEqualTo(3);
        assertThat(bindingMapper.current(customer)).isEqualTo(bound);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_human_message WHERE actor_id=? AND client_message_id=?",Long.class,first,messageId)).isEqualTo(1L);
        observed("revocation","Actual cross-group customer transfer revokes old HTTP detail/profile/device/flow/recovery/replay with exact 404 and no data; fresh owner can still read persisted messages");
        gap("BE-M3-CONTRACT.avatar-attachment","Real nonnull avatar/version and object bytes remain original controlledAvatarReadScopesAndReplacement/privateImages suites; this test covers verified author with honestly missing avatar only");
        gap("BE-M3-CONTRACT.unknown","Acknowledgment-ignore + durable readback tested; physical lost response/in-flight UNKNOWN not manufactured or claimed");
        gap("BE-M3-CONTRACT.window","HTTP scope revoked; live WS/main/float browser windows require original subscriptions plus browser producer");
        gap("BE-M3-CONTRACT.timeout","Real pending candidate exclusion checked; global sweep/close and policy mutations are outside this owned-row suite");
        finish("session-message-contract-runtime.json");
    }

    @Test void completeOwnedDevicePagesAndProfileUnknownsDoNotBecomeInventedZeros() throws Exception {
        long customer=ownedCustomer(first);String owner=token(first);
        String path="/api/admin/content/support-workbench/customers/"+customer;
        var empty=ok(http("GET",path+"/devices?pageNum=1&pageSize=5",owner,null,null));
        assertThat(empty.path("status").asText()).isEqualTo("READY");
        assertThat(empty.path("data").path("total").asLong()).isZero();
        assertThat(empty.path("data").has("idleCount")).isFalse();
        assertThat(empty.path("data").path("fieldStatuses").has("idleCount")).isFalse();
        var before=ok(http("GET",path+"/360",owner,null,null)).path("profile");
        assertThat(before.path("identity").path("status").asText()).isEqualTo("READY");
        assertThat(before.path("identity").path("data").path("customerId").asLong()).isEqualTo(customer);
        assertThat(before.path("finance").path("status").asText()).isEqualTo("READY");
        assertThat(before.path("finance").path("data").path("balanceSourceStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(before.path("finance").path("data").path("refundSourceStatus").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(before.path("security").path("status").asText()).isEqualTo("FORBIDDEN");
        assertThat(before.path("riskCases").path("status").asText()).isEqualTo("FORBIDDEN");
        assertThat(before.path("security").path("data").isNull()).isTrue();
        assertThat(before.path("risk").path("status").asText()).isEqualTo("UNKNOWN");
        Set<Long> expected=new LinkedHashSet<>();
        for(int i=0;i<13;i++)expected.add(ownedDevice(customer,i));
        var seen=new LinkedHashSet<Long>();
        for(int page=1;page<=3;page++) {
            var section=ok(http("GET",path+"/devices?pageNum="+page+"&pageSize=5",owner,null,null));
            assertThat(section.path("status").asText()).isEqualTo("READY");var data=section.path("data");
            assertThat(data.path("total").asLong()).isEqualTo(13L);
            assertThat(data.path("records").size()).isEqualTo(page<3?5:3);
            assertThat(data.path("onlineCount").isNull()).isTrue();
            assertThat(data.path("fieldStatuses").path("onlineCount").asText()).isEqualTo("UNKNOWN");
            assertThat(data.path("hashrateTotal").isNull()).isTrue();
            assertThat(data.has("idleCount")).isFalse();
            assertThat(data.path("fieldStatuses").has("idleCount")).isFalse();
            for(var row:data.path("records")) {
                assertThat(seen.add(row.path("id").asLong())).isTrue();
                assertThat(row.path("userId").asLong()).isEqualTo(customer);
                assertThat(row.path("storedHashrate").asText()).isEqualTo("88.123456");
                assertThat(row.path("hashrate").isNull()).isTrue();
                assertThat(row.path("heartbeatAt").isNull()).isTrue();
                assertThat(row.path("status").asText()).isEqualTo("ACTIVE");
            }
        }
        assertThat(seen).isEqualTo(expected);
        assertThat(ok(http("GET",path+"/devices?pageNum=4&pageSize=5",owner,null,null)).path("data").path("records").isEmpty()).isTrue();
        assertCode(http("GET",path+"/devices?pageNum=0&pageSize=5",owner,null,null),422);
        assertCode(http("GET",path+"/flows?pageNum=1&pageSize=101",owner,null,null),422);
        var flows=ok(http("GET",path+"/flows?pageNum=1&pageSize=5",owner,null,null));
        assertThat(flows.path("status").asText()).isEqualTo("READY");assertThat(flows.path("data").path("total").asLong()).isZero();
        var profile=ok(http("GET",path+"/360",owner,null,null)).path("profile");
        assertThat(profile.path("devices").path("data").path("total").asLong()).isEqualTo(13L);
        assertThat(profile.path("devices").path("data").has("idleCount")).isFalse();
        assertThat(profile.path("finance").path("data").path("balanceSourceStatus").asText()).isEqualTo("UNKNOWN");
        assertHidden(http("GET",path+"/devices",token(second),null,null));
        assertHidden(http("GET",path+"/flows",token(second),null,null));
        for(String table:List.of("nx_deposit_order","nx_payment_record","nx_topup_card_admission","nx_wallet_ledger","nx_withdrawal_order","nx_vietqr_intent","nx_vietqr_reconciliation"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Long.class,customer)).as("Independent absence of actual flow source %s",table).isZero();
        observed("profile-pages","13 independent real device rows over 3 pages, exact total+set equality, empty fourth page; no heartbeat remains UNKNOWN, empty actual flow list is independently zero and not a fake missing balance");
        gap("BE-M3-CONTRACT.finance-error","No shared table rename or mock; real section ERROR and nonempty funds pagination refer original specific Core methods, need fresh signed runtime evidence");
        gap("BE-M3-CONTRACT.IDC","No invented telemetry or idle fields asserted on real device pages; native execution and client no-idle rendering remain unverified");
        gap("BE-M3-CONTRACT.tools","Five quick-entry navigation and browser main/floating windows are not backend HTTP evidence");
        finish("session-profile-contract-runtime.json");
    }
    private Map<String,Object> reply(String no,String owner,String text,String client,long customer) throws Exception {
        var current=ok(http("GET","/api/admin/content/conversations/"+no,owner,null,null)).path("conversation");
        return Map.of("body",text,"reason","Owned native conversation replay","expectedStatus",current.path("status").asText(),
            "expectedVersion",current.path("version").asLong(),"intent","SERVICE","clientMessageId",client,"expectedAssignmentId",bindingMapper.current(customer).id());
    }
    private void markClientRead(String no,String client,long message) throws Exception {
        var view=ok(http("GET","/api/app/support/conversations/"+no,client,null,null)).path("conversation");
        ok(http("POST","/api/app/support/conversations/"+no+"/read",client,Map.of("lastSeenMessageId",message,"expectedStatus",view.path("status").asText(),"expectedVersion",view.path("version").asLong()),null));
    }
    private JsonNode message(JsonNode detail,long id) {for(var row:detail.path("messages"))if(row.path("id").asLong()==id)return row;throw new AssertionError("Expected actual message absent: "+id);}
}

/** New-file-only helper: no startFixture(), blanket shared cleanup, original profile suspension or global sweeps. */
abstract class SupportOwnedContractFixture extends SupportBulkRuntimeFixture {
    private String testcase,scene;
    private JsonNode originalRules,originalBindings;
    private final Map<Long,Map<String,Object>> customers=new LinkedHashMap<>(),devices=new LinkedHashMap<>();
    private final Map<String,Object> observations=new LinkedHashMap<>(),gaps=new LinkedHashMap<>();
    private final Set<Long> createdGroups=new LinkedHashSet<>();
    private static final List<String> CUSTOMER_FACTS=List.of("nx_support_customer_route_history","nx_support_agent_user_assignment","nx_support_binding_pool","nx_support_payment_history_birth");
    @Autowired private ffdd.opsconsole.content.facade.SupportRegistrationFacade registrations;
    private static final List<String> GROUP_FACTS=List.of("nx_support_group_member_history","nx_support_account_qualification_history","nx_support_group_owner_history","nx_support_group");
    private final Map<String,Map<Long,Map<String,Object>>> ownedFacts=new LinkedHashMap<>();
    private final Map<String,List<Map<String,Object>>> originalFacts=new LinkedHashMap<>();
    private final List<Map<String,Object>> factReceipts=new ArrayList<>();
    private boolean factsCleaned;
    @BeforeEach void prepareOwned(TestInfo info) {
        testcase=info.getTestMethod().orElseThrow().getName();boundary();fixtureActors().assertBusinessEntry();
        assertThat(System.getenv("WORKFLOW_RUN_ID")).isNotBlank();assertThat(System.getenv("WORKFLOW_SNAPSHOT_HASH")).matches("[a-f0-9]{64}");
        originalRules=json.valueToTree(jdbc.queryForList("SELECT * FROM nx_support_rules ORDER BY id"));
        originalBindings=json.valueToTree(jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",Long.class)).isEqualTo(377L);
        for(String table:allFactTables()){ownedFacts.put(table,new LinkedHashMap<>());originalFacts.put(table,allFacts(table));}
        boss=admin("contract_boss","SUPER_ADMIN","MANAGER");first=admin("contract_first","SUPPORT","DEDICATED");second=admin("contract_second","SUPPORT","DEDICATED");
        initializeSupportGroups();
        assertProtected();
    }
    private List<String> allFactTables(){var result=new ArrayList<>(CUSTOMER_FACTS);result.addAll(GROUP_FACTS);return result;}
    private String primary(String table){return Set.of("nx_support_binding_pool","nx_support_payment_history_birth").contains(table)?"customer_id":"id";}
    private long identity(String table,Map<String,Object> row){return ((Number)row.get(primary(table))).longValue();}
    private List<Map<String,Object>> allFacts(String table){assertThat(allFactTables()).contains(table);return jdbc.queryForList("SELECT * FROM "+table+" ORDER BY "+primary(table));}
    private Map<String,List<Map<String,Object>>> customerFacts(long id){
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:CUSTOMER_FACTS)result.put(table,jdbc.queryForList("SELECT * FROM "+table+" WHERE "+(table.equals("nx_support_agent_user_assignment")?"user_id":"customer_id")+"=? ORDER BY "+primary(table),id));
        return result;
    }
    private void factReceipt(String event,Object proof) {
        try {
            var body=new LinkedHashMap<String,Object>();body.put("event",event);body.put("suite",getClass().getSimpleName());body.put("testcase",testcase);body.put("proof",proof);
            body.put("workflowRunId",System.getenv("WORKFLOW_RUN_ID"));body.put("snapshotHash",System.getenv("WORKFLOW_SNAPSHOT_HASH"));
            Path directory=Path.of(Objects.requireNonNull(System.getenv("CS_ENHANCE_EVIDENCE_DIR")),"owned-contract-facts");Files.createDirectories(directory);
            Path file=directory.resolve(testcase+"-"+UUID.randomUUID()+".json");byte[] bytes=json.writeValueAsBytes(body);
            try(var channel=java.nio.channels.FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
            factReceipts.add(Map.of("path",file.toString(),"sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),"event",event));
        }catch(Exception error){throw new IllegalStateException("Exact owned fact receipt failed",error);}
    }
    @Override ffdd.opsconsole.content.domain.SupportGroupFacts.Group createSupportGroup(long owner,List<Long> members,String label) {
        fixtureActors().creationReference(owner);members.forEach(id->fixtureActors().creationReference(id));as(boss);
        grantOwnedQualification(owner,"SUPERVISOR");
        var request=new ffdd.opsconsole.content.dto.SupportGroupRequests.Create(run+"_"+label,owner,"Explicit owned group scope runtime fixture");
        var created=groupCommand("CREATE",request,owner,()->groupService.create(activeGroupKey,request).getData());
        assertThat(created).isNotNull();createdGroups.add(created.id());
        for(long member:members){
            grantOwnedQualification(member,"SERVICE");var before=groupMapper.memberCurrent(member);
            assertThat(before==null || before.groupId()==null).isTrue();
            var move=new ffdd.opsconsole.content.dto.SupportGroupRequests.Move(created.id(),before==null?0L:before.version(),null,groupMapper.group(created.id()).version(),"Explicit owned group scope runtime fixture");
            groupCommand("MEMBER",List.of(member,move),member,()->groupService.move(member,activeGroupKey,move).getData());
        }
        return groupMapper.group(created.id());
    }
    private String activeGroupKey;
    private void grantOwnedQualification(long admin,String kind){
        var old=groupMapper.qualification(admin,kind);if(old!=null&&"ENABLED".equals(old.state()))return;
        var request=new ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification(kind,"ENABLED",old==null?0L:old.version(),jdbc.queryForObject("SELECT version FROM nx_admin WHERE id=?",Long.class,admin),"Explicit owned group scope runtime fixture");
        groupCommand("QUALIFICATION",List.of(admin,request),admin,()->groupService.qualification(admin,activeGroupKey,request).getData());
    }
    private <T> T groupCommand(String kind,Object fingerprint,long target,java.util.function.Supplier<T> action){
        String command=key(),scope="SUPPORT_GROUP_"+kind+":"+boss;activeGroupKey=command;
        var before=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:GROUP_FACTS)before.put(table,allFacts(table));
        assertThat(jdbc.queryForList("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",scope,command)).isEmpty();
        String hash;
        try{hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(fingerprint.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException(failure);}
        factReceipt("GROUP_COMMAND_INTENT",Map.of("scope",scope,"commandKey",command,"requestHash",hash,"request",fingerprint,"targetCreator",fixtureActors().creationReference(target),"actorCreator",fixtureActors().creationReference(boss),"before",before));
        Throwable primaryFailure=null;
        try{return action.get();}catch(RuntimeException|Error failure){primaryFailure=failure;throw failure;}
        finally{
            try{captureGroupCommand(kind,target,command,scope,hash,fingerprint,before);}
            catch(RuntimeException|Error captureFailure){if(primaryFailure!=null)primaryFailure.addSuppressed(captureFailure);else throw captureFailure;}
        }
    }
    private void captureGroupCommand(String kind,long target,String command,String scope,String hash,Object fingerprint,Map<String,List<Map<String,Object>>> before){
        var claims=jdbc.queryForList("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",scope,command);
        var after=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:GROUP_FACTS)after.put(table,allFacts(table));
        if(claims.isEmpty()){assertThat(after).isEqualTo(before);factReceipt("GROUP_COMMAND_NO_COMMIT",Map.of("scope",scope,"commandKey",command,"before",before,"after",after));return;}
        assertThat(claims).hasSize(1);var claim=claims.get(0);assertThat(claim.get("request_hash")).isEqualTo(hash);
        if("FAILED".equals(claim.get("status"))){assertThat(after).isEqualTo(before);factReceipt("GROUP_COMMAND_ROLLED_BACK",Map.of("scope",scope,"commandKey",command,"claim",claim,"before",before,"after",after));return;}
        assertThat(claim.get("status")).as("Unknown/processing cannot authorize owned cleanup").isEqualTo("SUCCEEDED");
        String operation=boss+":"+command;
        // Enrol every causally proven row immediately. One refused row must not hide other proven commits.
        var captures=new ArrayList<Runnable>();
        for(String table:GROUP_FACTS){
            var oldById=new LinkedHashMap<Long,Map<String,Object>>();before.get(table).forEach(row->oldById.put(identity(table,row),row));
            for(var row:after.get(table)){
                long id=identity(table,row);var old=oldById.remove(id);if(Objects.equals(old,row))continue;
                captures.add(()->{
                if(old!=null){
                    assertThat(ownedFacts.get(table).get(id)).isEqualTo(old);
                    for(var field:old.entrySet())if(!Set.of("version","ends_at","updated_at","current_agent_id","current_admin_id","current_group_id").contains(field.getKey()))assertThat(row.get(field.getKey())).as("Only owned history end/version or group version may change").isEqualTo(field.getValue());
                    if(table.equals("nx_support_group"))assertThat(((Number)row.get("version")).longValue()).isEqualTo(((Number)old.get("version")).longValue()+1);
                    else{
                        assertThat(row.get("version")).isEqualTo(old.get("version"));assertThat(old.get("ends_at")).isNull();assertThat(row.get("ends_at")).isNotNull();
                        String generated=table.equals("nx_support_group_member_history")?"current_agent_id":table.equals("nx_support_account_qualification_history")?"current_admin_id":"current_group_id";assertThat(row.get(generated)).isNull();
                    }
                }
                else if(table.equals("nx_support_group")){
                    assertThat(kind).isEqualTo("CREATE");assertThat(((Number)row.get("supervisor_admin_id")).longValue()).isEqualTo(target);assertThat(row.get("name")).isEqualTo(((ffdd.opsconsole.content.dto.SupportGroupRequests.Create)fingerprint).name());
                }else{
                    assertThat(row.get("operation_id")).isEqualTo(operation);assertThat(((Number)row.get("actor_admin_id")).longValue()).isEqualTo(boss);
                    if(table.equals("nx_support_group_owner_history"))assertThat(kind).isEqualTo("CREATE");
                    if(table.equals("nx_support_account_qualification_history")){
                        assertThat(kind).isEqualTo("QUALIFICATION");var request=(ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification)((List<?>)fingerprint).get(1);assertThat(row.get("qualification_kind")).isEqualTo(request.qualificationKind());assertThat(row.get("state")).isEqualTo(request.state());
                    }
                    if(table.equals("nx_support_group_member_history")){
                        assertThat(kind).isIn("QUALIFICATION","MEMBER");Object expectedGroup=kind.equals("QUALIFICATION")?null:((ffdd.opsconsole.content.dto.SupportGroupRequests.Move)((List<?>)fingerprint).get(1)).targetGroupId();assertThat(row.get("group_id")).isEqualTo(expectedGroup);
                    }
                    String subject=table.equals("nx_support_group_member_history")?"agent_admin_id":table.equals("nx_support_account_qualification_history")?"admin_id":"supervisor_admin_id";
                    assertThat(((Number)row.get(subject)).longValue()).isEqualTo(target);
                }
                ownedFacts.get(table).put(id,row);if(table.equals("nx_support_group"))createdGroups.add(id);
                });
            }
            captures.add(()->assertThat(oldById).as("Production group commands cannot delete existing history").isEmpty());
        }
        SupportObjectEvidenceLedger.cleanupIndependently(captures.toArray(Runnable[]::new));
        factReceipt("GROUP_COMMAND_COMMITTED_READBACK",Map.of("scope",scope,"commandKey",command,"requestHash",hash,"claim",claim,"before",before,"after",after));
    }
    private void rememberCustomerFacts(long customer,String event,Map<String,List<Map<String,Object>>> before) {
        assertThat(jdbc.queryForMap("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",customer)).isEqualTo(customers.get(customer));
        var after=customerFacts(customer);
        for(String table:CUSTOMER_FACTS){
            var tracked=ownedFacts.get(table);for(var old:before.get(table))assertThat(tracked.get(identity(table,old))).isEqualTo(old);
            for(var old:before.get(table))tracked.remove(identity(table,old));
            for(var row:after.get(table)) {
                assertThat(((Number)row.get(table.equals("nx_support_agent_user_assignment")?"user_id":"customer_id")).longValue()).isEqualTo(customer);
                if(table.equals("nx_support_agent_user_assignment")){long actor=((Number)row.get("agent_admin_id")).longValue();assertThat(admins).contains(actor);fixtureActors().creationReference(actor);assertThat(row.get("operation_id")).isNotNull();}
                if(table.equals("nx_support_customer_route_history")&&row.get("group_id")!=null)assertThat(createdGroups).contains(((Number)row.get("group_id")).longValue());
                tracked.put(identity(table,row),row);
            }
        }
        factReceipt(event,Map.of("customerCreationIdentity",customers.get(customer),"before",before,"after",after));
    }
    @Override void transfer(long actor,long customer) {
        assertThat(customers).containsKey(customer);fixtureActors().creationReference(actor);
        var before=customerFacts(customer);as(boss);var old=bindingMapper.current(customer);String command=key();
        var request=new ffdd.opsconsole.content.dto.SupportBindingRequest(actor,List.of(new ffdd.opsconsole.content.dto.SupportBindingRequest.Customer(customer,old==null?null:old.id(),old==null?bindingMapper.poolVersion(customer):old.version())),"Bulk isolated formal transfer");
        assertThat(jdbc.queryForList("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?","SUPPORT_TRANSFER:"+boss,command)).isEmpty();
        factReceipt("CUSTOMER_TRANSFER_INTENT",Map.of("customer",customers.get(customer),"targetCreator",fixtureActors().creationReference(actor),"actorCreator",fixtureActors().creationReference(boss),"before",before,"commandKey",command,"request",request,"claimBeforeAbsent",true));
        try{assertThat(bindings.transfer(command,request).getCode()).isZero();}catch(RuntimeException|Error failure){assertThat(customerFacts(customer)).isEqualTo(before);throw failure;}
        var claimed=jdbc.queryForList("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?","SUPPORT_TRANSFER:"+boss,command);assertThat(claimed).hasSize(1);assertThat(claimed.get(0).get("status")).isEqualTo("SUCCEEDED");
        Set<Long> oldAssignments=new HashSet<>();before.get("nx_support_agent_user_assignment").forEach(row->oldAssignments.add(((Number)row.get("id")).longValue()));
        for(var row:customerFacts(customer).get("nx_support_agent_user_assignment"))if(!oldAssignments.contains(((Number)row.get("id")).longValue()))assertThat(row.get("operation_id")).isEqualTo(command);
        rememberCustomerFacts(customer,"CUSTOMER_TRANSFER_COMMITTED_READBACK",before);
        factReceipt("CUSTOMER_TRANSFER_EXACT_COMMAND_RECEIPT",Map.of("commandKey",command,"request",request,"claim",claimed.get(0),"customer",customers.get(customer)));
    }
    private void deleteExactFact(String table,Map<String,Object> row) {
        assertThat(allFactTables()).contains(table);
        if(CUSTOMER_FACTS.contains(table)){long customer=((Number)row.get(table.equals("nx_support_agent_user_assignment")?"user_id":"customer_id")).longValue();assertThat(customers).containsKey(customer);assertThat(jdbc.queryForMap("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",customer)).isEqualTo(customers.get(customer));}
        assertThat(jdbc.queryForMap("SELECT * FROM "+table+" WHERE "+primary(table)+"=?",row.get(primary(table)))).isEqualTo(row);
        var columns=new ArrayList<>(row.keySet());columns.forEach(column->assertThat(column).matches("[a-zA-Z0-9_]+"));
        String fence=String.join(" AND ",columns.stream().map(column->"`"+column+"` <=> ?").toList());
        factReceipt("EXACT_FACT_CLEANUP_INTENT",Map.of("table",table,"row",row));
        assertThat(jdbc.update("DELETE FROM "+table+" WHERE "+fence,columns.stream().map(row::get).toArray())).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM "+table+" WHERE "+primary(table)+"=?",row.get(primary(table)))).isEmpty();
        ownedFacts.get(table).remove(identity(table,row));factReceipt("EXACT_FACT_CLEANED",Map.of("table",table,"row",row,"affectedRows",1,"afterAbsent",true));
    }
    private void cleanupExactTables(List<String> tables){
        var cleanup=new ArrayList<Runnable>();
        for(String table:tables){for(var row:new ArrayList<>(ownedFacts.get(table).values()))cleanup.add(()->deleteExactFact(table,row));cleanup.add(()->assertThat(allFacts(table)).isEqualTo(originalFacts.get(table)));}
        SupportObjectEvidenceLedger.cleanupIndependently(cleanup.toArray(Runnable[]::new));
    }
    private void cleanupOwnedCustomerFacts(){
        var cleanup=new ArrayList<Runnable>();
        for(var customer:customers.entrySet())cleanup.add(()->assertThat(jdbc.queryForMap("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",customer.getKey())).isEqualTo(customer.getValue()));
        cleanup.add(()->cleanupExactTables(CUSTOMER_FACTS));SupportObjectEvidenceLedger.cleanupIndependently(cleanup.toArray(Runnable[]::new));
    }
    @Override void cleanupSupportGroupFacts(){cleanupExactTables(GROUP_FACTS);factsCleaned=true;}
    void assertOriginalBindingsAndExactOwnedExtras(){
        var original=originalFacts.get("nx_support_agent_user_assignment");var owned=ownedFacts.get("nx_support_agent_user_assignment");
        var all=allFacts("nx_support_agent_user_assignment");assertThat(all).containsAll(original);
        Set<Long> oldIds=new HashSet<>();original.forEach(row->oldIds.add(identity("nx_support_agent_user_assignment",row)));
        for(var row:all)if(!oldIds.contains(identity("nx_support_agent_user_assignment",row)))assertThat(owned.get(identity("nx_support_agent_user_assignment",row))).isEqualTo(row);
    }
    JsonNode ok(JsonNode response) {assertCode(response,0);assertThat(response.has("data")).isTrue();return response.path("data");}
    void assertCode(JsonNode response,int exact) {assertThat(response.path("code").asInt(-1)).as(response.toString()).isEqualTo(exact);}
    void assertHidden(JsonNode response) {assertCode(response,404);assertThat(response.path("data").isNull()||response.path("data").isMissingNode()).isTrue();}
    void assertProtected() {
        JsonNode rulesNow=json.valueToTree(jdbc.queryForList("SELECT * FROM nx_support_rules ORDER BY id"));
        assertThat(rulesNow).isEqualTo(originalRules);
        for(var row:originalBindings) {
            var current=jdbc.queryForMap("SELECT * FROM nx_support_agent_user_assignment WHERE id=?",row.path("id").asLong());
            JsonNode actual=json.valueToTree(current);assertThat(actual).isEqualTo(row);
        }
    }
    long ownedCustomer(long agent) {long customer=ownedRegistrationCustomer(null,false);transfer(agent,customer);return customer;}
    long ownedRegistrationCustomer(Long inviter,boolean selfCycle) {
        if(inviter!=null)assertThat(customers).containsKey(inviter);
        String referral=UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase();
        String phone="198"+String.format("%08d",Math.abs((long)referral.hashCode())%100000000);
        long id=objects().createCustomer(getClass().getSimpleName(),testcase,referral,()->{
            var generated=new org.springframework.jdbc.support.GeneratedKeyHolder();
            int affected=jdbc.update(c->{var statement=c.prepareStatement("INSERT INTO nx_user(country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox) VALUES('+86',?,'127.0.0.1','fixture-disabled-password',?,?,'ACTIVE',0)",java.sql.Statement.RETURN_GENERATED_KEYS);statement.setString(1,phone);statement.setString(2,run);statement.setString(3,referral);return statement;},generated);
            long inserted=Objects.requireNonNull(generated.getKey()).longValue();
            long lookup=jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,referral);
            assertThat(affected).isEqualTo(1);assertThat(lookup).isEqualTo(inserted);
            customerFacts(inserted).values().forEach(rows->assertThat(rows).isEmpty());
            if(inviter!=null||selfCycle)assertThat(jdbc.update("UPDATE nx_user SET sponsor_user_id=? WHERE id=? AND referral_code=?",selfCycle?inserted:inviter,inserted,referral)).isEqualTo(1);
            registrations.register(inserted,selfCycle?Long.valueOf(inserted):inviter);
            return new SupportObjectEvidenceLedger.CustomerInsert(inserted,affected,lookup);
        });
        var identity=jdbc.queryForMap("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",id);
        assertThat(identity.get("nickname")).isEqualTo(run);assertThat(((Number)identity.get("id")).longValue()).isEqualTo(id);
        customers.put(id,identity);
        var absent=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:CUSTOMER_FACTS)absent.put(table,List.of());
        rememberCustomerFacts(id,"CUSTOMER_REGISTRATION_COMMITTED_READBACK",absent);
        var birth=jdbc.queryForMap("SELECT * FROM nx_support_payment_history_birth WHERE customer_id=?",id);
        assertThat(birth.get("birth_origin")).isEqualTo("AUTH_NEW_ACCOUNT_REGISTRATION");assertThat(birth.get("capture_protocol")).isEqualTo("support-payment-attribution-v1");assertThat(birth.get("environment_status")).isEqualTo("PRODUCTION");assertThat(birth.get("birth_db_utc")).isNotNull();
        factReceipt("FACADE_REAL_NEW_ACCOUNT_BIRTH",Map.of("customerCreationIdentity",identity,"birth",birth,"inviter",inviter==null?"NONE":inviter,"source","SupportRegistrationFacade.register inside actual insertion transaction"));return id;
    }
    void withOwnedInactiveAccount(long actor,Runnable action){
        var creator=fixtureActors().creationReference(actor);assertThat(admins).contains(actor);
        var before=jdbc.queryForMap("SELECT id,username,status,is_deleted,version,created_at FROM nx_admin WHERE id=?",actor);assertThat(((Number)before.get("status")).intValue()).isEqualTo(1);
        factReceipt("OWNED_ACCOUNT_INACTIVE_FIXTURE_INTENT",Map.of("creator",creator,"before",before,"source","withOwnedInactiveAccount: existing binding eligibility state only, does not claim A1 disable workflow"));
        assertThat(jdbc.update("UPDATE nx_admin SET status=0 WHERE id=? AND username=? AND status=1 AND is_deleted=? AND version=? AND created_at=?",actor,before.get("username"),before.get("is_deleted"),before.get("version"),before.get("created_at"))).isEqualTo(1);
        var inactive=new LinkedHashMap<>(before);inactive.put("status",0);assertThat(jdbc.queryForMap("SELECT id,username,status,is_deleted,version,created_at FROM nx_admin WHERE id=?",actor)).isEqualTo(inactive);
        Throwable primaryFailure=null;
        try{factReceipt("OWNED_ACCOUNT_INACTIVE_FIXTURE_READBACK",Map.of("creator",creator,"before",before,"after",inactive));action.run();}
        catch(RuntimeException|Error failure){primaryFailure=failure;throw failure;}
        finally{try{
            assertThat(jdbc.queryForMap("SELECT id,username,status,is_deleted,version,created_at FROM nx_admin WHERE id=?",actor)).isEqualTo(inactive);
            assertThat(jdbc.update("UPDATE nx_admin SET status=1 WHERE id=? AND username=? AND status=0 AND is_deleted=? AND version=? AND created_at=?",actor,before.get("username"),before.get("is_deleted"),before.get("version"),before.get("created_at"))).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT id,username,status,is_deleted,version,created_at FROM nx_admin WHERE id=?",actor)).isEqualTo(before);factReceipt("OWNED_ACCOUNT_INACTIVE_FIXTURE_RESTORED",Map.of("creator",creator,"restored",before));
        }catch(RuntimeException|Error restoreFailure){if(primaryFailure!=null)primaryFailure.addSuppressed(restoreFailure);else throw restoreFailure;}}
    }
    @Override JsonNode http(String method,String path,String token,Object body,String command) throws Exception {
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(SupportRuntimeTarget.current().httpBase()+path)).timeout(Duration.ofSeconds(25)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);if(command!=null)request.header("Idempotency-Key",command);
        request.method(method,body==null?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=java.net.http.HttpClient.newHttpClient().send(request.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        var result=json.readTree(response.body());assertThat(result.path("code").isIntegralNumber()).isTrue();int code=result.path("code").asInt();
        assertThat(code==0 || code>=400 && code<=599).isTrue();assertThat(response.statusCode()).as(result.toString()).isEqualTo(code==0?200:code);return result;
    }
    long ownedDevice(long customer,int n) {
        assertThat(customers).containsKey(customer);
        String instance=run+"_contract_device_"+n;
        assertThat(jdbc.queryForList("SELECT id FROM nx_user_device WHERE instance_no=?",instance)).isEmpty();
        var generated=new org.springframework.jdbc.support.GeneratedKeyHolder();
        int affected=jdbc.update(c->{var statement=c.prepareStatement("INSERT INTO nx_user_device(user_id,instance_no,name,device_type,status,hashrate,daily_usdt,daily_nex,purchased_at,activated_at) VALUES(?,?,?,'PHONE','ACTIVE',88.123456,1.123456,2.123456,'2026-08-01 12:00:00','2026-08-01 12:00:00')",java.sql.Statement.RETURN_GENERATED_KEYS);statement.setLong(1,customer);statement.setString(2,instance);statement.setString(3,instance);return statement;},generated);
        long id=Objects.requireNonNull(generated.getKey()).longValue();assertThat(affected).isEqualTo(1);
        var row=jdbc.queryForMap("SELECT id,user_id,instance_no,created_at FROM nx_user_device WHERE instance_no=?",instance);
        assertThat(((Number)row.get("id")).longValue()).isEqualTo(id);assertThat(((Number)row.get("user_id")).longValue()).isEqualTo(customer);
        devices.put(id,row);return id;
    }
    String ownedTestcase(){return testcase;}
    void observed(String part,String evidence) {observations.put(part,Map.of("testcase",testcase,"suite",getClass().getSimpleName(),"evidence",evidence));}
    void gap(String id,String reason) {gaps.put(id,Map.of("status","PARTIAL","gap",reason));}
    void finish(String filename) {assertProtected();scene=filename;}
    @AfterEach void cleanOwnedAndRecordPartial() throws Exception {
        var cleanup=new ArrayList<Runnable>();
        cleanup.add(this::assertProtected);
        cleanup.add(()->objects().cleanup(getClass().getSimpleName(),testcase));
        for(var entry:devices.entrySet())cleanup.add(()->{
            var row=entry.getValue();assertThat(jdbc.queryForMap("SELECT id,user_id,instance_no,created_at FROM nx_user_device WHERE id=?",entry.getKey())).isEqualTo(row);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device_runtime WHERE user_device_id=?",Long.class,entry.getKey())).isZero();
            assertThat(jdbc.update("DELETE FROM nx_user_device WHERE id=? AND user_id=? AND instance_no=? AND created_at=?",entry.getKey(),row.get("user_id"),row.get("instance_no"),row.get("created_at"))).isEqualTo(1);
        });
        cleanup.add(this::cleanupOwnedCustomerFacts);cleanup.add(this::cleanupSupportGroupFacts);
        for(var entry:customers.entrySet())cleanup.add(()->{
            long id=entry.getKey();assertThat(jdbc.queryForMap("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",id)).isEqualTo(entry.getValue());
            // Exact committed customer insertion is in the object ledger. Never infer ownership from an actor.
            assertThat(jdbc.update("UPDATE nx_user SET status='DISABLED',is_deleted=1 WHERE id=? AND referral_code=? AND nickname=? AND created_at=?",id,entry.getValue().get("referral_code"),run,entry.getValue().get("created_at"))).isEqualTo(1);
        });
        cleanup.add(()->fixtureActors().cleanupAll(Set.of()));cleanup.add(SecurityContextHolder::clearContext);cleanup.add(this::assertProtected);
        for(String table:allFactTables())cleanup.add(()->assertThat(allFacts(table)).isEqualTo(originalFacts.get(table)));
        SupportObjectEvidenceLedger.cleanupIndependently(cleanup.toArray(Runnable[]::new));
        if(scene!=null) {
            Path directory=Path.of(Objects.requireNonNull(System.getenv("CS_ENHANCE_EVIDENCE_DIR")));Files.createDirectories(directory);
            var payload=new LinkedHashMap<String,Object>();payload.put("at",Instant.now().toString());payload.put("suite",getClass().getSimpleName());payload.put("testcase",testcase);
            payload.put("workflowRunId",System.getenv("WORKFLOW_RUN_ID"));payload.put("snapshotHash",System.getenv("WORKFLOW_SNAPSHOT_HASH"));payload.put("observations",observations);
            payload.put("acceptanceStatus","PARTIAL");payload.put("gaps",gaps);payload.put("cleanup","EXACT_OWNED_ROWS_ONLY");payload.put("customerCreations",customers);payload.put("deviceCreations",devices);payload.put("exactFactReceipts",factReceipts);payload.put("exactFactsCleaned",factsCleaned);
            Files.writeString(directory.resolve(scene),json.writeValueAsString(payload));
        }
    }
}
