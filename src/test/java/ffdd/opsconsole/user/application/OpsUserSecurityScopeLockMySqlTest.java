package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.content.application.SupportOwnershipService;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.user.domain.*;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import ffdd.opsconsole.shared.api.*;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual service/Spring transactions and MySQL customer/admin/ownership SQL; unrelated output projections mocked.
 * Writer executes the real transfer lock mappers, not the full transfer/audit command. No business schema writes. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD", matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpsUserSecurityScopeLockMySqlTest {
    Connection admin;
    String schema;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    TransactionTemplate tx;
    UserOpsMapper users;
    SupportBindingMapper bindings;
    SupportGroupMapper groups;
    UserOpsRepository repository;
    OpsUserService service;
    AtomicLong readerConnection = new AtomicLong(), writerConnection = new AtomicLong();
    List<Long> customerOrder = new CopyOnWriteArrayList<>();
    AtomicInteger sessionReads = new AtomicInteger();
    volatile CountDownLatch projectionEntered, releaseProjection;
    ExecutorService pool;

    @BeforeAll void setup() throws Exception {
        String url=System.getenv("NEXION_TEST_DB_SERVER_URL");
        if(url==null || !url.matches("jdbc:mysql://(127[.]0[.]0[.]1|localhost):[0-9]+/([?].*)?"))
            throw new IllegalArgumentException("Dedicated loopback server URL without catalog required");
        String user=System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME","root"), password=System.getenv("NEXION_TEST_DB_PASSWORD");
        admin=DriverManager.getConnection(url,user,password);
        schema="nx_c5_scope_lock_test_"+UUID.randomUUID().toString().replace("-","");
        admin.createStatement().execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");
        System.out.println("C5_SCOPE_FIXTURE_CREATE="+schema);
        int q=url.indexOf('?');
        var ds=new DriverManagerDataSource(url.substring(0,q<0?url.length():q)+schema+(q<0?"":url.substring(q)),user,password);
        jdbc=new JdbcTemplate(ds); manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        tx.setIsolationLevelName("ISOLATION_REPEATABLE_READ");
        // Copy structure only from the explicitly configured local migrated template; never copy rows/triggers.
        String template=System.getenv("NEXION_TEST_DB_TEMPLATE_SCHEMA");
        if(template==null || !template.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Template schema required");
        for(String table:List.of("nx_user","nx_user_security","nx_user_login_guard","nx_admin","nx_admin_role",
                "nx_admin_role_relation","nx_support_agent_profile","nx_support_agent_user_assignment",
                "nx_support_group","nx_support_group_owner_history","nx_support_group_member_history",
                "nx_support_account_qualification_history","nx_support_customer_route_history"))
            jdbc.execute("CREATE TABLE "+table+" LIKE `"+template+"`."+table);
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("scope-lock",new SpringManagedTransactionFactory(),ds));
        configuration.addMapper(UserOpsMapper.class);configuration.addMapper(SupportBindingMapper.class);configuration.addMapper(SupportGroupMapper.class);
        var session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        users=session.getMapper(UserOpsMapper.class);bindings=session.getMapper(SupportBindingMapper.class);groups=session.getMapper(SupportGroupMapper.class);
        jdbc.update("INSERT INTO nx_admin(id,username,password_hash,status) VALUES(4,'synthetic-scope-agent','not-a-login',1)");
        jdbc.update("INSERT INTO nx_admin_role(id,role_code,role_name,status) VALUES(7,'SUPPORT','synthetic',1)");
        jdbc.update("INSERT INTO nx_admin_role_relation(admin_id,role_id) VALUES(4,7)");
        jdbc.update("INSERT INTO nx_support_agent_profile(admin_id,position,service_types,tags) VALUES(4,'synthetic','test','test')");
        jdbc.update("INSERT INTO nx_support_account_qualification_history(admin_id,qualification_kind,state,starts_at,version,reason,operation_id) VALUES(4,'SERVICE','ENABLED',DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY),1,'synthetic','scope-test')");
        for(long id:List.of(100L,200L)) {
            jdbc.update("INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,status) VALUES(?,'+00',?,'127.0.0.1','not-a-login','synthetic',?,'FROZEN')",id,"test"+id,"test"+id);
            jdbc.update("INSERT INTO nx_user_security(user_id,login_fail_count) VALUES(?,6)",id);
            jdbc.update("INSERT INTO nx_user_login_guard(login_key,user_id,failed_count,window_started_at,locked_until) VALUES(?,?,6,NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY))",String.format("%064d",id),id);
            jdbc.update("INSERT INTO nx_support_agent_user_assignment(agent_admin_id,user_id,status,starts_at) VALUES(4,?,'ACTIVE',DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY))",id);
        }
    }
    @BeforeEach void prepare() {
        pool=Executors.newFixedThreadPool(2);
        jdbc.update("UPDATE nx_support_agent_user_assignment SET status='ACTIVE',ends_at=NULL WHERE user_id IN(100,200)");
        readerConnection.set(0);writerConnection.set(0);customerOrder.clear();sessionReads.set(0);
        projectionEntered=null;releaseProjection=null;
        repository=mock(UserOpsRepository.class);
        when(repository.lockedSecurityUsers(anyInt(),anyInt(),anyInt(),anyInt(),anyInt())).thenAnswer(a -> {
            readerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
            assertThat(jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ");
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            System.out.println("C5_SCOPE_READER_CONNECTION="+readerConnection.get());
            return users.lockedSecurityUsers(a.getArgument(0),a.getArgument(1),a.getArgument(2),a.getArgument(3),a.getArgument(4));
        });
        doAnswer(a -> {long id=a.getArgument(0);customerOrder.add(id);users.lockUser(id);return null;}).when(repository).lockUser(anyLong());
        when(repository.findById(anyLong(),any(ReadScope.class))).thenAnswer(a -> {
            long id=a.getArgument(0);ReadScope scope=a.getArgument(1);
            return bindings.readableCustomer(scope,id)==1?Optional.of(new UserAccountView(id,"U"+id,"synthetic","***","+00","FROZEN","L1","V0",false,BigDecimal.ZERO,BigDecimal.ZERO,0,"LOW",0L,0L,null,null)):Optional.empty();
        });
        when(repository.countReadableSupportCustomers(anyList(),any(ReadScope.class))).thenAnswer(a -> {
            List<Long> ids=a.getArgument(0);ReadScope scope=a.getArgument(1);
            return users.countReadableSupportCustomers(ids,scope);
        });
        when(repository.pageSessions(anyLong(),anyInt(),anyInt(),anyInt())).thenAnswer(a -> {
            sessionReads.incrementAndGet();
            if(projectionEntered!=null){projectionEntered.countDown();await(releaseProjection);}
            return new PageResult<UserSessionView>(0L,1,10,List.of());
        });
        var ownership=proxy(new SupportOwnershipService(bindings,groups));
        var target=new OpsUserService(repository,
            mock(ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade.class),
            mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class),
            mock(ffdd.opsconsole.finance.facade.FinanceWithdrawalControlFacade.class),
            mock(ffdd.opsconsole.risk.facade.RiskUserStateFacade.class),
            mock(ffdd.opsconsole.shared.audit.AuditLogService.class),
            mock(ffdd.opsconsole.shared.idempotency.AdminIdempotencyService.class),
            mock(ffdd.opsconsole.shared.security.AdminOperatorRoleResolver.class),
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
            mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class),
            mock(ffdd.opsconsole.shared.outbox.EventOutboxService.class),
            mock(ffdd.opsconsole.shared.security.JwtTokenProvider.class),Clock.systemUTC(),ownership);
        service=proxy(target);
    }
    @SuppressWarnings("unchecked") <T> T proxy(T target){
        var p=new ProxyFactory(target);p.setProxyTargetClass(true);
        p.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (T)p.getProxy();
    }
    ApiResult<UserSecurityOverview> read(Long selected){
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("4","unused",List.of()));
        try{return service.securityOverview(null,selected,1,10,null);}finally{SecurityContextHolder.clearContext();}
    }
    @Test void readerWaitsForTransferCustomerLockWithoutHoldingAdminLock() throws Exception {writerFirst(100L,false);}
    @Test void unselectedOverviewAlsoLocksItsFixedCandidatesBeforeAdmin() throws Exception {writerFirst(null,false);}
    @Test void assignmentRevokedWhileReaderWaitsIsDeniedDespiteItsEarlierSnapshot() throws Exception {writerFirst(100L,true);}
    void writerFirst(Long selected,boolean revoke) throws Exception {
        var customerLocked=new CountDownLatch(1);var continueWriter=new CountDownLatch(1);
        Future<?> writer=pool.submit(()->tx.executeWithoutResult(s->{
            writerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
            bindings.lockCustomer(100L);customerLocked.countDown();await(continueWriter);
            assertThat(groups.lockAccount(4L)).isNotNull();
            if(revoke)jdbc.update("UPDATE nx_support_agent_user_assignment SET status='ENDED',ends_at=UTC_TIMESTAMP() WHERE user_id=100");
        }));
        await(customerLocked);Future<ApiResult<UserSecurityOverview>> reader=pool.submit(()->read(selected));
        try {waitForLock(readerConnection,reader);assertThat(writer.isDone()).isFalse();}
        finally {continueWriter.countDown();}
        writer.get(15,TimeUnit.SECONDS);
        if(revoke){
            assertThatThrownBy(()->reader.get(15,TimeUnit.SECONDS)).hasRootCauseInstanceOf(BizException.class).hasRootCauseMessage("SUPPORT_CUSTOMER_NOT_FOUND");
            assertThat(sessionReads).hasValue(0);
        }else{
            var result=reader.get(15,TimeUnit.SECONDS);assertThat(result.getCode()).isZero();
            assertThat(result.getData().lockedUsers()).extracting(UserSecurityUserRow::userId).containsExactlyInAnyOrder(100L,200L);
        }
        assertThat(customerOrder).containsExactly(100L,200L);
        verify(repository).lockedSecurityUsers(anyInt(),anyInt(),anyInt(),anyInt(),anyInt());
    }
    @Test void assignmentCannotChangeBetweenAuthorizationAndPrivateProjection() throws Exception {
        projectionEntered=new CountDownLatch(1);releaseProjection=new CountDownLatch(1);
        Future<ApiResult<UserSecurityOverview>> reader=pool.submit(()->read(100L));await(projectionEntered);
        Future<?> writer=pool.submit(()->tx.executeWithoutResult(s->{
            writerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
            bindings.lockCustomer(100L);groups.lockAccount(4L);
            jdbc.update("UPDATE nx_support_agent_user_assignment SET status='ENDED',ends_at=UTC_TIMESTAMP() WHERE user_id=100");
        }));
        try{waitForLock(writerConnection,writer);assertThat(writer.isDone()).isFalse();}
        finally{releaseProjection.countDown();}
        assertThat(reader.get(15,TimeUnit.SECONDS).getData().selectedUser().userId()).isEqualTo(100L);
        writer.get(15,TimeUnit.SECONDS);projectionEntered=null;releaseProjection=null;
        assertThatThrownBy(()->read(100L)).isInstanceOf(BizException.class).hasMessage("SUPPORT_CUSTOMER_NOT_FOUND");
    }
    void waitForLock(AtomicLong connection, Future<?> waiting) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){
            if(waiting.isDone()) {waiting.get();throw new AssertionError("Expected blocked transaction completed early; connection="+connection.get());}
            if(connection.get()!=0 && jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_locks l JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID WHERE t.PROCESSLIST_ID=? AND l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='nx_user' AND l.LOCK_STATUS='WAITING'",Integer.class,connection.get(),schema)>0){
                System.out.println("C5_SCOPE_OBSERVED_LOCK_WAIT="+connection.get());return;
            }
            Thread.sleep(25);
        }
        System.out.println("C5_SCOPE_LOCK_DIAGNOSTIC="+jdbc.queryForList("SELECT ENGINE_TRANSACTION_ID,THREAD_ID,OBJECT_NAME,INDEX_NAME,LOCK_TYPE,LOCK_MODE,LOCK_STATUS,LOCK_DATA FROM performance_schema.data_locks WHERE OBJECT_SCHEMA=?",schema));
        throw new AssertionError("Expected actual MySQL LOCK WAIT was not observed; connection="+connection.get()+"; customerOrder="+customerOrder+"; futureDone="+waiting.isDone());
    }
    static void await(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("fixture coordination timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    @AfterEach void finishWorkers() throws Exception {
        if(releaseProjection!=null)releaseProjection.countDown();
        pool.shutdown();assertThat(pool.awaitTermination(25,TimeUnit.SECONDS)).as("All fixture transactions finished before next test").isTrue();
    }
    @AfterAll void cleanup() throws Exception {
        if(admin!=null){try{admin.createStatement().execute("DROP DATABASE `"+schema+"`");System.out.println("C5_SCOPE_FIXTURE_DROP="+schema);}finally{admin.close();}}
    }
}
