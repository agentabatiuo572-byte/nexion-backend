package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.Policy;
import ffdd.opsconsole.device.application.PhoneReplacementPolicyPublicationService.Result;
import ffdd.opsconsole.device.mapper.PhoneReplacementPolicyOperationMapper;
import ffdd.opsconsole.onboarding.application.PhoneReplacementService;
import ffdd.opsconsole.onboarding.facade.PhoneReplacementFacade;
import ffdd.opsconsole.onboarding.mapper.PhoneReplacementMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.platform.application.PlatformConfigFacadeAdapter;
import ffdd.opsconsole.platform.infrastructure.MybatisPlatformConfigRepository;
import ffdd.opsconsole.platform.mapper.PlatformConfigItemMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import ffdd.opsconsole.shared.exception.PhoneReplacementException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.outbox.OutboxProperties;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.security.PhoneReplacementAdminAccess;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** Real policy/config/permissions/A2/A4 persistence, with owned databases and injected post-write failures. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD",matches=".+")
class PhoneReplacementPolicyPublicationMySqlTest {
    static final String PREFIX=PhoneReplacementPolicyPublicationService.PREFIX;
    static final String EVENT="admin.phone_replacement_policy_updated";
    static final String REASON="Verified phone policy publication";
    static final long DAY=86_400_000L;
    final AtomicLong now=new AtomicLong(1_800_000_000_000L);
    final AtomicReference<String> failAfterWrite=new AtomicReference<>();
    Connection admin;
    String schema;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    PhoneReplacementPolicyPublicationService service;
    boolean passed;

