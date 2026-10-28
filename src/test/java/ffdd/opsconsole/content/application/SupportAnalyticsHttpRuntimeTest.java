package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.dto.SupportBindingRequest;
import ffdd.opsconsole.content.facade.SupportRegistrationFacade;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.shared.security.AdminSessionRegistry;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
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

/** Real filter-chain requests; scenario success never signs a whole acceptance ID. */
@EnabledIfEnvironmentVariable(named="CS_ANALYTICS_HTTP_ENABLED",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({SupportIsolatedRuntime.class,SupportObjectEvidenceLedger.Configuration.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportAnalyticsHttpRuntimeTest extends SupportAnalyticsRuntimeFixture {
    @DynamicPropertySource static void boundary(DynamicPropertyRegistry r) {SupportEnhancementPreparationTest.isolatedBoundary(r);}

    @Test void actualAnalyticsScopesRejectForeignAndForgedIdentityAndKeepOwnerlessEmpty() throws Exception {
        long own=customer(false),foreign=customer(false);bind(own,agent);bind(foreign,foreignAgent);
        for(long actor:List.of(agent,manager,boss)) {
            JsonNode result=analytics(actor,"view=CUSTOMERS"+(actor==agent?"":"&groupId="+group)+"&keyword="+run);
            assertThat(ids(result,"customerId")).containsExactly(own);
            assertThat(result.path("scopeSummary").path("mode").asText()).isEqualTo(actor==agent?"PERSONAL":actor==manager?"MANAGED":"ALL");
        }
        for(long actor:List.of(foreignAgent,foreignManager))
            assertThat(ids(analytics(actor,"view=CUSTOMERS&keyword="+run),"customerId")).containsExactly(foreign);
        assertThat(ids(analytics(boss,"view=CUSTOMERS&keyword="+run),"customerId")).containsExactlyInAnyOrder(own,foreign);
        JsonNode empty=analytics(ownerless,"view=CUSTOMERS&keyword="+run);
        assertThat(empty.path("records")).isEmpty();assertThat(empty.path("selectedCustomerCount").asLong()).isZero();
        deny(manager,"groupId="+foreignGroup+"&pageNum=2&expectedVersion="+stale(),403,404);
        deny(agent,"agentId="+foreignAgent,422);deny(agent,"groupId="+foreignGroup,422);
        deny(agent,"customerId="+foreign,422);deny(agent,"mode=ALL",422);
        deny(manager,"view=CUSTOMERS&view=AGENTS",422);deny(manager,"currency=BTC",422);
        deny(manager,"pageNum=2",422);
        checked("Real SERVICE/MANAGED/ALL roots, ownerless empty and raw query boundary denial; no foreign identities/counts returned");
    }

    @Test void realViewAndCurrencyMatrixKeepsUnknownSeparateFromZero() throws Exception {
        long own=customer(false);bind(own,agent);
        for(long actor:List.of(agent,manager,boss))for(String currency:List.of("USDT","UVT"))
            for(String basis:List.of("CURRENT_ASSET","CURRENT_CUSTOMER_HISTORY","PERIOD_EVENT"))
                for(String view:List.of("OVERVIEW","AGENTS","CUSTOMERS","FINANCE","DEVICES","ACTIVITY")) {
                    if(basis.equals("CURRENT_ASSET") && view.equals("FINANCE"))continue;
                    String period=basis.equals("PERIOD_EVENT")?"&from=2026-10-01T00%3A00%3A00Z&to=2026-11-01T00%3A00%3A00Z":"";
                    JsonNode result=analytics(actor,"view="+view+"&currency="+currency+"&basis="+basis+"&keyword="+run+period);
                    assertThat(result.path("basis").asText()).isEqualTo(basis);
                    assertThat(result.path("businessZone").asText()).isEqualTo("Asia/Shanghai");
                    assertThat(result.path("versionState").asText()).isIn("READY","UNKNOWN");
                    if(result.path("versionState").asText().equals("UNKNOWN")) {
                        assertThat(result.path("queryVersion").isNull()).isTrue();
                        assertThat(result.path("total").isNull()).isTrue();assertThat(result.path("canContinue").asBoolean()).isFalse();
                    }
                    assertThat(result.toString()).doesNotContain("password_hash","sourceFactJson","beforeSourceJson","attribution_evidence_json","target_address");
                }
        checked("Actual production HTTP view/basis/two-currency matrix; UNKNOWN has no version/complete total; internal financial proof and withdrawal address absent");
    }

    @Test void oldTokenLosesCustomerReadImmediatelyAfterProductionTransferBeforeStaleVersion() throws Exception {
        long own=customer(false);bind(own,agent);
        analytics(agent,"keyword="+run);String originalToken=token(agent);
        bind(own,foreignAgent);
        HttpResult old=request("GET","/api/admin/content/support-workbench/customers/"+own,agent,null,null);
        rejected(old,403,404);
        assertThat(token(agent)).isEqualTo(originalToken);
        deny(manager,"groupId="+foreignGroup+"&pageNum=2&expectedVersion="+stale(),403,404);
        assertThat(ids(analytics(foreignAgent,"keyword="+run),"customerId")).containsExactly(own);
        assertThat(ids(analytics(agent,"keyword="+run),"customerId")).isEmpty();
        checked("Same bearer token and old direct customer URL denied after committed production transfer; foreign scope denial precedes stale version");
    }

    @Test void readyVersionMustActuallySupportThreePagesAndRejectOwnedWalletMutation() throws Exception {
        var expected=new TreeSet<Long>();
        for(int i=0;i<5;i++){long id=customer(true);bind(id,agent);wallet(id,"1.000001","2.000002");expected.add(id);}
        String q="view=CUSTOMERS&basis=CURRENT_ASSET&sortKey=REGISTERED_AT&direction=ASC&pageSize=2&keyword="+run;
        JsonNode first=analytics(agent,q);
        // Missing source coverage is a failing prerequisite, never a READY-or-UNKNOWN success branch.
        assertThat(first.path("versionState").asText()).as("Actual READY is required; do not invent activity/history coverage").isEqualTo("READY");
        String version=first.path("queryVersion").asText();assertThat(version).matches("saq-v1:[0-9a-f]{64}");
        var found=new LinkedHashSet<Long>();
        for(int page=1;page<=3;page++) {
            JsonNode data=analytics(agent,q+"&pageNum="+page+"&expectedVersion="+encoded(version));
            assertThat(data.path("queryVersion").asText()).isEqualTo(version);assertThat(data.path("total").asLong()).isEqualTo(5);
            for(long id:ids(data,"customerId"))assertThat(found.add(id)).isTrue();
            assertThat(data.path("funds")).isEqualTo(first.path("funds"));
        }
        assertThat(found).containsExactlyElementsOf(expected);
        long changed=expected.first();requireCustomer(changed);
        exactFacts.mutate("WALLET_VERSION_UPDATE",()->{assertThat(jdbc.update("UPDATE nx_user_wallet SET usdt_available=3.000003,version=version+1,updated_at=NOW(6) WHERE user_id=?",changed)).isEqualTo(1);return null;});
        assertThat(fresh("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",changed).get(0).values().iterator().next().toString()).isEqualTo("3.000003");
        deny(agent,q+"&pageNum=2&expectedVersion="+encoded(version),409);
        checked("Strict actual READY, three stable pages and same aggregate; committed own-wallet version change rejects old expectedVersion with 409");
    }
}

/** Dedicated fixture: exact created fact receipts and fenced cleanup; real actor/object creator APIs retained. */
abstract class SupportAnalyticsRuntimeFixture {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired SupportGroupService groups;
    @Autowired SupportGroupMapper groupMapper;
    @Autowired SupportBindingMapper bindings;
    @Autowired SupportBindingService bindingService;
    @Autowired SupportRegistrationFacade registration;
    @Autowired SupportObjectEvidenceLedger objects;
    @Autowired JwtTokenProvider tokens;
    @Autowired AdminSessionRegistry sessions;
    @Autowired SupportAnalyticsPrivateQueryService privateQuery;
    final String run="an12_"+UUID.randomUUID().toString().replace("-","").substring(0,10);
    final Set<Long> customers=new LinkedHashSet<>();
    final List<Map<String,Object>> requests=new ArrayList<>();
    final Map<String,Object> nativeObservations=new LinkedHashMap<>();
    final Map<Long,String> bearer=new HashMap<>();
    SupportFixtureActors actors;SupportGroupRuntimeFixtures fixture;
    SupportGroupRuntimeFixtures.ExactFacts exactFacts;
    final Map<Long,Map<String,Object>> customerIdentities=new LinkedHashMap<>();
    long boss,manager,ownerless,agent,foreignManager,foreignAgent,group,foreignGroup;
    String testcase;String assertionEvidence;
    List<Map<String,Object>> originalBindings;

    @BeforeEach void controlledActorsAndCurrentResource(TestInfo info) throws Exception {
        testcase=info.getTestMethod().orElseThrow().getName();
        var target=SupportRuntimeTarget.current();assertThat(target.analytics()).isTrue();target.requireEnvironment(System.getenv());
        var context=json.readTree(Path.of(required("CS_ENHANCE_ACTOR_CONTEXT")).toFile());
        SupportExclusiveRuntimeOwnership.requireActual(context,target,jdbc);
        assertThat(context.path("identity").path("runId").asText()).isEqualTo(required("WORKFLOW_RUN_ID"));
        assertThat(context.path("identity").path("snapshotHash").asText()).isEqualTo(required("WORKFLOW_SNAPSHOT_HASH"));
        originalBindings=jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id");
        actors=new SupportFixtureActors(jdbc,redis,json,transactions,run,getClass().getSimpleName());actors.assertBusinessEntry();
        boss=actor("boss","SUPER_ADMIN","MANAGER");manager=actor("manager","SUPPORT","MANAGER");
        ownerless=actor("ownerless","SUPPORT","MANAGER");agent=actor("agent","SUPPORT","DEDICATED");
        foreignManager=actor("foreignmanager","SUPPORT","MANAGER");foreignAgent=actor("foreignagent","SUPPORT","DEDICATED");
        exactFacts=new SupportGroupRuntimeFixtures.ExactFacts(jdbc,json,actors,()->Set.copyOf(customers),()->fixture==null?Set.of():fixture.createdGroupIds(),getClass().getSimpleName(),testcase,true);
        fixture=new SupportGroupRuntimeFixtures(actors,jdbc,groupMapper,groups,()->Set.copyOf(customers),exactFacts);
        fixture.asSuper(boss,()->{
            group=fixture.create(manager,List.of(agent),run+"own").id();
            foreignGroup=fixture.create(foreignManager,List.of(foreignAgent),run+"foreign").id();
            var old=groupMapper.qualification(ownerless,"SUPERVISOR");
            String command=key();var request=new ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification("SUPERVISOR","ENABLED",old==null?0L:old.version(),jdbc.queryForObject("SELECT version FROM nx_admin WHERE id=?",Long.class,ownerless),"Owned ownerless analytics supervisor");
            exactFacts.command("GROUP_QUALIFICATION",command,request,()->groups.qualification(ownerless,command,request));
        });
    }
    long actor(String label,String role,String seat){return actors.createSql(run+label,"!disabled-fixture-password",run+label,role,seat);}
    long customer(boolean registerHistory) {
        String ref=UUID.randomUUID().toString().replace("-","").substring(0,20);
        return exactFacts.mutate("CUSTOMER_REGISTER",()->{long id=objects.createCustomer(getClass().getSimpleName(),testcase,ref,()->{
            var generated=new org.springframework.jdbc.support.GeneratedKeyHolder();
            int affected=jdbc.update(c->{var s=c.prepareStatement("INSERT INTO nx_user(country_code,phone,password_hash,nickname,referral_code,status,sandbox,client_ip) VALUES('0',?,'!disabled-fixture-password',?,?,'ACTIVE',0,'127.0.0.1')",java.sql.Statement.RETURN_GENERATED_KEYS);s.setString(1,ref);s.setString(2,run);s.setString(3,ref);return s;},generated);
            long created=Objects.requireNonNull(generated.getKey()).longValue();long lookup=jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,ref);
            assertThat(affected).isEqualTo(1);assertThat(lookup).isEqualTo(created);
            if(registerHistory)registration.register(created,null);else bindingService.register(created,null);
            return new SupportObjectEvidenceLedger.CustomerInsert(created,affected,lookup);
        });customers.add(id);customerIdentities.put(id,jdbc.queryForMap("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",id));
        exactFacts.proof("CUSTOMER_CREATOR_COMMITTED_READBACK",Map.of("identity",customerIdentities.get(id),"referralCode",ref,"creatorSource","SupportObjectEvidenceLedger.createCustomer generatedKey+affected1+referral lookup; actual committed registration callback","registeredHistory",registerHistory));return id;});
    }
    void requireCustomer(long id){assertThat(customers).contains(id);assertThat(jdbc.queryForObject("SELECT nickname FROM nx_user WHERE id=?",String.class,id)).isEqualTo(run);assertThat(jdbc.queryForMap("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",id)).isEqualTo(customerIdentities.get(id));}
    void wallet(long id,String usdt,String nex){requireCustomer(id);exactFacts.mutate("WALLET_INSERT",()->{assertThat(jdbc.queryForList("SELECT * FROM nx_user_wallet WHERE user_id=?",id)).isEmpty();assertThat(jdbc.update("INSERT INTO nx_user_wallet(user_id,usdt_available,nex_available,version) VALUES(?,?,?,7)",id,new java.math.BigDecimal(usdt),new java.math.BigDecimal(nex))).isEqualTo(1);return null;});}
    void bind(long customer,long target) throws Exception {
        requireCustomer(customer);actors.creationReference(target);
        var old=bindings.current(customer);var c=new SupportBindingRequest.Customer(customer,old==null?null:old.id(),old==null?bindings.poolVersion(customer):old.version());
        String command=key();var payload=new SupportBindingRequest(target,List.of(c),"Owned analytics runtime transfer");
        assertThat(jdbc.queryForList("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?","SUPPORT_TRANSFER:"+boss,command)).isEmpty();
        exactFacts.command("CUSTOMER_TRANSFER_HTTP",command,payload,()->{ok(request("POST","/api/admin/content/support-agents/assignments/transfer",boss,payload,command));return null;});
        assertThat(fresh("SELECT agent_admin_id FROM nx_support_agent_user_assignment WHERE user_id=? AND is_deleted=0 AND ends_at IS NULL",customer)).singleElement().satisfies(row->assertThat(((Number)row.get("agent_admin_id")).longValue()).isEqualTo(target));
    }
    List<Map<String,Object>> fresh(String sql,Object... args) {
        try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection();var s=c.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);var out=new ArrayList<Map<String,Object>>();
            try(var rows=s.executeQuery()){var meta=rows.getMetaData();while(rows.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i),rows.getObject(i));out.add(row);}}return out;
        }catch(java.sql.SQLException e){throw new IllegalStateException("Independent JDBC read failed",e);}
    }
    <T>T as(long actor,Supplier<T> action){var old=SecurityContextHolder.getContext().getAuthentication();var auth=new UsernamePasswordAuthenticationToken(String.valueOf(actor),null,List.of("service_m1_read","service_m3_read","platform_a1_read").stream().map(SimpleGrantedAuthority::new).toList());auth.setDetails(Map.of("subjectType","ADMIN","username",run));SecurityContextHolder.getContext().setAuthentication(auth);try{return action.get();}finally{SecurityContextHolder.getContext().setAuthentication(old);}}
    JsonNode analytics(long actor,String query) throws Exception{return ok(request("GET","/api/admin/content/support-workbench/analytics?"+query,actor,null,null));}
    void deny(long actor,String query,Integer... codes)throws Exception{rejected(request("GET","/api/admin/content/support-workbench/analytics?"+query,actor,null,null),codes);}
    String token(long actor){return bearer.computeIfAbsent(actor,id->{String username=jdbc.queryForObject("SELECT username FROM nx_admin WHERE id=?",String.class,id);return tokens.createToken(id,"ADMIN",username,List.of(),sessions.createSession(id,username));});}
    record HttpResult(int status,JsonNode body) {}
    HttpResult request(String method,String path,long actor,Object body,String key)throws Exception {
        actors.assertBusinessEntry();var builder=HttpRequest.newBuilder(URI.create(SupportRuntimeTarget.current().httpBase()+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token(actor)).header("Content-Type","application/json");
        if(key!=null)builder.header("Idempotency-Key",key);builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());JsonNode result=json.readTree(response.body());
        var evidence=new LinkedHashMap<String,Object>();evidence.put("method",method);evidence.put("path",path);evidence.put("actorId",actor);evidence.put("httpStatus",response.statusCode());evidence.put("body",result);requests.add(evidence);return new HttpResult(response.statusCode(),result);
    }
    JsonNode ok(HttpResult response){assertThat(response.status()).as("actual HTTP success status").isEqualTo(200);assertThat(response.body().path("code").asInt(-1)).as("actual HTTP response %s",response.body().path("message")).isZero();return response.body().path("data");}
    void rejected(HttpResult response,Integer... codes){int code=response.body().path("code").asInt(-1);assertThat(code).isIn((Object[])codes);assertThat(response.status()).as("actual HTTP denial status agrees with BizException code").isEqualTo(code);assertThat(response.body().path("data").isMissingNode()||response.body().path("data").isNull()).isTrue();}
    static List<Long> ids(JsonNode page,String field){var ids=new ArrayList<Long>();for(JsonNode row:page.path("records"))ids.add(row.path(field).asLong());assertThat(ids).doesNotHaveDuplicates();return ids;}
    static String stale(){return "saq-v1:"+"0".repeat(64);}
    static String encoded(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    String key(){return run+UUID.randomUUID();}
    void checked(String evidence){assertionEvidence=evidence;}
    static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing runtime identity: "+name);return value;}

    @AfterEach void ownedCleanupAndSceneReadback() throws Exception {
        boolean cleanupComplete=false;
        var cleanup=new ArrayList<Runnable>();
        cleanup.add(()->objects.cleanup(getClass().getSimpleName(),testcase));
        if(fixture!=null)cleanup.add(fixture::cleanup);
        if(actors!=null && ownerless>0)cleanup.add(()->{actors.creationReference(ownerless);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_account_qualification_history WHERE admin_id=?",Long.class,ownerless)).isZero();});
        for(long id:customers)cleanup.add(()->{
            requireCustomer(id);
            for(String table:List.of("nx_support_customer_route_history","nx_support_payment_history_birth","nx_user_wallet","nx_support_agent_user_assignment","nx_support_binding_pool"))assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+(table.equals("nx_user_wallet")||table.equals("nx_support_agent_user_assignment")?"user_id":"customer_id")+"=?",Long.class,id)).isZero();
            var retiringRow=jdbc.queryForMap("SELECT * FROM nx_user WHERE id=?",id);exactFacts.proof("CUSTOMER_RETIRE_INTENT",Map.of("creatorIdentity",customerIdentities.get(id),"currentFullRow",retiringRow));
            assertThat(jdbc.update("UPDATE nx_user SET sponsor_user_id=NULL,status='DISABLED',is_deleted=1 WHERE "+SupportGroupRuntimeFixtures.ExactFacts.fence(retiringRow),retiringRow.values().toArray())).isEqualTo(1);
            assertThat(fresh("SELECT status,is_deleted FROM nx_user WHERE id=?",id)).singleElement().satisfies(row->{assertThat(row.get("status")).isEqualTo("DISABLED");assertThat(((Number)row.get("is_deleted")).intValue()).isEqualTo(1);});
        });
        cleanup.add(()->{if(actors!=null)actors.cleanupAll(Set.of());});
        cleanup.add(()->{if(originalBindings!=null)assertThat(jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id")).isEqualTo(originalBindings);});
        try {SupportObjectEvidenceLedger.cleanupIndependently(cleanup.toArray(Runnable[]::new));cleanupComplete=true;}
        finally {
            SecurityContextHolder.clearContext();var result=new LinkedHashMap<String,Object>();
            result.put("workflowRunId",required("WORKFLOW_RUN_ID"));result.put("snapshotHash",required("WORKFLOW_SNAPSHOT_HASH"));result.put("capability","runtime");result.put("suite",getClass().getSimpleName());result.put("testcase",testcase);
            result.put("status",assertionEvidence==null||!cleanupComplete?"not-completed":"scenario-assertions-passed");result.put("cleanupComplete",cleanupComplete);result.put("acceptanceCoverage","PARTIAL_NO_PHASE_SIGNOFF");result.put("assertions",assertionEvidence);result.put("requests",requests);result.put("ownedCustomerIds",customers);result.put("ownedActorIds",actors==null?Set.of():actors.ownedIds());
            result.put("nativeObservations",nativeObservations);
            result.put("exactFactReceipts",exactFacts==null?List.of():exactFacts.receipts());
            result.put("httpBase",SupportRuntimeTarget.current().httpBase());result.put("database",jdbc.queryForObject("SELECT DATABASE()",String.class));result.put("serverUuid",jdbc.queryForObject("SELECT @@server_uuid",String.class));
            Files.write(Path.of(required("CS_ENHANCE_EVIDENCE_DIR")).resolve(getClass().getSimpleName()+"-"+testcase+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);
        }
    }
}
