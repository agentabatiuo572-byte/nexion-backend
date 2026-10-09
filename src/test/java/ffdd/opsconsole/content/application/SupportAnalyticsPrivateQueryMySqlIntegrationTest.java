package ffdd.opsconsole.content.application;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.mapper.*;
import ffdd.opsconsole.device.application.SupportDeviceReadService;
import ffdd.opsconsole.device.mapper.SupportDeviceReadMapper;
import ffdd.opsconsole.finance.application.SupportPaymentFactService;
import ffdd.opsconsole.finance.application.SupportPaymentSourceService;
import ffdd.opsconsole.finance.mapper.SupportPaymentFactMapper;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper;
import ffdd.opsconsole.shared.config.MybatisPlusConfig;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.application.SupportInvitationReadService;
import ffdd.opsconsole.team.mapper.SupportInvitationReadMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertAll;

/** Candidate only: fixture SecurityContext, production readers/SQL, no HTTP/filter-chain claim. */
@EnabledIfEnvironmentVariable(named="SUPPORT_CAPTURE_MYSQL_ENABLED",matches="true")
class SupportAnalyticsPrivateQueryMySqlIntegrationTest {
    private static final String SERVER_UUID="3556ddae-c1a1-11f1-8853-a40c6626953d";
    private static final List<String> CAPS=List.of("service_m1_read","service_m3_read","platform_a1_read");
    private static final List<String> TABLES=List.of("nx_user","nx_admin","nx_admin_role","nx_admin_role_relation",
        "nx_admin_permission","nx_admin_role_permission","nx_support_agent_profile","nx_support_account_qualification_history",
        "nx_support_group","nx_support_group_owner_history","nx_support_group_member_history",
        "nx_support_customer_route_history","nx_support_agent_user_assignment","nx_support_activity_coverage",
        "nx_support_activity_event","nx_support_activity_state","nx_support_rules","nx_support_maintenance_preference",
        "nx_support_maintenance_execution","nx_support_human_message","nx_support_reply_cursor","nx_conversation",
        "nx_conversation_message","nx_user_wallet","nx_wallet_ledger","nx_payment_record","nx_order","nx_wallet_bill",
        "nx_deposit_order","nx_cregis_deposit_event","nx_topup_card_settlement","nx_vietqr_intent",
        "nx_vietqr_reconciliation","nx_hdpay_payin_order","nx_user_device","nx_trial_claim",
        "nx_support_payment_attribution","nx_support_payment_history_birth","nx_user_device_runtime");
    private final String marker="pq-native-"+UUID.randomUUID().toString().replace("-","");
    private final Map<String,List<Long>> owned=new LinkedHashMap<>();
    private final Map<String,Long> permissionIds=new LinkedHashMap<>();
    private final PhysicalSqlProbe probe=new PhysicalSqlProbe();
    private HikariDataSource dataSource,outsideDataSource;
    private JdbcTemplate jdbc,outside;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transaction;
    private SupportOwnershipService ownership;
    private SupportAnalyticsPrivateQueryService query;
    private Map<String,Long> beforeCounts;
    private List<Map<String,Object>> beforeSharedGrants,beforeRules,beforeActivity;

