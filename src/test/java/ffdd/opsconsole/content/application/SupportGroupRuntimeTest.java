package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.Group;
import ffdd.opsconsole.content.dto.SupportGroupRequests.*;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.platform.application.OpsAdminAccountService;
import ffdd.opsconsole.platform.dto.AdminAccountRoleUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountStatusUpdateRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminSessionRegistry;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
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

/** Real isolated commands; assertions precede evidence. Existing actors and assignments are never rewritten. */
@EnabledIfEnvironmentVariable(named="CS_ANALYTICS_GROUPS_ENABLED",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(SupportIsolatedRuntime.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportGroupRuntimeTest {
    @DynamicPropertySource static void boundary(DynamicPropertyRegistry r){SupportEnhancementPreparationTest.isolatedBoundary(r);}
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired SupportGroupService groups;
    @Autowired SupportGroupMapper mapper;
    @Autowired SupportOwnershipService ownership;
    @Autowired OpsAdminAccountService accounts;
    @Autowired JwtTokenProvider tokens;
    @Autowired AdminSessionRegistry sessions;
    @org.springframework.boot.test.mock.mockito.SpyBean ffdd.opsconsole.shared.audit.AuditLogService audit;
    private final String run="groups_"+UUID.randomUUID().toString().substring(0,8);
    private final List<Long> actors=new ArrayList<>(),createdGroups=new ArrayList<>();
    private final Map<String,Object> proofs=new LinkedHashMap<>();
    private SupportFixtureActors ledger;
    private long boss,managerA,managerB,agent,dual,customer;
    private final String reason="本轮隔离分组验收，保留原业务事实";
    private List<Map<String,Object>> originalBindings;

    @Test void foundationCommandsPersistAndRejectAdversarialChanges() throws Exception {
        ledger=new SupportFixtureActors(jdbc,redis,json,transactions,run,getClass().getSimpleName());ledger.assertBusinessEntry();
        originalBindings=jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id");
        boss=actor("boss","SUPER_ADMIN","MANAGER");managerA=actor("managerA","SUPPORT","MANAGER");
        managerB=actor("managerB","SUPPORT","MANAGER");agent=actor("agent","SUPPORT","DEDICATED");dual=actor("dual","SUPPORT","DEDICATED");
        as(boss);var originalDirectory=groups.supervisors();
        qualify(managerA,"SUPERVISOR");qualify(managerB,"SUPERVISOR");qualify(agent,"SERVICE");qualify(dual,"SERVICE");qualify(dual,"SUPERVISOR");
        as(managerB);assertThat(groups.groups()).isEmpty();
        as(managerA);assertThatThrownBy(()->groups.create(key(),new Create(run+" forged",managerB,reason))).isInstanceOf(BizException.class);
        Group first=create("first",managerA),second=create("second",managerA);
        as(managerB);Group other=create("other",managerB);
        as(managerA);assertThatThrownBy(()->groups.rename(other.id(),key(),new Rename("forged",other.version(),reason))).hasMessage("SUPPORT_GROUP_NOT_FOUND");
        assertThatThrownBy(()->groups.move(agent,key(),new Move(first.id(),1L,null,first.version(),reason))).hasMessage("SUPPORT_GROUP_FORBIDDEN");
        as(boss);move(agent,first.id());move(dual,first.id());
        var memberDirectory=groups.supervisors();
        assertThat(((Number)memberDirectory.get("supervisorCount")).intValue()).isEqualTo(((Number)originalDirectory.get("supervisorCount")).intValue()+3);
        assertThat(((Number)memberDirectory.get("memberCount")).intValue()).isEqualTo(((Number)originalDirectory.get("memberCount")).intValue()+2);
        assertThat(((Number)memberDirectory.get("peopleCount")).intValue()).isEqualTo(((Number)originalDirectory.get("peopleCount")).intValue()+4);
        assertThat(mapper.member(dual).groupId()).isEqualTo(first.id());
        proofs.put("directoryWhileDualIsMember",Map.of("before",originalDirectory,"after",memberDirectory,"dualAdminId",dual,"groupId",first.id(),"unionIncrease",4));
        httpScope(first.id(),other.id());
        as(managerA);String renameKey=key();Group before=mapper.group(first.id());Rename rename=new Rename(run+" renamed",before.version(),reason);
        groups.rename(first.id(),renameKey,rename);groups.rename(first.id(),renameKey,rename);
        assertThat(mapper.group(first.id()).version()).isEqualTo(before.version()+1);
        assertThatThrownBy(()->groups.rename(first.id(),key(),rename)).hasMessage("SUPPORT_GROUP_VERSION_CONFLICT");
        move(agent,second.id());
        assertThatThrownBy(()->groups.move(agent,key(),request(agent,other.id()))).hasMessage("SUPPORT_GROUP_NOT_FOUND");
        assertThatThrownBy(()->groups.move(agent,key(),request(agent,null))).hasMessage("SUPPORT_GROUP_FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_group_member_history WHERE agent_admin_id=? AND ends_at IS NULL",Long.class,agent)).isEqualTo(1L);
        proofs.put("BE-G05",Map.of("groupIds",List.copyOf(createdGroups),"renamedVersion",mapper.group(first.id()).version(),"singleCurrentMember",true,"forgedOwnerAndForeignGroupRejected",true));
        proofs.put("BE-G06",Map.of("managerUngroupedAndCrossOwnerAndNullTargetRejected",true,"adminAssignedUngrouped",true));
        as(boss);concurrentMove(first.id(),second.id());
        // A new owned customer proves ordinary disable preserves the original binding ID and member interval.
        customer=createCustomer();
        jdbc.update("INSERT INTO nx_support_agent_user_assignment(agent_admin_id,user_id,status,starts_at,source,segment_root_id,depth,version,operation_id) VALUES(?,?,'ACTIVE',UTC_TIMESTAMP(),'EXPLICIT',?,0,1,?)",agent,customer,customer,run);
        Long binding=jdbc.queryForObject("SELECT id FROM nx_support_agent_user_assignment WHERE user_id=? AND status='ACTIVE'",Long.class,customer);
        // New supervisor qualification must not enter the old all-customer MANAGER shortcut.
        assertThat(mapper.qualified(dual,"SUPERVISOR")).isEqualTo(1);
        assertThat(ownership.supervisor(dual)).isFalse();
        assertThat(ownership.canRead(dual,customer)).isFalse();
        var member=mapper.member(agent);
        var disabled=accounts.updateStatus(key(),String.valueOf(agent),new AdminAccountStatusUpdateRequest("disabled",reason,run,accountVersion(agent)));
        assertThat(disabled.getCode()).isZero();assertThat(mapper.member(agent)).isEqualTo(member);
        assertThat(jdbc.queryForObject("SELECT id FROM nx_support_agent_user_assignment WHERE user_id=? AND status='ACTIVE'",Long.class,customer)).isEqualTo(binding);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_admin WHERE id=?",Integer.class,agent)).isZero();
        assertThat(mapper.qualified(agent,"SERVICE")).isZero();
        proofs.put("BE-G07",Map.of("customerId",customer,"bindingId",binding,"memberId",member.id(),"accountDisabled",true,"bindingAndMemberPreserved",true));
        // Every owned group, including archived groups, blocks the old A1 role and status entrances.
        assertThatThrownBy(()->accounts.updateStatus(key(),String.valueOf(managerA),new AdminAccountStatusUpdateRequest("disabled",reason,run,accountVersion(managerA)))).hasMessage("SUPPORT_GROUP_OWNER_HANDOVER_REQUIRED");
        assertThatThrownBy(()->accounts.changeRole(key(),String.valueOf(managerA),new AdminAccountRoleUpdateRequest("unassigned",reason,run,accountVersion(managerA)))).hasMessage("SUPPORT_GROUP_OWNER_HANDOVER_REQUIRED");
        assertThatThrownBy(()->groups.qualification(managerA,key(),qualification(managerA,"SUPERVISOR","REMOVED"))).hasMessage("SUPPORT_GROUP_OWNER_HANDOVER_REQUIRED");
        Group g=mapper.group(first.id());
        assertThatThrownBy(()->groups.status(g.id(),key(),new Status("DISABLED",g.version(),reason))).hasMessage("SUPPORT_GROUP_HANDOVER_REQUIRED");
        move(dual,null);move(agent,null);
        for(Long id:List.of(first.id(),second.id())) {
            Group current=mapper.group(id);groups.status(id,key(),new Status("DISABLED",current.version(),reason));
            current=mapper.group(id);groups.status(id,key(),new Status("ARCHIVED",current.version(),reason));
        }
        assertThatThrownBy(()->groups.qualification(managerA,key(),qualification(managerA,"SUPERVISOR","DISABLED"))).hasMessage("SUPPORT_GROUP_OWNER_HANDOVER_REQUIRED");
        for(Long id:List.of(first.id(),second.id()))groups.owner(id,key(),new Owner(managerB,mapper.group(id).version(),reason));
        as(managerA);assertThatThrownBy(()->groups.rename(first.id(),renameKey,rename)).hasMessage("SUPPORT_GROUP_NOT_FOUND");
        as(boss);groups.qualification(managerA,key(),qualification(managerA,"SUPERVISOR","REMOVED"));
        assertThat(mapper.qualification(managerA,"SUPERVISOR").state()).isEqualTo("REMOVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_group_owner_history WHERE group_id=?",Long.class,first.id())).isEqualTo(2);
        proofs.put("BE-G24-FOUNDATION",Map.of("oldAccountRoleStatusAndQualificationBlocked",true,"archivedOwnersTransferred",true,"removedAfterHandover",true,"ownerHistoryCount",2));
        rollbackOnRequiredAuditFailure();
        proofs.put("BE-G21-FOUNDATION",Map.of("membersBlockExit",true,"emptyGroupsArchived",true,"replayOnce",true,"staleVersionRejected",true,"competingMemberCas",true,"auditFailureRollsBack",true));
        var directory=groups.supervisors();
        assertThat(mapper.qualifications(dual)).hasSize(2);
        assertThat(mapper.qualified(dual,"SERVICE")).isEqualTo(1);assertThat(mapper.qualified(dual,"SUPERVISOR")).isEqualTo(1);
        proofs.put("BE-G23-FOUNDATION",Map.of("dualAdminId",dual,"dualQualifications",mapper.qualifications(dual),"emptySupervisorWasAllowed",true,"directory",directory));
        var after=jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment WHERE user_id<>? ORDER BY id",customer);
        assertThat(after).isEqualTo(originalBindings);
        proofs.put("legacyBindingsUnchanged",true);
    }

    private void concurrentMove(Long first,Long second) throws Exception {
        var req=request(agent,Objects.equals(mapper.member(agent).groupId(),first)?second:first);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);
        try{
            Callable<Integer> action=()->{as(boss);gate.await();try{groups.move(agent,key(),req);return 0;}catch(BizException e){return e.getCode();}finally{SecurityContextHolder.clearContext();}};
            Future<Integer> a=pool.submit(action),b=pool.submit(action);gate.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(0,409);
        }finally{pool.shutdownNow();}
    }
    private void httpScope(Long own,Long foreign) throws Exception {
        String managerToken=token(managerA),bossToken=token(boss);
        String path="/api/admin/content/support-agents/groups";
        assertThat(get(path,null).path("code").asInt()).isEqualTo(401);
        JsonNode ownResponse=get(path+"/"+own,managerToken);
        assertThat(ownResponse.path("code").asInt(-1)).isZero();
        assertThat(ownResponse.path("data").path("group").path("id").asLong()).isEqualTo(own);
        JsonNode denied=get(path+"/"+foreign,managerToken);
        assertThat(denied.path("code").asInt()).isEqualTo(404);
        assertThat(denied.path("message").asText()).isEqualTo("SUPPORT_GROUP_NOT_FOUND");
        assertThat(get(path+"/"+foreign,bossToken).path("code").asInt(-1)).isZero();
        assertThat(get(path+"/supervisors",managerToken).path("code").asInt()).isEqualTo(403);
        proofs.put("authenticatedHttpScope",Map.of("path",path,"ownGroupId",own,"foreignGroupId",foreign,
                "ownAllowed",true,"foreignRejected",true,"adminAllAllowed",true,"anonymousRejected",true,"a1DirectoryDeniedToSupport",true));
    }
    private String token(long id){String username=jdbc.queryForObject("SELECT username FROM nx_admin WHERE id=?",String.class,id);return tokens.createToken(id,"ADMIN",username,List.of(),sessions.createSession(id,username));}
    private JsonNode get(String path,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(SupportRuntimeTarget.current().httpBase()+path)).timeout(Duration.ofSeconds(20)).GET();
        if(token!=null)request.header("Authorization","Bearer "+token);
        return json.readTree(HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString()).body());
    }
    private void rollbackOnRequiredAuditFailure(){
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_group",Long.class);
        org.mockito.Mockito.doAnswer(i->{i.callRealMethod();throw new IllegalStateException("I1_REQUIRED_AUDIT_ROLLBACK_PROBE");}).when(audit)
                .recordRequired(org.mockito.ArgumentMatchers.argThat(r->r!=null&&"SUPPORT_GROUP_CREATED".equals(r.getAction())));
        try{assertThatThrownBy(()->groups.create(key(),new Create(run+" rollback",managerB,reason))).hasMessageContaining("I1_REQUIRED_AUDIT_ROLLBACK_PROBE");}
        finally{org.mockito.Mockito.reset(audit);}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_group",Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_group WHERE name=?",Long.class,run+" rollback")).isZero();
    }
    private long actor(String label,String role,String seat){long id=ledger.createSql(run+"_"+label,"!disabled-fixture-password",run+label,role,seat);actors.add(id);return id;}
    private Group create(String label,long owner){Group g=groups.create(key(),new Create(run+label,owner,reason)).getData();createdGroups.add(g.id());return g;}
    private void qualify(long id,String kind){groups.qualification(id,key(),qualification(id,kind,"ENABLED"));}
    private ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification qualification(long id,String kind,String state){var q=mapper.qualification(id,kind);return new ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification(kind,state,q==null?0L:q.version(),Long.valueOf(accountVersion(id)),reason);}
    private String accountVersion(long id){return String.valueOf(jdbc.queryForObject("SELECT version FROM nx_admin WHERE id=?",Long.class,id));}
    private Move request(long id,Long target){var m=mapper.member(id);return new Move(target,m==null?0L:m.version(),m==null||m.groupId()==null?null:mapper.group(m.groupId()).version(),target==null?null:mapper.group(target).version(),reason);}
    private void move(long id,Long target){groups.move(id,key(),request(id,target));}
    private String key(){return run+"-"+UUID.randomUUID();}
    private void as(long id){var auth=new UsernamePasswordAuthenticationToken(String.valueOf(id),null,List.of("service_m1_read","service_m1_write","platform_a1_read","platform_a1_write","service_m3_write").stream().map(SimpleGrantedAuthority::new).toList());auth.setDetails(Map.of("subjectType","ADMIN","username",run));SecurityContextHolder.getContext().setAuthentication(auth);}
    private long createCustomer(){String suffix=UUID.randomUUID().toString().replace("-","");jdbc.update("INSERT INTO nx_user(country_code,phone,password_hash,nickname,referral_code,status,sandbox,client_ip) VALUES('+86',?,'!disabled-fixture-password',?,?,'ACTIVE',0,'127.0.0.1')",suffix.substring(0,16),run,suffix.substring(0,20));return jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,suffix.substring(0,20));}

    @AfterEach void cleanup() throws Exception {
        try{
            // Exact generated IDs belong to this ledger. Never delete by prefix or touch a pre-existing row.
            var actions=new ArrayList<Runnable>();
            for(Long id:createdGroups)actions.add(()->{assertThat(actors).contains(mapper.group(id).supervisorAdminId());jdbc.update("DELETE FROM nx_support_group_owner_history WHERE group_id=?",id);jdbc.update("DELETE FROM nx_support_group WHERE id=?",id);});
            for(Long id:actors)actions.add(()->{ledger.creationReference(id);jdbc.update("DELETE FROM nx_support_group_member_history WHERE agent_admin_id=?",id);jdbc.update("DELETE FROM nx_support_account_qualification_history WHERE admin_id=?",id);});
            actions.add(()->{if(customer>0){jdbc.update("DELETE FROM nx_support_agent_user_assignment WHERE user_id=? AND agent_admin_id=? AND operation_id=?",customer,agent,run);jdbc.update("UPDATE nx_user SET status='DISABLED',is_deleted=1 WHERE id=? AND nickname=?",customer,run);}});
            actions.add(()->{if(ledger!=null)ledger.cleanupAll(Set.of());});
            actions.add(()->{if(originalBindings!=null)assertThat(jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id")).isEqualTo(originalBindings);});
            SupportOriginalProfiles.cleanup(actions.toArray(Runnable[]::new));
            proofs.put("cleanupComplete",true);
            Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("workflowRunId",System.getenv("WORKFLOW_RUN_ID"));evidence.put("snapshotHash",System.getenv("WORKFLOW_SNAPSHOT_HASH"));evidence.put("proofs",proofs);evidence.put("actorIds",actors);evidence.put("groupIds",createdGroups);
            Files.writeString(Path.of(System.getenv("CS_ENHANCE_EVIDENCE_DIR"),"groups-runtime.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        }finally{SecurityContextHolder.clearContext();}
    }
}
