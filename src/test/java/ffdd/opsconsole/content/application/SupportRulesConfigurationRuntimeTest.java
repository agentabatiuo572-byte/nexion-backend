package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.NexionOpsConsoleApplication;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.nio.file.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Controlled singleton writes use existing source/actor/transaction journal and restore original business values. */
@EnabledIfEnvironmentVariable(named="CS_ANALYTICS_RULES_CONTRACT_ENABLED",matches="true")
@SpringBootTest(classes=NexionOpsConsoleApplication.class,webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({SupportEnhancementPreparationTest.IsolatedConfiguration.class,SupportObjectEvidenceLedger.Configuration.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportRulesConfigurationRuntimeTest extends SupportOwnedContractFixture {
    private static final String RULES="/api/admin/content/support-agents/rules";
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) {SupportEnhancementPreparationTest.isolatedBoundary(r);}

    private JsonNode initialRule;
    private List<Map<String,Object>> initialBindings;
    private Set<String> initialJournalFiles=Set.of();
    private boolean controlledWrites,restored,registrationWrites;
    private final Map<String,Map<String,Object>> commandIntents=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>> commandRows=new LinkedHashMap<>();
    @BeforeEach void captureRuleRestorationBoundary() throws Exception {
        oldRules=bindingMapper.rules();initialRule=fullRule();initialBindings=jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id");
        Path dir=journalDirectory();if(Files.isDirectory(dir))try(var files=Files.list(dir)){initialJournalFiles=files.map(file->file.getFileName().toString()).collect(java.util.stream.Collectors.toSet());}
    }
    @Test void persistedRulesStrictInputsAndControlledCasReplayRestoreOriginalBusinessValues() throws Exception {
        String superToken=token(boss),advisor=token(first);
        long manager=admin("rules_manager","SUPPORT","MANAGER");
        createSupportGroup(manager,List.of(),"rules_managed");
        String supervisor=token(manager);
        JsonNode read=ok(http("GET",RULES,superToken,null,null));
        var persisted=jdbc.queryForList("SELECT * FROM nx_support_rules WHERE id=1");
        if(persisted.isEmpty()) {
            assertThat(read.isNull()).as("Missing rule cannot become invented unlimited defaults").isTrue();
            observed("rules-read","Missing singleton returned null; initialization/migration is NOT_RUN here");
        } else {
            assertThat(persisted).hasSize(1);
            var row=persisted.get(0);
            assertThat(read.path("version").asLong()).isEqualTo(((Number)row.get("version")).longValue());
            String mode=String.valueOf(row.get("inheritance_mode"));
            assertThat(read.path("inheritanceMode").asText()).isEqualTo(mode);
            assertThat(mode).isIn("UNLIMITED","LIMITED","UNCONFIGURED");
            if("LIMITED".equals(mode)) {
                assertThat(row.get("max_inheritance_depth")).isInstanceOf(Number.class);
                assertThat(read.path("maxInheritanceDepth").asInt()).isEqualTo(((Number)row.get("max_inheritance_depth")).intValue()).isGreaterThanOrEqualTo(0);
            } else assertThat(read.path("maxInheritanceDepth").isNull()).isTrue();
            assertThat(ok(http("GET",RULES,superToken,null,null))).isEqualTo(read);
            observed("rules-read","Exact stored mode/depth/version read twice; LIMITED 0 is accepted without conversion");
        }
        long expected=read.path("version").asLong(1L);
        Map<String,Object> valid=request(expected,"UNLIMITED",null);
        List<Map<String,Object>> invalid=new ArrayList<>();
        invalid.add(change(valid,"inheritanceMode","UNCONFIGURED"));
        invalid.add(change(valid,"inheritanceMode","unlimited"));
        invalid.add(change(valid,"maxInheritanceDepth",7));
        invalid.add(request(expected,"LIMITED",null));
        invalid.add(request(expected,"LIMITED",-1));
        invalid.add(change(valid,"unboundAssignmentMode","EVERYONE"));
        invalid.add(change(valid,"activityWindowDays",31));
        invalid.add(change(valid,"reason","short"));
        invalid.add(change(valid,"expectedVersion",0));
        for(var body:invalid) {
            int exact=body.get("expectedVersion").equals(0)?400:422;
            assertCode(http("PUT",RULES,superToken,body,key()),exact);
            assertProtected();
        }
        for(var badNumber:List.of(change(valid,"expectedVersion","1"),change(valid,"maxInheritanceDepth",0.5),change(valid,"dormantDays",3.2),change(valid,"maintenanceDays","3"))) {
            assertCode(http("PUT",RULES,superToken,badNumber,key()),400);
            assertProtected();
        }
        assertCode(http("PUT",RULES,advisor,valid,key()),403);
        assertCode(http("PUT",RULES,supervisor,valid,key()),403);
        var executor=Executors.newFixedThreadPool(2);
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try {
            var one=executor.submit(()->{ready.countDown();go.await();return http("PUT",RULES,advisor,valid,key());});
            var two=executor.submit(()->{ready.countDown();go.await();return http("PUT",RULES,supervisor,valid,key());});
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();
            assertCode(one.get(30,TimeUnit.SECONDS),403);assertCode(two.get(30,TimeUnit.SECONDS),403);
        } finally {go.countDown();executor.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin_idempotency_record WHERE scope IN (?,?,?)",Long.class,
            "SUPPORT_RULES:"+boss,"SUPPORT_RULES:"+first,"SUPPORT_RULES:"+manager)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='SUPPORT_RULES_CHANGED' AND actor_id IN (?,?,?)",Long.class,boss,first,manager)).isZero();
        assertProtected();
        observed("rules-negative","Exact 400/422 invalid enums/numbers/reason and 403 advisor/supervisor including concurrent rejection; no claim/audit or singleton change");
        controlledConfigurationWrites(superToken);
        gap("BE-CFG-01","New-install initialization requires parent-owned isolated singleton lifecycle; not executed by this suite");
        gap("BE-CFG-02","Real LIMITED0/2 save/readback covered; migration and second startup still require canonical migration evidence");
        gap("BE-CFG-03","Explicit UNCONFIGURED migration and missing-field/read-failure branches require isolated migration/failure boundary");
        gap("BE-CFG-04","Real UNLIMITED save/readback and unchanged original binding rows covered; future registration/old pool behavior remains separate; no draft/publish API exists");
        gap("BE-CFG-05","Depth>7/direct inviter/cycle registration modes require controlled singleton state; existing Core method is separate evidence, not inherited PASS");
        gap("BE-CFG-06","Strict inputs, two actual SUPER CAS winner/conflict, committed audit and same-key replay/recovery covered; physical lost response and client confirmation remain separate");
        gap("BE-G10","Existing bindings unchanged across real LIMITED0/2/UNLIMITED saves; inheritance/random registration and pool routing modes remain separate");
        finish("rules-configuration-contract-runtime.json");
    }
    @Test void actualLostHttpResponseRecoversOwnedCommandBeforeSameKeyRetry() throws Exception {
        controlledWrites=true;String superToken=token(boss);long version=ok(http("GET",RULES,superToken,null,null)).path("version").asLong();
        var body=request(version,"UNLIMITED",null);String command=key();prepareCommand(boss,body,command);byte[] exactBody=json.writeValueAsBytes(body);
        var forwarded=new java.util.concurrent.atomic.AtomicBoolean();var upstream=new java.util.concurrent.atomic.AtomicReference<JsonNode>();var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();var completed=new CountDownLatch(1);
        var proxy=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        proxy.createContext(RULES,exchange->{
            try{
                // This task-only socket can forward exactly one already-authorized request; client retries never forward twice.
                assertThat(exchange.getRequestMethod()).isEqualTo("PUT");assertThat(exchange.getRequestURI().toString()).isEqualTo(RULES);
                assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo(command);assertThat(exchange.getRequestBody().readAllBytes()).isEqualTo(exactBody);
                if(forwarded.compareAndSet(false,true))upstream.set(ok(http("PUT",RULES,superToken,body,command)));
            }catch(Throwable error){failure.compareAndSet(null,error);}
            finally{completed.countDown();exchange.close();} // Close before any status/header/body: the real client receives no result.
        });
        try{
            proxy.start();var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+proxy.getAddress().getPort()+RULES)).timeout(java.time.Duration.ofSeconds(20)).header("Content-Type","application/json").header("Idempotency-Key",command).PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(exactBody)).build();
            assertThatThrownBy(()->java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString())).isInstanceOf(java.io.IOException.class);
            assertThat(completed.await(30,TimeUnit.SECONDS)).isTrue();if(failure.get()!=null)throw new AssertionError("Task-only lost-response proxy failed",failure.get());assertThat(forwarded.get()).isTrue();assertThat(upstream.get()).isNotNull();
        }finally{proxy.stop(0);}
        // Client result is unknown. Recovery precedes retry; no DB status is artificially rewritten to UNKNOWN.
        var recovered=ok(http("GET","/api/admin/content/support-workbench/commands/"+command,superToken,null,null));assertThat(recovered.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(recovered.path("resultType").asText()).isEqualTo("SUPPORT_RULES");
        var saved=ok(http("GET",RULES,superToken,null,null));assertSaved(saved,body,boss,version+1);assertThat(recovered.path("result").path("data")).isEqualTo(saved);assertThat(saved).isEqualTo(upstream.get());
        terminalCommand(boss,body,command,"SUCCEEDED");assertSuccessAudit(boss,command,body);var snapshot=fullRule();assertHidden(http("GET","/api/admin/content/support-workbench/commands/"+command,token(first),null,null));
        assertThat(ok(http("PUT",RULES,superToken,body,command))).isEqualTo(saved);assertThat(fullRule()).isEqualTo(snapshot);terminalCommand(boss,body,command,"SUCCEEDED");assertSuccessAudit(boss,command,body);assertProtected();
        observed("physical-lost-response","Task-owned loopback socket forwards one exact prepared PUT to real API, commits then closes without HTTP response; actual client IOException -> real owned command recovery then fresh API/SQL -> same-key retry identical result, one version/audit/claim; foreign recovery404; no simulated UNKNOWN SQL flag or production proxy");finish("rules-lost-response-runtime.json");
    }
    @Test void partialGroupSetupRetainsEveryCommittedCommandAndCleansExactRows() throws Exception {
        long owner=admin("partial_owner","SUPPORT","MANAGER"),member=admin("partial_member","SUPPORT","DEDICATED");
        assertThat(groupMapper.qualification(owner,"SUPERVISOR")).isNull();assertThat(groupMapper.memberCurrent(member)).isNull();
        // Owner qualification, group create, first member qualification/move commit; last member is already grouped.
        assertThatThrownBy(()->createSupportGroup(owner,List.of(member,first),"partial_chain")).isInstanceOf(AssertionError.class);
        assertThat(groupMapper.qualificationCurrent(owner,"SUPERVISOR")).isNotNull();assertThat(groupMapper.memberCurrent(member).groupId()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_group WHERE supervisor_admin_id=?",Long.class,owner)).isEqualTo(1L);
        cleanupSupportGroupFacts();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_group WHERE supervisor_admin_id=?",Long.class,owner)).isZero();
        assertThat(groupMapper.qualification(owner,"SUPERVISOR")).isNull();assertThat(groupMapper.member(member)).isNull();assertProtected();
        observed("partial-group-cleanup","Later fixture precondition rejects already-grouped final member only after earlier production commands actually committed; per-command finally receipts enrol committed qualification/group/member rows; explicit exact full-row cleanup restores original four entire tables, repeated final cleanup is empty/idempotent");finish("rules-partial-group-cleanup-runtime.json");
    }
    @Test void genuineFacadeRegistrationsHonorZeroTwoUnlimitedDirectInviterCycleAndPolicyEpoch() throws Exception {
        controlledWrites=true;registrationWrites=true;String superToken=token(boss);
        saveSelection(superToken,"LIMITED",0,"SUPERVISOR");
        long root=ownedCustomer(first);var rootBinding=bindingMapper.current(root);assertThat(rootBinding.depth()).isZero();
        long zeroChild=ownedRegistrationCustomer(root,false);assertPool(zeroChild,"DEPTH_LIMIT");
        var zeroPool=jdbc.queryForMap("SELECT * FROM nx_support_binding_pool WHERE customer_id=?",zeroChild);
        saveSelection(superToken,"LIMITED",2,"SUPERVISOR");
        long child1=ownedRegistrationCustomer(root,false);assertInherited(child1,rootBinding,1);
        long child2=ownedRegistrationCustomer(child1,false);assertInherited(child2,bindingMapper.current(child1),2);
        long child3=ownedRegistrationCustomer(child2,false);assertPool(child3,"DEPTH_LIMIT");
        var finiteRows=jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id");
        var finitePool=jdbc.queryForList("SELECT * FROM nx_support_binding_pool ORDER BY customer_id");
        saveSelection(superToken,"UNLIMITED",null,"SUPERVISOR");
        assertThat(jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id")).isEqualTo(finiteRows);
        assertThat(jdbc.queryForList("SELECT * FROM nx_support_binding_pool ORDER BY customer_id")).isEqualTo(finitePool);
        assertThat(jdbc.queryForMap("SELECT * FROM nx_support_binding_pool WHERE customer_id=?",zeroChild)).isEqualTo(zeroPool);
        long previous=child2;for(int depth=3;depth<=9;depth++){var parent=bindingMapper.current(previous);long current=ownedRegistrationCustomer(previous,false);assertInherited(current,parent,depth);previous=current;}
        // The direct inviter is unbound despite a bound ancestor. Registration must not jump over it.
        long belowUnbound=ownedRegistrationCustomer(child3,false);assertPool(belowUnbound,"INVITER_UNBOUND");
        withOwnedInactiveAccount(first,()->{assertThat(bindingMapper.eligibleAgent(first)).isZero();long unavailable=ownedRegistrationCustomer(root,false);assertPool(unavailable,"AGENT_UNAVAILABLE");});
        assertThat(bindingMapper.eligibleAgent(first)).isEqualTo(1);
        long noInviter=ownedRegistrationCustomer(null,false);assertPool(noInviter,"NO_INVITER");
        long cycle=ownedRegistrationCustomer(null,true);assertPool(cycle,"MIGRATION_REVIEW");
        var oldPool=jdbc.queryForMap("SELECT * FROM nx_support_binding_pool WHERE customer_id=?",noInviter);
        var oldBirth=jdbc.queryForMap("SELECT * FROM nx_support_payment_history_birth WHERE customer_id=?",noInviter);
        saveSelection(superToken,"UNLIMITED",null,"AUTO_RANDOM");
        var effective=jdbc.queryForObject("SELECT mode_effective_at FROM nx_support_rules WHERE id=1",java.sql.Timestamp.class);assertThat(effective).isNotNull();
        assertThat(jdbc.queryForMap("SELECT * FROM nx_support_binding_pool WHERE customer_id=?",noInviter)).isEqualTo(oldPool);
        assertThat(jdbc.queryForMap("SELECT * FROM nx_support_payment_history_birth WHERE customer_id=?",noInviter)).isEqualTo(oldBirth);
        long future=ownedRegistrationCustomer(null,false);assertPool(future,"NO_INVITER");
        var attempted=jdbc.queryForMap("SELECT * FROM nx_support_binding_pool WHERE customer_id=?",future);
        assertThat(((Number)attempted.get("auto_eligible")).intValue()).isEqualTo(1);
        assertThat(attempted.get("auto_attempt_state")).isEqualTo("WAITING_ROUTE");assertThat(attempted.get("last_outcome")).isEqualTo("GROUP_ROUTE_REQUIRED");
        assertThat(((Number)attempted.get("attempts")).intValue()).isEqualTo(1);assertThat(attempted.get("operation_id")).isEqualTo("registration:"+future);
        assertThat(((Number)attempted.get("auto_rule_version")).longValue()).isEqualTo(bindingMapper.rules().version());
        // Actual DB UTC birth is authoritative; account created_at is the legacy +08 calendar.
        var born=jdbc.queryForObject("SELECT birth_db_utc FROM nx_support_payment_history_birth WHERE customer_id=?",java.sql.Timestamp.class,future);
        assertThat((java.sql.Timestamp)oldBirth.get("birth_db_utc")).isBefore(effective);assertThat(born).isAfterOrEqualTo(effective);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_customer_route_history WHERE customer_id=?",Long.class,future)).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM nx_support_binding_pool WHERE customer_id=?",noInviter)).isEqualTo(oldPool);
        assertProtected();
        observed("genuine-registration","Actual SupportRegistrationFacade in same generated-ID insertion transaction writes genuine birth receipt; LIMITED0 rejects depth1, LIMITED2 allows1/2 and rejects3; UNLIMITED allows3..9 with exact direct-parent/root/agent/rule/registration operation; old bindings/pool unchanged; unbound direct inviter does not jump ancestor; no inviter queues; self-cycle MIGRATION_REVIEW; actual later AUTO_RANDOM epoch applies to newly born account only and missing route yields WAITING_ROUTE/GROUP_ROUTE_REQUIRED without global-agent adoption");
        gap("BE-G10-routed-random","Existing fresh owned random suite supplies explicit routed group positive/isolation proof; this method proves actual no-route registration exclusion, not random preview/confirm");

        finish("rules-registration-contract-runtime.json");
    }
    private void saveSelection(String superToken,String mode,Integer depth,String unbound) throws Exception {
        long version=ok(http("GET",RULES,superToken,null,null)).path("version").asLong();var body=change(request(version,mode,depth),"unboundAssignmentMode",unbound);String command=key();prepareCommand(boss,body,command);
        var saved=ok(http("PUT",RULES,superToken,body,command));assertSaved(saved,body,boss,version+1);terminalCommand(boss,body,command,"SUCCEEDED");assertSuccessAudit(boss,command,body);assertThat(ok(http("GET",RULES,superToken,null,null))).isEqualTo(saved);assertProtected();
    }
    private void assertPool(long customer,String reason){assertThat(bindingMapper.current(customer)).isNull();assertThat(jdbc.queryForObject("SELECT reason FROM nx_support_binding_pool WHERE customer_id=?",String.class,customer)).isEqualTo(reason);}
    private void assertInherited(long customer,ffdd.opsconsole.content.domain.SupportAssignment parent,int depth){
        var current=bindingMapper.current(customer);assertThat(current).isNotNull();assertThat(current.source()).isEqualTo("INHERITED");assertThat(current.depth()).isEqualTo(depth);
        assertThat(current.parentAssignmentId()).isEqualTo(parent.id());assertThat(current.segmentRootId()).isEqualTo(parent.segmentRootId());assertThat(current.agentAdminId()).isEqualTo(parent.agentAdminId());
        assertThat(current.ruleVersion()).isEqualTo(bindingMapper.rules().version());assertThat(jdbc.queryForObject("SELECT operation_id FROM nx_support_agent_user_assignment WHERE id=?",String.class,current.id())).isEqualTo("registration:"+customer);
        assertThat(jdbc.queryForList("SELECT * FROM nx_support_binding_pool WHERE customer_id=?",customer)).isEmpty();
    }
    @Test void canonicalRulesInitializationAndLegacyLimitedValuesReplayInExclusiveOwnedScratch() throws Exception {
        var target=SupportRuntimeTarget.current();var context=json.readTree(Path.of(System.getenv("CS_ENHANCE_ACTOR_CONTEXT")).toFile());SupportExclusiveRuntimeOwnership.requireActual(context,target,jdbc);
        assertThat(jdbc.queryForObject("SELECT @@server_uuid",String.class)).isEqualTo("3556ddae-c1a1-11f1-8853-a40c6626953d");assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(33337);assertThat(jdbc.queryForObject("SELECT CURRENT_USER()",String.class)).isEqualTo("cs_analytics_runner@127.0.0.1");
        String schema=target.database()+"_rules_cfg_audit";assertThat(schema).matches("[a-zA-Z0-9_]+");boolean owned=false;
        var evidence=new LinkedHashMap<String,Object>();
        try{
            // Existing scratch cannot be reused or dropped. Successful CREATE is the only ownership gate.
            jdbc.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");owned=true;
            var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:mysql://127.0.0.1:"+target.databasePort()+"/"+schema+"?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true",System.getenv("NEXION_DB_USERNAME"),System.getenv("NEXION_DB_PASSWORD"));var scratch=new org.springframework.jdbc.core.JdbcTemplate(ds);
            for(String ddl:List.of("CREATE TABLE nx_admin(id BIGINT PRIMARY KEY,status INT,is_deleted INT)","CREATE TABLE nx_user(id BIGINT PRIMARY KEY,is_deleted INT)","CREATE TABLE nx_admin_role(id BIGINT PRIMARY KEY,role_code VARCHAR(32),status INT,is_deleted INT)","CREATE TABLE nx_admin_role_relation(id BIGINT PRIMARY KEY,admin_id BIGINT,role_id BIGINT,is_deleted INT)","CREATE TABLE nx_support_agent_profile(admin_id BIGINT PRIMARY KEY,seat_type VARCHAR(16),service_types VARCHAR(64),enabled INT,is_deleted INT)","CREATE TABLE nx_support_agent_user_assignment(id BIGINT PRIMARY KEY,user_id BIGINT,agent_admin_id BIGINT,status VARCHAR(16),starts_at DATETIME,ends_at DATETIME,is_deleted INT)","CREATE TABLE nx_conversation_message(id BIGINT PRIMARY KEY,conversation_no VARCHAR(64),sender_type VARCHAR(16),is_deleted INT)"))scratch.execute(ddl);
            replayCanonicalRulesMigration(scratch,ds);var fresh=scratch.queryForMap("SELECT * FROM nx_support_rules WHERE id=1");
            assertThat(fresh.get("inheritance_mode")).isEqualTo("UNLIMITED");assertThat(fresh.get("max_inheritance_depth")).isNull();assertThat(((Number)fresh.get("version")).longValue()).isEqualTo(1L);
            assertThat(scratch.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_support_rules' AND column_name='inheritance_mode'",String.class)).isEqualTo("UNLIMITED");
            replayCanonicalRulesMigration(scratch,ds);assertThat(scratch.queryForMap("SELECT * FROM nx_support_rules WHERE id=1")).isEqualTo(fresh);evidence.put("fresh",fresh);
            for(int depth:List.of(0,2)){
                scratch.execute("ALTER TABLE nx_support_rules ALTER COLUMN inheritance_mode SET DEFAULT 'UNCONFIGURED'");
                assertThat(scratch.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_support_rules' AND column_name='inheritance_mode'",String.class)).isEqualTo("UNCONFIGURED");
                assertThat(scratch.update("UPDATE nx_support_rules SET inheritance_mode='LIMITED',max_inheritance_depth=?,version=17,reason='Owned actual legacy finite fixture' WHERE id=1",depth)).isEqualTo(1);
                var before=scratch.queryForMap("SELECT * FROM nx_support_rules WHERE id=1");replayCanonicalRulesMigration(scratch,ds);replayCanonicalRulesMigration(scratch,ds);assertThat(scratch.queryForMap("SELECT * FROM nx_support_rules WHERE id=1")).isEqualTo(before);evidence.put("limited"+depth,before);
                // The timeout migration updates only the column default; the existing finite row stays unchanged.
                assertThat(scratch.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_support_rules' AND column_name='inheritance_mode'",String.class)).isEqualTo("UNLIMITED");
            }
            assertThat(scratch.update("UPDATE nx_support_rules SET inheritance_mode='UNCONFIGURED',max_inheritance_depth=NULL,version=18 WHERE id=1")).isEqualTo(1);
            var explicit=scratch.queryForMap("SELECT * FROM nx_support_rules WHERE id=1");replayCanonicalRulesMigration(scratch,ds);replayCanonicalRulesMigration(scratch,ds);assertThat(scratch.queryForMap("SELECT * FROM nx_support_rules WHERE id=1")).isEqualTo(explicit);evidence.put("historicalUnconfiguredDDLDoesNotAutoExpand",explicit);
        }finally{if(owned){jdbc.execute("DROP DATABASE "+schema);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Long.class,schema)).isZero();}}
        evidence.put("ownedScratchRemoved",true);evidence.put("scope","Full canonical S3 stored procedure plus timeout segment SQL twice; minimal compatible empty legacy prerequisite tables; no main-schema DDL and not full application fresh-start/browser proof");
        observed("canonical-rules-migration",json.writeValueAsString(evidence));gap("BE-CFG-01-browser","Scratch proves real canonical initialization; fresh application API/page initialization still root-owned separate evidence");finish("rules-canonical-migration-runtime.json");
    }
    private void replayCanonicalRulesMigration(org.springframework.jdbc.core.JdbcTemplate scratch,javax.sql.DataSource ds) throws Exception {
        try(var connection=ds.getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.FileSystemResource("scripts/migrations/20260929_support_binding_rules_schema_20261015.sql"));}
        String source=Files.readString(Path.of("scripts/migrations/20260929_support_binding_s3.sql"));int start=source.indexOf("CREATE PROCEDURE support_binding_s3_migrate()"),end=source.indexOf("END$$",start);assertThat(start).isGreaterThan(0);assertThat(end).isGreaterThan(start);
        scratch.execute(source.substring(start,end+3));try{scratch.execute("CALL support_binding_s3_migrate()");}finally{scratch.execute("DROP PROCEDURE support_binding_s3_migrate");}
        try(var connection=ds.getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.FileSystemResource("scripts/migrations/20261009_support_timeout_segment.sql"));}
    }
    @Test void approvedManualUnconfiguredUpgradeUsesActualHttpAuditRecoveryAndSecondRun() throws Exception {
        controlledWrites=true;registrationWrites=true;String superToken=token(boss);
        saveSelection(superToken,"LIMITED",0,"SUPERVISOR");String finite0=runUpgradeScript(superToken,"limited0");assertThat(json.readTree(finite0).path("status").asText()).isEqualTo("NOT_NEEDED");assertThat(json.readTree(runUpgradeScript(superToken,"limited0"))).isEqualTo(json.readTree(finite0));
        saveSelection(superToken,"LIMITED",2,"SUPERVISOR");String finite2=runUpgradeScript(superToken,"limited2");assertThat(json.readTree(finite2).path("status").asText()).isEqualTo("NOT_NEEDED");assertThat(json.readTree(runUpgradeScript(superToken,"limited2"))).isEqualTo(json.readTree(finite2));
        // Historical sentinel setup is explicit, journaled, task singleton only; it is not normal API behavior.
        assertThat(SharedMutationJournal.sql(jdbc,run,"SupportRulesConfigurationRuntimeTest",boss,"approvedManualUnconfiguredUpgradeUsesActualHttpAuditRecoveryAndSecondRun","UPDATE nx_support_rules SET inheritance_mode='UNCONFIGURED',max_inheritance_depth=NULL,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(6) WHERE id=1",boss)).isEqualTo(1);assertProtected();
        long unconfigured=ownedRegistrationCustomer(null,false);assertPool(unconfigured,"RULE_UNCONFIGURED");
        long before=bindingMapper.rules().version();var originalAssignments=jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id");var originalPool=jdbc.queryForList("SELECT * FROM nx_support_binding_pool ORDER BY customer_id");
        String first=runUpgradeScript(superToken,"sentinel");var proof=json.readTree(first);assertThat(proof.path("status").asText()).isEqualTo("SUCCEEDED");
        var body=request(before,"UNLIMITED",null);String operation=proof.path("operationId").asText();assertThat(operation).isNotBlank();body.put("reason","Owned canonical historical rules upgrade");
        var saved=ok(http("GET",RULES,superToken,null,null));assertSaved(saved,body,boss,before+1);assertSuccessAudit(boss,operation,body);
        var claim=jdbc.queryForMap("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?","SUPPORT_RULES:"+boss,operation);assertThat(claim.get("status")).isEqualTo("SUCCEEDED");assertThat(claim.get("request_hash")).isEqualTo(fingerprint(body));
        var after=fullRule();String second=runUpgradeScript(superToken,"sentinel");assertThat(json.readTree(second).path("status").asText()).isIn("SUCCEEDED","NOT_NEEDED");assertThat(fullRule()).isEqualTo(after);assertSuccessAudit(boss,operation,body);
        assertThat(jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id")).isEqualTo(originalAssignments);assertThat(jdbc.queryForList("SELECT * FROM nx_support_binding_pool ORDER BY customer_id")).isEqualTo(originalPool);
        observed("manual-unconfigured-upgrade","Canonical PS script on actual task API leaves LIMITED0/2 unchanged; explicitly journaled historical UNCONFIGURED fixture upgrades UNLIMITED/null once with actual mandatory audit/version/command recovery, repeated same durable proof causes no second write; original assignments/pool unchanged; token stdin never commandline/log");gap("BE-CFG-03-readfailure","Missing record/field/read-error require isolated real API negative host; pure script mocks are not native evidence and current shared singleton is never removed or renamed");finish("rules-manual-upgrade-runtime.json");
    }
    private String runUpgradeScript(String token,String label) throws Exception {
        Path directory=Path.of(Objects.requireNonNull(System.getenv("CS_ENHANCE_EVIDENCE_DIR")),"rules-manual-upgrade");Files.createDirectories(directory);Path proof=directory.resolve(label+".json");
        var before=fullRule();String operation="rules-upgrade-"+label+"-"+boss;
        if(Files.exists(proof))operation=json.readTree(proof.toFile()).path("operationId").asText();
        var process=new ProcessBuilder("pwsh","-NoProfile","-NonInteractive","-Command","$ErrorActionPreference='Stop'; $secure=ConvertTo-SecureString ([Console]::In.ReadLine()) -AsPlainText -Force; & $env:CFG_SCRIPT -BaseUri $env:CFG_ORIGIN -Token $secure -OperationId $env:CFG_OPERATION -Reason 'Owned canonical historical rules upgrade' -ProofFile $env:CFG_PROOF");
        process.environment().put("CFG_SCRIPT",Path.of("scripts/manual_migrations/20261009_support_rules_unconfigured_upgrade.ps1").toAbsolutePath().toString());process.environment().put("CFG_ORIGIN",SupportRuntimeTarget.current().httpBase()+"/");process.environment().put("CFG_OPERATION",operation);process.environment().put("CFG_PROOF",proof.toString());
        // Before absence and exact request hash are durable before canonical script can submit its real PUT.
        if("UNCONFIGURED".equals(before.path("inheritance_mode").asText())&&!Files.exists(proof)){var body=request(before.path("version").asLong(),"UNLIMITED",null);body.put("reason","Owned canonical historical rules upgrade");prepareCommand(boss,body,operation);}
        Path transcript=directory.resolve(label+"-"+UUID.randomUUID()+".log");process.redirectErrorStream(true).redirectOutput(transcript.toFile());var running=process.start();
        try(var stdin=running.outputWriter(java.nio.charset.StandardCharsets.UTF_8)){stdin.write(token);stdin.newLine();}
        if(!running.waitFor(50,TimeUnit.SECONDS)){running.destroy();throw new IllegalStateException("Canonical rules migration script timeout; durable proof and command must be recovered by root before any retry");}
        assertThat(running.exitValue()).as("Canonical migration exit; inspect task transcript (token never logged)").isZero();String saved=Files.readString(proof);
        if(commandIntents.containsKey(operation)){var body=request(before.path("version").asLong(),"UNLIMITED",null);body.put("reason","Owned canonical historical rules upgrade");if("UNCONFIGURED".equals(before.path("inheritance_mode").asText()))terminalCommand(boss,body,operation,"SUCCEEDED");}
        else assertThat(fullRule()).isEqualTo(before);
        return saved;
    }
    private void controlledConfigurationWrites(String superToken) throws Exception {
        assertThat(oldRules).as("Controlled positive saves require the real existing singleton, not an invented id2").isNotNull();
        assertThat(initialRule.path("id").asText()).isEqualTo("1");controlledWrites=true;
        for(var selection:List.of(new Selection("LIMITED",0),new Selection("LIMITED",2),new Selection("UNLIMITED",null))) {
            long before=ok(http("GET",RULES,superToken,null,null)).path("version").asLong();
            var body=request(before,selection.mode(),selection.depth());String command=key();prepareCommand(boss,body,command);
            JsonNode result=ok(http("PUT",RULES,superToken,body,command));assertSaved(result,body,boss,before+1);
            terminalCommand(boss,body,command,"SUCCEEDED");assertSuccessAudit(boss,command,body);
            assertThat(ok(http("GET",RULES,superToken,null,null))).isEqualTo(result);
            var snapshot=fullRule();
            assertThat(ok(http("PUT",RULES,superToken,body,command))).isEqualTo(result);
            assertThat(fullRule()).isEqualTo(snapshot);assertSuccessAudit(boss,command,body);
            var recovered=ok(http("GET","/api/admin/content/support-workbench/commands/"+command,superToken,null,null));
            assertThat(recovered.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(recovered.path("resultType").asText()).isEqualTo("SUPPORT_RULES");
            assertThat(recovered.path("result").path("data")).isEqualTo(result);
            assertHidden(http("GET","/api/admin/content/support-workbench/commands/"+command,token(first),null,null));
            var changed=change(body,"reason","Different payload under same original command");
            assertCode(http("PUT",RULES,superToken,changed,command),409);assertThat(fullRule()).isEqualTo(snapshot);
            terminalCommand(boss,body,command,"SUCCEEDED");assertProtected();
        }
        long peer=admin("rules_peer_super","SUPER_ADMIN","MANAGER");String peerToken=token(peer);
        long version=ok(http("GET",RULES,superToken,null,null)).path("version").asLong();
        var left=request(version,"LIMITED",2);var right=request(version,"UNLIMITED",null);String leftKey=key(),rightKey=key();
        prepareCommand(boss,left,leftKey);prepareCommand(peer,right,rightKey);
        var executor=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        JsonNode a,b;
        try {
            var one=executor.submit(()->{ready.countDown();go.await();return http("PUT",RULES,superToken,left,leftKey);});
            var two=executor.submit(()->{ready.countDown();go.await();return http("PUT",RULES,peerToken,right,rightKey);});
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();a=one.get(30,TimeUnit.SECONDS);b=two.get(30,TimeUnit.SECONDS);
        }finally{go.countDown();executor.shutdownNow();}
        assertThat(List.of(a.path("code").asInt(),b.path("code").asInt())).containsExactlyInAnyOrder(0,409);
        boolean leftWon=a.path("code").asInt()==0;
        long winner=leftWon?boss:peer,loser=leftWon?peer:boss;String winnerToken=leftWon?superToken:peerToken,loserToken=leftWon?peerToken:superToken;
        String winnerKey=leftWon?leftKey:rightKey,loserKey=leftWon?rightKey:leftKey;var winnerBody=leftWon?left:right;var loserBody=leftWon?right:left;
        JsonNode winnerResult=ok(leftWon?a:b);assertSaved(winnerResult,winnerBody,winner,version+1);
        terminalCommand(winner,winnerBody,winnerKey,"SUCCEEDED");terminalCommand(loser,loserBody,loserKey,"FAILED");
        assertSuccessAudit(winner,winnerKey,winnerBody);assertThat(audits(loser,loserKey)).isEmpty();
        var after=fullRule();assertThat(ok(http("GET",RULES,superToken,null,null))).isEqualTo(winnerResult);
        assertThat(ok(http("PUT",RULES,winnerToken,winnerBody,winnerKey))).isEqualTo(winnerResult);assertThat(fullRule()).isEqualTo(after);
        assertCode(http("PUT",RULES,loserToken,loserBody,loserKey),409);assertThat(fullRule()).isEqualTo(after);
        terminalCommand(loser,loserBody,loserKey,"FAILED");assertThat(audits(loser,loserKey)).isEmpty();
        assertThat(ok(http("GET","/api/admin/content/support-workbench/commands/"+loserKey,loserToken,null,null)).path("status").asText()).isEqualTo("FAILED");
        assertSuccessAudit(winner,winnerKey,winnerBody);assertProtected();
        observed("controlled-rules-write","Real HTTP saves LIMITED0, LIMITED2, UNLIMITED/null; fresh SQL/API exact version+1 and mandatory audit; same-key replay/recovery no second mutation; two actual SUPER concurrent same-version writes yield exactly one success and one409, loser retries stay single FAILED claim and cannot overwrite winner; original bindings unchanged");
    }
    private record Selection(String mode,Integer depth){}
    private Path journalDirectory(){return Path.of(Objects.requireNonNull(System.getenv("CS_ENHANCE_EVIDENCE_DIR")),"shared-mutations");}
    private JsonNode fullRule(){
        var rows=jdbc.query("SELECT * FROM nx_support_rules WHERE id=1",(rs,n)->{var row=new LinkedHashMap<String,String>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)row.put(rs.getMetaData().getColumnName(i),rs.getString(i));return row;});
        assertThat(rows.size()).isLessThanOrEqualTo(1);return rows.isEmpty()?json.nullNode():json.valueToTree(rows.get(0));
    }
    private List<JsonNode> committedRuleTrace() {
        try {
            var committed=new ArrayList<JsonNode>();var intents=new LinkedHashMap<String,JsonNode>();var outcomes=new LinkedHashMap<String,JsonNode>();
            Path dir=journalDirectory();if(Files.isDirectory(dir))try(var files=Files.list(dir)){
                for(Path file:files.filter(path->path.getFileName().toString().endsWith(".json")&&!initialJournalFiles.contains(path.getFileName().toString())).toList()){
                    JsonNode event=json.readTree(file.toFile());if(!event.path("table").asText().equals("nx_support_rules"))continue;
                    assertThat(event.path("rowId").asText()).isEqualTo("1");assertThat(event.path("runId").asText()).isEqualTo(System.getenv("WORKFLOW_RUN_ID"));
                    assertThat(event.path("snapshotHash").asText()).isEqualTo(System.getenv("WORKFLOW_SNAPSHOT_HASH"));
                    assertThat(event.path("contextSha256").asText()).isEqualTo(System.getenv("CS_ENHANCE_ACTOR_CONTEXT_SHA256"));
                    long actor=Long.parseLong(event.path("actorProof").path("actorId").asText());assertThat(admins).contains(actor);
                    var creator=fixtureActors().creationReference(actor);
                    assertThat(event.path("actorProof").path("path").asText()).isEqualTo(creator.get("path"));
                    assertThat(event.path("actorProof").path("sha256").asText()).isEqualTo(creator.get("sha256"));
                    String operation=event.path("operationId").asText();assertThat(operation).isNotBlank();
                    if(event.path("event").asText().equals("SHARED_MUTATION_INTENT")){assertThat(intents.put(operation,event)).isNull();continue;}
                    assertThat(outcomes.put(operation,event)).isNull();
                    if(event.path("event").asText().equals("SHARED_MUTATION_COMMITTED")){
                        assertThat(event.path("transactionOutcome").asText()).isEqualTo("COMMITTED");assertThat(event.path("commitEvidence").asText()).isEqualTo("SPRING_AFTER_COMMIT");
                        assertThat(event.path("successfulExactResponse").asBoolean()).isTrue();assertThat(event.path("commitReturned").asBoolean()).isTrue();committed.add(event);
                    } else {assertThat(event.path("event").asText()).isEqualTo("SHARED_MUTATION_ROLLED_BACK");assertThat(event.path("transactionOutcome").asText()).isEqualTo("ROLLED_BACK");assertThat(event.path("commitReturned").asBoolean()).isFalse();}
                }
            }
            assertThat(outcomes.keySet()).isEqualTo(intents.keySet());
            for(var entry:outcomes.entrySet())for(String field:List.of("operationId","identity","contextSha256","candidate","windowId","resourceIdentity","table","rowId","before","source","actorProof","dbStarted"))assertThat(entry.getValue().path(field)).isEqualTo(intents.get(entry.getKey()).path(field));
            SupportAnalyticsSharedState.requireChain(initialRule,fullRule(),committed);return committed;
        }catch(Exception error){throw new IllegalStateException("Rule mutation chain must be complete and owned",error);}
    }
    @Override void assertProtected(){
        if(!controlledWrites){super.assertProtected();return;}
        for(var row:initialBindings)assertThat(jdbc.queryForMap("SELECT * FROM nx_support_agent_user_assignment WHERE id=?",row.get("id"))).isEqualTo(row);
        if(registrationWrites)assertOriginalBindingsAndExactOwnedExtras();else assertThat(jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id")).isEqualTo(initialBindings);
        committedRuleTrace();if(restored)assertBusinessRestored();
    }
    private void assertBusinessRestored(){
        JsonNode current=fullRule();initialRule.fields().forEachRemaining(field->{if(!Set.of("version","updated_by","updated_at","reason").contains(field.getKey()))assertThat(current.path(field.getKey())).as("Restored business column %s",field.getKey()).isEqualTo(field.getValue());});
    }
    private String fingerprint(Map<String,Object> body) throws Exception {
        var request=new ffdd.opsconsole.content.dto.SupportRulesRequest((Integer)body.get("dormantDays"),(Integer)body.get("maintenanceDays"),(Integer)body.get("activityWindowDays"),(String)body.get("inheritanceMode"),(Integer)body.get("maxInheritanceDepth"),((Number)body.get("expectedVersion")).longValue(),(String)body.get("reason"),(String)body.get("unboundAssignmentMode"));
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(request.commandFingerprint().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private void prepareCommand(long actor,Map<String,Object> body,String key) throws Exception {
        String scope="SUPPORT_RULES:"+actor;assertThat(jdbc.queryForList("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",scope,key)).isEmpty();
        var intent=new LinkedHashMap<String,Object>();intent.put("scope",scope);intent.put("commandKey",key);intent.put("requestHash",fingerprint(body));intent.put("request",body);intent.put("beforeAbsent",true);intent.put("actorCreator",fixtureActors().creationReference(actor));
        Path dir=Path.of(Objects.requireNonNull(System.getenv("CS_ENHANCE_EVIDENCE_DIR")),"rules-command-intents");Files.createDirectories(dir);
        Path file=dir.resolve(key+".json");byte[] bytes=json.writeValueAsBytes(intent);
        try(var channel=java.nio.channels.FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes);intent.put("intentPath",file.toString());commandIntents.put(key,intent);
    }
    private void terminalCommand(long actor,Map<String,Object> body,String key,String status) throws Exception {
        var rows=jdbc.queryForList("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?","SUPPORT_RULES:"+actor,key);assertThat(rows).hasSize(1);var row=rows.get(0);
        assertThat(row.get("request_hash")).isEqualTo(fingerprint(body));assertThat(row.get("status")).isEqualTo(status);assertThat(((Number)row.get("is_deleted")).intValue()).isZero();
        assertThat(commandIntents).containsKey(key);var old=commandRows.get(key);if(old!=null)assertThat(row.get("id")).isEqualTo(old.get("id"));commandRows.put(key,row);
    }
    private List<Map<String,Object>> audits(long actor,String key){return jdbc.queryForList("SELECT * FROM nx_audit_log WHERE action='SUPPORT_RULES_CHANGED' AND actor_id=? AND resource_id='1' AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.commandKey'))=? ORDER BY id",actor,key);}
    private void assertSuccessAudit(long actor,String key,Map<String,Object> body) throws Exception {
        var rows=audits(actor,key);assertThat(rows).hasSize(1);var row=rows.get(0);assertThat(row.get("result")).isEqualTo("SUCCESS");assertThat(row.get("risk_level")).isEqualTo("HIGH");
        var detail=json.readTree(String.valueOf(row.get("detail_json")));assertThat(detail.path("commandKey").asText()).isEqualTo(key);assertThat(detail.path("reason").asText()).isEqualTo(body.get("reason"));
        assertThat(detail.path("request")).isEqualTo(json.valueToTree(body));assertThat(detail.path("before").path("version").asLong()).isEqualTo(((Number)body.get("expectedVersion")).longValue());
        assertThat(detail.path("after").path("version").asLong()).isEqualTo(((Number)body.get("expectedVersion")).longValue()+1);assertThat(detail.path("after").path("inheritanceMode").asText()).isEqualTo(body.get("inheritanceMode"));
    }
    private void assertSaved(JsonNode result,Map<String,Object> body,long actor,long version){
        assertThat(result.path("version").asLong()).isEqualTo(version);assertThat(result.path("inheritanceMode").asText()).isEqualTo(body.get("inheritanceMode"));
        if(body.get("maxInheritanceDepth")==null)assertThat(result.path("maxInheritanceDepth").isNull()).isTrue();else assertThat(result.path("maxInheritanceDepth").asInt()).isEqualTo((Integer)body.get("maxInheritanceDepth"));
        for(String field:List.of("dormantDays","maintenanceDays","activityWindowDays"))assertThat(result.path(field).asInt()).isEqualTo((Integer)body.get(field));
        assertThat(result.path("unboundAssignmentMode").asText()).isEqualTo(body.get("unboundAssignmentMode"));
        var row=fullRule();assertThat(row.path("version").asLong()).isEqualTo(version);assertThat(row.path("updated_by").asLong()).isEqualTo(actor);assertThat(row.path("reason").asText()).isEqualTo(body.get("reason"));
        assertThat(row.path("inheritance_mode").asText()).isEqualTo(body.get("inheritanceMode"));if(body.get("maxInheritanceDepth")==null)assertThat(row.path("max_inheritance_depth").isNull()).isTrue();else assertThat(row.path("max_inheritance_depth").asInt()).isEqualTo((Integer)body.get("maxInheritanceDepth"));
    }
    @AfterEach void restoreControlledRuleBusinessValues() throws Exception {
        if(!controlledWrites)return;assertProtected();
        assertThat(SharedMutationJournal.cleanupSql(jdbc,run,"SupportRulesConfigurationRuntimeTest",boss,"restoreControlledRuleBusinessValues",
            "UPDATE nx_support_rules SET dormant_days=?,maintenance_days=?,activity_window_days=?,inheritance_mode=?,max_inheritance_depth=?,unbound_assignment_mode=?,mode_effective_at=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(6) WHERE id=1",
            oldRules.dormantDays(),oldRules.maintenanceDays(),oldRules.activityWindowDays(),oldRules.inheritanceMode(),oldRules.maxInheritanceDepth(),oldRules.unboundAssignmentMode(),oldRules.modeEffectiveAt(),boss)).isEqualTo(1);
        restored=true;assertProtected();
        for(var entry:commandRows.entrySet()){
            var row=entry.getValue();var actual=jdbc.queryForMap("SELECT * FROM nx_admin_idempotency_record WHERE id=?",row.get("id"));assertThat(actual).isEqualTo(row);
            assertThat(actual.get("status")).isIn("SUCCEEDED","FAILED");assertThat(actual.get("request_hash")).isEqualTo(commandIntents.get(entry.getKey()).get("requestHash"));
        }
        var proof=new LinkedHashMap<String,Object>();proof.put("workflowRunId",System.getenv("WORKFLOW_RUN_ID"));proof.put("snapshotHash",System.getenv("WORKFLOW_SNAPSHOT_HASH"));proof.put("originalRule",initialRule);proof.put("restoredRule",fullRule());
        proof.put("rulesBusinessValuesEqual",true);proof.put("byteExact",false);proof.put("sharedMutationTrace",committedRuleTrace());proof.put("retainedCommandRows",commandRows);proof.put("commandCreationIntents",commandIntents);
        proof.put("claimBoundary","claimRetained and markFailed are REQUIRES_NEW; exact newly requested scope/key/hash/id receipts retained after SUCCEEDED/FAILED. Never actor-only delete; not whole-DB rollback.");
        Files.writeString(Path.of(Objects.requireNonNull(System.getenv("CS_ENHANCE_EVIDENCE_DIR")),ownedTestcase().equals("persistedRulesStrictInputsAndControlledCasReplayRestoreOriginalBusinessValues")?"rules-controlled-write-restoration.json":"rules-controlled-write-restoration-"+ownedTestcase()+".json"),json.writeValueAsString(proof),StandardOpenOption.CREATE_NEW);
        observed("rules-restoration","Original singleton business columns including mode_effective_at restored; legitimate version/updated_by/updated_at/reason changes remain backed by complete source/actor transaction journal; exact independent SUCCEEDED/FAILED claim rows retained with before-absence request receipts, no actor-only deletion");
    }
    private Map<String,Object> request(long version,String mode,Integer depth) {
        var body=new LinkedHashMap<String,Object>();body.put("dormantDays",30);body.put("maintenanceDays",15);body.put("activityWindowDays",7);
        body.put("inheritanceMode",mode);body.put("maxInheritanceDepth",depth);body.put("unboundAssignmentMode","SUPERVISOR");
        body.put("expectedVersion",version);body.put("reason","Owned native strict rules boundary");return body;
    }
    private Map<String,Object> change(Map<String,Object> source,String key,Object value) {var copy=new LinkedHashMap<>(source);copy.put(key,value);return copy;}
}