    @BeforeEach
    void actualExclusiveResourceAndProductionReaders() throws Exception {
        var target=SupportRuntimeTarget.select(Map.of("SUPPORT_RUNTIME_TARGET","analytics-20261007"));
        String url=required("NEXION_DB_URL"),username=required("NEXION_DB_USERNAME");
        assertThat(url.startsWith(target.jdbcPrefix())).as("Allowlisted isolated JDBC prefix").isTrue();assertThat(username).isEqualTo(target.username());
        dataSource=pool(url,username,2);outsideDataSource=pool(url,username,1);
        jdbc=new JdbcTemplate(dataSource);outside=new JdbcTemplate(outsideDataSource);
        var json=new ObjectMapper();Path proofPath=Path.of(required("SUPPORT_CAPTURE_OWNERSHIP")).toAbsolutePath().normalize();
        byte[] bytes=Files.readAllBytes(proofPath);JsonNode proof=json.readTree(bytes);
        assertThat(proof.path("databaseIdentity").path("serverUuid").asText()).isEqualTo(SERVER_UUID);
        ObjectNode context=json.createObjectNode();context.put("schemaVersion",2).put("ownershipMode","EXCLUSIVE_ANALYTICS");
        context.set("resourceIdentity",proof.path("resourceIdentity").deepCopy());
        context.putObject("resourceOwnership").put("path",proofPath.toString())
            .put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        SupportExclusiveRuntimeOwnership.requireActual(context,target,jdbc);
        assertThat(jdbc.queryForObject("SELECT @@server_uuid",String.class)).isEqualTo(SERVER_UUID);
        assertThat(outside.queryForObject("SELECT @@server_uuid",String.class)).isEqualTo(SERVER_UUID);
        beforeCounts=outsideCounts();beforeSharedGrants=sharedGrants(outside);
        beforeRules=outside.queryForList("SELECT * FROM nx_support_rules ORDER BY id");
        beforeActivity=outside.queryForList("SELECT * FROM nx_support_activity_coverage ORDER BY id");
        for(String cap:CAPS) {
            var ids=jdbc.queryForList("SELECT id FROM nx_admin_permission WHERE permission_code=? AND resource_type='API' AND status=1 AND is_deleted=0",Long.class,cap);
            assertThat(ids).as("Existing API permission is required; no seed/bootstrap").hasSize(1);permissionIds.put(cap,ids.get(0));
        }
        Configuration configuration=new Configuration(new Environment("private-query-native",new SpringManagedTransactionFactory(),dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        // Main content factory has no local-cache override. Never hide the final-guard counterexample with STATEMENT.
        assertThat(configuration.getLocalCacheScope()).isEqualTo(LocalCacheScope.SESSION);
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());configuration.addInterceptor(probe);
        for(Class<?> mapper:List.of(SupportBindingMapper.class,SupportGroupMapper.class,SupportAnalyticsMapper.class,
            SupportPaymentFactMapper.class,SupportPaymentSourceMapper.class,SupportPaymentCaptureHistoryMapper.class,
            SupportPaymentHistoryBirthMapper.class,SupportInvitationReadMapper.class,SupportDeviceReadMapper.class))configuration.addMapper(mapper);
        var factory=new MybatisSqlSessionFactoryBuilder().build(configuration);var template=new SqlSessionTemplate(factory);
        assertThat(factory.getConfiguration().getLocalCacheScope()).isEqualTo(LocalCacheScope.SESSION);
        manager=new DataSourceTransactionManager(dataSource);transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);transaction.setTimeout(30);
        ownership=proxy(new SupportOwnershipService(template.getMapper(SupportBindingMapper.class),template.getMapper(SupportGroupMapper.class)));
        var history=proxy(new SupportPaymentFactService(template.getMapper(SupportPaymentFactMapper.class)));
        var captured=new SupportPaymentCaptureHistoryService(template.getMapper(SupportPaymentCaptureHistoryMapper.class),template.getMapper(SupportPaymentHistoryBirthMapper.class));
        var finance=proxy(new SupportPaymentSourceService(template.getMapper(SupportPaymentSourceMapper.class),history,dataSource,json,captured));
        var invitations=proxy(new SupportInvitationReadService(template.getMapper(SupportInvitationReadMapper.class)));
        var devices=new SupportDeviceReadService(template.getMapper(SupportDeviceReadMapper.class));
        var stats=proxy(new SupportAnalyticsService(ownership,finance,template.getMapper(SupportAnalyticsMapper.class),invitations,devices));
        query=proxy(new SupportAnalyticsPrivateQueryService(ownership,stats,template.getMapper(SupportAnalyticsMapper.class)));
        probe.dataSource=dataSource;
    }

