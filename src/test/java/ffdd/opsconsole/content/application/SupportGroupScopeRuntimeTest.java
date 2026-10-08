package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.dto.SupportBindingRequest;
import ffdd.opsconsole.content.dto.SupportGroupRequests;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.shared.security.AdminSessionRegistry;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/** Current-scope assertions use authenticated HTTP, committed group commands and live subscriptions. */
@EnabledIfEnvironmentVariable(named="CS_ANALYTICS_GROUP_SCOPE_ENABLED",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({SupportIsolatedRuntime.class,SupportObjectEvidenceLedger.Configuration.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportGroupScopeRuntimeTest {
    @DynamicPropertySource static void boundary(DynamicPropertyRegistry r) { SupportEnhancementPreparationTest.isolatedBoundary(r); }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired SupportGroupService groups;
    @Autowired SupportGroupMapper groupMapper;
    @Autowired SupportBindingMapper bindings;
    @Autowired SupportBindingService bindingService;
    @Autowired JwtTokenProvider tokens;
    @Autowired AdminSessionRegistry sessions;
    @Autowired SupportObjectEvidenceLedger objects;
    @Autowired ffdd.opsconsole.platform.application.OpsGlobalSearchService globalSearch;
    @Autowired ffdd.opsconsole.shared.security.AdminPermissionCache permissionCache;
    @Autowired ffdd.opsconsole.shared.storage.StorageProperties storageProperties;
    private final String run="scope_"+UUID.randomUUID().toString().substring(0,8);
    private final String reason="本轮隔离分组权限验收，保留原业务事实";
    private final Set<Long> actors=new LinkedHashSet<>(),customers=new LinkedHashSet<>();
    private final Map<String,Object> proofs=new LinkedHashMap<>();
    private final List<Map<String,Object>> requests=new ArrayList<>();
    private final List<Map<String,Object>> binaryReads=new ArrayList<>();
    private final List<Map<String,Object>> searchReads=new ArrayList<>();
    private final Map<Long,String> bearer=new HashMap<>();
    private SupportFixtureActors ledger;
    private SupportGroupRuntimeFixtures fixture;
    private List<Map<String,Object>> originalBindings;
    private long boss,managerA,managerB,agentA,agentB,dual,agentC,legacy,groupA,groupB,groupD;
    private long boundA,boundB,personal,managed,queue,unrouted;
    private String attachmentPath,ticketNo,batchId;
    private static final String TESTCASE="currentGroupScopeCoversListsObjectsQueueCommandsAndSubscriptions";

    @Test void currentGroupScopeCoversListsObjectsQueueCommandsAndSubscriptions() throws Exception {
        ledger=new SupportFixtureActors(jdbc,redis,json,transactions,run,getClass().getSimpleName());
        ledger.assertBusinessEntry();
        originalBindings=jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id");
        boss=actor("boss","SUPER_ADMIN","MANAGER");
        managerA=actor("managerA","SUPPORT","MANAGER"); managerB=actor("managerB","SUPPORT","MANAGER");
        agentA=actor("agentA","SUPPORT","DEDICATED"); agentB=actor("agentB","SUPPORT","DEDICATED");
        dual=actor("dual","SUPPORT","DEDICATED"); agentC=actor("agentC","SUPPORT","DEDICATED");
        legacy=actor("legacy","SUPPORT","MANAGER");
        fixture=new SupportGroupRuntimeFixtures(ledger,jdbc,groupMapper,groups,()->Set.copyOf(customers));
        as(boss);
        groupA=fixture.create(managerA,List.of(agentA),run+"甲组").id();
        groupB=fixture.create(managerB,List.of(agentB,dual),run+"乙组").id();
        groupD=fixture.create(dual,List.of(agentC),run+"兼任组").id();
        assignmentClockPrecision();
        boundA=customer();boundB=customer();personal=customer();managed=customer();queue=customer();unrouted=customer();
        bind(boundA,agentA);bind(boundB,agentB);bind(personal,dual);bind(managed,agentC);
        fixture.route(queue,groupA);
        queueAndPagination();
        roleRevocationMakesScopedHandoverUnavailable();
        String conversationA=conversation(agentA,boundA,"advisor");
        String conversationB=conversation(agentB,boundB,"support");
        String conversationPersonal=conversation(dual,personal,"advisor");
        String conversationManaged=conversation(agentC,managed,"support");
        createPrivateObjects(conversationA);
        objectAndModeMatrix(conversationA,conversationB,conversationPersonal,conversationManaged);
        handoverRevokesExistingReaders(conversationA,conversationPersonal,conversationManaged);
        exitAndQualificationGuards(conversationA);
        proofs.put("completed",true);
    }

    private void assignmentClockPrecision() throws Exception {
        // MySQL cannot self-join a temporary table. Use one uniquely owned clone and always drop it.
        String table="scope_clock_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection();var statement=connection.createStatement()) {
            boolean created=false;
            try {
                statement.execute("CREATE TABLE "+table+" LIKE nx_support_agent_user_assignment");created=true;
                statement.execute("SET timestamp=1791396000.900000");
                var configuration=new org.apache.ibatis.session.Configuration();
                configuration.setMapUnderscoreToCamelCase(true);configuration.addMapper(SupportBindingMapper.class);
                Map<String,Object> values=new HashMap<>(Map.of("agent",101L,"customer",201L,"actor","fixture","reason",reason,"source","MANUAL","root",201L,"depth",0,"operation",key()));
                clockSql(connection,configuration,table,"insertAssignment",values);
                var first=clockSql(connection,configuration,table,"current",Map.of("id",201L));
                assertThat(first).hasSize(1);assertThat(((Number)first.get(0).get("agentAdminId")).longValue()).isEqualTo(101L);
                clockSql(connection,configuration,table,"endAssignment",Map.of("id",first.get(0).get("id"),"version",first.get(0).get("version")));
                values.put("agent",102L);values.put("operation",key());clockSql(connection,configuration,table,"insertAssignment",values);
                var second=clockSql(connection,configuration,table,"current",Map.of("id",201L));
                assertThat(second).hasSize(1);assertThat(((Number)second.get(0).get("agentAdminId")).longValue()).isEqualTo(102L);
                proofs.put("bindingPrecision",Map.of("fractionalSecond",900000,"initialReadback",true,"transferReadback",true,"businessWrites",0));
            } finally {
                try {if(created)statement.execute("DROP TABLE "+table);} finally {statement.execute("SET timestamp=0");}
            }
        }
    }

    private List<Map<String,Object>> clockSql(java.sql.Connection connection,org.apache.ibatis.session.Configuration configuration,String table,String method,Map<String,Object> values) throws Exception {
        var bound=configuration.getMappedStatement(SupportBindingMapper.class.getName()+"."+method).getBoundSql(values);
        try(var statement=connection.prepareStatement(bound.getSql().replace("nx_support_agent_user_assignment",table))) {
            int index=0;for(var parameter:bound.getParameterMappings())statement.setObject(++index,values.get(parameter.getProperty()));
            if(!statement.execute()){assertThat(statement.getUpdateCount()).isEqualTo(1);return List.of();}
            var rows=new ArrayList<Map<String,Object>>();
            try(var result=statement.getResultSet()) {while(result.next()){var row=new HashMap<String,Object>();for(int i=1;i<=result.getMetaData().getColumnCount();i++)row.put(result.getMetaData().getColumnLabel(i),result.getObject(i));rows.add(row);}}
            return rows;
        }
    }

    private void queueAndPagination() throws Exception {
        String pool="/api/admin/content/support-agents/binding-pool?keyword="+run+"&pageSize=1";
        JsonNode admin=ok(get(pool,boss));
        assertThat(admin.path("total").asLong()).isEqualTo(2);
        Set<Long> adminIds=new LinkedHashSet<>();
        for(int page=1;page<=2;page++) adminIds.addAll(ids(ok(get(pool+"&pageNum="+page,boss)),"customerId"));
        assertThat(adminIds).containsExactlyInAnyOrder(queue,unrouted);
        JsonNode own=ok(get(pool,managerA));assertThat(own.path("total").asLong()).isEqualTo(1);
        assertThat(ids(own,"customerId")).containsExactly(queue);
        assertThat(ok(get(pool,managerB)).path("total").asLong()).isZero();
        denied(get(pool,legacy));
        denied(get(pool+"&groupId="+groupB,managerA));
        assertThat(customerIds(workbench(dual,"PERSONAL",null))).containsExactly(personal);
        assertThat(customerIds(workbench(dual,"MANAGED",groupD))).containsExactly(managed);
        assertThat(customerIds(workbench(managerA,"MANAGED",groupA))).containsExactly(boundA);
        denied(get("/api/admin/content/support-workbench/customers?mode=ALL",managerA));
        rejected(get("/api/admin/content/support-workbench/customers?mode=unknown",managerA),400,422);
        proofs.put("BE-G08",Map.of("adminPool",adminIds,"managerPool",List.of(queue),"unroutedExcluded",unrouted,"legacyManagerDenied",true,"pagingCountSameScope",true));
        bind(queue,agentA);
        assertThat(ok(get(pool,managerA)).path("total").asLong()).isZero();
        assertThat(groupMapper.routeCurrent(queue)).isNull();
        assertThat(customerIds(workbench(agentA,"PERSONAL",null))).containsExactlyInAnyOrder(boundA,queue);
        proofs.put("BE-G09",Map.of("customerId",queue,"poolAfter",0,"routeClosed",true,"bindingId",bindings.current(queue).id(),"personalNotDoubleCounted",true));
    }

    private void roleRevocationMakesScopedHandoverUnavailable() throws Exception {
        ledger.creationReference(agentA);
        List<Map<String,Object>> relations=jdbc.queryForList("SELECT * FROM nx_admin_role_relation WHERE admin_id=? ORDER BY id",agentA);
        assertThat(relations).hasSize(1);
        Map<String,Object> relation=relations.get(0);
        long relationId=((Number)relation.get("id")).longValue(),roleId=((Number)relation.get("role_id")).longValue();
        assertThat(((Number)relation.get("is_deleted")).intValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT role_code FROM nx_admin_role WHERE id=?",String.class,roleId)).isEqualTo("SUPPORT");
        var sharedRoles=jdbc.queryForList("SELECT * FROM nx_admin_role ORDER BY id");
        var assignments=jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id");
        var qualification=groupMapper.qualification(agentA,"SERVICE");var member=groupMapper.member(agentA);
        assertThat(qualification.state()).isEqualTo("ENABLED");assertThat(member.groupId()).isEqualTo(groupA);
        assertThat(bindings.eligibleAgent(agentA)).isEqualTo(1);
        handoverCustomers(managerA,groupA,agentA,false,List.of(boundA,queue));
        handoverCustomers(managerA,groupA,agentA,true,List.of());
        handoverCustomers(managerB,groupB,null,false,List.of(boundB,personal));
        handoverCustomers(managerB,groupB,null,true,List.of());
        boolean revoked=false;
        try {
            // Only this run's generated relation changes; the shared SUPPORT role stays active.
            assertThat(jdbc.update("UPDATE nx_admin_role_relation SET is_deleted=1 WHERE id=? AND admin_id=? AND role_id=? AND is_deleted=0",relationId,agentA,roleId)).isEqualTo(1);
            revoked=true;
            assertThat(bindings.eligibleAgent(agentA)).isZero();
            assertThat(groupMapper.qualification(agentA,"SERVICE")).isEqualTo(qualification);
            assertThat(groupMapper.member(agentA)).isEqualTo(member);
            handoverCustomers(managerA,groupA,agentA,true,List.of(boundA,queue));
            handoverCustomers(managerA,groupA,null,true,List.of(boundA,queue));
            handoverCustomers(managerA,groupA,agentA,false,List.of(boundA,queue));
            handoverCustomers(boss,groupA,null,true,List.of(boundA,queue));
            handoverCustomers(boss,null,agentA,true,List.of(boundA,queue));
            handoverCustomers(managerB,groupB,null,true,List.of());
            handoverCustomers(managerB,groupB,null,false,List.of(boundB,personal));
            denied(get("/api/admin/content/support-agents/handover-customers?unavailableOnly=true&groupId="+groupB,managerA));
            denied(get("/api/admin/content/support-agents/handover-customers?unavailableOnly=true&groupId="+groupA,managerB));
            denied(get("/api/admin/content/support-agents/handover-customers?unavailableOnly=true&groupId="+groupA+"&agentAdminId="+agentB,managerA));
            assertThat(jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id")).isEqualTo(assignments);
            assertThat(jdbc.queryForList("SELECT * FROM nx_admin_role ORDER BY id")).isEqualTo(sharedRoles);
        } finally {
            if(revoked)assertThat(jdbc.update("UPDATE nx_admin_role_relation SET is_deleted=0,updated_at=? WHERE id=? AND admin_id=? AND role_id=? AND is_deleted=1",relation.get("updated_at"),relationId,agentA,roleId)).isEqualTo(1);
        }
        assertThat(jdbc.queryForList("SELECT * FROM nx_admin_role_relation WHERE admin_id=? ORDER BY id",agentA)).isEqualTo(relations);
        assertThat(bindings.eligibleAgent(agentA)).isEqualTo(1);
        handoverCustomers(managerA,groupA,agentA,true,List.of());
        assertThat(jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id")).isEqualTo(assignments);
        var roleProof=new LinkedHashMap<String,Object>(Map.of("actorId",agentA,"roleRelationId",relationId,"customerIds",List.of(boundA,queue),
                "eligibleBefore",1,"eligibleRevoked",0,"eligibleRestored",1,"scopeCountAndPaging",true,
                "otherGroupAndSharedRolesUnchanged",true,"qualificationAndBindingsUnchanged",true,"foreignGroupDenied",true));
        roleProof.put("precondition","fixture-owned roleRelation; SQL soft-delete and exact restoration, not an A6 command");
        proofs.put("roleRevokedScopedHandover",roleProof);
    }

    private void handoverCustomers(long actor,Long group,Long agent,boolean unavailable,List<Long> expected) throws Exception {
        String path="/api/admin/content/support-agents/handover-customers?unavailableOnly="+unavailable+"&pageSize=1"
                +(group==null?"":"&groupId="+group)+(agent==null?"":"&agentAdminId="+agent);
        JsonNode first=ok(get(path,actor));assertThat(first.path("total").asLong()).isEqualTo(expected.size());
        Set<Long> found=new LinkedHashSet<>(ids(first,"customerId"));
        for(int page=2;page<=expected.size();page++) {
            JsonNode next=ok(get(path+"&pageNum="+page,actor));assertThat(next.path("total").asLong()).isEqualTo(expected.size());
            for(Long customer:ids(next,"customerId"))assertThat(found.add(customer)).as("unique handover page customer").isTrue();
        }
        assertThat(found).containsExactlyInAnyOrderElementsOf(expected);
    }

    private void objectAndModeMatrix(String ca,String cb,String cp,String cm) throws Exception {
        String conversations="/api/admin/content/conversations";
        ok(get(conversations+"/"+ca,managerA));denied(get(conversations+"/"+cb,managerA));
        assertThat(search(managerA,ca)).contains("conversation:"+ca);
        assertThat(search(managerA,cb)).doesNotContain("conversation:"+cb);
        JsonNode searchEntry=get("/api/admin/platform/search?keyword="+ca,managerA);
        rejected(searchEntry,403);assertThat(searchEntry.path("message").asText()).isEqualTo("ADMIN_PERMISSION_DENIED");
        ok(get(conversations+"/"+cp,dual));ok(get(conversations+"/"+cm,dual));
        JsonNode management=ok(get(conversations+"?readMode=MANAGED&groupId="+groupD,dual));
        assertThat(management.toString()).contains(cm).doesNotContain(cp,ca,cb);
        JsonNode personalRows=ok(get(conversations+"?readMode=PERSONAL",dual));
        assertThat(personalRows.toString()).contains(cp).doesNotContain(cm,ca,cb);
        denied(get(conversations+"?readMode=ALL",dual));
        denied(get(conversations+"?readMode=MANAGED&groupId="+groupB,dual));
        for(long target:List.of(boundA,boundB,unrouted)) {
            JsonNode result=get("/api/admin/content/support-workbench/customers/"+target,managerA);
            if(target==boundA)ok(result);else denied(result);
        }
        // Old service-type request aliases cannot select a second ownership or permission model.
        JsonNode directory=ok(get("/api/admin/content/support-agents",managerA));
        assertThat(directory.path("serviceTypes")).isEqualTo(json.valueToTree(List.of("support")));
        assertThat(directory.path("positions").toString()).doesNotContain("普通客服","专属顾问");
        assertThat(directory.path("agents")).isNotEmpty();
        for(JsonNode agent:directory.path("agents")) {
            assertThat(agent.path("serviceTypes")).isEqualTo(json.valueToTree(List.of("support")));
            assertThat(agent.path("position").asText()).isIn("专属客服","客服主管");
        }
        for(JsonNode target:directory.path("transferTargets"))assertThat(target.path("serviceTypes")).isEqualTo(json.valueToTree(List.of("support")));
        JsonNode roster=ok(get("/api/admin/content/support-agents/page",managerA));
        assertThat(roster.path("serviceTypes")).isEqualTo(json.valueToTree(List.of("support")));
        denied(http("POST",conversations,managerA,initiate(boundB,"support"),key()));
        denied(http("POST",conversations,legacy,initiate(boundA,"advisor"),key()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_agent_user_assignment WHERE user_id=? AND status='ACTIVE' AND is_deleted=0",Long.class,boundA)).isEqualTo(1);
        proofs.put("BE-SUP-01",Map.of("legacyAliases",List.of("advisor","support"),"sameAssignmentAuthority",true,"managerNotSecondServiceType",true,"publicServiceType","support","canonicalDirectoryAndPage",true));
        proofs.put("BE-SUP-04",Map.of("oldTypeCannotWiden",true,"unqualifiedLegacyManagerRejected",true,"dualModesSeparate",true));
    }

    private void createPrivateObjects(String conversation) throws Exception {
        String tickets="/api/admin/content/tickets";
        JsonNode created=ok(http("POST",tickets,agentA,Map.of("userId",boundA,"category","withdrawal","priority","NORMAL","title",run+"权限工单","body","实际创建的范围验收工单","operator",run,"reason",reason),key()));
        ticketNo=created.path("ticket").path("ticketNo").asText();assertThat(ticketNo).isNotBlank();
        ok(get(tickets+"/"+ticketNo,managerA));denied(get(tickets+"/"+ticketNo,managerB));
        String asset=uploadAttachment(boundA,agentA);
        JsonNode detail=ok(get("/api/admin/content/conversations/"+conversation,agentA)).path("conversation");
        Map<String,Object> reply=new LinkedHashMap<>();reply.put("body","");reply.put("expectedStatus",detail.path("status").asText());reply.put("expectedVersion",detail.path("version").asLong());reply.put("reason",reason);reply.put("operator",run);reply.put("kind","IMAGE");reply.put("attachmentId",asset);reply.put("intent","SERVICE");reply.put("clientMessageId",key());reply.put("expectedAssignmentId",bindings.current(boundA).id());
        ok(http("POST","/api/admin/content/conversations/"+conversation+"/replies",agentA,reply,key()));
        attachmentPath="/api/admin/content/conversations/attachments/"+asset+"/content";
        assertThat(binary(attachmentPath,managerA).statusCode()).isEqualTo(200);
        assertThat(binary(attachmentPath,managerB).statusCode()).isEqualTo(404);
        String bulk="/api/admin/content/support-workbench/bulk";
        JsonNode preview=ok(http("POST",bulk+"/preview",agentA,new ffdd.opsconsole.content.dto.SupportBulkRequest.Preview(null,List.of(boundA,queue),List.of(),"EXPLICIT"),null));
        assertThat(preview.path("count").asLong()).isEqualTo(2);
        JsonNode batch=ok(http("POST",bulk,agentA,new ffdd.opsconsole.content.dto.SupportBulkRequest.Create(preview.path("selectionId").asText(),"SERVICE","TEXT",run+"冻结待发送",null,null,null,reason),key()));
        batchId=batch.path("batchId").asText();assertThat(batchId).isNotBlank();
        assertThat(ok(get(bulk+"/"+batchId+"/recipients",managerA)).path("total").asLong()).isEqualTo(2);
        denied(get(bulk+"/"+batchId,managerB));
    }

    private void handoverRevokesExistingReaders(String ca,String cp,String cm) throws Exception {
        as(boss);long frozenCustomer=customer();fixture.route(frozenCustomer,groupD);
        JsonNode frozen=ok(http("POST","/api/admin/content/support-agents/assignments/random-preview",dual,
                new ffdd.opsconsole.content.dto.SupportRandomRequest.Preview(List.of(new ffdd.opsconsole.content.domain.SupportRandom.Customer(frozenCustomer,bindings.poolVersion(frozenCustomer))),false,null,null),null));
        assertThat(frozen.path("count").asLong()).isEqualTo(1);
        JsonNode beforeManaged=ok(get("/api/admin/content/conversations/"+cm,dual)).path("conversation");
        Map<String,Object> validManagedReply=reply(beforeManaged,managed,"Manager cannot borrow the service writer identity");
        long beforeMessages=messageCount(cm);
        try(SocketProbe socket=socket(dual);StreamProbe stream=stream(dual)) {
            socket.send(Map.of("type","watch","conversationNo",cm));socket.await("presence");
            socket.send(Map.of("type","watch","conversationNo",cp));socket.await("presence");
            as(boss);
            groups.owner(groupD,key(),new SupportGroupRequests.Owner(managerB,groupMapper.group(groupD).version(),reason));
            assertThat(groupMapper.group(groupD).supervisorAdminId()).isEqualTo(managerB);
            socket.await("scope-invalidated");stream.await("event:scope-invalidated");
            denied(http("POST","/api/admin/content/support-agents/assignments/random",dual,
                    new ffdd.opsconsole.content.dto.SupportRandomRequest.Confirm(frozen.path("id").asText(),frozen.path("rulesVersion").asLong(),reason),key()));
            assertThat(bindings.current(frozenCustomer)).isNull();
            String endpoint="/api/admin/content/conversations/";
            denied(get(endpoint+cm,dual));ok(get(endpoint+cm,managerB));ok(get(endpoint+cp,dual));
            denied(get("/api/admin/content/support-workbench/customers/"+managed,dual));
            JsonNode forbiddenReply=http("POST",endpoint+cm+"/replies",dual,validManagedReply,key());
            rejected(forbiddenReply,404);assertThat(forbiddenReply.path("message").asText()).isEqualTo("SUPPORT_CUSTOMER_NOT_FOUND");
            assertThat(messageCount(cm)).isEqualTo(beforeMessages);
            socket.send(Map.of("type","watch","conversationNo",cm));assertThat(socket.await("error").path("code").asInt()).isEqualTo(404);
            socket.send(Map.of("type","typing","conversationNo",cm,"active",true));assertThat(socket.await("error").path("code").asInt()).isEqualTo(404);
            socket.send(Map.of("type","watch","conversationNo",cp));socket.await("presence");
            socket.observed.clear();stream.lines.clear();
            ok(http("POST",endpoint+cm+"/replies",agentC,reply(ok(get(endpoint+cm,agentC)).path("conversation"),managed,"New group message must not reach the former manager"),key()));
            ok(http("POST",endpoint+cp+"/replies",dual,reply(ok(get(endpoint+cp,dual)).path("conversation"),personal,"Personal service subscription remains valid"),key()));
            assertThat(socket.await("event").path("conversationNo").asText()).isEqualTo(cp);
            stream.awaitContaining("\"conversationNo\":\""+cp+"\"");
            assertThat(socket.observed).noneMatch(frame->frame.contains("\"conversationNo\":\""+cm+"\""));
            assertThat(stream.lines).noneMatch(line->line.contains("\"conversationNo\":\""+cm+"\""));
            assertThat(customerIds(workbench(dual,"PERSONAL",null))).containsExactly(personal);
            assertThat(customerIds(workbench(dual,"MANAGED",null))).isEmpty();
            // A previously issued manager token is tested again after committed owner transfer.
            groups.owner(groupA,key(),new SupportGroupRequests.Owner(managerB,groupMapper.group(groupA).version(),reason));
            denied(get(endpoint+ca,managerA));ok(get(endpoint+ca,managerB));
            assertThat(search(managerA,ca)).doesNotContain("conversation:"+ca);
            assertThat(search(managerB,ca)).contains("conversation:"+ca);
            JsonNode searchEntry=get("/api/admin/platform/search?keyword="+ca,managerA);
            rejected(searchEntry,403);assertThat(searchEntry.path("message").asText()).isEqualTo("ADMIN_PERMISSION_DENIED");
            denied(get("/api/admin/content/tickets/"+ticketNo,managerA));ok(get("/api/admin/content/tickets/"+ticketNo,managerB));
            assertThat(binary(attachmentPath,managerA).statusCode()).isEqualTo(404);
            assertThat(binary(attachmentPath,managerB).statusCode()).isEqualTo(200);
            String bulk="/api/admin/content/support-workbench/bulk/"+batchId;
            denied(get(bulk,managerA));denied(get(bulk+"/recipients",managerA));
            assertThat(ok(get(bulk+"/recipients",managerB)).path("total").asLong()).isEqualTo(2);
            JsonNode currentBatch=ok(get(bulk,agentA));
            ok(http("POST",bulk+"/cancel",agentA,new ffdd.opsconsole.content.dto.SupportBulkRequest.Mutation(currentBatch.path("version").asLong(),reason),key()));
            assertThat(ok(get("/api/admin/content/support-agents/binding-pool?keyword="+run,managerA)).path("total").asLong()).isZero();
            var revocation=new LinkedHashMap<String,Object>();
            for(String field:List.of("ownerChangedReadback","oldTokenAndUrlDenied","socketWatchAndTypingDenied","sseInvalidated","dualPersonalStillReadable","managementHasNoPersonalRows","oldTicketAndAttachmentDenied","oldBulkSummaryAndRecipientsDenied","frozenAssignmentRejected","sseCurrentMessagesFiltered","socketCurrentEventsFiltered","searchRechecksCurrentScope"))revocation.put(field,true);
            revocation.put("searchEvidenceLayer","DIRECT_SERVICE");revocation.put("searchHttpEntry","RBAC_DENIED_EXPECTED");
            proofs.put("BE-G24-OBJECTS",revocation);
        }
    }

    private void exitAndQualificationGuards(String agentConversation) throws Exception {
        as(boss);
        long empty=fixture.create(managerA,List.of(),run+"待处理组").id();
        long waiting=customer();fixture.route(waiting,empty);
        JsonNode pending=ok(http("POST","/api/admin/content/support-agents/assignments/random-preview",boss,
                new ffdd.opsconsole.content.dto.SupportRandomRequest.Preview(List.of(new ffdd.opsconsole.content.domain.SupportRandom.Customer(waiting,bindings.poolVersion(waiting))),false,null,null),null));
        assertThat(pending.path("count").asLong()).isEqualTo(1);
        rejected(http("PATCH","/api/admin/content/support-agents/groups/"+empty+"/status",boss,
                new SupportGroupRequests.Status("DISABLED",groupMapper.group(empty).version(),reason),key()),409);
        assertThat(groupMapper.group(empty).status()).isEqualTo("ENABLED");
        var route=groupMapper.routeCurrent(waiting);
        groups.route(waiting,key(),new SupportGroupRequests.Route(null,route.version(),groupMapper.group(empty).version(),null,reason));
        rejected(http("PATCH","/api/admin/content/support-agents/groups/"+empty+"/status",boss,
                new SupportGroupRequests.Status("DISABLED",groupMapper.group(empty).version(),reason),key()),409);
        assertThat(groupMapper.group(empty).status()).isEqualTo("ENABLED");
        // Advance only this fixture's exact preview expiry; other work never gets retired by a broad predicate.
        assertThat(jdbc.update("UPDATE nx_support_random_preview SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=? AND actor_id=?",pending.path("id").asText(),boss)).isEqualTo(1);
        long version=groupMapper.group(empty).version();String command=key();
        var disable=new SupportGroupRequests.Status("DISABLED",version,reason);
        ok(http("PATCH","/api/admin/content/support-agents/groups/"+empty+"/status",boss,disable,command));
        ok(http("PATCH","/api/admin/content/support-agents/groups/"+empty+"/status",boss,disable,command));
        assertThat(groupMapper.group(empty).version()).isEqualTo(version+1);
        rejected(http("PATCH","/api/admin/content/support-agents/groups/"+empty+"/status",boss,disable,key()),409);
        var qualification=groupMapper.qualification(dual,"SERVICE");
        assertThatThrownBy(()->groups.qualification(dual,key(),new SupportGroupRequests.Qualification("SERVICE","DISABLED",qualification.version(),
                jdbc.queryForObject("SELECT version FROM nx_admin WHERE id=?",Long.class,dual),reason))).hasMessage("SUPPORT_PERSONAL_HANDOVER_REQUIRED");
        JsonNode managerExit=http("PATCH","/api/admin/platform/accounts/"+dual+"/status",boss,
                new ffdd.opsconsole.platform.dto.AdminAccountStatusUpdateRequest("disabled",reason,run,String.valueOf(jdbc.queryForObject("SELECT version FROM nx_admin WHERE id=?",Long.class,dual))),key());
        rejected(managerExit,409);assertThat(managerExit.path("message").asText()).isEqualTo("SUPPORT_PERSONAL_HANDOVER_REQUIRED");
        String ownReplyPath="/api/admin/content/conversations/"+agentConversation+"/replies";
        Map<String,Object> ownReply=reply(ok(get("/api/admin/content/conversations/"+agentConversation,agentA)).path("conversation"),boundA,"Owned service reply before account disable");
        String ownCommand=key();long oldCount=messageCount(agentConversation);
        ok(http("POST",ownReplyPath,agentA,ownReply,ownCommand));assertThat(messageCount(agentConversation)).isEqualTo(oldCount+1);
        ok(http("PATCH","/api/admin/platform/accounts/"+agentA+"/status",boss,
                new ffdd.opsconsole.platform.dto.AdminAccountStatusUpdateRequest("disabled",reason,run,String.valueOf(jdbc.queryForObject("SELECT version FROM nx_admin WHERE id=?",Long.class,agentA))),key()));
        rejected(get("/api/admin/content/support-workbench/customers/"+boundA,agentA),401);
        rejected(http("POST",ownReplyPath,agentA,ownReply,ownCommand),401);
        assertThat(messageCount(agentConversation)).isEqualTo(oldCount+1);
        assertThat(bindings.current(boundA).agentAdminId()).isEqualTo(agentA);
        assertThat(proofs).containsKey("roleRevokedScopedHandover");
        proofs.put("BE-G21",Map.of("queueBlocksExit",true,"pendingPreviewBlocksExit",true,"routeClosedBeforeDisable",true,"replaySingleVersion",true,"staleVersionDenied",true,"qualificationExitRequiresHandover",true,"managerAccountExitRequiresHandover",true,"disabledAccountKeepsBindingButDeniesReadAndSend",true,"roleRevokedScopedHandover",proofs.get("roleRevokedScopedHandover")));
    }

    private JsonNode workbench(long actor,String mode,Long group) throws Exception {
        return ok(get("/api/admin/content/support-workbench/customers?keyword="+run+"&mode="+mode+(group==null?"":"&groupId="+group),actor));
    }
    private List<String> search(long actor,String keyword) {
        var previous=SecurityContextHolder.getContext().getAuthentication();
        try {
            var auth=new UsernamePasswordAuthenticationToken(String.valueOf(actor),null,permissionCache.getPermissionCodes(actor).stream().map(SimpleGrantedAuthority::new).toList());
            auth.setDetails(Map.of("subjectType","ADMIN","username",run));SecurityContextHolder.getContext().setAuthentication(auth);
            JsonNode rows=ok(json.valueToTree(globalSearch.search(keyword,20)));
            var ids=new ArrayList<String>();for(JsonNode row:rows)ids.add(row.path("kind").asText()+":"+row.path("id").asText());
            searchReads.add(Map.of("layer","DIRECT_SERVICE","actorId",actor,"keyword",keyword,"resultIds",ids));return ids;
        } finally {SecurityContextHolder.getContext().setAuthentication(previous);}
    }
    private Set<Long> customerIds(JsonNode snapshot) {return ids(snapshot.path("customers"),"customerId");}
    private Set<Long> ids(JsonNode page,String field) {Set<Long> found=new LinkedHashSet<>();for(JsonNode row:page.path("records"))assertThat(found.add(row.path(field).asLong())).as("unique paged IDs").isTrue();return found;}
    private String conversation(long actor,long customer,String type) throws Exception {return ok(http("POST","/api/admin/content/conversations",actor,initiate(customer,type),key())).path("conversationNo").asText();}
    private Object initiate(long customer,String type) {return Map.of("conversationType",type,"userId",customer,"openingText",run+"真实权限验收","reason",reason,"operator",run);}
    private Map<String,Object> reply(JsonNode conversation,long customer,String body) {
        Map<String,Object> request=new LinkedHashMap<>();request.put("body",body);request.put("expectedStatus",conversation.path("status").asText());request.put("expectedVersion",conversation.path("version").asLong());request.put("reason",reason);request.put("operator",run);request.put("kind","TEXT");request.put("intent","SERVICE");request.put("clientMessageId",key());request.put("expectedAssignmentId",bindings.current(customer).id());return request;
    }
    private long messageCount(String conversation) {return jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no=?",Long.class,conversation);}
    private void bind(long customer,long agent) throws Exception {
        var old=bindings.current(customer);var expected=new SupportBindingRequest.Customer(customer,old==null?null:old.id(),old==null?bindings.poolVersion(customer):old.version());
        ok(http("POST","/api/admin/content/support-agents/assignments/transfer",boss,new SupportBindingRequest(agent,List.of(expected),reason),key()));
        assertThat(bindings.current(customer).agentAdminId()).isEqualTo(agent);
    }
    private long customer() {
        String suffix=UUID.randomUUID().toString().replace("-","");String referral=suffix.substring(0,20);
        long created=objects.createCustomer(getClass().getSimpleName(),TESTCASE,referral,()->{
            var generated=new org.springframework.jdbc.support.GeneratedKeyHolder();
            int affected=jdbc.update(connection->{var statement=connection.prepareStatement("INSERT INTO nx_user(country_code,phone,password_hash,nickname,referral_code,status,sandbox,client_ip) VALUES('+86',?,'!disabled-fixture-password',?,?,'ACTIVE',0,'127.0.0.1')",java.sql.Statement.RETURN_GENERATED_KEYS);statement.setString(1,suffix.substring(0,16));statement.setString(2,run);statement.setString(3,referral);return statement;},generated);
            long id=Objects.requireNonNull(generated.getKey()).longValue();
            long lookup=jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,referral);
            assertThat(affected).isEqualTo(1);assertThat(lookup).isEqualTo(id);
            bindingService.register(id,null);return new SupportObjectEvidenceLedger.CustomerInsert(id,affected,lookup);
        });
        customers.add(created);return created;
    }
    private String uploadAttachment(long customer,long actor) throws Exception {
        long assignment=bindings.current(customer).id();String upload=key(),command=key(),boundary="scope"+UUID.randomUUID();
        var intent=objects.request(new SupportObjectEvidenceLedger.Request(getClass().getSimpleName(),TESTCASE,SupportObjectEvidenceLedger.Kind.ATTACHMENT,"ADMIN",actor,customer,assignment,upload,command,null,storageProperties.getBucket(),false));
        var image=new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB);image.setRGB(1,1,0x3487ab);
        var png=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",png);
        var out=new java.io.ByteArrayOutputStream();
        for(var field:Map.of("clientUploadId",upload,"customerId",String.valueOf(customer),"expectedAssignmentId",String.valueOf(assignment)).entrySet())out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field.getKey()+"\"\r\n\r\n"+field.getValue()+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"scope.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));out.write(png.toByteArray());out.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create(SupportRuntimeTarget.current().httpBase()+"/api/admin/content/conversations/attachments")).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token(actor)).header("Idempotency-Key",command).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        try {var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());JsonNode body=json.readTree(response.body());objects.httpOutcome(intent,response.statusCode(),body);String id=ok(body).path("id").asText();assertThat(id).isNotBlank();return id;}
        catch(Exception|Error failure){try{objects.requestFailure(intent,failure);}catch(Throwable evidenceFailure){failure.addSuppressed(evidenceFailure);}throw failure;}
    }
    private HttpResponse<byte[]> binary(String path,long actor) throws Exception {
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(SupportRuntimeTarget.current().httpBase()+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token(actor)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()==200){assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("image/png");assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(response.body()))).isNotNull();}
        binaryReads.add(Map.of("path",path,"actorId",actor,"status",response.statusCode(),"bytes",response.body().length,"sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(response.body()))));return response;
    }
    private long actor(String label,String role,String seat) {long id=ledger.createSql(run+"_"+label,"!disabled-fixture-password",run+label,role,seat);actors.add(id);return id;}
    private String token(long id) {return bearer.computeIfAbsent(id,actor->{String username=jdbc.queryForObject("SELECT username FROM nx_admin WHERE id=?",String.class,actor);return tokens.createToken(actor,"ADMIN",username,List.of(),sessions.createSession(actor,username));});}
    private void as(long id) {var auth=new UsernamePasswordAuthenticationToken(String.valueOf(id),null,List.of("platform_a1_read","platform_a1_write","service_m1_read","service_m1_write","service_m2_read","service_m2_write","service_m3_read","service_m3_write").stream().map(SimpleGrantedAuthority::new).toList());auth.setDetails(Map.of("subjectType","ADMIN","username",run));SecurityContextHolder.getContext().setAuthentication(auth);}
    private String key(){return run+"-"+UUID.randomUUID();}
    private JsonNode get(String path,long actor) throws Exception {return http("GET",path,actor,null,null);}
    private JsonNode http(String method,String path,long actor,Object body,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(SupportRuntimeTarget.current().httpBase()+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token(actor)).header("Content-Type","application/json");
        if(key!=null)request.header("Idempotency-Key",key);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
        JsonNode result=json.readTree(response.body());requests.add(Map.of("method",method,"path",path,"actorId",actor,"httpStatus",response.statusCode(),"code",result.path("code").asInt(-1)));return result;
    }
    private JsonNode ok(JsonNode response) {assertThat(response.path("code").asInt(-1)).as("authenticated response: %s",response.path("message")).isZero();return response.path("data");}
    private void denied(JsonNode response) {rejected(response,403,404);}
    private void rejected(JsonNode response,Integer... codes) {assertThat(response.path("code").asInt(-1)).as("actual denial: %s",response.path("message")).isIn(codes);assertThat(response.path("data").isMissingNode()||response.path("data").isNull()).isTrue();}

    private SocketProbe socket(long actor) throws Exception {
        JsonNode ticket=ok(http("POST","/api/admin/content/conversations/realtime-ticket",actor,null,null));
        var probe=new SocketProbe();probe.socket=HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+SupportIsolatedRuntime.port()+"/ws/conversations"),probe).get(10,TimeUnit.SECONDS);
        probe.send(Map.of("type","auth","ticket",ticket.path("ticket").asText()));probe.await("ready");return probe;
    }
    private final class SocketProbe implements WebSocket.Listener,AutoCloseable {
        WebSocket socket;final BlockingQueue<String> frames=new LinkedBlockingQueue<>();final List<String> observed=new CopyOnWriteArrayList<>();final StringBuilder buffer=new StringBuilder();
        public void onOpen(WebSocket ws){ws.request(1);}
        public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){buffer.append(data);if(last){observed.add(buffer.toString());frames.add(buffer.toString());buffer.setLength(0);}ws.request(1);return null;}
        void send(Object frame) throws Exception {socket.sendText(json.writeValueAsString(frame),true).get(5,TimeUnit.SECONDS);}
        JsonNode await(String type) throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<deadline){String frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame!=null){JsonNode value=json.readTree(frame);if(type.equals(value.path("type").asText()))return value;}}throw new AssertionError("Missing socket event "+type);}
        public void close(){socket.abort();}
    }
    private StreamProbe stream(long actor) throws Exception {
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(SupportRuntimeTarget.current().httpBase()+"/api/admin/content/conversations/stream")).header("Authorization","Bearer "+token(actor)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);return new StreamProbe(response.body());
    }
    private static final class StreamProbe implements AutoCloseable {
        final java.io.InputStream input;final List<String> lines=new CopyOnWriteArrayList<>();
        StreamProbe(java.io.InputStream input){this.input=input;Thread reader=new Thread(()->{try(var in=new java.io.BufferedReader(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8))){String line;while((line=in.readLine())!=null)lines.add(line);}catch(java.io.IOException closed){lines.add("CLOSED");}});reader.setDaemon(true);reader.start();}
        void await(String expected) throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<deadline){if(lines.stream().anyMatch(line->line.replace(" ","").equals(expected)))return;Thread.sleep(25);}throw new AssertionError("Missing SSE "+expected);}
        void awaitContaining(String expected) throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<deadline){if(lines.stream().anyMatch(line->line.contains(expected)))return;Thread.sleep(25);}throw new AssertionError("Missing SSE payload "+expected);}
        public void close() throws Exception {input.close();}
    }

    @AfterEach void cleanup() throws Exception {
        try {
            var cleanup=new ArrayList<Runnable>();
            cleanup.add(()->objects.cleanup(getClass().getSimpleName(),TESTCASE));
            for(long id:customers)cleanup.add(()->{
                assertThat(jdbc.queryForObject("SELECT nickname FROM nx_user WHERE id=?",String.class,id)).isEqualTo(run);
                jdbc.update("DELETE FROM nx_support_customer_route_history WHERE customer_id=?",id);
                jdbc.update("DELETE FROM nx_support_agent_user_assignment WHERE user_id=?",id);
                jdbc.update("DELETE FROM nx_support_binding_pool WHERE customer_id=?",id);
                jdbc.update("UPDATE nx_user SET status='DISABLED',is_deleted=1 WHERE id=? AND nickname=?",id,run);
            });
            if(fixture!=null)for(long id:fixture.createdGroupIds())cleanup.add(()->{jdbc.update("DELETE FROM nx_support_group_owner_history WHERE group_id=?",id);jdbc.update("DELETE FROM nx_support_group WHERE id=?",id);});
            for(long id:actors)cleanup.add(()->{ledger.creationReference(id);jdbc.update("DELETE FROM nx_support_group_member_history WHERE agent_admin_id=?",id);jdbc.update("DELETE FROM nx_support_account_qualification_history WHERE admin_id=?",id);});
            cleanup.add(()->{if(ledger!=null)ledger.cleanupAll(Set.of());});
            cleanup.add(()->{if(originalBindings!=null)assertThat(jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id")).isEqualTo(originalBindings);});
            SupportOriginalProfiles.cleanup(cleanup.toArray(Runnable[]::new));
            proofs.put("cleanupComplete",true);proofs.put("legacyBindingsUnchanged",true);
            var evidence=new LinkedHashMap<String,Object>();evidence.put("workflowRunId",System.getenv("WORKFLOW_RUN_ID"));evidence.put("snapshotHash",System.getenv("WORKFLOW_SNAPSHOT_HASH"));evidence.put("capability","runtime");evidence.put("proofs",proofs);evidence.put("requests",requests);evidence.put("binaryReads",binaryReads);evidence.put("actorIds",actors);evidence.put("customerIds",customers);
            evidence.put("searchReads",searchReads);
            Files.writeString(Path.of(System.getenv("CS_ENHANCE_EVIDENCE_DIR"),"group-scope-runtime.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        } finally {SecurityContextHolder.clearContext();}
    }
}