    @BeforeEach void setup() throws Exception {
        String url=System.getenv("NEXION_TEST_DB_SERVER_URL");
        if(url==null || !url.matches("jdbc:mysql://(127[.]0[.]0[.]1|localhost):[0-9]+/([?].*)?"))
            throw new IllegalArgumentException("Dedicated loopback MySQL server URL without catalog required");
        String username=System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME","root"), password=System.getenv("NEXION_TEST_DB_PASSWORD");
        admin=DriverManager.getConnection(url,username,password);
        schema="nx_phone_policy_test_"+UUID.randomUUID().toString().replace("-","");
        admin.createStatement().execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");
        System.out.println("PHONE_POLICY_FIXTURE_CREATE="+schema);
        int query=url.indexOf('?');
        var ds=new DriverManagerDataSource(url.substring(0,query<0?url.length():query)+schema+(query<0?"":url.substring(query)),username,password);
        jdbc=new JdbcTemplate(ds);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setIsolationLevelName("ISOLATION_REPEATABLE_READ");
        String baseline=Files.readString(Path.of("scripts/schema.sql"));
        for(String table:List.of("nx_config_item","nx_event_schema_registry","nx_event_schema_property","nx_audit_log","nx_event_outbox",
                "nx_admin","nx_admin_role","nx_admin_permission","nx_admin_role_relation","nx_admin_role_permission","nx_compute_task")) createTable(baseline,table);
        createTable(Files.readString(Path.of("scripts/migrations/20260810_ab_pending_closure.sql")),"nx_admin_event_lifecycle");
        createTable(Files.readString(Path.of("scripts/migrations/20260928_phone_calibration_policy.sql")),"nx_phone_binding");
        seedAdmins();
        migrate("20261019_phone_replacement_domain.sql");
        migrate("20261019_phone_replacement_ops.sql");
        jdbc.update("INSERT INTO nx_phone_binding(user_id,source_environment,run_id,installation_id,execution_installation_id,user_device_id,hardware_key,changed_at,version) "
                +"VALUES(701,'PRODUCTION','','bound-A','bound-A',702,'hw',FROM_UNIXTIME(1700000000),9)");
        jdbc.update("INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_type,client_name,status) "
                +"VALUES('POLICY-SENTINEL',701,702,'PHONE','policy-fixture','RUNNING')");
        var configuration=new MybatisConfiguration();
        configuration.setEnvironment(new Environment("phone-policy",new SpringManagedTransactionFactory(),ds));
        configuration.setMapUnderscoreToCamelCase(true);
        for(Class<?> mapper:List.of(PhoneReplacementMapper.class,PhoneReplacementPolicyOperationMapper.class,PlatformConfigItemMapper.class,
                AuditLogMapper.class,EventOutboxMapper.class,AdminMapper.class,AdminRolePermissionMapper.class)) configuration.addMapper(mapper);
        var sql=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var admins=sql.getMapper(AdminMapper.class);
        var access=new PhoneReplacementAdminAccess(new AdminPermissionCache(mock(StringRedisTemplate.class),sql.getMapper(AdminRolePermissionMapper.class)),admins);
        var a2=mock(A2RuntimePolicy.class);when(a2.retentionMonths()).thenReturn(13);when(a2.schemaVersion()).thenReturn("v3");
        var json=new ObjectMapper().findAndRegisterModules();
        var audit=spy(new AuditLogService(sql.getMapper(AuditLogMapper.class),new AuditLogSanitizer(json),
                new ApplicationNameProperties(),new AuditProperties(),admins,a2));
        doAnswer(call->{Object result=call.callRealMethod();if("AUDIT".equals(failAfterWrite.get()))throw new IllegalStateException("injected-after-audit");return result;})
                .when(audit).recordRequired(any());
        var outbox=spy(new EventOutboxService(sql.getMapper(EventOutboxMapper.class),json,new OutboxProperties(),mock(A4RuntimePolicyService.class)));
        doAnswer(call->{Object result=call.callRealMethod();if("OUTBOX".equals(failAfterWrite.get()))throw new IllegalStateException("injected-after-outbox");return result;})
                .when(outbox).publish(anyString(),anyString(),anyString(),anyMap());
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return Instant.ofEpochMilli(now.get());}};
        var domain=new PhoneReplacementFacade(new PhoneReplacementService(sql.getMapper(PhoneReplacementMapper.class),clock,audit,outbox));
        var config=new PlatformConfigFacadeAdapter(new MybatisPlatformConfigRepository(sql.getMapper(PlatformConfigItemMapper.class)));
        service=new PhoneReplacementPolicyPublicationService(domain,config,sql.getMapper(PhoneReplacementPolicyOperationMapper.class),access,audit,outbox,clock);
    }

    @Test void migrationsReplayPreservesPolicyAndMapsFinePermissionsOnlyToSuperAdmin() throws Exception {
        jdbc.update("UPDATE nx_config_item SET config_value='0' WHERE config_key=?",PREFIX+"minReplacementIntervalDays");
        migrate("20261019_phone_replacement_domain.sql");migrate("20261019_phone_replacement_ops.sql");migrate("20261019_phone_replacement_ops.sql");
        assertThat(values().get(PREFIX+"minReplacementIntervalDays")).isEqualTo("0");
        assertThat(count("nx_admin_permission","permission_code LIKE 'user_c5_phone_replacement_exception_%'")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT r.role_code FROM nx_admin_role_permission rp JOIN nx_admin_role r ON r.id=rp.role_id "
                +"JOIN nx_admin_permission p ON p.id=rp.permission_id WHERE p.permission_code LIKE 'user_c5_phone_replacement_exception_%'",String.class))
                .containsExactlyInAnyOrder("SUPER_ADMIN","SUPER_ADMIN");
        assertThat(count("nx_event_schema_registry","event_name='"+EVENT+"'")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT property_name FROM nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id WHERE s.event_name=?",
                String.class,EVENT)).containsExactlyInAnyOrder("operation_id","operator_id","old_policy","new_policy","reason");
        assertThat(count("nx_phone_policy_operation","1=1")).isZero();assertBusinessUnchanged();passed=true;
    }

    @Test void fullPublicationAndSingleFieldMergeCommitWithRealAuditAndRegisteredOutbox() {
        var full=group("on","0","45","3");
        var first=publish(901,full,1,"full-success",false);
        assertThat(first.getCode()).isZero();assertThat(values()).containsAllEntriesOf(full);
        var second=publish(902,Map.of(PREFIX+"allowReplacement","off"),2,"flag-success",true);
        assertThat(second.getCode()).isZero();
        assertThat(service.currentPolicy()).isEqualTo(new Policy(true,false,0L,45,3,3L));
        assertThat(count("nx_audit_log","action='ADMIN.PHONE_REPLACEMENT_POLICY_UPDATED'")).isEqualTo(2);
        assertThat(count("nx_event_outbox","event_name='"+EVENT+"' AND schema_registered=1")).isEqualTo(2);
        assertThat(count("nx_phone_policy_operation","status='SUCCEEDED' AND audit_ref IS NOT NULL")).isEqualTo(2);
        assertBusinessUnchanged();passed=true;
    }

    @Test void fullAndSinglePublishRacingOnSameVersionHaveExactlyOneWinner() throws Exception {
        Map<String,String> full=group("on","0","45","3"),single=Map.of(PREFIX+"maxReplacementsInWindow","7");
        var results=race(()->publish(901,full,1,"race-full",false),()->publish(901,single,1,"race-single",true));
        assertOneVersionWinner(results);
        Map<String,String> actual=values();
        if(actual.get(PREFIX+"maxReplacementsInWindow").equals("3"))assertThat(actual).containsAllEntriesOf(full);
        else assertThat(actual).containsAllEntriesOf(group("off","1","30","7"));
        assertBusinessUnchanged();passed=true;
    }

    @Test void twoSingleFieldPublicationsCannotBothUseTheSameVersion() throws Exception {
        var results=race(()->publish(901,Map.of(PREFIX+"replacementWindowDays","60"),1,"single-window",true),
                ()->publish(901,Map.of(PREFIX+"maxReplacementsInWindow","8"),1,"single-count",true));
        assertOneVersionWinner(results);
        var actual=service.currentPolicy();
        assertThat(actual.replacementWindowDays()==60 ^ actual.maxReplacementsInWindow()==8).isTrue();
        assertThat(actual.minReplacementIntervalDays()).isEqualTo(1L);assertThat(actual.allowReplacement()).isFalse();
        assertBusinessUnchanged();passed=true;
    }

    @Test void invalidGroupsAndUnsafeNumericValuesNeverPartiallyWrite() {
        Map<String,String> before=values();
        List<Map<String,String>> rejected=List.of(group("on","9999999999999999","30","2"),group("on","1","2147483648","2"),
                group("on","1","30","0"),group("on","1","30","101"),Map.of(PREFIX+"allowReplacement","on"));
        int index=0;
        for(var values:rejected){String key="invalid-"+index++;
            assertThatThrownBy(()->publish(901,values,1,key,false)).isInstanceOfSatisfying(PhoneReplacementException.class,
                    error->{assertThat(error.details().conflictType()).isEqualTo("INVALID_POLICY");assertThat(error.details().fieldErrors()).isNotEmpty();});
            assertThat(values()).isEqualTo(before);
        }
        assertThat(count("nx_phone_policy_operation","1=1")).isZero();assertThat(count("nx_audit_log","1=1")).isZero();
        assertThat(count("nx_event_outbox","1=1")).isZero();assertBusinessUnchanged();passed=true;
    }

    @Test void originalReceiptSurvivesMoreThan24HoursAndNewerPolicyWithoutRepublishing() {
        Map<String,String> firstInput=group("on","0","45","3");
        var original=publish(901,firstInput,1,"durable-original",false).getData().receipt();
        publish(901,Map.of(PREFIX+"maxReplacementsInWindow","9"),2,"later-version",true);
        now.addAndGet(3*DAY);
        var replay=publish(901,firstInput,1,"durable-original",false).getData().receipt();
        assertThat(replay.operationId()).isEqualTo(original.operationId());assertThat(replay.originalResult()).isEqualTo(original.originalResult());
        assertThat(((Policy)replay.currentResource()).policyVersion()).isEqualTo(3L);
        assertThat(((Policy)replay.currentResource()).maxReplacementsInWindow()).isEqualTo(9);
        assertThat(asAdmin(901,()->service.operation(original.operationId())).originalResult()).isEqualTo(original.originalResult());
        assertThat(asAdmin(901,()->service.operationByKey(PhoneReplacementPolicyPublicationService.BATCH,"durable-original")).operationId()).isEqualTo(original.operationId());
        assertThatThrownBy(()->publish(901,group("off","0","45","3"),1,"durable-original",false)).hasMessage("IDEMPOTENCY_MISMATCH");
        assertThat(count("nx_phone_policy_operation","1=1")).isEqualTo(2);assertThat(count("nx_event_outbox","1=1")).isEqualTo(2);
        assertBusinessUnchanged();passed=true;
    }

    @Test void postWriteAuditAndOutboxFailuresRollBackConfigVersionReceiptAndBothSinks() {
        for(String phase:List.of("AUDIT","OUTBOX")){
            long version=service.currentPolicy().policyVersion();var before=values();
            int operations=count("nx_phone_policy_operation","1=1"),audits=count("nx_audit_log","1=1"),events=count("nx_event_outbox","1=1");
            failAfterWrite.set(phase);
            assertThatThrownBy(()->publish(901,group("on","0","90","4"),version,"failure-"+phase,false))
                    .hasMessage("injected-after-"+phase.toLowerCase(Locale.ROOT));
            assertThat(values()).isEqualTo(before);assertThat(count("nx_phone_policy_operation","1=1")).isEqualTo(operations);
            assertThat(count("nx_audit_log","1=1")).isEqualTo(audits);assertThat(count("nx_event_outbox","1=1")).isEqualTo(events);
            failAfterWrite.set(null);
            assertThat(publish(901,group("on","0","90","4"),version,"failure-"+phase,false).getCode()).isZero();
        }
        assertBusinessUnchanged();passed=true;
    }

    @Test void currentPermissionsAndA2ConfirmationGuardNewWritesAndDurableRecovery() {
        var flag=Map.of(PREFIX+"allowReplacement","on");
        var unconfirmed=asAdmin(901,()->tx.execute(s->service.publish(flag,1L,REASON,"no-confirm",true)));
        assertThat(unconfirmed.getMessage()).isEqualTo("A2_CONFIRMATION_REQUIRED");
        assertThatThrownBy(()->publish(903,group("on","0","30","2"),1,"risk-cannot-publish",false)).hasMessage("PERMISSION_DENIED");
        jdbc.update("UPDATE nx_admin_role_permission rp JOIN nx_admin_permission p ON p.id=rp.permission_id SET rp.is_deleted=1 WHERE rp.role_id=4 AND p.permission_code='platform_a2_operation_approve'");
        assertThatThrownBy(()->publish(902,group("on","0","30","2"),1,"flag-cannot-publish-batch",false)).hasMessage("PERMISSION_DENIED");
        jdbc.update("UPDATE nx_admin_role_permission SET is_deleted=0 WHERE role_id=4");
        var original=publish(902,flag,1,"flag-original",true).getData().receipt();
        jdbc.update("UPDATE nx_admin_role_permission SET is_deleted=1 WHERE role_id=4");
        assertThatThrownBy(()->publish(902,flag,1,"flag-original",true)).hasMessage("PERMISSION_DENIED");
        assertThatThrownBy(()->asAdmin(902,()->service.operation(original.operationId()))).hasMessage("PERMISSION_DENIED");
        assertThat(count("nx_phone_policy_operation","1=1")).isEqualTo(1);assertBusinessUnchanged();passed=true;
    }

    private ApiResult<Result> publish(long actor,Map<String,String> values,long version,String key,boolean single){
        return asAdmin(actor,()->{A2ReplayContext.enterReplay("A2-"+key);try{return tx.execute(s->service.publish(values,version,REASON,key,single));}
            finally{A2ReplayContext.exitReplay();}});
    }
    private <T>T asAdmin(long actor,Supplier<T> work){
        var auth=new UsernamePasswordAuthenticationToken(Long.toString(actor),null,List.of());auth.setDetails(Map.of("subjectType","ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try{return work.get();}finally{SecurityContextHolder.clearContext();}
    }
    private Map<String,String> group(String flag,String interval,String window,String count){return Map.of(PREFIX+"allowReplacement",flag,
            PREFIX+"minReplacementIntervalDays",interval,PREFIX+"replacementWindowDays",window,PREFIX+"maxReplacementsInWindow",count);}
    private Map<String,String> values(){var result=new TreeMap<String,String>();jdbc.query("SELECT config_key,config_value FROM nx_config_item WHERE config_key LIKE 'E.compute.phoneBinding.%'",
            rs->{result.put(rs.getString(1),rs.getString(2));});return result;}
    private int count(String table,String where){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+where,Integer.class);}
    private void assertBusinessUnchanged(){
        assertThat(jdbc.queryForObject("SELECT installation_id FROM nx_phone_binding WHERE user_id=701",String.class)).isEqualTo("bound-A");
        assertThat(jdbc.queryForObject("SELECT version FROM nx_phone_binding WHERE user_id=701",Long.class)).isEqualTo(9L);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE task_no='POLICY-SENTINEL'",String.class)).isEqualTo("RUNNING");
        assertThat(count("nx_phone_replacement_event","1=1")).isZero();assertThat(count("nx_phone_replacement_grant","1=1")).isZero();
    }
    private void assertOneVersionWinner(List<Object> results){
        assertThat(results.stream().filter(ApiResult.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(Throwable.class::isInstance)).singleElement().satisfies(value->assertThat((Throwable)value).hasMessage("POLICY_VERSION_CHANGED"));
        assertThat(service.currentPolicy().policyVersion()).isEqualTo(2L);
        assertThat(count("nx_phone_policy_operation","1=1")).isEqualTo(1);assertThat(count("nx_audit_log","1=1")).isEqualTo(1);
        assertThat(count("nx_event_outbox","1=1")).isEqualTo(1);
    }
    private List<Object> race(Supplier<?> left,Supplier<?> right)throws Exception{
        var pool=Executors.newFixedThreadPool(2);var ready=new CyclicBarrier(2);
        try{List<Future<Object>> futures=new ArrayList<>();for(var job:List.of(left,right))futures.add(pool.submit(()->{
            ready.await(10,TimeUnit.SECONDS);try{return job.get();}catch(RuntimeException failure){return failure;}}));
            return List.of(futures.get(0).get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
    private void seedAdmins(){
        jdbc.update("INSERT INTO nx_admin_role(id,role_code,role_name) VALUES(1,'SUPER_ADMIN','Super'),(2,'RISK','Risk'),(3,'E6_WRITER','Writer'),(4,'E6_FLAG','Flag')");
        jdbc.update("INSERT INTO nx_admin(id,username,password_hash,status) VALUES(900,'policy-super','TEST',1),(901,'policy-writer','TEST',1),(902,'policy-flag','TEST',1),(903,'policy-risk','TEST',1)");
        jdbc.update("INSERT INTO nx_admin_role_relation(admin_id,role_id) VALUES(900,1),(901,3),(902,4),(903,2)");
        jdbc.update("INSERT INTO nx_admin_permission(permission_code,permission_name,resource_type,status) VALUES('device_e6_write','Write','API',1),('device_e6_flag_toggle','Flag','API',1),('device_e6_read','Read','API',1),('platform_a2_operation_approve','Approve','API',1)");
        jdbc.update("INSERT INTO nx_admin_role_permission(role_id,permission_id) SELECT 3,id FROM nx_admin_permission WHERE permission_code IN ('device_e6_write','device_e6_read','platform_a2_operation_approve')");
        jdbc.update("INSERT INTO nx_admin_role_permission(role_id,permission_id) SELECT 4,id FROM nx_admin_permission WHERE permission_code IN ('device_e6_flag_toggle','device_e6_read','platform_a2_operation_approve')");
    }
    private void createTable(String source,String table){int start=source.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");
        assertThat(start).as(table+" canonical DDL").isGreaterThanOrEqualTo(0);jdbc.execute(source.substring(start,source.indexOf(';',start)+1));}
    private void migrate(String name)throws Exception{String sql=Files.readString(Path.of("scripts/migrations",name));
        for(String part:sql.replaceAll("(?m)^--.*$","").split(";"))if(!part.isBlank())jdbc.execute(part);}
    @AfterEach void cleanup()throws Exception{
        A2ReplayContext.exitReplay();SecurityContextHolder.clearContext();if(admin==null)return;
        try{if(schema!=null&&passed){admin.createStatement().execute("DROP DATABASE `"+schema+"`");System.out.println("PHONE_POLICY_FIXTURE_DROP="+schema);}
            else if(schema!=null)System.out.println("PHONE_POLICY_FIXTURE_RETAINED="+schema);
        }finally{admin.close();}
    }
}