    @AfterEach
    void independentRollbackReadbackAndAlwaysCloseBothPools() {
        try {
            if(beforeCounts!=null) {
                var checks=new ArrayList<Executable>();
                checks.add(()->assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse());
                for(String table:TABLES)checks.add(()->assertThat(outside.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).as(table+" rollback count").isEqualTo(beforeCounts.get(table)));
                checks.add(()->assertThat(sharedGrants(outside)).isEqualTo(beforeSharedGrants));
                checks.add(()->assertThat(outside.queryForList("SELECT * FROM nx_support_rules ORDER BY id")).isEqualTo(beforeRules));
                checks.add(()->assertThat(outside.queryForList("SELECT * FROM nx_support_activity_coverage ORDER BY id")).isEqualTo(beforeActivity));
                for(var table:owned.entrySet())for(long id:table.getValue())
                    checks.add(()->assertThat(outside.queryForObject("SELECT COUNT(*) FROM "+table.getKey()+" WHERE id=?",Long.class,id)).as(table.getKey()+" fixture ID "+id).isZero());
                assertAll("Independent rollback readbacks",checks);
            }
        } finally {
            try {if(outsideDataSource!=null)outsideDataSource.close();}
            finally {if(dataSource!=null)dataSource.close();}
        }
    }

    @Test
    void emptyManagedRootsStillExecuteInitialAndFinalPhysicalCurrentAuthorizationSql() {
        rollback(()->{
            long owner=actor("SUPERVISOR","service_m3_read");
            probe.reset();var response=as(owner,List.of("service_m3_read"),params("view","CUSTOMERS"));
            assertThat(records(response)).isEmpty();assertThat(map(response,"scopeSummary").get("mode")).isEqualTo("MANAGED");
            // Hard failure if MyBatis L1 serves the final invocation without any JDBC preparation.
            probe.assertCount("SupportAnalyticsMapper.currentReadGrants",2);
            probe.assertCount("SupportAnalyticsMapper.currentGrantedGroupIds",2);
            probe.assertCount("SupportBindingMapper.roles",2);
            assertThat(probe.match("SupportGroupMapper.qualificationCurrent").size()).isGreaterThanOrEqualTo(2);
            assertThat(probe.match("SupportAnalyticsMapper.currentReadGrants")).allSatisfy(sql->assertThat(sql.sql()).endsWith("FOR SHARE"));
            assertThat(probe.match("SupportAnalyticsMapper.currentGrantedGroupIds")).allSatisfy(sql->assertThat(sql.sql()).endsWith("FOR SHARE"));
        });
    }

    @Test
    void realDatabaseAndFixtureAuthenticationMustShareTheSameReadCapability() {
        rollback(()->{
            long m1=actor("SUPERVISOR","service_m1_read"),m3=actor("SUPERVISOR","service_m3_read");
            deny(403,()->as(m1,List.of("service_m3_read"),params()));
            deny(403,()->as(m3,List.of("service_m1_read"),params("pageNum","2","expectedVersion",stale())));
            assertThat(records(as(m1,List.of("service_m1_read"),params()))).isEmpty();
            assertThat(records(as(m3,List.of("service_m3_read"),params()))).isEmpty();
        });
    }

