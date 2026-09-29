package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import ffdd.opsconsole.content.dto.SupportBindingRequest;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.shared.security.*;
import ffdd.opsconsole.onboarding.application.OnboardingCalibrationService;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties={"server.port=18130","server.address=127.0.0.1"})
@Import(SupportIsolatedRuntime.class)
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="SUPPORT_PATCH_ISOLATED",matches="true")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppSupportAdvisorRuntimeTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SupportBindingService bindings;
    @Autowired SupportBindingMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JwtTokenProvider tokens;
    @Autowired AdminSessionRegistry sessions;
    @Autowired OnboardingCalibrationService onboarding;
    @Autowired ApplicationContext context;
    private final HttpClient http=HttpClient.newHttpClient();
    private final List<String> passed=new ArrayList<>();
    private long boss,advisor,next,customer,unbound;
    private String userToken,unboundToken,adminToken;

    @BeforeEach void fixture() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("cs_advisor_patch");
        assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(33329);
        assertThat(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor")).isFalse();
        assertThat(context.getEnvironment().getProperty("spring.data.redis.database")).isEqualTo("14");
        assertThat(context.getEnvironment().getProperty("nexion.storage.bucket")).isEqualTo("cs-advisor-patch-private");
        assertThat(context.getEnvironment().getProperty("nexion.storage.endpoint")).isEqualTo("http://127.0.0.1:19030");
        boss=admin("SUPER_ADMIN","MANAGER");advisor=admin("SUPPORT","DEDICATED");next=admin("SUPPORT","DEDICATED");
        asBoss();customer=customer();unbound=customer();transfer(customer,advisor);
        userToken=userToken(customer);unboundToken=userToken(unbound);
        String name=jdbc.queryForObject("SELECT username FROM nx_admin WHERE id=?",String.class,boss);
        adminToken=tokens.createToken(boss,"ADMIN",name,List.of(),sessions.createSession(boss,name));
        SecurityContextHolder.clearContext();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @AfterAll void evidence() throws Exception {
        assertThat(passed).hasSize(11);
        Path dir=Path.of(System.getenv("S4_EVIDENCE_DIR"));
        Files.writeString(dir.resolve("advisor-evidence.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("at",Instant.now().toString(),"database","cs_advisor_patch","port",18130,
                        "scheduledJobsDisabled",true,"checks",passed)));
    }
    @Test void noConversationFirstScreenAndTransferReadFreshResponsibility() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation WHERE user_id IN (?,?)",Long.class,customer,unbound)).isZero();
        var none=get(unboundToken,"");assertState(none,"UNBOUND","UNBOUND");
        assertThat(none.path("assignmentId").isNull()).isTrue();
        assertThat(none.path("currentAdvisorId").isNull()).isTrue();
        assertThat(none.path("currentAdvisorName").isNull()).isTrue();
        var initial=get(userToken,"");assertState(initial,"ASSIGNED","UNKNOWN");
        assertThat(initial.path("assignmentId").asLong()).isEqualTo(mapper.current(customer).id());
        assertThat(initial.path("currentAdvisorId").asLong()).isEqualTo(advisor);
        assertThat(initial.path("currentAdvisorName").asText()).isEqualTo(name(advisor));
        asBoss();transfer(customer,next);SecurityContextHolder.clearContext();
        var fresh=get(userToken,"");assertState(fresh,"ASSIGNED","UNKNOWN");
        assertThat(fresh.path("assignmentId").asLong()).isNotEqualTo(initial.path("assignmentId").asLong());
        assertThat(fresh.path("currentAdvisorId").asLong()).isEqualTo(next);
        assertThat(fresh.path("currentAdvisorName").asText()).isEqualTo(name(next));
        passed.add("first-screen-unbound-assigned-transfer-fresh");
    }
    @Test void busyIsNotDisabledAndGetsHaveNoActivityOrMaintenanceWrites() throws Exception {
        List<Long> before=facts();
        jdbc.update("UPDATE nx_support_agent_profile SET busy=1 WHERE admin_id=?",advisor);
        assertState(get(userToken,""),"ASSIGNED","BUSY");
        jdbc.update("UPDATE nx_support_agent_profile SET busy=0 WHERE admin_id=?",advisor);
        assertState(get(userToken,""),"ASSIGNED","UNKNOWN");
        assertThat(facts()).isEqualTo(before);
        passed.add("busy-unknown-no-business-writes");
    }
    @ParameterizedTest
    @ValueSource(strings={"admin-disabled","admin-deleted","profile-disabled","profile-deleted","seat","service","role-link","role-missing"})
    void unavailableAdvisorKeepsRealResponsibility(String cause) throws Exception {
        var before=get(userToken,"");
        switch(cause){
            case "admin-disabled" -> jdbc.update("UPDATE nx_admin SET status=0 WHERE id=?",advisor);
            case "admin-deleted" -> jdbc.update("UPDATE nx_admin SET is_deleted=1 WHERE id=?",advisor);
            case "profile-disabled" -> jdbc.update("UPDATE nx_support_agent_profile SET enabled=0,busy=1 WHERE admin_id=?",advisor);
            case "profile-deleted" -> jdbc.update("UPDATE nx_support_agent_profile SET is_deleted=1 WHERE admin_id=?",advisor);
            case "seat" -> jdbc.update("UPDATE nx_support_agent_profile SET seat_type='GENERAL' WHERE admin_id=?",advisor);
            case "service" -> jdbc.update("UPDATE nx_support_agent_profile SET service_types='support' WHERE admin_id=?",advisor);
            case "role-link" -> jdbc.update("UPDATE nx_admin_role_relation SET is_deleted=1 WHERE admin_id=?",advisor);
            case "role-missing" -> jdbc.update("UPDATE nx_admin_role_relation SET role_id=-1 WHERE admin_id=?",advisor);
            default -> throw new AssertionError(cause);
        }
        var disabled=get(userToken,"");assertState(disabled,"ADVISOR_DISABLED","DISABLED");
        assertThat(disabled.path("assignmentId")).isEqualTo(before.path("assignmentId"));
        assertThat(disabled.path("currentAdvisorId")).isEqualTo(before.path("currentAdvisorId"));
        assertThat(disabled.path("currentAdvisorName")).isEqualTo(before.path("currentAdvisorName"));
        // Existing customer assistance remains available even when the responsible advisor is disabled.
        var result=request("POST","/api/app/support/conversations",userToken,
                Map.of("conversationType","support","openingText","Please help while my advisor is unavailable"));
        assertThat(result.path("code").asInt()).isZero();
        assertThat(result.path("data").path("conversation").path("ownerAgentId").asLong()).isEqualTo(advisor);
        passed.add("disabled-keeps-identity-and-help-"+cause);
    }
    @Test void authenticationAudienceParameterAndResponseBoundaries() throws Exception {
        var anonymous=send("GET","/api/app/support/advisor",null,null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(request("GET","/api/app/support/advisor",adminToken,null).path("code").asInt()).isEqualTo(403);
        assertThat(request("GET","/api/app/support/advisor?customerId="+customer,unboundToken,null).path("code").asInt()).isEqualTo(422);
        assertThat(request("GET","/api/app/support/advisor?userId="+unbound,userToken,null).path("code").asInt()).isEqualTo(422);
        var response=send("GET","/api/app/support/advisor",userToken,null);
        assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
        var data=json.readTree(response.body()).path("data");
        List<String> fields=new ArrayList<>();data.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("assignmentId","currentAdvisorId","currentAdvisorName","assignmentState","availability");
        passed.add("authentication-audience-no-substitution-no-store-minimal-fields");
    }
    private List<Long> facts(){
        return List.of("nx_support_activity_event","nx_support_maintenance_execution","nx_support_maintenance_cycle").stream()
                .map(table->jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE customer_id=?",Long.class,customer)).toList();
    }
    private void assertState(JsonNode data,String assignment,String availability){
        assertThat(data.path("assignmentState").asText()).isEqualTo(assignment);
        assertThat(data.path("availability").asText()).isEqualTo(availability);
    }
    private JsonNode get(String token,String query)throws Exception {
        var result=request("GET","/api/app/support/advisor"+query,token,null);
        assertThat(result.path("code").asInt(-1)).as("Advisor GET response code").isZero();return result.path("data");
    }
    private String name(long id){return jdbc.queryForObject("SELECT nickname FROM nx_admin WHERE id=?",String.class,id);}
    private String key(){return "advisor-"+UUID.randomUUID();}
    private long admin(String role,String seat){
        String name=key();jdbc.update("INSERT INTO nx_admin(username,password_hash,nickname,super_admin,status) VALUES(?,'NO_LOGIN',?,?,1)",name,name,"SUPER_ADMIN".equals(role)?1:0);
        long id=jdbc.queryForObject("SELECT id FROM nx_admin WHERE username=?",Long.class,name);
        jdbc.update("INSERT INTO nx_admin_role_relation(admin_id,role_id) SELECT ?,id FROM nx_admin_role WHERE role_code=? AND is_deleted=0",id,role);
        jdbc.update("INSERT INTO nx_support_agent_profile(admin_id,seat_type,position,service_types,tags,max_concurrent,enabled,transferable,busy) VALUES(?,?,?,'support,advisor','',0,1,1,0)",id,seat,seat);return id;
    }
    private long customer(){
        return new TransactionTemplate(transactions).execute(status->{
            String ref=UUID.randomUUID().toString().replace("-","").substring(0,20);
            String phone="198"+String.format("%08d",Math.abs((long)ref.hashCode())%100000000);
            jdbc.update("INSERT INTO nx_user(country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox) VALUES('+86',?,'127.0.0.1','NO_LOGIN','Advisor patch',?,'ACTIVE',0)",phone,ref);
            long id=jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,ref);bindings.register(id,null);return id;
        });
    }
    private void asBoss(){
        var auth=new UsernamePasswordAuthenticationToken(String.valueOf(boss),null,List.of(new SimpleGrantedAuthority("service_m3_write")));
        auth.setDetails(Map.of("subjectType","ADMIN"));SecurityContextHolder.getContext().setAuthentication(auth);
    }
    private void transfer(long id,long agent){
        var assignment=mapper.current(id);
        assertThat(bindings.transfer(key(),new SupportBindingRequest(agent,List.of(new SupportBindingRequest.Customer(id,
                assignment==null?null:assignment.id(),assignment==null?mapper.poolVersion(id):assignment.version())),"Isolated advisor projection test")).getCode()).isZero();
    }
    private String userToken(long id)throws Exception {
        assertThat(onboarding.defer(id,new OnboardingCalibrationService.ActionRequest("advisor-device-"+id,0,key())).getCode()).isZero();
        String session=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,expires_at,last_active_at) VALUES(?,?,?,DATE_ADD(NOW(),INTERVAL 1 DAY),NOW())",id,session,session);
        String token=tokens.createUserToken(id,"advisor-test",List.of(),session,Duration.ofHours(1),UserAuthEnvironment.PRODUCTION);
        var terms=request("GET","/api/legal/terms/current?locale=en&jurisdiction=GLOBAL",token,null).path("data");
        assertThat(request("POST","/api/legal/terms/acknowledgment",token,Map.of("locale","en","jurisdiction","GLOBAL","version",terms.path("version").asText(),"confirmed",true,"idempotencyKey",key(),"runId","")).path("code").asInt()).isZero();
        return token;
    }
    private JsonNode request(String method,String path,String token,Object body)throws Exception {
        return json.readTree(send(method,path,token,body).body());
    }
    private HttpResponse<String> send(String method,String path,String token,Object body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18130"+path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type","application/json").header("Idempotency-Key",key());
        if(token!=null)request.header("Authorization","Bearer "+token);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