    @Test
    void realPersonalManagedAndSuperScopesKeepForeignRootsAndMissingA1DirectoryOutside() {
        rollback(()->{
            long owner=actor("SUPERVISOR","service_m3_read"),foreignOwner=actor("SUPERVISOR","service_m3_read");
            long agent=actor("SERVICE","service_m1_read"),foreignAgent=actor("SERVICE","service_m1_read");
            long boss=actor("ALL","service_m1_read","platform_a1_read"),bossNoA1=actor("ALL","service_m1_read"),unqualified=actor("NONE","service_m1_read");
            long group=group(owner),foreignGroup=group(foreignOwner);member(agent,group);member(foreignAgent,foreignGroup);
            long ownedRoot=customer(agent,group),foreignRoot=customer(foreignAgent,foreignGroup);
            probe.reset();var personal=as(agent,List.of("service_m1_read"),params("keyword",marker));
            assertThat(customerIds(personal)).containsExactly(Long.toString(ownedRoot));
            assertThat(map(personal,"scopeSummary").get("mode")).isEqualTo("PERSONAL");
            probe.assertBatch("SupportAnalyticsMapper.rootDisplayRows",List.of(ownedRoot));
            probe.assertBatch("SupportPaymentCaptureHistoryMapper.readNewFinancialProofs",List.of(ownedRoot));
            probe.reset();var managed=as(owner,List.of("service_m3_read"),params("keyword",marker));
            assertThat(customerIds(managed)).containsExactly(Long.toString(ownedRoot));
            assertThat(map(managed,"scopeSummary").get("mode")).isEqualTo("MANAGED");
            probe.assertBatch("SupportAnalyticsMapper.rootDisplayRows",List.of(ownedRoot));
            probe.assertBatch("SupportPaymentCaptureHistoryMapper.readNewFinancialProofs",List.of(ownedRoot));
            deny(404,()->as(owner,List.of("service_m3_read"),params("groupId",Long.toString(foreignGroup),"pageNum","2","expectedVersion",stale())));
            deny(403,()->as(unqualified,List.of("service_m1_read"),params()));
            for(long selectedGroup:List.of(group,foreignGroup)) {
                var all=as(boss,List.of("service_m1_read"),params("groupId",Long.toString(selectedGroup),"keyword",marker));
                assertThat(map(all,"scopeSummary").get("mode")).isEqualTo("ALL");
                assertThat(customerIds(all)).containsExactly(Long.toString(selectedGroup==group?ownedRoot:foreignRoot));
            }
            deny(403,()->withAuth(agent,List.of("service_m1_read"),()->ownership.queryScope(ReadMode.ALL,null,null)));
            probe.reset();var directory=as(boss,List.of("service_m1_read"),params("view","AGENTS","groupId",Long.toString(group)));
            probe.assertCount("SupportAnalyticsMapper.supervisorAccountRows",0);
            assertThat(records(directory)).extracting(row->row.get("accountId")).contains(Long.toString(agent)).doesNotContain(Long.toString(owner),Long.toString(foreignAgent),Long.toString(foreignOwner));
            probe.reset();as(boss,List.of("service_m1_read","platform_a1_read"),params("view","AGENTS","groupId",Long.toString(group)));
            probe.assertCount("SupportAnalyticsMapper.supervisorAccountRows",1);
            // Auth A1 alone also cannot mint current DB directory authority.
            probe.reset();var authOnlyDirectory=as(bossNoA1,List.of("service_m1_read","platform_a1_read"),params("view","AGENTS","groupId",Long.toString(group)));
            probe.assertCount("SupportAnalyticsMapper.supervisorAccountRows",0);
            assertThat(records(authOnlyDirectory)).extracting(row->row.get("accountId")).contains(Long.toString(agent)).doesNotContain(Long.toString(owner),Long.toString(foreignAgent),Long.toString(foreignOwner));
            probe.reset();as(owner,List.of("service_m3_read","platform_a1_read"),params("view","AGENTS"));
            // MANAGED intentionally owns its own supervisor directory under the pre-existing rule.
            probe.assertCount("SupportAnalyticsMapper.supervisorAccountRows",1);
            probe.reset();var noDirectory=as(agent,List.of("service_m1_read","platform_a1_read"),params("view","AGENTS"));
            probe.assertCount("SupportAnalyticsMapper.supervisorAccountRows",0);
            assertThat(noDirectory.get("recordsStatus")).isEqualTo("UNAVAILABLE");assertThat(noDirectory.get("total")).isNull();
        });
    }

    @Test
    void observedNativeSourcesSupportSortedPagesOrUnknownWithoutInventedCoverageAndRevocationPrecedesConflict() {
        rollback(()->{
            long owner=actor("SUPERVISOR","service_m3_read"),agent=actor("SERVICE","service_m1_read");
            long group=group(owner);member(agent,group);
            long first=customer(agent,group),second=customer(agent,group),third=customer(agent,group);
            LocalDateTime registered=jdbc.queryForObject("SELECT NOW(6)",LocalDateTime.class).minusDays(2);
            for(long id:List.of(first,second,third))assertThat(jdbc.update("UPDATE nx_user SET created_at=? WHERE id=? AND nickname=?",registered,id,marker)).isEqualTo(1);
            probe.reset();var raw=params("sortKey","REGISTERED_AT","direction","DESC","pageSize","2","keyword",marker);
            var page1=as(agent,List.of("service_m1_read"),raw);
            assertThat(customerIds(page1)).containsExactly(Long.toString(first),Long.toString(second));
            assertThat(page1.get("observedTotal")).isEqualTo(3L);assertThat(records(page1).get(0).get("nickname")).isEqualTo(marker);
            assertThat(map(records(page1).get(0),"first").get("state")).isEqualTo("UNKNOWN");
            // All underlying readers remain real. Missing NEW-account history is business UNKNOWN, not a made-up birth proof.
            probe.assertBatch("SupportAnalyticsMapper.rootDisplayRows",List.of(first,second,third));
            probe.assertBatch("SupportPaymentCaptureHistoryMapper.readNewFinancialProofs",List.of(first,second,third));
            probe.assertBatch("SupportPaymentHistoryBirthMapper.readBirths",List.of(first,second,third));
            for(String source:List.of("deposits","cards","vietqr","hdpay","orders","trials","refunds","freeTrials","unmatched"))
                probe.assertBatch("SupportPaymentFactMapper."+source,List.of(first,second,third));
            probe.assertBatch("SupportInvitationReadMapper.readUsers",List.of(first,second,third));
            probe.assertBatch("SupportDeviceReadMapper.readCurrent",List.of(first,second,third));
            assertThat(map(page1,"funds")).containsEntry("balanceStatus","UNAVAILABLE").containsEntry("withdrawalStatus","UNAVAILABLE");
            String token=(String)page1.get("queryVersion");
            System.out.println("PRIVATE_NATIVE_SOURCE_VERSION_STATE="+page1.get("versionState"));
            if("READY".equals(page1.get("versionState"))) {
                assertThat(token).matches("saq-v1:[0-9a-f]{64}");assertThat(page1.get("total")).isEqualTo(3L);
                var page2=new LinkedHashMap<>(raw);page2.put("pageNum",List.of("2"));page2.put("expectedVersion",List.of(token));
                var next=as(agent,List.of("service_m1_read"),page2);
                assertThat(customerIds(next)).containsExactly(Long.toString(third));assertThat(next.get("queryVersion")).isEqualTo(token);
                // Fresh fixture JDBC write plus next real device read's existing flush policy: no test cache clearing.
                assertThat(jdbc.update("UPDATE nx_user SET nickname=? WHERE id=? AND nickname=?",marker+"-changed",third,marker)).isEqualTo(1);
                deny(409,()->as(agent,List.of("service_m1_read"),page2));
            } else {
                assertThat(page1.get("versionState")).isEqualTo("UNKNOWN");assertThat(token).isNull();
                assertThat(page1.get("total")).isNull();assertThat(page1.get("canContinue")).isEqualTo(false);
                var page2=new LinkedHashMap<>(raw);page2.put("pageNum",List.of("2"));page2.put("expectedVersion",List.of(stale()));
                deny(409,()->as(agent,List.of("service_m1_read"),page2));
            }
            // A precise own-row mutation seam, NOT an assertion about concurrent writers blocked by FOR SHARE.
            probe.reset();probe.afterPrepareOnce="SupportAnalyticsMapper.queryRules";
            probe.afterPrepare=()->assertThat(jdbc.update("UPDATE nx_admin_role_permission SET is_deleted=1 WHERE role_id=? AND permission_id=? AND is_deleted=0",capabilityRole(agent),permissionIds.get("service_m1_read"))).isEqualTo(1);
            var stalePage=new LinkedHashMap<>(raw);stalePage.put("pageNum",List.of("2"));stalePage.put("expectedVersion",List.of(stale()));
            deny(403,()->as(agent,List.of("service_m1_read"),stalePage));
            assertThat(probe.afterPrepareOnce).as("Revocation seam actually ran before final guard").isNull();
            probe.assertCount("SupportAnalyticsMapper.currentReadGrants",2);
        });
    }

    private HikariDataSource pool(String url,String username,int size) {
        var pool=new HikariDataSource();pool.setJdbcUrl(url);pool.setUsername(username);pool.setPassword(required("NEXION_DB_PASSWORD"));
        pool.setMaximumPoolSize(size);pool.setMinimumIdle(0);pool.setConnectionTimeout(10000);
        // Match main application.yml; support-history UTC_TIMESTAMP remains its own original SQL contract.
        pool.setConnectionInitSql("SET time_zone = '+08:00'");return pool;
    }
    private static String required(String name) {String value=System.getenv(name);if(value==null || value.isBlank())throw new IllegalStateException("Required isolated setting: "+name);return value;}
    @SuppressWarnings("unchecked") private <T> T proxy(T service) {
        var factory=new ProxyFactory(service);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (T)factory.getProxy();
    }
    private void rollback(Runnable body) {
        transaction.executeWithoutResult(status->{try {
            jdbc.execute((ConnectionCallback<Void>)connection->{
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(connection.getAutoCommit()).isFalse();assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(dataSource);
                assertThat(holder).isNotNull();assertThat(org.springframework.jdbc.datasource.DataSourceUtils.getTargetConnection(connection)).isSameAs(org.springframework.jdbc.datasource.DataSourceUtils.getTargetConnection(holder.getConnection()));return null;
            });
            isolateSharedCapabilities();body.run();
        }finally {probe.afterPrepare=null;probe.afterPrepareOnce=null;status.setRollbackOnly();}});
    }
    private static List<Map<String,Object>> sharedGrants(JdbcTemplate reader) {
        return reader.queryForList("SELECT rp.* FROM nx_admin_role_permission rp JOIN nx_admin_role r ON r.id=rp.role_id JOIN nx_admin_permission p ON p.id=rp.permission_id WHERE r.role_code IN ('SUPPORT','SUPER_ADMIN') AND p.permission_code IN ('service_m1_read','service_m3_read','platform_a1_read') ORDER BY rp.id");
    }
    private void isolateSharedCapabilities() {
        // Canonical role_code is unique and eligibility requires it; isolate only these exact existing grants in the rollback transaction.
        for(var row:sharedGrants(jdbc)) {
            int deleted=((Number)row.get("is_deleted")).intValue();if(deleted==1)continue;
            assertThat(jdbc.update("UPDATE nx_admin_role_permission SET is_deleted=1 WHERE id=? AND role_id=? AND permission_id=? AND is_deleted=?",
                row.get("id"),row.get("role_id"),row.get("permission_id"),deleted)).isEqualTo(1);
        }
    }
    private long insert(String table,String sql,Object... values) {
        var key=new org.springframework.jdbc.support.GeneratedKeyHolder();
        assertThat(jdbc.update(connection->{var statement=connection.prepareStatement(sql,java.sql.Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);return statement;},key)).isEqualTo(1);
        long id=Objects.requireNonNull(key.getKey()).longValue();owned.computeIfAbsent(table,ignored->new ArrayList<>()).add(id);return id;
    }
    private long actor(String kind,String... capabilities) {
        String name=marker+"-"+owned.getOrDefault("nx_admin",List.of()).size();
        long actor=insert("nx_admin","INSERT INTO nx_admin(username,password_hash,nickname,status,version,is_deleted) VALUES(?,'fixture-only',?,1,1,0)",name,marker);
        long role=insert("nx_admin_role","INSERT INTO nx_admin_role(role_code,role_name,remark,status,is_deleted) VALUES(?,?,?,1,0)",name,name,marker);
        long canonical=jdbc.queryForObject("SELECT id FROM nx_admin_role WHERE role_code=? AND status=1 AND is_deleted=0",Long.class,"ALL".equals(kind)?"SUPER_ADMIN":"SUPPORT");
        for(long roleId:List.of(canonical,role))insert("nx_admin_role_relation","INSERT INTO nx_admin_role_relation(admin_id,role_id) VALUES(?,?)",actor,roleId);
        for(String cap:capabilities)insert("nx_admin_role_permission","INSERT INTO nx_admin_role_permission(role_id,permission_id) VALUES(?,?)",role,permissionIds.get(cap));
        if(!Set.of("ALL","NONE").contains(kind))insert("nx_support_account_qualification_history","INSERT INTO nx_support_account_qualification_history(admin_id,qualification_kind,state,starts_at,version,reason,operation_id) VALUES(?,?,'ENABLED',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY),1,?,?)",actor,kind,marker,UUID.randomUUID().toString());
        if("SERVICE".equals(kind))insert("nx_support_agent_profile","INSERT INTO nx_support_agent_profile(admin_id,seat_type,position,service_types,tags,enabled,version) VALUES(?,'DEDICATED',?,'advisor','',1,1)",actor,marker);
        return actor;
    }
    private long capabilityRole(long actor) {
        return jdbc.queryForObject("SELECT r.id FROM nx_admin_role r JOIN nx_admin_role_relation rr ON rr.role_id=r.id WHERE rr.admin_id=? AND r.remark=?",Long.class,actor,marker);
    }
    private long group(long owner) {
        long group=insert("nx_support_group","INSERT INTO nx_support_group(name,supervisor_admin_id,status,version,created_at,updated_at) VALUES(?,?,'ENABLED',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",marker+"-g"+owned.getOrDefault("nx_support_group",List.of()).size(),owner);
        insert("nx_support_group_owner_history","INSERT INTO nx_support_group_owner_history(group_id,supervisor_admin_id,starts_at,version,reason,operation_id) VALUES(?,?,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY),1,?,?)",group,owner,marker,UUID.randomUUID().toString());return group;
    }
    private void member(long agent,long group) {insert("nx_support_group_member_history","INSERT INTO nx_support_group_member_history(agent_admin_id,group_id,starts_at,version,reason,operation_id) VALUES(?,?,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY),1,?,?)",agent,group,marker,UUID.randomUUID().toString());}
    private long customer(long agent,long group) {
        String identity=UUID.randomUUID().toString().replace("-","");
        long customer=insert("nx_user","INSERT INTO nx_user(country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox) VALUES('0',?,'127.0.0.1','fixture-only',?,?,'ACTIVE',0)",identity,marker,identity);
        insert("nx_support_agent_user_assignment","INSERT INTO nx_support_agent_user_assignment(agent_admin_id,user_id,status,starts_at,version,source,segment_root_id,depth,operation_id) VALUES(?,?,'ACTIVE',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY),1,'MANUAL',?,0,?)",agent,customer,customer,UUID.randomUUID().toString());
        insert("nx_support_customer_route_history","INSERT INTO nx_support_customer_route_history(customer_id,group_id,starts_at,version,reason,operation_id) VALUES(?,?,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY),1,?,?)",customer,group,marker,UUID.randomUUID().toString());return customer;
    }
    private Map<String,Long> outsideCounts() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return outside.execute((ConnectionCallback<Map<String,Long>>)connection->{var counts=new LinkedHashMap<String,Long>();
            try(var statement=connection.createStatement()) {for(String table:TABLES)try(var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)) {assertThat(rows.next()).isTrue();counts.put(table,rows.getLong(1));assertThat(rows.next()).isFalse();}}return counts;});
    }
    private Map<String,Object> as(long actor,List<String> caps,Map<String,List<String>> raw) {return withAuth(actor,caps,()->query.query(raw));}
    private static <T> T withAuth(long actor,List<String> caps,java.util.function.Supplier<T> body) {
        var previous=SecurityContextHolder.getContext();var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(actor),"fixture-only",caps.stream().map(SimpleGrantedAuthority::new).toList()));
        SecurityContextHolder.setContext(context);try{return body.get();}finally{SecurityContextHolder.setContext(previous);}
    }
    private static void deny(int code,Runnable body) {assertThatThrownBy(body::run).isInstanceOfSatisfying(BizException.class,ex->assertThat(ex.getCode()).isEqualTo(code));}
    private static String stale() {return "saq-v1:"+"0".repeat(64);}
    private static Map<String,List<String>> params(String... values) {var result=new LinkedHashMap<String,List<String>>();for(int i=0;i<values.length;i+=2)result.put(values[i],List.of(values[i+1]));return result;}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Map<String,Object> value,String key) {return (Map<String,Object>)value.get(key);}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> records(Map<String,Object> response) {return (List<Map<String,Object>>)response.get("records");}
    private static List<String> customerIds(Map<String,Object> response) {return records(response).stream().map(row->(String)row.get("customerId")).toList();}

    record PhysicalSql(String mappedStatement,String sql,List<Object> parameters,List<Object> foreachParameters) { }
    @Intercepts({@Signature(type=StatementHandler.class,method="prepare",args={Connection.class,Integer.class}), @Signature(type=org.apache.ibatis.executor.Executor.class,method="query",args={MappedStatement.class,Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class})})
    static final class PhysicalSqlProbe implements Interceptor {
        private final List<PhysicalSql> prepared=new ArrayList<>();
        HikariDataSource dataSource;String afterPrepareOnce;Runnable afterPrepare;
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if(invocation.getTarget() instanceof org.apache.ibatis.executor.Executor) {
                try {return invocation.proceed();}
                catch(Throwable error) {
                    Throwable cause=error;while(cause.getCause()!=null && cause.getCause()!=cause)cause=cause.getCause();
                    System.err.println("PRIVATE_NATIVE_SQL_FAILURE="+((MappedStatement)invocation.getArgs()[0]).getId()+";cause="+cause.getClass().getSimpleName()+";message="+String.valueOf(cause.getMessage()).split("\\R",2)[0]);
                    throw error;
                }
            }
            var handler=(StatementHandler)com.baomidou.mybatisplus.core.toolkit.PluginUtils.realTarget(invocation.getTarget());
            var mapped=(MappedStatement)SystemMetaObject.forObject(handler).getValue("delegate.mappedStatement");
            var connection=(Connection)invocation.getArgs()[0];
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(dataSource);
            assertThat(holder).isNotNull();assertThat(org.springframework.jdbc.datasource.DataSourceUtils.getTargetConnection(connection)).isSameAs(org.springframework.jdbc.datasource.DataSourceUtils.getTargetConnection(holder.getConnection()));
            var bound=handler.getBoundSql();var parameters=new ArrayList<Object>();var foreachParameters=new ArrayList<Object>();
            for(var mapping:bound.getParameterMappings())if(mapping.getMode()!=ParameterMode.OUT) {
                String property=mapping.getProperty();Object value;
                if(bound.hasAdditionalParameter(property))value=bound.getAdditionalParameter(property);
                else if(bound.getParameterObject()==null)value=null;
                else if(mapped.getConfiguration().getTypeHandlerRegistry().hasTypeHandler(bound.getParameterObject().getClass()))value=bound.getParameterObject();
                else value=mapped.getConfiguration().newMetaObject(bound.getParameterObject()).getValue(property);
                parameters.add(value);
                if(property.startsWith("__frch_"))foreachParameters.add(value);
            }
            Object statement=invocation.proceed();
            prepared.add(new PhysicalSql(mapped.getId(),bound.getSql().replaceAll("\\s+"," ").trim(),Collections.unmodifiableList(parameters),Collections.unmodifiableList(foreachParameters)));
            if(afterPrepareOnce!=null && mapped.getId().endsWith(afterPrepareOnce)) {afterPrepareOnce=null;afterPrepare.run();}
            return statement;
        }
        void reset() {prepared.clear();}
        List<PhysicalSql> match(String suffix) {return prepared.stream().filter(sql->sql.mappedStatement().endsWith(suffix)).toList();}
        void assertCount(String suffix,int count) {assertThat(match(suffix)).as("Physical StatementHandler.prepare count for "+suffix).hasSize(count);}
        void assertBatch(String suffix,List<Long> ids) {
            var calls=match(suffix);assertThat(calls).as("One physical batch, no per-customer SQL: "+suffix).hasSize(1);
            var actual=new TreeSet<Long>();for(Object parameter:calls.get(0).foreachParameters()) {assertThat(parameter).isInstanceOf(Long.class);actual.add((Long)parameter);}
            assertThat(actual).containsExactlyElementsOf(new TreeSet<>(ids));
        }
    }
}
