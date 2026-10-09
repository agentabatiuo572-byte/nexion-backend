package ffdd.opsconsole.content.application;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade.Prepared;
import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade;
import ffdd.opsconsole.content.mapper.SupportPaymentAttributionMapper;
import ffdd.opsconsole.content.mapper.SupportPaymentCaptureHistoryMapper;
import ffdd.opsconsole.content.mapper.SupportPaymentHistoryBirthMapper;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats.Basis;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats.Query;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats.Result;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.device.application.SupportDeviceReadService;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade.ConnectionStatus;
import ffdd.opsconsole.device.mapper.SupportDeviceReadMapper;
import ffdd.opsconsole.finance.application.SupportPaymentFactService;
import ffdd.opsconsole.finance.application.SupportPaymentSourceService;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Fact;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Issue;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Kind;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Snapshot;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Status;
import ffdd.opsconsole.finance.mapper.E4OrderRefundMapper;
import ffdd.opsconsole.finance.mapper.SupportPaymentFactMapper;
import ffdd.opsconsole.finance.mapper.SupportPaymentSourceMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.team.application.SupportInvitationReadService;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Completeness;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Reason;
import ffdd.opsconsole.team.mapper.SupportInvitationReadMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Opt-in auxiliary acceptance against the exclusively owned analytics MySQL instance.
 * This starts no application and uses production SQL and real Spring transaction proxies.
 * Financial writes below affect fresh fixture accounts only. Audit is a failure/count mock:
 * this test makes no assertion about a durable production audit record or workflow approval.
 */
@EnabledIfEnvironmentVariable(named="SUPPORT_CAPTURE_MYSQL_ENABLED", matches="true")
class SupportPaymentCaptureMySqlIntegrationTest {
    private static final String SERVER_UUID="3556ddae-c1a1-11f1-8853-a40c6626953d";
    private static final List<String> TABLES=List.of("nx_user","nx_user_wallet","nx_wallet_ledger",
        "nx_payment_record","nx_order","nx_wallet_bill","nx_deposit_order","nx_cregis_deposit_event",
        "nx_topup_card_settlement","nx_vietqr_intent","nx_vietqr_reconciliation","nx_hdpay_payin_order",
        "nx_user_device","nx_trial_claim","nx_support_payment_attribution","nx_support_payment_history_birth",
        "nx_user_device_runtime","nx_admin","nx_admin_role_relation","nx_support_agent_profile",
        "nx_support_agent_user_assignment","nx_support_group","nx_support_group_owner_history",
        "nx_support_group_member_history","nx_support_account_qualification_history",
        "nx_support_customer_route_history","nx_admin_role");
    private final ObjectMapper json=new ObjectMapper();
    private final List<Long> readerFixtureDeviceIds=new ArrayList<>();
    private final List<Long> readerFixtureRuntimeIds=new ArrayList<>();
    private final List<Long> statsFixtureAdminIds=new ArrayList<>();
    private final List<Long> statsFixtureGroupIds=new ArrayList<>();
    private final List<Long> statsFixtureCustomerIds=new ArrayList<>();
    private HikariDataSource dataSource;
    private HikariDataSource outsideDataSource;
    private JdbcTemplate jdbc;
    private JdbcTemplate outside;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transaction;
    private FinanceSupportPaymentFactsFacade finance;
    private SupportPaymentAttributionFacade capture;
    private SupportPaymentHistoryBirthService birth;
    private AuditLogService audit;
    private SupportPaymentSourceMapper sourceMapper;
    private E4OrderRefundMapper refundMapper;
    private SupportPaymentFactService history;
    private SupportPaymentCaptureHistoryMapper historyMapper;
    private SupportPaymentHistoryBirthMapper birthMapper;
    private SupportInvitationReadFacade invitations;
    private SupportDeviceReadFacade devices;
    private SupportAnalyticsService statistics;
    private SupportAnalyticsService enrichedStatistics;
    private final List<List<Long>> enrichedFinancialScopes=new ArrayList<>();
    private final List<Long> nativeActivityFixtureIds=new ArrayList<>();

    @BeforeEach
    void exclusivelyOwnedDatabaseAndRealTransactionProxies() throws Exception {
        var target=SupportRuntimeTarget.select(Map.of("SUPPORT_RUNTIME_TARGET","analytics-20261007"));
        String url=requiredEnvironment("NEXION_DB_URL"), username=requiredEnvironment("NEXION_DB_USERNAME");
        assertThat(url).startsWith(target.jdbcPrefix());
        assertThat(username).isEqualTo(target.username());
        dataSource=new HikariDataSource();
        dataSource.setJdbcUrl(url);dataSource.setUsername(username);dataSource.setPassword(requiredEnvironment("NEXION_DB_PASSWORD"));
        dataSource.setMaximumPoolSize(8);dataSource.setMinimumIdle(0);
        jdbc=new JdbcTemplate(dataSource);
        // A distinct DataSource object ensures even an accidental in-transaction readback opens a new connection.
        outsideDataSource=new HikariDataSource();
        outsideDataSource.setJdbcUrl(url);outsideDataSource.setUsername(username);outsideDataSource.setPassword(requiredEnvironment("NEXION_DB_PASSWORD"));
        outsideDataSource.setMaximumPoolSize(2);outsideDataSource.setMinimumIdle(0);
        outside=new JdbcTemplate(outsideDataSource);
        Path proofPath=Path.of(requiredEnvironment("SUPPORT_CAPTURE_OWNERSHIP")).toAbsolutePath().normalize();
        byte[] proofBytes=Files.readAllBytes(proofPath);
        JsonNode proof=json.readTree(proofBytes);
        assertThat(proof.path("databaseIdentity").path("serverUuid").asText()).isEqualTo(SERVER_UUID);
        ObjectNode context=json.createObjectNode();
        context.put("schemaVersion",2).put("ownershipMode","EXCLUSIVE_ANALYTICS");
        context.set("resourceIdentity",proof.path("resourceIdentity").deepCopy());
        context.putObject("resourceOwnership").put("path",proofPath.toString())
            .put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(proofBytes)));
        // No run/step/snapshot is synthesized. The existing verifier compares server/account/grants/datadir.
        SupportExclusiveRuntimeOwnership.requireActual(context,target,jdbc);
        assertThat(jdbc.queryForObject("SELECT @@server_uuid",String.class)).isEqualTo(SERVER_UUID);
        applyStructureOnlyTwice();

        Configuration configuration=new Configuration(new Environment("support-capture-actual-mysql",
            new SpringManagedTransactionFactory(),dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SupportPaymentSourceMapper.class);
        configuration.addMapper(SupportPaymentFactMapper.class);
        configuration.addMapper(SupportPaymentCaptureHistoryMapper.class);
        configuration.addMapper(E4OrderRefundMapper.class);
        configuration.addMapper(SupportPaymentAttributionMapper.class);
        configuration.addMapper(SupportPaymentHistoryBirthMapper.class);
        configuration.addMapper(SupportInvitationReadMapper.class);
        configuration.addMapper(SupportDeviceReadMapper.class);
        configuration.addMapper(SupportBindingMapper.class);
        configuration.addMapper(SupportGroupMapper.class);
        configuration.addMapper(SupportAnalyticsMapper.class);
        var factory=new MybatisSqlSessionFactoryBuilder().build(configuration);
        var template=new SqlSessionTemplate(factory);
        assertThat(factory.getConfiguration().getEnvironment().getDataSource()).isSameAs(dataSource);
        manager=new DataSourceTransactionManager(dataSource);
        assertThat(manager.getDataSource()).isSameAs(dataSource);
        transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(30);
        audit=mock(AuditLogService.class);
        sourceMapper=template.getMapper(SupportPaymentSourceMapper.class);
        refundMapper=template.getMapper(E4OrderRefundMapper.class);
        history=proxy(new SupportPaymentFactService(template.getMapper(SupportPaymentFactMapper.class)));
        historyMapper=template.getMapper(SupportPaymentCaptureHistoryMapper.class);
        birthMapper=template.getMapper(SupportPaymentHistoryBirthMapper.class);
        finance=historyReader(historyMapper);
        capture=proxy(new SupportPaymentAttributionService(template.getMapper(SupportPaymentAttributionMapper.class),
            finance,audit,dataSource,json));
        birth=proxy(new SupportPaymentHistoryBirthService(birthMapper,dataSource));
        invitations=proxy(new SupportInvitationReadService(template.getMapper(SupportInvitationReadMapper.class)));
        devices=new SupportDeviceReadService(template.getMapper(SupportDeviceReadMapper.class));
        var ownership=proxy(new SupportOwnershipService(template.getMapper(SupportBindingMapper.class),
            template.getMapper(SupportGroupMapper.class)));
        statistics=proxy(new SupportAnalyticsService(ownership,finance,template.getMapper(SupportAnalyticsMapper.class)));
        // Observe the real transaction facade; never synthesize a financial Fact or coverage certificate.
        ProxyFactory observedFinance=new ProxyFactory(finance);
        observedFinance.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            if(invocation.getMethod().getName().equals("readHistory")) {
                @SuppressWarnings("unchecked") var ids=(java.util.Collection<Long>)invocation.getArguments()[0];
                enrichedFinancialScopes.add(List.copyOf(ids));
            }
            return invocation.proceed();
        });
        enrichedStatistics=proxy(new SupportAnalyticsService(ownership,(FinanceSupportPaymentFactsFacade)observedFinance.getProxy(),
            template.getMapper(SupportAnalyticsMapper.class),invitations,devices));
    }

    @AfterEach
    void releaseTestConnections() {
        // Each test owns both pools; reuse avoids thousands of short-lived Windows sockets.
        try {if(outsideDataSource!=null)outsideDataSource.close();}
        finally {if(dataSource!=null)dataSource.close();}
    }

    @Test
    void enrichedCommittedCurrentPaidAndOverlappingInvitationsSurviveIndependentReadWithoutHistoryLeakage() {
        Map<String,Long> before=outsideCounts();
        List<Long> accounts=new ArrayList<>();List<Fixture> fixtures=new ArrayList<>();long[] actors=new long[2];
        try {
            transaction.executeWithoutResult(status -> {
                long owner=statsActor("SUPERVISOR"),agent=statsActor("SERVICE"),other=statsActor("SERVICE");actors[0]=agent;actors[1]=other;
                long group=statsGroup(owner);statsMember(agent,group);
                long root=newAccount(accounts,0),overlap=newAccount(accounts,0),hidden=newAccount(accounts,0),queue=newAccount(accounts,0),foreign=newAccount(accounts,0);
                statsBind(root,agent,false);statsBind(overlap,agent,false);statsBind(foreign,other,false);statsRoute(queue,group);
                readerSponsor(overlap,root);readerSponsor(hidden,overlap);readerSponsor(queue,root);
                for(var entry:List.of(Map.entry(root,Source.WALLET_ORDER),Map.entry(overlap,Source.CARD_TOPUP),Map.entry(hidden,Source.CARD_TOPUP))) {
                    Fixture f=pending(entry.getValue(),entry.getKey());fixtures.add(f);Prepared prepared=prepare(f);settle(f,prepared,true,false);capture.record(prepared);
                }
                Fixture purchase=fixtures.get(0);
                long paid=readerDevice(root,"OWNED","ACTIVE","ORDER",purchase.order,"DEVICE");readerRuntime(paid,"ONLINE",databaseLocalDateTime("SELECT NOW(6)").withNano(0),0);
                long gift=readerDevice(root,"OWNED","ACTIVE","GIFT",null,"DEVICE");readerRuntime(gift,"OFFLINE",databaseLocalDateTime("SELECT NOW(6)").withNano(0),0);
                readerDevice(root,"OWNED","ACTIVE","TRIAL",null,"DEVICE");readerDevice(root,"OWNED","INVENTORY","ORDER",null,"DEVICE");
                long excluded=readerDevice(root,"OWNED","ACTIVE","ORDER",purchase.order,"DEVICE");
                assertThat(jdbc.update("UPDATE nx_user_device SET source_environment='SANDBOX',run_id=? WHERE id=? AND user_id=?",unique()+"sandbox-device",excluded,root)).isEqualTo(1);
                readerDevice(foreign,"OWNED","ACTIVE","ORDER",purchase.order,"DEVICE");
            });
            long root=accounts.get(0),overlap=accounts.get(1),hidden=accounts.get(2),queue=accounts.get(3),foreign=accounts.get(4);
            Fixture purchase=fixtures.get(0);Map<String,String> saved=outsideEvidence(purchase);
            assertThat(saved.get("capture_mode")).isEqualTo("NEW_SUCCESS");
            assertThat(outside.queryForObject("SELECT payment_status FROM nx_order WHERE order_no=? AND user_id=?",String.class,purchase.order,root)).isEqualTo("PAID");
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_user_device WHERE user_id=? AND source_order_no=? AND source_environment='PRODUCTION' AND run_id=''",Long.class,root,purchase.order)).isEqualTo(1);
            int recordedAuditCalls=auditCalls();
            for(int fresh=0;fresh<2;fresh++) {
                enrichedFinancialScopes.clear();
                Result result=freshEnrichedStatsAs(actors[0],statsQuery(ReadMode.PERSONAL,null,Basis.CURRENT_CUSTOMER_HISTORY,"USDT"));
                assertThat(result.currentCustomers()).extracting(c->c.customerId()).containsExactly(root,overlap);
                assertThat(result.toString()).doesNotContain("customerId="+hidden,"customerId="+queue,"customerId="+foreign,fixtures.get(2).key);
                assertThat(result.currentMetrics().ownLifetime()).singleElement().satisfies(c->{assertThat(c.deposits().observedAmount()).isEqualByComparingTo("10");assertThat(c.purchases().observedAmount()).isEqualByComparingTo("10");assertThat(c.deposits().confirmedAmount()).isNull();});
                assertThat(result.currentCustomers()).filteredOn(c->c.customerId()==root).singleElement().satisfies(c->{
                    assertThat(c.metrics().invitations().directCustomers().confirmedValue()).isEqualTo(2);
                    assertThat(c.metrics().invitations().descendantCustomers().confirmedValue()).isEqualTo(3);
                    assertThat(c.metrics().invitations().descendantDeposits()).singleElement().satisfies(m->assertThat(m.deposits().observedAmount()).isEqualByComparingTo("20"));
                    assertThat(c.first().state()).isEqualTo(SupportAnalyticsStats.FirstState.UNKNOWN);
                });
                assertThat(result.currentCustomers()).filteredOn(c->c.customerId()==overlap).singleElement().satisfies(c->{
                    assertThat(c.metrics().invitations().descendantCustomers().confirmedValue()).isEqualTo(1);
                    assertThat(c.metrics().invitations().descendantDeposits()).singleElement().satisfies(m->assertThat(m.deposits().observedAmount()).isEqualByComparingTo("10"));
                });
                var stock=result.currentMetrics().devices();assertThat(stock.held().confirmedValue()).isEqualTo(4);
                assertThat(stock.partitions()).filteredOn(p->p.dimension().equals("CONNECTION")).extracting(p->p.devices().confirmedValue()).containsExactly(1L,1L,1L,1L);
                assertThat(nativeAcquisition(stock,SupportAnalyticsStats.Acquisition.PAID_PURCHASE)).isEqualTo(1);assertThat(nativeAcquisition(stock,SupportAnalyticsStats.Acquisition.UNKNOWN)).isEqualTo(3);
                assertThat(enrichedFinancialScopes).containsExactly(List.of(root,overlap),List.of(hidden,queue));
                Snapshot actual=finance.readHistory(List.of(root));assertThat(actual.facts()).singleElement().satisfies(f->{assertThat(f.kind()).isEqualTo(Kind.DEVICE_PURCHASE);assertThat(f.amount()).isEqualByComparingTo("10");assertThat(f.historicalEnvironmentStatus()).isEqualTo(Status.UNKNOWN);});
                assertUnknownCoverage(actual);assertThat(outsideEvidence(purchase)).isEqualTo(saved);
            }
            enrichedFinancialScopes.clear();
            Result asset=freshEnrichedStatsAs(actors[0],statsQuery(ReadMode.PERSONAL,null,Basis.CURRENT_ASSET,"USDT"));
            assertThat(nativeAcquisition(asset.currentMetrics().devices(),SupportAnalyticsStats.Acquisition.PAID_PURCHASE)).isEqualTo(1);
            assertThat(asset.financialSummary().status()).isEqualTo(SupportAnalyticsStats.Status.UNAVAILABLE);assertThat(asset.coverage()).isEmpty();
            assertThat(asset.currentMetrics().firstConfirmed().status()).isEqualTo(SupportAnalyticsStats.Status.UNAVAILABLE);
            assertThat(asset.currentMetrics().ownLifetime()).singleElement().satisfies(c->{assertThat(c.deposits().observedAmount()).isNull();assertThat(c.purchases().observedAmount()).isNull();});
            assertThat(asset.currentCustomers()).allSatisfy(c->{assertThat(c.first().observedCandidate()).isNull();assertThat(c.metrics().lifetimeStatus()).isEqualTo(SupportAnalyticsStats.Status.UNAVAILABLE);assertThat(c.metrics().invitations().descendantDeposits()).singleElement().satisfies(m->assertThat(m.deposits().observedAmount()).isNull());});
            assertThat(enrichedFinancialScopes).containsExactly(List.of(root,overlap));
            assertThat(auditCalls()).isEqualTo(recordedAuditCalls);verify(audit,times(3)).recordRequired(any(AuditLogWriteRequest.class));
        } finally {
            try {cleanupOwnSourceFixtures(fixtures);}
            finally {try {cleanupEnrichedOwnedRows(accounts);}finally {try {cleanupOwnAccounts(accounts);}finally {assertRollbackReadback(before,accounts);}}}
        }
    }

    @Test
    void enrichedActivityReadsStoredWindowAndInteractiveLoginRowsWithoutAdvancingCoverage() {
        var sharedBefore=outsideMetricActivityRows();
        try {
            rollbackFixtures(accounts -> {
                long baselineActor=statsActor("ALL"),actor=statsActor("ALL"),agent=statsActor("SERVICE");
                Integer configuredDays=jdbc.queryForObject("SELECT activity_window_days FROM nx_support_rules WHERE id=1",Integer.class);
                if(configuredDays==null) {
                    Result unconfigured=enrichedStatsAs(baselineActor,statsQuery(ReadMode.ALL,null,Basis.CURRENT_ASSET,null));
                    assertThat(unconfigured.currentMetrics().activity().window().status()).isEqualTo(SupportAnalyticsStats.Status.UNKNOWN);
                    assertThat(jdbc.update("UPDATE nx_support_rules SET activity_window_days=2 WHERE id=1")).isEqualTo(1);
                }
                int days=configuredDays==null?2:configuredDays;
                assertThat(days).as("Explicit rollback-only fixture window, never a production default").isPositive();
                LocalDateTime watermark=databaseLocalDateTime("SELECT DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE)");
                assertThat(jdbc.update("UPDATE nx_support_activity_coverage SET coverage_start_at=?,observed_through_at=? WHERE id=1",watermark.minusDays(days+2L),watermark)).isEqualTo(1);
                var coverage=jdbc.queryForObject("SELECT * FROM nx_support_activity_coverage WHERE id=1",(rs,n)->strings(rs));
                Result baseline=enrichedStatsAs(baselineActor,statsQuery(ReadMode.ALL,null,Basis.CURRENT_ASSET,null));
                long bound=newAccount(accounts,0),pending=newAccount(accounts,0),anomaly=newAccount(accounts,0);
                statsBind(bound,agent,false);statsBind(anomaly,agent,true);
                nativeLoginEvent(bound,1,watermark.minusHours(1));nativeLoginEvent(pending,1,watermark.minusDays(days+1L));nativeLoginEvent(anomaly,1,watermark.plusMinutes(1));
                Result result=enrichedStatsAs(actor,statsQuery(ReadMode.ALL,null,Basis.CURRENT_ASSET,null));
                assertThat(result.currentCustomers()).filteredOn(c->accounts.contains(c.customerId())).hasSize(3).allSatisfy(c->{
                    if(c.customerId()==bound){assertThat(c.category()).isEqualTo(SupportAnalyticsStats.Category.BOUND);assertThat(c.metrics().activity().state()).isEqualTo(SupportAnalyticsStats.WindowState.ACTIVE);}
                    else if(c.customerId()==pending){assertThat(c.category()).isEqualTo(SupportAnalyticsStats.Category.PENDING);assertThat(c.metrics().activity().state()).isEqualTo(SupportAnalyticsStats.WindowState.INACTIVE);}
                    else {assertThat(c.category()).isEqualTo(SupportAnalyticsStats.Category.ANOMALY);assertThat(c.metrics().activity().state()).isEqualTo(SupportAnalyticsStats.WindowState.INACTIVE);assertThat(c.metrics().activity().lastEffectiveAt()).isNull();}
                });
                assertThat(result.currentMetrics().activity().window().days()).isEqualTo(days);
                assertThat(result.currentMetrics().activity().window().throughInclusive()).isEqualTo(watermark);
                assertThat(result.currentMetrics().activity().active().confirmedValue()).isEqualTo(baseline.currentMetrics().activity().active().confirmedValue()+1);
                for(var category:List.of(SupportAnalyticsStats.Category.BOUND,SupportAnalyticsStats.Category.PENDING,SupportAnalyticsStats.Category.ANOMALY)) {
                    var after=result.currentMetrics().activity().partitions().stream().filter(p->p.category()==category).findFirst().orElseThrow();
                    var before=baseline.currentMetrics().activity().partitions().stream().filter(p->p.category()==category).findFirst().orElseThrow();
                    assertThat(category==SupportAnalyticsStats.Category.BOUND?after.active().confirmedValue():after.inactive().confirmedValue()).isEqualTo((category==SupportAnalyticsStats.Category.BOUND?before.active().confirmedValue():before.inactive().confirmedValue())+1);
                }
                Map<String,String> persistedCoverage=jdbc.queryForObject("SELECT * FROM nx_support_activity_coverage WHERE id=1",(rs,n)->strings(rs));
                assertThat(persistedCoverage).isEqualTo(coverage);
                assertThat(jdbc.update("UPDATE nx_support_activity_coverage SET coverage_start_at=? WHERE id=1",watermark.minusHours(2))).isEqualTo(1);
                var shortened=jdbc.queryForObject("SELECT * FROM nx_support_activity_coverage WHERE id=1",(rs,n)->strings(rs));
                long missingActor=statsActor("ALL");
                Result missing=enrichedStatsAs(missingActor,statsQuery(ReadMode.ALL,null,Basis.CURRENT_ASSET,null));
                assertThat(missing.currentMetrics().activity().window().status()).isEqualTo(SupportAnalyticsStats.Status.PARTIAL);
                assertThat(missing.currentCustomers()).filteredOn(c->accounts.contains(c.customerId())).allSatisfy(c->assertThat(c.metrics().activity().state()).isEqualTo(c.customerId()==bound?SupportAnalyticsStats.WindowState.ACTIVE:SupportAnalyticsStats.WindowState.UNKNOWN));
                Map<String,String> persistedShortened=jdbc.queryForObject("SELECT * FROM nx_support_activity_coverage WHERE id=1",(rs,n)->strings(rs));
                assertThat(persistedShortened).isEqualTo(shortened);
                verifyNoInteractions(audit);
            });
        } finally {
            assertThat(outsideMetricActivityRows()).isEqualTo(sharedBefore);
            for(long event:nativeActivityFixtureIds)assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_activity_event WHERE id=?",Long.class,event)).isZero();
        }
    }

    @Test
    void enrichedPersonnelRetainsDisabledCategoriesAndIndependentQualificationsWhileGroupsUseSavedPeriodOwnership() {
        rollbackFixtures(accounts -> {
            long boss=statsActor("ALL"),owner=statsActor("SUPERVISOR"),otherOwner=statsActor("SUPERVISOR"),noGroup=statsActor("SUPERVISOR");
            long dual=statsActor("SERVICE"),disabled=statsActor("SERVICE"),removedService=statsActor("SERVICE"),removedSupervisor=statsActor("SUPERVISOR");
            long group=statsGroup(owner),otherGroup=statsGroup(otherOwner);statsMember(dual,group);statsMember(disabled,group);statsMember(removedService,group);statsMember(removedSupervisor,group);
            nativeQualification(dual,"SUPERVISOR","ENABLED");nativeQualification(removedService,"SUPERVISOR","DISABLED");nativeQualification(removedSupervisor,"SERVICE","ENABLED");
            assertThat(jdbc.update("UPDATE nx_support_agent_profile SET seat_type='GENERAL',enabled=0 WHERE admin_id=?",disabled)).isEqualTo(1);
            assertThat(jdbc.update("UPDATE nx_admin SET status=0 WHERE id=?",disabled)).isEqualTo(1);
            assertThat(jdbc.update("UPDATE nx_support_account_qualification_history SET state='DISABLED' WHERE admin_id=? AND qualification_kind='SERVICE' AND ends_at IS NULL",disabled)).isEqualTo(1);
            assertThat(jdbc.update("UPDATE nx_support_account_qualification_history SET state='REMOVED' WHERE admin_id=? AND qualification_kind='SERVICE' AND ends_at IS NULL",removedService)).isEqualTo(1);
            assertThat(jdbc.update("UPDATE nx_support_account_qualification_history SET state='REMOVED' WHERE admin_id=? AND qualification_kind='SUPERVISOR' AND ends_at IS NULL",removedSupervisor)).isEqualTo(1);
            assertThat(jdbc.update("INSERT INTO nx_support_agent_profile(admin_id,seat_type,position,service_types,tags,enabled,version) VALUES(?,'MANAGER','owned stats fixture','advisor','',1,1)",removedSupervisor)).isEqualTo(1);
            long customer=newAccount(accounts,0),disabledCustomer=newAccount(accounts,0),queue=newAccount(accounts,0);statsBind(customer,dual,false);statsBind(disabledCustomer,disabled,false);statsRoute(queue,group);
            Fixture payment=pending(Source.WALLET_ORDER,customer);Prepared prepared=prepare(payment);settle(payment,prepared,true,false);capture.record(prepared);var saved=evidence(payment);
            Result all=enrichedStatsAs(boss,statsQuery(ReadMode.ALL,null,Basis.CURRENT_ASSET,null));
            assertThat(all.personnel().accounts()).filteredOn(a->a.accountId()==dual).singleElement().satisfies(a->{assertThat(a.serviceAccount()).isTrue();assertThat(a.supervisorAccount()).isTrue();});
            assertThat(all.personnel().accounts()).filteredOn(a->a.accountId()==disabled).singleElement().satisfies(a->{assertThat(a.serviceAccount()).isTrue();assertThat(a.serviceQualification()).isEqualTo(SupportAnalyticsStats.QualificationState.DISABLED);assertThat(a.accountState()).isEqualTo(SupportAnalyticsStats.AccountState.DISABLED);assertThat(a.handoverRequired()).isTrue();});
            assertThat(all.personnel().accounts()).filteredOn(a->a.accountId()==removedService).singleElement().satisfies(a->{assertThat(a.serviceAccount()).isFalse();assertThat(a.supervisorAccount()).isTrue();});
            assertThat(all.personnel().accounts()).filteredOn(a->a.accountId()==removedSupervisor).singleElement().satisfies(a->{assertThat(a.supervisorAccount()).isFalse();assertThat(a.serviceAccount()).isTrue();});
            long categoryUnion=all.personnel().accounts().stream().filter(a->a.serviceAccount()||a.supervisorAccount()).count();assertThat(all.personnel().people().observedValue()).isEqualTo(categoryUnion);
            assertThat(all.personnel().accounts()).filteredOn(a->List.of(owner,otherOwner,noGroup,dual,removedService).contains(a.accountId())).allSatisfy(a->assertThat(a.supervisorAccount()).isTrue());
            Result managed=enrichedStatsAs(owner,statsQuery(ReadMode.MANAGED,group,Basis.CURRENT_ASSET,null));
            assertThat(managed.currentCustomers()).extracting(c->c.customerId()).containsExactlyInAnyOrder(customer,disabledCustomer,queue);
            assertThat(managed.groups()).singleElement().satisfies(g->{assertThat(g.groupId()).isEqualTo(group);assertThat(g.customers().total()).isEqualTo(3);assertThat(g.customers().bound()).isEqualTo(2);assertThat(g.customers().pending()).isEqualTo(1);});
            assertThat(managed.personnel().serviceAccounts().confirmedValue()).isEqualTo(3);assertThat(managed.personnel().groupMembers().confirmedValue()).isEqualTo(3);
            assertThat(managed.personnel().supervisors().confirmedValue()).isEqualTo(1);assertThat(managed.personnel().people().confirmedValue()).isEqualTo(4);
            assertThat(managed.personnel().accounts()).noneMatch(a->a.accountId()==otherOwner||a.accountId()==noGroup);
            Result empty=enrichedStatsAs(noGroup,statsQuery(ReadMode.MANAGED,null,Basis.CURRENT_ASSET,null));assertThat(empty.currentCustomers()).isEmpty();assertThat(empty.groups()).isEmpty();assertThat(empty.currentScope().total()).isZero();
            databasePause();LocalDateTime transferAt=databaseLocalDateTime("SELECT UTC_TIMESTAMP()");
            assertThat(jdbc.update("UPDATE nx_support_agent_user_assignment SET status='INACTIVE',ends_at=? WHERE user_id=? AND agent_admin_id=? AND status='ACTIVE'",transferAt,customer,dual)).isEqualTo(1);
            long next=statsActor("SERVICE");statsMember(next,otherGroup);statsBind(customer,next,transferAt);
            Result original=enrichedStatsAs(owner,statsPeriod(ReadMode.MANAGED,group)),current=enrichedStatsAs(otherOwner,statsPeriod(ReadMode.MANAGED,otherGroup));
            assertThat(original.groups()).singleElement().satisfies(g->{assertThat(g.customers().total()).isEqualTo(2);assertThat(g.period().currencies()).singleElement().satisfies(c->assertThat(c.purchases().observedAmount()).isEqualByComparingTo("10"));});
            assertThat(original.currentCustomers()).noneMatch(c->c.customerId()==customer);assertThat(original.restrictedSummary().customers().observedValue()).isEqualTo(1);
            assertThat(original.toString()).doesNotContain("customerId="+customer,payment.order,payment.factId());
            assertThat(current.groups()).singleElement().satisfies(g->{assertThat(g.customers().total()).isEqualTo(1);assertThat(g.current().ownLifetime()).singleElement().satisfies(c->assertThat(c.purchases().observedAmount()).isEqualByComparingTo("10"));assertThat(g.period().currencies()).singleElement().satisfies(c->{assertThat(c.purchases().observedAmount()).isEqualByComparingTo("0");assertThat(c.purchases().confirmedAmount()).isNull();assertThat(c.purchases().status()).isEqualTo(SupportAnalyticsStats.Status.PARTIAL);});});
            assertThat(evidence(payment)).isEqualTo(saved);verify(audit,times(1)).recordRequired(any(AuditLogWriteRequest.class));
        });
    }

    @Test
    void statsCurrentPlatformCategoriesAreExclusiveAndDisabledBindingRequiresHandover() {
        rollbackFixtures(accounts -> {
            Object resource=readerRrResource();
            long baselineActor=statsActor("ALL"), actor=statsActor("ALL"), disabled=statsActor("SERVICE");
            Query query=statsQuery(ReadMode.ALL,null,Basis.CURRENT_ASSET,null);
            Result before=statsAs(baselineActor,query);
            long bound=newAccount(accounts,0), pending=newAccount(accounts,0), anomaly=newAccount(accounts,0);
            long sandbox=newAccount(accounts,1);
            statsBind(bound,disabled,false);statsBind(anomaly,disabled,true);
            assertThat(jdbc.update("UPDATE nx_admin SET status=0 WHERE id=?",disabled)).isEqualTo(1);
            assertThat(jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?",disabled)).isEqualTo(1);
            // A distinct actor prevents the baseline SQL's session cache from hiding the fixture INSERTs.
            Result result=statsAs(actor,query);
            assertThat(result.currentScope().total()).isEqualTo(before.currentScope().total()+3);
            assertThat(result.currentScope().bound()).isEqualTo(before.currentScope().bound()+1);
            assertThat(result.currentScope().pending()).isEqualTo(before.currentScope().pending()+1);
            assertThat(result.currentScope().anomaly()).isEqualTo(before.currentScope().anomaly()+1);
            assertThat(result.currentScope().total()).isEqualTo(result.currentScope().bound()
                +result.currentScope().pending()+result.currentScope().anomaly());
            assertThat(result.currentCustomers()).filteredOn(c -> c.customerId()==bound).singleElement().satisfies(c -> {
                assertThat(c.category()).isEqualTo(SupportAnalyticsStats.Category.BOUND);
                assertThat(c.handoverRequired()).isTrue();
            });
            assertThat(result.currentCustomers()).filteredOn(c -> c.customerId()==pending).singleElement().satisfies(c -> {
                assertThat(c.category()).isEqualTo(SupportAnalyticsStats.Category.PENDING);
                assertThat(c.placement()).isEqualTo(SupportAnalyticsStats.Placement.GLOBAL_QUEUE);
            });
            assertThat(result.currentCustomers()).filteredOn(c -> c.customerId()==anomaly).singleElement()
                .satisfies(c -> assertThat(c.category()).isEqualTo(SupportAnalyticsStats.Category.ANOMALY));
            assertThat(result.currentCustomers()).noneMatch(c -> c.customerId()==sandbox);
            assertThat(result.financialSummary().status()).isEqualTo(SupportAnalyticsStats.Status.UNAVAILABLE);
            assertThat(physicalResource()).isSameAs(resource);
        });
    }

    @Test
    void statsManagedGroupFiltersAndOwnerlessSupervisorNeverFallbackOrReadOtherGroups() {
        rollbackFixtures(accounts -> {
            long owner=statsActor("SUPERVISOR"), otherOwner=statsActor("SUPERVISOR"), noGroup=statsActor("SUPERVISOR");
            long agent=statsActor("SERVICE"), otherAgent=statsActor("SERVICE");
            long group=statsGroup(owner), otherGroup=statsGroup(otherOwner);
            statsMember(agent,group);statsMember(otherAgent,otherGroup);
            long bound=newAccount(accounts,0), queue=newAccount(accounts,0), foreign=newAccount(accounts,0);
            statsBind(bound,agent,false);statsBind(foreign,otherAgent,false);statsRoute(queue,group);
            Result result=statsAs(owner,statsQuery(ReadMode.MANAGED,group,Basis.CURRENT_ASSET,null));
            assertThat(result.currentCustomers()).extracting(c -> c.customerId()).containsExactlyInAnyOrder(bound,queue);
            assertThat(result.currentScope().total()).isEqualTo(2L);
            assertThat(result.currentScope().bound()).isEqualTo(1L);
            assertThat(result.currentScope().pending()).isEqualTo(1L);
            assertThat(result.currentScope().anomaly()).isZero();
            assertThat(result.currentCustomers()).filteredOn(c -> c.customerId()==queue).singleElement()
                .satisfies(c -> assertThat(c.placement()).isEqualTo(SupportAnalyticsStats.Placement.GROUP_QUEUE));
            Result empty=statsAs(noGroup,statsQuery(ReadMode.MANAGED,null,Basis.CURRENT_ASSET,null));
            assertThat(empty.currentScope().mode()).isEqualTo(ReadMode.MANAGED);
            assertThat(empty.currentScope().total()).isZero();assertThat(empty.currentCustomers()).isEmpty();
            // Authorization failure is last: its original transaction must remain rollback-only.
            assertThatThrownBy(() -> statsAs(owner,statsQuery(ReadMode.MANAGED,otherGroup,Basis.CURRENT_ASSET,null)))
                .isInstanceOf(BizException.class).hasMessage("SUPPORT_GROUP_NOT_FOUND")
                .satisfies(ex -> assertThat(((BizException)ex).getCode()).isEqualTo(404));
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"PURCHASE_FIRST","DEPOSIT_FIRST"})
    void statsObservedDepositAndPurchaseFirstCandidatesStaySeparated(String firstKind) {
        rollbackFixtures(accounts -> {
            Object resource=readerRrResource();
            long owner=statsActor("SUPERVISOR"), agent=statsActor("SERVICE"), group=statsGroup(owner);
            statsMember(agent,group);long customer=newAccount(accounts,0);statsBind(customer,agent,false);
            Source first=firstKind.equals("PURCHASE_FIRST")?Source.WALLET_ORDER:Source.HDPAY;
            Fixture firstPayment=pending(first,customer);Prepared prepared=prepare(firstPayment);
            settle(firstPayment,prepared,true);capture.record(prepared);
            Fixture secondPayment=pending(first==Source.WALLET_ORDER?Source.HDPAY:Source.WALLET_ORDER,customer);
            Prepared second=prepare(secondPayment);settle(secondPayment,second,true);capture.record(second);
            Result result=statsAs(agent,statsQuery(ReadMode.PERSONAL,null,Basis.CURRENT_CUSTOMER_HISTORY,"USDT"));
            assertThat(result.currentCustomers()).singleElement().satisfies(c -> {
                assertThat(c.customerId()).isEqualTo(customer);
                assertThat(c.first().observedCandidate().kind()).isEqualTo(firstKind.equals("PURCHASE_FIRST")?"DEVICE_PURCHASE":"DEPOSIT");
                assertThat(c.first().observedCandidate().currency()).isEqualTo("USDT");
                assertThat(c.first().observedCandidate().attribution().agent()).isEqualTo(SupportAnalyticsStats.AttributionStatus.KNOWN);
                assertThat(c.first().observedCandidate().attribution().group()).isEqualTo(SupportAnalyticsStats.AttributionStatus.KNOWN);
            });
            var totals=statsCurrency(result,"USDT");
            assertThat(totals.deposits().observedAmount()).isEqualByComparingTo("10.000000");
            assertThat(totals.purchases().observedAmount()).isEqualByComparingTo("10.000000");
            assertThat(totals.deposits().observedEvents()).isEqualTo(1L);
            assertThat(totals.purchases().observedEvents()).isEqualTo(1L);
            assertThat(result.financialSummary().firstCandidates().observedValue()).isEqualTo(1L);
            assertStatsUncertified(result);assertThat(physicalResource()).isSameAs(resource);
        });
    }

    @Test
    void statsFirstSelectionPrecedesMonthCurrencyAndHalfOpenQueryZoneBoundaries() {
        rollbackFixtures(accounts -> {
            long owner=statsActor("SUPERVISOR"), agent=statsActor("SERVICE"), group=statsGroup(owner);
            statsMember(agent,group);long customer=newAccount(accounts,0);statsBind(customer,agent,false);
            LocalDateTime older=databaseLocalDateTime("SELECT NOW()").withDayOfMonth(1).minusDays(1).withNano(0);
            String legacy=unique();
            assertThat(jdbc.update("""
                INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,created_at)
                VALUES(?,?,'CHAIN_TOPUP','NEX','IN',12.123456,12.123456,'SUCCESS',?)
                """,customer,legacy,older)).isEqualTo(1);
            long ledger=jdbc.queryForObject("SELECT id FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND asset='NEX'",Long.class,customer,legacy);
            assertThat(jdbc.update("""
                INSERT INTO nx_deposit_order(user_id,deposit_no,chain_name,chain_tx_hash,asset,amount,status,ledger_id,credited_at,created_at)
                VALUES(?,?,'BEP20',?,'NEX',12.123456,'CREDITED',?,?,?)
                """,customer,legacy,legacy,ledger,older,older)).isEqualTo(1);
            Fixture deposit=pending(Source.HDPAY,customer);Prepared dp=prepare(deposit);settle(deposit,dp,true);capture.record(dp);
            Fixture purchase=pending(Source.WALLET_ORDER,customer);Prepared pp=prepare(purchase);settle(purchase,pp,true);capture.record(pp);
            Snapshot money=finance.readHistory(List.of(customer));
            assertThat(money.facts()).filteredOn(f -> f.ledgerId()==ledger).singleElement().satisfies(f -> {
                assertThat(f.currency()).isEqualTo("NEX");assertThat(f.amount()).isEqualByComparingTo("12.123456");
            });
            Fact from=money.facts().stream().filter(f -> f.factId().equals(deposit.factId())).findFirst().orElseThrow();
            Fact to=money.facts().stream().filter(f -> f.factId().equals(purchase.factId())).findFirst().orElseThrow();
            LocalDateTime start=from.succeededAt().atZone(ZoneId.of(money.businessZone())).withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
            LocalDateTime end=to.succeededAt().atZone(ZoneId.of(money.businessZone())).withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
            assertThat(start).isBefore(end);
            Result period=statsAs(agent,new Query(ReadMode.PERSONAL,null,null,Basis.PERIOD_EVENT,start,end,"UTC","USDT"));
            assertThat(period.currentCustomers()).singleElement().satisfies(c -> {
                assertThat(c.first().observedCandidate().currency()).isEqualTo("NEX");
                assertThat(c.first().observedCandidate().succeededAt()).isEqualTo(older.atZone(ZoneId.of(money.businessZone()))
                    .withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime());
            });
            assertThat(period.financialSummary().firstCandidates().observedValue()).isZero();
            assertThat(period.financialSummary().currencies()).extracting(c -> c.currency()).containsExactly("USDT");
            assertThat(statsCurrency(period,"USDT").deposits().observedAmount()).isEqualByComparingTo("10");
            assertThat(statsCurrency(period,"USDT").purchases().observedAmount()).isEqualByComparingTo("0");
            Result all=statsAs(agent,statsQuery(ReadMode.PERSONAL,null,Basis.CURRENT_CUSTOMER_HISTORY,null));
            assertThat(all.financialSummary().currencies()).extracting(c -> c.currency()).containsExactly("NEX","USDT");
            assertThat(statsCurrency(all,"NEX").deposits().observedAmount()).isEqualByComparingTo("12.123456");
            assertThat(statsCurrency(all,"USDT").deposits().observedAmount()).isEqualByComparingTo("10");
            assertThat(statsCurrency(all,"USDT").purchases().observedAmount()).isEqualByComparingTo("10");
            assertStatsUncertified(period);assertStatsUncertified(all);
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"PURCHASE_FIRST","DEPOSIT_FIRST"})
    void registeredFirstIsConfirmedOnceWithSeparateSourceAmountsAndNoRefundCertification(String firstKind) {
        rollbackFixtures(accounts -> {
            Object resource=readerRrResource();
            long agent=statsActor("SERVICE"), customer=newAccount(accounts,0);
            birth.registerNewAccount(customer);statsBind(customer,agent,false);
            readerDevice(customer,"OWNED","ACTIVE","GIFT",null,"BOX");
            pending(Source.TRIAL_CONVERT,customer);
            Query query=statsQuery(ReadMode.PERSONAL,null,Basis.CURRENT_CUSTOMER_HISTORY,"USDT");
            Result empty=statsAs(agent,query);
            assertThat(empty.currentCustomers()).singleElement().satisfies(c -> {
                assertThat(c.first().state()).isEqualTo(SupportAnalyticsStats.FirstState.NONE);
                assertThat(c.first().observedCandidate()).isNull();
            });
            assertThat(empty.financialSummary().firstCandidates().confirmedValue()).isZero();

            Source first=firstKind.equals("PURCHASE_FIRST")?Source.WALLET_ORDER:Source.HDPAY;
            Fixture firstPayment=pending(first,customer);Prepared fp=prepare(firstPayment);
            settle(firstPayment,fp,true);capture.record(fp);
            Fixture later=pending(first==Source.WALLET_ORDER?Source.HDPAY:Source.WALLET_ORDER,customer);
            Prepared lp=prepare(later);settle(later,lp,true);capture.record(lp);
            Map<String,String> original=evidence(firstPayment);
            capture.record(prepare(firstPayment));
            assertThat(evidence(firstPayment)).isEqualTo(original);

            for(int read=0;read<2;read++) {
                Snapshot facts=finance.readHistory(List.of(customer));
                assertThat(facts.firstHistory()).singleElement().satisfies(h -> {
                    assertThat(h.customerId()).isEqualTo(customer);assertThat(h.status()).isEqualTo(Status.READY);
                });
                Result result=statsAs(agent,query);
                assertThat(result.currentCustomers()).singleElement().satisfies(c -> {
                    assertThat(c.first().state()).isEqualTo(SupportAnalyticsStats.FirstState.CONFIRMED);
                    assertThat(c.first().status()).isEqualTo(SupportAnalyticsStats.Status.AVAILABLE);
                    assertThat(c.first().observedCandidate().kind()).isEqualTo(firstKind.equals("PURCHASE_FIRST")?"DEVICE_PURCHASE":"DEPOSIT");
                });
                assertThat(result.financialSummary().firstCandidates().confirmedValue()).isEqualTo(1L);
                assertThat(result.financialSummary().firstSources()).singleElement().satisfies(s -> {
                    assertThat(s.currency()).isEqualTo("USDT");
                    var selected=first==Source.WALLET_ORDER?s.purchases():s.deposits();
                    var other=first==Source.WALLET_ORDER?s.deposits():s.purchases();
                    assertThat(selected.confirmedAmount()).isEqualByComparingTo("10");
                    assertThat(selected.confirmedEvents()).isEqualTo(1L);
                    assertThat(other.confirmedAmount()).isEqualByComparingTo("0");
                    assertThat(other.confirmedEvents()).isZero();
                });
                assertThat(statsCurrency(result,"USDT").deposits().observedAmount()).isEqualByComparingTo("10");
                assertThat(statsCurrency(result,"USDT").purchases().observedAmount()).isEqualByComparingTo("10");
                assertThat(statsCurrency(result,"USDT").net().confirmedAmount()).isNull();
                assertThat(statsCurrency(result,"USDT").net().status()).isEqualTo(SupportAnalyticsStats.Status.UNKNOWN);
            }
            assertThat(physicalResource()).isSameAs(resource);
        });
    }

    @Test
    void registeredHistoryDistinguishesNoPaymentLegacyMissingCaptureAndCorruptCustomerWithoutPoisoningKnownPeer() {
        rollbackFixtures(accounts -> {
            long agent=statsActor("SERVICE");
            long bad=newAccount(accounts,0), known=newAccount(accounts,0), legacy=newAccount(accounts,0);
            long none=newAccount(accounts,0), missing=newAccount(accounts,0);
            for(long customer:List.of(bad,known,none,missing))birth.registerNewAccount(customer);
            for(long customer:List.of(bad,known,legacy,none,missing))statsBind(customer,agent,false);
            Fixture a=pending(Source.WALLET_ORDER,bad);Prepared ap=prepare(a);settle(a,ap,true);capture.record(ap);
            Fixture b=pending(Source.WALLET_ORDER,known);Prepared bp=prepare(b);settle(b,bp,true);capture.record(bp);
            Fixture old=pending(Source.HDPAY,legacy);Prepared op=prepare(old);settle(old,op,true);capture.record(op);
            Fixture gap=pending(Source.HDPAY,missing);Prepared gp=prepare(gap);settle(gap,gp,true);
            Map<String,String> savedB=evidence(b);
            assertThat(jdbc.update("UPDATE nx_support_payment_attribution SET source_fact_json=? WHERE fact_id=? AND customer_id=?",
                savedB.get("source_fact_json"),a.factId(),bad)).isEqualTo(1);
            Snapshot facts=finance.readHistory(List.of(bad,known,legacy,none,missing));
            assertThat(facts.firstHistory()).hasSize(5);
            Map<Long,Status> statuses=new LinkedHashMap<>();
            for(var h:facts.firstHistory())assertThat(statuses.put(h.customerId(),h.status())).isNull();
            assertThat(statuses).containsEntry(known,Status.READY).containsEntry(none,Status.READY)
                .containsEntry(bad,Status.UNKNOWN).containsEntry(legacy,Status.UNKNOWN).containsEntry(missing,Status.UNKNOWN);
            Result result=statsAs(agent,statsQuery(ReadMode.PERSONAL,null,Basis.CURRENT_CUSTOMER_HISTORY,"USDT"));
            assertThat(result.currentCustomers()).hasSize(5).allSatisfy(c -> {
                var expected=c.customerId()==known?SupportAnalyticsStats.FirstState.CONFIRMED
                    :c.customerId()==none?SupportAnalyticsStats.FirstState.NONE:SupportAnalyticsStats.FirstState.UNKNOWN;
                assertThat(c.first().state()).isEqualTo(expected);
            });
            assertThat(result.financialSummary().firstCandidates().confirmedValue()).isEqualTo(1L);
            assertThat(result.financialSummary().firstCandidates().status()).isEqualTo(SupportAnalyticsStats.Status.PARTIAL);
            assertThat(evidence(b)).isEqualTo(savedB);
        });
    }

    @Test
    void supportedWholeOrderRefundDoesNotEraseTheNewCustomersPurchaseFirstOrCertifyMissingDepositRefunds() {
        rollbackFixtures(accounts -> {
            long agent=statsActor("SERVICE"), customer=newAccount(accounts,0);
            birth.registerNewAccount(customer);statsBind(customer,agent,false);
            Fixture refund=pending(Source.ORDER_REFUND,customer);
            Prepared rp=prepare(refund);settle(refund,rp,true);capture.record(rp);
            Query query=statsQuery(ReadMode.PERSONAL,null,Basis.CURRENT_CUSTOMER_HISTORY,"USDT");
            for(int read=0;read<2;read++) {
                Result result=statsAs(agent,query);
                assertThat(result.currentCustomers()).singleElement().satisfies(c -> {
                    assertThat(c.first().state()).isEqualTo(SupportAnalyticsStats.FirstState.CONFIRMED);
                    assertThat(c.first().observedCandidate().kind()).isEqualTo("DEVICE_PURCHASE");
                });
                assertThat(result.financialSummary().firstCandidates().confirmedValue()).isEqualTo(1L);
                assertThat(result.financialSummary().firstSources()).singleElement().satisfies(s -> {
                    assertThat(s.purchases().confirmedEvents()).isEqualTo(1L);
                    assertThat(s.purchases().confirmedAmount()).isEqualByComparingTo("10");
                });
                assertThat(statsCurrency(result,"USDT").purchaseRefunds().observedAmount()).isEqualByComparingTo("10");
                assertThat(statsCurrency(result,"USDT").purchases().observedAmount()).isEqualByComparingTo("10");
                assertThat(statsCurrency(result,"USDT").net().confirmedAmount()).isNull();
            }
        });
    }

    @Test
    void contradictoryCardSettlementCannotReuseKnownAttributionForPeriodMoneyAndDoesNotRejectAnotherCustomer() {
        rollbackFixtures(accounts -> {
            long owner=statsActor("SUPERVISOR"), agent=statsActor("SERVICE"), group=statsGroup(owner);
            statsMember(agent,group);
            long bad=newAccount(accounts,0), known=newAccount(accounts,0);
            birth.registerNewAccount(bad);birth.registerNewAccount(known);
            statsBind(bad,agent,false);statsBind(known,agent,false);
            Fixture card=pending(Source.CARD_TOPUP,bad);Prepared cp=prepare(card);settle(card,cp,false);capture.record(cp);
            Fixture purchase=pending(Source.WALLET_ORDER,known);Prepared pp=prepare(purchase);settle(purchase,pp,true);capture.record(pp);
            Map<String,String> savedCard=evidence(card), savedPurchase=evidence(purchase);
            assertThat(savedCard.get("agent_status")).isEqualTo("KNOWN");
            assertThat(savedCard.get("group_status")).isEqualTo("KNOWN");
            assertThat(jdbc.update("UPDATE nx_topup_card_settlement SET amount_usdt=9 WHERE settlement_event_id=? AND payment_no=? AND user_id=?",
                card.name,card.key,bad)).isEqualTo(1);
            Snapshot facts=finance.readHistory(List.of(bad,known));
            assertThat(facts.issues()).anyMatch(i -> i.source()==Source.CARD_TOPUP && i.reason().equals("SETTLEMENT_MISMATCH")
                && Long.valueOf(bad).equals(i.customerId()) && card.factId().equals(i.sourceId()));
            assertThat(facts.facts()).extracting(Fact::factId).doesNotContain(card.factId()).contains(purchase.factId());
            assertThat(facts.firstHistory()).filteredOn(h -> h.customerId()==bad).singleElement()
                .satisfies(h -> assertThat(h.status()).isEqualTo(Status.UNKNOWN));
            assertThat(facts.firstHistory()).filteredOn(h -> h.customerId()==known).singleElement()
                .satisfies(h -> assertThat(h.status()).isEqualTo(Status.READY));
            for(Result result:List.of(statsAs(agent,statsPeriod(ReadMode.PERSONAL,null)),
                    statsAs(owner,statsPeriod(ReadMode.MANAGED,group)))) {
                assertThat(statsCurrency(result,"USDT").deposits().observedAmount()).isNull();
                assertThat(statsCurrency(result,"USDT").deposits().status()).isEqualTo(SupportAnalyticsStats.Status.UNKNOWN);
                assertThat(result.financialSummary().reasons()).contains("PERIOD_EVENT_COVERAGE_UNVERIFIED");
                assertThat(statsCurrency(result,"USDT").purchases().observedAmount()).isEqualByComparingTo("10");
                assertThat(result.financialSummary().firstCandidates().confirmedValue()).isEqualTo(1L);
                assertThat(result.currentCustomers()).filteredOn(c -> c.customerId()==bad).singleElement()
                    .satisfies(c -> assertThat(c.first().state()).isEqualTo(SupportAnalyticsStats.FirstState.UNKNOWN));
            }
            assertThat(evidence(card)).isEqualTo(savedCard);
            assertThat(evidence(purchase)).isEqualTo(savedPurchase);
        });
    }

    @Test
    void statsTransferAndRegroupKeepHistoricalGroupMoneyWithoutCurrentCustomerIdentity() {
        rollbackFixtures(accounts -> {
            long owner=statsActor("SUPERVISOR"), otherOwner=statsActor("SUPERVISOR");
            long agent=statsActor("SERVICE"), otherAgent=statsActor("SERVICE");
            long group=statsGroup(owner), otherGroup=statsGroup(otherOwner);
            statsMember(agent,group);statsMember(otherAgent,otherGroup);
            long customer=newAccount(accounts,0);statsBind(customer,agent,false);
            Fixture f=pending(Source.WALLET_ORDER,customer);Prepared prepared=prepare(f);settle(f,prepared,true);capture.record(prepared);
            Map<String,String> saved=evidence(f);
            assertThat(saved.get("agent_status")).isEqualTo("KNOWN");assertThat(saved.get("group_status")).isEqualTo("KNOWN");
            // Assignment DATETIME(0) follows production's UTC_TIMESTAMP(), not a rounded future microsecond.
            // Advance past capture first so the shared transfer boundary cannot precede its saved UTC instant.
            databasePause();
            LocalDateTime transferAt=databaseLocalDateTime("SELECT UTC_TIMESTAMP()");
            assertThat(transferAt).isAfter(databaseLocalDateTime("SELECT capture_db_utc FROM nx_support_payment_attribution WHERE fact_id=?",f.factId()));
            assertThat(jdbc.update("UPDATE nx_support_agent_user_assignment SET status='INACTIVE',ends_at=?,version=version+1 WHERE user_id=? AND agent_admin_id=? AND status='ACTIVE'",transferAt,customer,agent)).isEqualTo(1);
            statsBind(customer,otherAgent,transferAt);
            assertThat(jdbc.update("UPDATE nx_support_group_member_history SET ends_at=? WHERE agent_admin_id=? AND ends_at IS NULL",transferAt,agent)).isEqualTo(1);
            assertThat(jdbc.update("""
                INSERT INTO nx_support_group_member_history(agent_admin_id,group_id,starts_at,version,reason,operation_id)
                VALUES(?,?,?,1,'owned stats transfer fixture',?)
                """,agent,otherGroup,transferAt,unique())).isEqualTo(1);
            assertThat(databaseLocalDateTime("SELECT ends_at FROM nx_support_agent_user_assignment WHERE user_id=? AND agent_admin_id=? AND status='INACTIVE'",customer,agent)).isEqualTo(transferAt);
            assertThat(databaseLocalDateTime("SELECT starts_at FROM nx_support_agent_user_assignment WHERE user_id=? AND agent_admin_id=? AND status='ACTIVE'",customer,otherAgent)).isEqualTo(transferAt);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_agent_user_assignment WHERE user_id=? AND is_deleted=0 AND starts_at<=UTC_TIMESTAMP(6) AND (ends_at IS NULL OR ends_at>UTC_TIMESTAMP(6))",Long.class,customer)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT group_id FROM nx_support_group_member_history WHERE agent_admin_id=? AND starts_at<=UTC_TIMESTAMP(6) AND ends_at IS NULL",Long.class,agent)).isEqualTo(otherGroup);
            Result personal=statsAs(agent,statsPeriod(ReadMode.PERSONAL,null));
            Result originalGroup=statsAs(owner,statsPeriod(ReadMode.MANAGED,group));
            Result currentGroup=statsAs(otherOwner,statsPeriod(ReadMode.MANAGED,otherGroup));
            for(Result historical:List.of(personal,originalGroup)) {
                assertThat(historical.currentCustomers()).isEmpty();assertThat(historical.currentScope().total()).isZero();
                assertThat(statsCurrency(historical,"USDT").purchases().observedAmount()).isEqualByComparingTo("10");
                assertThat(historical.restrictedSummary().customers().observedValue()).isEqualTo(1L);
                assertThat(historical.restrictedSummary().firstCandidates().observedValue()).isEqualTo(1L);
                assertThat(historical.toString()).doesNotContain("customerId="+customer,f.order,f.factId());
                assertStatsUncertified(historical);
            }
            assertThat(statsCurrency(currentGroup,"USDT").purchases().observedAmount()).isEqualByComparingTo("0");
            assertThat(currentGroup.currentCustomers()).extracting(c -> c.customerId()).containsExactly(customer);
            assertThat(evidence(f)).isEqualTo(saved);
        });
    }

    @Test
    void statsRejectedCanonicalCaptureCannotPoisonIndependentKnownLegacyEventAndMoney() {
        rollbackFixtures(accounts -> {
            long owner=statsActor("SUPERVISOR"), agent=statsActor("SERVICE"), group=statsGroup(owner);
            statsMember(agent,group);long customer=newAccount(accounts,0);statsBind(customer,agent,false);
            Fixture a=pending(Source.WALLET_ORDER,customer);Prepared ap=prepare(a);settle(a,ap,false);capture.record(ap);
            databasePause();
            Fixture b=pending(Source.WALLET_ORDER,customer);Prepared bp=prepare(b);settle(b,bp,false);capture.record(bp);
            Map<String,String> savedB=evidence(b);
            assertThat(savedB.get("agent_status")).isEqualTo("KNOWN");assertThat(savedB.get("group_status")).isEqualTo("KNOWN");
            // B is a genuinely settled, canonical transaction. Only A's JSON is changed; its scalar identity stays A.
            assertThat(jdbc.update("UPDATE nx_support_payment_attribution SET source_fact_json=? WHERE fact_id=? AND customer_id=?",
                savedB.get("source_fact_json"),a.factId(),customer)).isEqualTo(1);
            Snapshot history=finance.readHistory(List.of(customer));
            assertThat(history.facts()).extracting(Fact::factId).containsExactlyInAnyOrder(a.factId(),b.factId());
            assertThat(history.issues()).anyMatch(i -> i.source()==Source.WALLET_ORDER && a.factId().equals(i.sourceId())
                && i.reason().equals("CAPTURED_SOURCE_PROOF_MISMATCH"));
            assertThat(history.issues()).noneMatch(i -> b.factId().equals(i.sourceId()));
            Result personal=statsAs(agent,statsQuery(ReadMode.PERSONAL,null,Basis.CURRENT_CUSTOMER_HISTORY,"USDT"));
            assertThat(statsCurrency(personal,"USDT").purchases().observedAmount()).isEqualByComparingTo("20");
            assertThat(personal.currentCustomers()).singleElement().satisfies(c ->
                assertThat(c.first().observedCandidate().attribution().agent()).isEqualTo(SupportAnalyticsStats.AttributionStatus.UNKNOWN));
            assertThat(personal.financialSummary().attribution()).anyMatch(p -> p.layer().equals("GROUP")
                && p.status()==SupportAnalyticsStats.AttributionStatus.KNOWN && p.observedEvents()==1);
            assertThat(personal.financialSummary().attribution()).anyMatch(p -> p.layer().equals("GROUP")
                && p.status()==SupportAnalyticsStats.AttributionStatus.UNKNOWN && p.observedEvents()==1);
            Result managed=statsAs(owner,statsPeriod(ReadMode.MANAGED,group));
            assertThat(statsCurrency(managed,"USDT").purchases().observedAmount()).isEqualByComparingTo("10");
            assertThat(statsCurrency(managed,"USDT").purchases().observedEvents()).isEqualTo(1L);
            assertThat(managed.reasons()).contains("EVENT_ATTRIBUTION_UNVERIFIED","PERIOD_EVENT_COVERAGE_UNVERIFIED");
            assertThat(evidence(b)).isEqualTo(savedB);
            assertStatsUncertified(personal);assertStatsUncertified(managed);
        });
    }

    @Test
    void invitationReaderTraversesBeyondSevenLevelsAndKeepsInactiveOverlappingRoots() {
        rollbackFixtures(accounts -> {
            Object resource=readerRrResource();
            var chain=new ArrayList<Long>();
            for(int depth=0;depth<=9;depth++) {
                long customer=newAccount(accounts,0);
                chain.add(customer);
                if(depth>0) readerSponsor(customer,chain.get(depth-1));
            }
            assertThat(jdbc.update("UPDATE nx_user SET status='DISABLED' WHERE id=?",chain.get(3))).isEqualTo(1);
            var results=invitations.readInvitations(List.of(chain.get(5),chain.get(0),chain.get(0)));
            assertThat(results).extracting(r -> r.rootCustomerId()).containsExactly(chain.get(0),chain.get(5));
            assertThat(results.get(0).directCustomerIds()).containsExactly(chain.get(1));
            assertThat(results.get(0).descendantCustomerIds()).containsExactlyElementsOf(chain.subList(1,10));
            assertThat(results.get(1).descendantCustomerIds()).containsExactlyElementsOf(chain.subList(6,10));
            assertThat(results).allSatisfy(r -> {
                assertThat(r.completeness()).isEqualTo(Completeness.COMPLETE);
                assertThat(r.reasons()).isEmpty();
            });
            assertThat(physicalResource()).isSameAs(resource);
        });
    }

    @Test
    void invitationReaderTraversesDeletedBridgeAsPartialWithoutLosingRetainedDescendants() {
        rollbackFixtures(accounts -> {
            Object resource=readerRrResource();
            long root=newAccount(accounts,0), bridge=newAccount(accounts,0);
            long inactive=newAccount(accounts,0), leaf=newAccount(accounts,0);
            readerSponsor(bridge,root);readerSponsor(inactive,bridge);readerSponsor(leaf,inactive);
            assertThat(jdbc.update("UPDATE nx_user SET is_deleted=1 WHERE id=?",bridge)).isEqualTo(1);
            assertThat(jdbc.update("UPDATE nx_user SET status='DISABLED' WHERE id=?",inactive)).isEqualTo(1);
            var result=invitations.readInvitations(List.of(root)).get(0);
            assertThat(result.directCustomerIds()).isEmpty();
            assertThat(result.descendantCustomerIds()).containsExactly(inactive,leaf);
            assertThat(result.completeness()).isEqualTo(Completeness.PARTIAL);
            assertThat(result.reasons()).containsExactly(Reason.DELETED_NODE);
            assertThat(physicalResource()).isSameAs(resource);
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"CROSS_ENV","CYCLE"})
    void invitationReaderMarksCrossEnvironmentOrCyclePartialWithoutFakeZero(String defect) {
        rollbackFixtures(accounts -> {
            Object resource=readerRrResource();
            long root=newAccount(accounts,0), clean=newAccount(accounts,0);
            long bridge=newAccount(accounts,defect.equals("CROSS_ENV")?1:0), leaf=newAccount(accounts,0);
            readerSponsor(clean,root);readerSponsor(bridge,root);readerSponsor(leaf,bridge);
            if(defect.equals("CYCLE")) readerSponsor(root,leaf);
            var result=invitations.readInvitations(List.of(root)).get(0);
            assertThat(result.completeness()).isEqualTo(Completeness.PARTIAL);
            if(defect.equals("CROSS_ENV")) {
                assertThat(result.directCustomerIds()).containsExactly(clean);
                assertThat(result.descendantCustomerIds()).containsExactly(clean);
                assertThat(result.reasons()).containsExactly(Reason.ENVIRONMENT_CONFLICT);
            } else {
                assertThat(result.directCustomerIds()).containsExactly(clean,bridge);
                assertThat(result.descendantCustomerIds()).containsExactly(clean,bridge,leaf);
                assertThat(result.reasons()).containsExactly(Reason.CYCLE);
            }
            assertThat(result.descendantCustomerIds()).doesNotContain(root).doesNotHaveDuplicates();
            assertThat(physicalResource()).isSameAs(resource);
        });
    }

    @Test
    void deviceReaderRetainsOwnedStoppedInventoryPendingAndRawTrialGiftSources() {
        rollbackFixtures(accounts -> {
            Object resource=readerRrResource();
            long customer=newAccount(accounts,0);
            Fixture paidTrial=pending(Source.TRIAL_CONVERT,customer);
            Prepared prepared=prepare(paidTrial);settle(paidTrial,prepared,true,false);capture.record(prepared);
            readerFixtureDeviceIds.add(paidTrial.device);
            assertThat(jdbc.update("UPDATE nx_user_device SET source_channel='TRIAL',activated_at=NOW(),deactivated_at=NULL WHERE id=? AND user_id=?",
                paidTrial.device,customer)).isEqualTo(1);
            long freeTrial=readerDevice(customer,"OWNED","ACTIVE","TRIAL",null,"DEVICE");
            long gift=readerDevice(customer,"OWNED","ACTIVE","GIFT",null,"DEVICE");
            long stopped=readerDevice(customer,"OWNED","DEACTIVATED","ORDER",null,"DEVICE");
            long inventory=readerDevice(customer,"OWNED","INVENTORY","ORDER",null,"DEVICE");
            long pending=readerDevice(customer,"OWNED","PENDING_ACTIVATION","ORDER",null,"DEVICE");
            long deferred=readerDevice(customer,"OWNED","RUNNING","ORDER",null,"DEVICE");
            assertThat(jdbc.update("UPDATE nx_user_device SET pending_deactivate=1 WHERE id=?",deferred)).isEqualTo(1);
            readerRuntime(deferred,"ONLINE",databaseLocalDateTime("SELECT NOW(6)").withNano(0),0);
            var result=devices.readCurrent(List.of(customer,customer));
            assertThat(result.devices()).extracting(d -> d.deviceId()).containsExactly(
                paidTrial.device,freeTrial,gift,stopped,inventory,pending,deferred);
            assertThat(result.unknownHoldingDevices()).isEmpty();
            assertThat(result.devices().get(0).sourceChannel()).isEqualTo("TRIAL");
            assertThat(result.devices().get(0).sourceOrderNo()).isEqualTo(paidTrial.order);
            assertThat(result.devices().get(1).sourceChannel()).isEqualTo("TRIAL");
            assertThat(result.devices().get(1).sourceOrderNo()).isNull();
            assertThat(result.devices().get(2).sourceChannel()).isEqualTo("GIFT");
            assertThat(result.devices().get(2).sourceOrderNo()).isNull();
            assertThat(result.devices().get(3).deactivatedAt()).isNotNull();
            assertThat(result.devices().subList(3,6)).allSatisfy(d ->
                assertThat(d.connectionStatus()).isEqualTo(ConnectionStatus.NOT_APPLICABLE));
            var running=result.devices().get(6);
            assertThat(running.pendingDeactivate()).isEqualTo(1);
            assertThat(running.connectionStatus()).isEqualTo(ConnectionStatus.ONLINE);
            assertThat(running.runtime().pausedReason()).isEqualTo("maintenance");
            assertThat(running.runtime().activeTaskNo()).isEqualTo("reader-task-"+deferred);
            // The reader preserves a verified payment anchor; TRIAL alone does not assert paid ownership.
            var financial=finance.readHistory(List.of(customer));
            assertThat(financial.facts()).filteredOn(f -> f.factId().equals(paidTrial.factId())).hasSize(1);
            assertUnknownCoverage(financial);
            assertThat(physicalResource()).isSameAs(resource);
        });
    }

    @Test
    void deviceReaderUsesActualTenMinuteWindowForOnlineStaleFutureAndShare() {
        rollbackFixtures(accounts -> {
            Object resource=readerRrResource();
            try {
                // Session-only integer DB time makes the DATETIME-second inclusive boundary exact; no global clock changes.
                jdbc.execute("SET timestamp = UNIX_TIMESTAMP()");
                LocalDateTime now=databaseLocalDateTime("SELECT NOW(6)");
                assertThat(now.getNano()).isZero();
                long customer=newAccount(accounts,0);
                long boundary=readerDevice(customer,"OWNED","ACTIVE","ORDER",null,"DEVICE");
                long stale=readerDevice(customer,"OWNED","ACTIVE","ORDER",null,"DEVICE");
                long future=readerDevice(customer,"OWNED","ACTIVE","ORDER",null,"DEVICE");
                long share=readerDevice(customer,"OWNED","ACTIVE","ORDER",null,"SHARE");
                long reportedOffline=readerDevice(customer,"OWNED","ACTIVE","ORDER",null,"DEVICE");
                readerRuntime(boundary,"ONLINE",now.minusMinutes(10),0);
                readerRuntime(stale,"ONLINE",now.minusMinutes(10).minusSeconds(1),0);
                readerRuntime(future,"OFFLINE",now.plusSeconds(1),0);
                readerRuntime(share,"ONLINE",now,0);
                readerRuntime(reportedOffline,"OFFLINE",now.minusMinutes(1),0);
                var result=devices.readCurrent(List.of(customer));
                assertThat(result.evaluatedDbAt()).isEqualTo(now);
                assertThat(result.devices()).extracting(d -> d.deviceId()).containsExactly(boundary,stale,future,share,reportedOffline);
                assertThat(result.devices()).extracting(d -> d.connectionStatus()).containsExactly(
                    ConnectionStatus.ONLINE,ConnectionStatus.OFFLINE,ConnectionStatus.UNKNOWN,
                    ConnectionStatus.ONLINE,ConnectionStatus.OFFLINE);
                assertThat(result.devices().get(0).runtime().heartbeatAt()).isEqualTo(now.minusMinutes(10));
                assertThat(result.devices().get(1).runtime().reportedStatus()).isEqualTo("ONLINE");
                assertThat(result.devices().get(3).deviceType()).isEqualTo("SHARE");
                assertThat(physicalResource()).isSameAs(resource);
            } finally {
                jdbc.execute("SET timestamp = 0");
            }
        });
    }

    @Test
    void deviceReaderKeepsMissingOrDeletedRuntimeUnknown() {
        rollbackFixtures(accounts -> {
            Object resource=readerRrResource();
            long customer=newAccount(accounts,0);
            long missing=readerDevice(customer,"OWNED","ACTIVE","ORDER",null,"DEVICE");
            long deletedRuntime=readerDevice(customer,"OWNED","ACTIVE","ORDER",null,"DEVICE");
            // heartbeat_at is NOT NULL: absence is proved by no retained runtime row, not an illegal NULL fixture.
            readerRuntime(deletedRuntime,"OFFLINE",databaseLocalDateTime("SELECT NOW(6)").withNano(0),1);
            var result=devices.readCurrent(List.of(customer));
            assertThat(result.devices()).extracting(d -> d.deviceId()).containsExactly(missing,deletedRuntime);
            assertThat(result.devices()).allSatisfy(d -> {
                assertThat(d.connectionStatus()).isEqualTo(ConnectionStatus.UNKNOWN);
                assertThat(d.runtime().runtimeId()).isNull();
                assertThat(d.runtime().heartbeatAt()).isNull();
            });
            assertThat(physicalResource()).isSameAs(resource);
        });
    }

    @Test
    void deviceReaderExcludesDisposedOwnedAndSeparatesUnknownHoldingWithinExactScope() {
        rollbackFixtures(accounts -> {
            Object resource=readerRrResource();
            long customer=newAccount(accounts,0), other=newAccount(accounts,0);
            long held=readerDevice(customer,"OWNED","ACTIVE","ORDER",null,"DEVICE");
            readerDevice(customer,"OWNED","RECYCLED","ORDER",null,"DEVICE");
            readerDevice(customer,"OWNED","RETIRED","ORDER",null,"DEVICE");
            readerDevice(customer,"REFUNDED","DEACTIVATED","ORDER",null,"DEVICE");
            readerDevice(customer,"UNBOUND","ACTIVE","ORDER",null,"DEVICE");
            readerDevice(customer,"TRANSFERRED","ACTIVE","ORDER",null,"DEVICE");
            long unknown=readerDevice(customer,"UNKNOWN","ACTIVE","ORDER",null,"DEVICE");
            long otherDevice=readerDevice(other,"OWNED","ACTIVE","ORDER",null,"DEVICE");
            var result=devices.readCurrent(List.of(customer));
            assertThat(result.devices()).extracting(d -> d.deviceId()).containsExactly(held);
            assertThat(result.unknownHoldingDevices()).extracting(d -> d.deviceId()).containsExactly(unknown);
            assertThat(result.devices()).allSatisfy(d -> assertThat(d.customerId()).isEqualTo(customer));
            assertThat(result.unknownHoldingDevices()).allSatisfy(d -> {
                assertThat(d.customerId()).isEqualTo(customer);
                assertThat(d.connectionStatus()).isEqualTo(ConnectionStatus.UNKNOWN);
            });
            assertThat(result.devices()).extracting(d -> d.deviceId()).doesNotContain(otherDevice);
            assertThat(physicalResource()).isSameAs(resource);
        });
    }

    @ParameterizedTest
    @EnumSource(value=Source.class,names={"DEPOSIT_ORDER","CARD_TOPUP","VIETQR","HDPAY","WALLET_ORDER",
        "TRADE_IN","CAPACITY_KEEP","TRIAL_CONVERT","ORDER_REFUND"})
    void nineSourcesCaptureNewSuccessThenFreshOldPreparationReplaysWithoutChangingAnyRowBytes(Source source) {
        rollbackFixtures(accounts -> {
            Fixture fixture=pending(source,newAccount(accounts,0));
            int auditBefore=auditCalls();
            Object resource=physicalResource();
            assertThat(sourceMapper.settled(source,List.of(fixture.customer),fixture.key)).isEmpty();
            Prepared prepared=prepare(fixture);
            settle(fixture,prepared,true);
            capture.record(prepared);
            assertThat(physicalResource()).isSameAs(resource);
            Map<String,String> original=evidence(fixture);
            assertThat(original.get("capture_mode")).isEqualTo("NEW_SUCCESS");
            assertThat(original.get("source")).isEqualTo(source.name());
            JsonNode witness=tree(original.get("attribution_evidence_json")).path("beforeSource");
            assertThat(witness.path("oldSource").booleanValue()).isFalse();
            assertThat(witness.path("stableBusinessKey").asText()).isEqualTo(fixture.canonicalKey());
            assertThat(original.get("fractional_second_digits")).isEqualTo(orderSource(source)?"6":"0");
            if(source==Source.VIETQR) {
                assertThat(jdbc.queryForObject("SELECT intent_no FROM nx_vietqr_reconciliation WHERE reconciliation_no=?",
                    String.class,fixture.receipt)).isEqualTo(fixture.partition);
            }
            if(source==Source.CARD_TOPUP) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_topup_card_admission WHERE user_id=?",Long.class,
                    fixture.customer)).isZero(); // Actual CARD_SCOPE ledger fallback, without an admission fixture.
            }
            if(source==Source.ORDER_REFUND) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_bill WHERE user_id=? AND bill_no=? AND type='ORDER_REFUND' AND token='USDT' AND direction='IN' AND amount=10 AND deleted=0",
                    Long.class,fixture.customer,"E4-BILL-"+fixture.order)).isEqualTo(1);
            }
            Snapshot observed=finance.readHistory(List.of(fixture.customer));
            assertCapturedHistory(fixture,original,observed);
            assertThat(observed.facts()).hasSize(source==Source.ORDER_REFUND?2:1);
            assertThat(observed.issues()).isEmpty();
            assertThat(physicalResource()).isSameAs(resource); // The read joins the real writable RR transaction.
            Prepared old=prepare(fixture); // A fresh opaque token must now observe the successful source as OLD.
            capture.record(old);
            assertThat(evidence(fixture)).isEqualTo(original);
            assertThat(auditCalls()-auditBefore).isEqualTo(1);
        });
    }

    @Test
    void committedCrossSecondHistoryUsesOneRrSnapshotAndSurvivesSoftDeletedOrderWithFinancialAnchors() throws Exception {
        Map<String,Long> before=outsideCounts();
        List<Long> accounts=new ArrayList<>();
        AtomicReference<Fixture> fixture=new AtomicReference<>();
        ExecutorService writer=Executors.newSingleThreadExecutor();
        try {
            transaction.executeWithoutResult(status -> fixture.set(pending(Source.WALLET_ORDER,newAccount(accounts,0))));
            Fixture f=fixture.get();
            TransactionTemplate reader=new TransactionTemplate(manager);
            reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            reader.setReadOnly(true);reader.setTimeout(30);
            reader.executeWithoutResult(status -> {
                Object resource=physicalResource();
                assertThat(finance.readHistory(List.of(f.customer)).facts()).isEmpty();
                assertThat(countForCustomer("nx_support_payment_attribution",f.customer)).isZero();
                Future<?> committed=writer.submit(() -> transaction.executeWithoutResult(write -> {
                    Prepared prepared=prepare(f);settle(f,prepared,true);capture.record(prepared);
                }));
                try { committed.get(20,TimeUnit.SECONDS); }
                catch(InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Owned history writer interrupted",failure);
                }
                catch(Exception failure) { throw new IllegalStateException("Owned history writer did not commit",failure); }
                assertThat(outsideEvidence(f).get("capture_mode")).isEqualTo("NEW_SUCCESS");
                // This second JDBC SELECT also proves the old database view, independently of MyBatis query caches.
                assertThat(countForCustomer("nx_support_payment_attribution",f.customer)).isZero();
                assertThat(finance.readHistory(List.of(f.customer)).facts()).isEmpty();
                assertThat(physicalResource()).isSameAs(resource);
            });
            Map<String,String> saved=outsideEvidence(f);
            assertCapturedHistory(f,saved,finance.readHistory(List.of(f.customer)));
            transaction.executeWithoutResult(status -> jdbc.update("UPDATE nx_order SET is_deleted=1 WHERE user_id=? AND order_no=?",f.customer,f.order));
            Snapshot retained=finance.readHistory(List.of(f.customer));
            assertCapturedHistory(f,saved,retained);
            assertThat(retained.facts()).hasSize(1);assertThat(retained.issues()).isEmpty();
            assertThat(outsideEvidence(f)).isEqualTo(saved);
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_payment_record WHERE user_id=? AND payment_no=? AND is_deleted=0",
                Long.class,f.customer,f.payment)).isEqualTo(1);
            // A semantic finance DTO has no captured attribution/proof field. This does not claim an HTTP permission test.
            assertThat(java.util.Arrays.stream(Fact.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("agentAdminId","groupId","ownerAdminId","sourceFactJson","beforeSourceJson","evidenceJson");
            assertThat(java.util.Arrays.stream(Snapshot.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("capturedHistory","envelopes","attributionEvidence");
        } finally {
            awaitCaptureWorkers(writer);
            cleanupOwnAccounts(accounts);assertRollbackReadback(before,accounts);
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"AMOUNT","CUSTOMER","PARTITION"})
    void historicalNewProofCannotBorrowAnotherLedgerOrSelfAttestAChangedPartition(String corruption) {
        rollbackFixtures(accounts -> {
            Fixture f=pending("PARTITION".equals(corruption)?Source.DEPOSIT_ORDER:Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true);capture.record(prepared);
            if("AMOUNT".equals(corruption))
                jdbc.update("UPDATE nx_wallet_ledger SET amount=11 WHERE id=? AND user_id=?",f.ledger,f.customer);
            else if("CUSTOMER".equals(corruption))
                jdbc.update("UPDATE nx_wallet_ledger SET user_id=? WHERE id=? AND user_id=?",newAccount(accounts,0),f.ledger,f.customer);
            else {
                String forged=Long.toString(Long.parseLong(f.partition)+1);
                jdbc.update("UPDATE nx_support_payment_attribution SET source_partition=?,attribution_evidence_json=JSON_SET(attribution_evidence_json,'$.beforeSource.sourcePartition',?) WHERE fact_id=? AND customer_id=?",
                    forged,forged,f.factId(),f.customer);
            }
            Snapshot result=finance.readHistory(List.of(f.customer));
            assertThat(result.facts()).noneMatch(fact -> fact.factId().equals(f.factId()));
            assertThat(result.issues()).isNotEmpty();
            assertThat(result.coverage()).filteredOn(c -> c.source()==f.source)
                .allSatisfy(c -> assertThat(c.observedStatus()).isEqualTo(Status.UNKNOWN));
            assertUnknownCoverage(result);
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"FAILED","CANCELLED","OTHER_LEDGER"})
    void actualFailedPaymentOrDifferentLedgerCannotBeReplacedByCapturedNewJson(String violation) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true);capture.record(prepared);
            if("OTHER_LEDGER".equals(violation)) {
                String other="OTHER-"+f.name;
                jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status) VALUES(?,?,'ORDER_PURCHASE','USDT','OUT',1,0,'SUCCESS')",f.customer,other);
                long otherLedger=jdbc.queryForObject("SELECT id FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND asset='USDT' AND direction='OUT'",
                    Long.class,f.customer,other);
                assertThat(otherLedger).isNotEqualTo(f.ledger);
                jdbc.update("UPDATE nx_payment_record SET wallet_ledger_id=? WHERE payment_no=? AND user_id=?",otherLedger,f.payment,f.customer);
            } else jdbc.update("UPDATE nx_payment_record SET payment_status=? WHERE payment_no=? AND user_id=?",violation,f.payment,f.customer);
            Snapshot result=finance.readHistory(List.of(f.customer));
            assertThat(result.facts()).noneMatch(fact -> fact.factId().equals(f.factId()));
            assertThat(result.issues()).anyMatch(issue -> issue.source()==Source.WALLET_ORDER);
            assertThat(result.coverage()).filteredOn(c -> c.source()==Source.WALLET_ORDER)
                .allSatisfy(c -> assertThat(c.observedStatus()).isEqualTo(Status.UNKNOWN));
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"NULL_LINK","REFUNDED"})
    void actualNullableLegacyPaymentLinkAndRefundedOriginalRemainValid(String state) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true);capture.record(prepared);
            Map<String,String> saved=evidence(f);
            if("NULL_LINK".equals(state))
                jdbc.update("UPDATE nx_payment_record SET wallet_ledger_id=NULL WHERE payment_no=? AND user_id=?",f.payment,f.customer);
            else {
                jdbc.update("UPDATE nx_payment_record SET payment_status='REFUNDED' WHERE payment_no=? AND user_id=?",f.payment,f.customer);
                jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=? AND user_id=?",f.order,f.customer);
            }
            Snapshot result=finance.readHistory(List.of(f.customer));
            assertCapturedHistory(f,saved,result);
            assertThat(result.facts()).hasSize(1);assertThat(result.issues()).isEmpty();
            assertThat(evidence(f)).isEqualTo(saved);
        });
    }

    @Test
    void softDeletedOrderWithChangedAmountCannotBeRestoredFromCapturedJson() {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true);capture.record(prepared);
            jdbc.update("UPDATE nx_order SET is_deleted=1,amount_usdt=11 WHERE order_no=? AND user_id=?",f.order,f.customer);
            Snapshot result=finance.readHistory(List.of(f.customer));
            assertThat(result.facts()).noneMatch(fact -> fact.factId().equals(f.factId()));
            assertThat(result.issues()).anyMatch(issue -> issue.source()==Source.WALLET_ORDER && issue.reason().equals("SETTLEMENT_MISMATCH"));
            assertThat(result.coverage()).filteredOn(c -> c.source()==Source.WALLET_ORDER)
                .allSatisfy(c -> assertThat(c.observedStatus()).isEqualTo(Status.UNKNOWN));
        });
    }

    @ParameterizedTest
    @EnumSource(value=Source.class,names={"WALLET_ORDER","TRADE_IN","CAPACITY_KEEP"})
    void selfConsistentSoftDeletedOrderAndSavedTypeCannotChangeTheFinancialSourceFamily(Source source) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(source,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true);capture.record(prepared);
            String wrongType=source==Source.WALLET_ORDER?"CAPACITY_KEEP":"SINGLE";
            jdbc.update("UPDATE nx_order SET is_deleted=1,order_type=? WHERE order_no=? AND user_id=?",wrongType,f.order,f.customer);
            jdbc.update("UPDATE nx_support_payment_attribution SET order_type=?,source_fact_json=JSON_SET(source_fact_json,'$.orderType',?) WHERE fact_id=? AND customer_id=?",wrongType,wrongType,f.factId(),f.customer);
            Map<String,String> saved=evidence(f);
            Snapshot result=finance.readHistory(List.of(f.customer));
            assertThat(result.facts()).noneMatch(fact -> fact.factId().equals(f.factId()));
            assertThat(result.issues()).anyMatch(issue -> issue.source()==source && issue.reason().equals("SETTLEMENT_MISMATCH"));
            assertThat(result.coverage()).filteredOn(c -> c.source()==source)
                .allSatisfy(c -> assertThat(c.observedStatus()).isEqualTo(Status.UNKNOWN));
            assertUnknownCoverage(result);
            assertThat(evidence(f)).isEqualTo(saved);
        });
    }

    @ParameterizedTest
    @EnumSource(value=Source.class,names={"WALLET_ORDER","TRADE_IN","CAPACITY_KEEP"})
    void hardDeletedPurchaseRootHasNoIndependentPaidTimeEvidenceEvenWithCapturedJson(Source source) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(source,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true);capture.record(prepared);
            jdbc.update("DELETE FROM nx_order WHERE order_no=? AND user_id=?",f.order,f.customer);
            Snapshot result=finance.readHistory(List.of(f.customer));
            assertThat(result.facts()).noneMatch(fact -> fact.factId().equals(f.factId()));
            assertThat(result.issues()).anyMatch(issue -> issue.source()==source && issue.reason().startsWith("MISSING_"));
            assertThat(result.coverage()).filteredOn(c -> c.source()==source)
                .allSatisfy(c -> assertThat(c.observedStatus()).isEqualTo(Status.UNKNOWN));
        });
    }

    @ParameterizedTest
    @EnumSource(value=Source.class,names={"WALLET_ORDER","TRADE_IN","CAPACITY_KEEP"})
    void capturedCanonicalPurchaseCannotBorrowADifferentOrderNumberFromTheSameRealLedger(Source source) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(source,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true);capture.record(prepared);
            jdbc.update("UPDATE nx_order SET is_deleted=1 WHERE order_no=? AND user_id=?",f.order,f.customer);
            String wrongOrder="other-"+f.name;
            String wrongFact="PURCHASE:"+wrongOrder;
            jdbc.update("UPDATE nx_support_payment_attribution SET fact_id=?,order_no=?,source_fact_json=JSON_SET(source_fact_json,'$.factId',?,'$.orderNo',?) WHERE fact_id=? AND customer_id=?",wrongFact,wrongOrder,wrongFact,wrongOrder,f.factId(),f.customer);
            Snapshot result=finance.readHistory(List.of(f.customer));
            assertThat(result.facts()).isEmpty();
            assertThat(result.issues()).anyMatch(issue -> issue.source()==source && issue.reason().equals("SETTLEMENT_MISMATCH"));
            assertUnknownCoverage(result);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void softDeletedVietqrReceiptStillAnchorsItsActualProviderTime(boolean changedTime) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.VIETQR,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true);capture.record(prepared);
            Map<String,String> saved=evidence(f);
            jdbc.update("UPDATE nx_vietqr_reconciliation SET is_deleted=1 WHERE reconciliation_no=? AND user_id=?",f.receipt,f.customer);
            if(changedTime)jdbc.update("UPDATE nx_vietqr_reconciliation SET received_at=DATE_ADD(received_at,INTERVAL 1 SECOND) WHERE reconciliation_no=? AND user_id=?",f.receipt,f.customer);
            Snapshot result=finance.readHistory(List.of(f.customer));
            if(changedTime) {
                assertThat(result.facts()).isEmpty();
                assertThat(result.issues()).anyMatch(issue -> issue.source()==Source.VIETQR && issue.reason().equals("BROKEN_INTENT_IDENTITY"));
                assertUnknownCoverage(result);
            } else {
                assertCapturedHistory(f,saved,result);
                assertThat(result.facts()).hasSize(1);assertThat(result.issues()).isEmpty();
            }
            assertThat(evidence(f)).isEqualTo(saved);
        });
    }

    @ParameterizedTest
    @EnumSource(value=Source.class,names={"FREE_TRIAL","UNMATCHED_LEDGER"},mode=EnumSource.Mode.EXCLUDE)
    void capturedProofCannotInventFieldsAbsentFromItsRealFinancialSource(Source source) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(source,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true);capture.record(prepared);
            switch(source) {
                case DEPOSIT_ORDER -> jdbc.update("UPDATE nx_support_payment_attribution SET order_no='invented-order',source_fact_json=JSON_SET(source_fact_json,'$.orderNo','invented-order') WHERE fact_id=? AND customer_id=?",f.factId(),f.customer);
                case CARD_TOPUP -> jdbc.update("UPDATE nx_support_payment_attribution SET order_type='SINGLE',source_fact_json=JSON_SET(source_fact_json,'$.orderType','SINGLE') WHERE fact_id=? AND customer_id=?",f.factId(),f.customer);
                case VIETQR,TRADE_IN,ORDER_REFUND -> jdbc.update("UPDATE nx_support_payment_attribution SET source_fact_json=JSON_SET(source_fact_json,'$.sourceConfirmationAt',JSON_UNQUOTE(JSON_EXTRACT(source_fact_json,'$.ledgerRecordedAt'))) WHERE fact_id=? AND customer_id=?",f.factId(),f.customer);
                default -> jdbc.update("UPDATE nx_support_payment_attribution SET source_fact_json=JSON_SET(source_fact_json,'$.providerPaidAt',JSON_UNQUOTE(JSON_EXTRACT(source_fact_json,'$.ledgerRecordedAt'))) WHERE fact_id=? AND customer_id=?",f.factId(),f.customer);
            }
            Map<String,String> saved=evidence(f);
            Snapshot result=finance.readHistory(List.of(f.customer));
            assertThat(result.issues()).anyMatch(issue -> issue.source()==source && issue.reason().equals("INVALID_PERSISTED_SOURCE_PROOF"));
            // An independently valid legacy fact may survive; it must retain only fields its actual source produces.
            assertThat(result.facts()).allSatisfy(fact -> {
                if(fact.kind()==Kind.DEPOSIT) {assertThat(fact.orderNo()).isNull();assertThat(fact.orderType()).isNull();}
                if(fact.source()!=Source.CARD_TOPUP && fact.source()!=Source.VIETQR)assertThat(fact.providerPaidAt()).isNull();
                if(fact.source()!=Source.WALLET_ORDER && fact.source()!=Source.TRIAL_CONVERT)assertThat(fact.sourceConfirmationAt()).isNull();
            });
            assertUnknownCoverage(result);
            assertThat(evidence(f)).isEqualTo(saved);
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"SOFT_DELETED","AMOUNT","TIME","MISSING","SAVED_ORDER_TYPE"})
    void trialHistoryRequiresTheRetainedOrderAmountAndExactMicrosecondPaidTime(String state) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.TRIAL_CONVERT,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true);capture.record(prepared);
            Map<String,String> saved=evidence(f);
            LocalDateTime paidAt=databaseLocalDateTime("SELECT paid_at FROM nx_order WHERE order_no=? AND user_id=?",f.order,f.customer);
            jdbc.update("UPDATE nx_order SET is_deleted=1 WHERE order_no=? AND user_id=?",f.order,f.customer);
            if(state.equals("AMOUNT"))
                jdbc.update("UPDATE nx_order SET amount_usdt=11 WHERE order_no=? AND user_id=?",f.order,f.customer);
            else if(state.equals("TIME"))
                jdbc.update("UPDATE nx_order SET paid_at=DATE_ADD(paid_at,INTERVAL 1 MICROSECOND) WHERE order_no=? AND user_id=?",f.order,f.customer);
            else if(state.equals("MISSING"))
                jdbc.update("DELETE FROM nx_order WHERE order_no=? AND user_id=?",f.order,f.customer);
            else if(state.equals("SAVED_ORDER_TYPE")) {
                jdbc.update("UPDATE nx_support_payment_attribution SET order_type='SINGLE',source_fact_json=JSON_SET(source_fact_json,'$.orderType','SINGLE') WHERE fact_id=? AND customer_id=?",f.factId(),f.customer);
                saved=evidence(f);
            }
            Snapshot result=finance.readHistory(List.of(f.customer));
            if(state.equals("SOFT_DELETED")) {
                assertCapturedHistory(f,saved,result);
                assertThat(result.facts()).hasSize(1);assertThat(result.issues()).isEmpty();
                assertThat(result.facts().get(0).succeededAt()).isEqualTo(paidAt);
            } else {
                assertThat(result.facts()).noneMatch(fact -> fact.factId().equals(f.factId()));
                assertThat(result.issues()).anyMatch(issue -> issue.source()==Source.TRIAL_CONVERT
                    && issue.reason().equals(state.equals("MISSING")?"MISSING_AUTHORITATIVE_SOURCE":"SETTLEMENT_MISMATCH"));
                assertThat(result.coverage()).filteredOn(c -> c.source()==Source.TRIAL_CONVERT)
                    .allSatisfy(c -> assertThat(c.observedStatus()).isEqualTo(Status.UNKNOWN));
                assertUnknownCoverage(result);
            }
            assertThat(evidence(f)).isEqualTo(saved);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void cregisEventAndDepositOrderUseIndependentSqlTimesAndTheRetainedOrderRemainsRequired(boolean missingOrder) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.DEPOSIT_ORDER,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true,false,true);capture.record(prepared);
            Map<String,String> saved=evidence(f);
            LocalDateTime eventAt=databaseLocalDateTime("SELECT credited_at FROM nx_cregis_deposit_event WHERE project_id=? AND cid=?",Long.parseLong(f.partition),f.cid);
            LocalDateTime orderAt=databaseLocalDateTime("SELECT credited_at FROM nx_deposit_order WHERE deposit_no=? AND user_id=?",f.key,f.customer);
            assertThat(eventAt).isBefore(orderAt);
            assertThat(LocalDateTime.parse(tree(saved.get("source_fact_json")).path("succeededAt").asText())).isEqualTo(orderAt);
            if(missingOrder)jdbc.update("DELETE FROM nx_deposit_order WHERE deposit_no=? AND user_id=?",f.key,f.customer);
            Snapshot result=finance.readHistory(List.of(f.customer));
            if(missingOrder) {
                assertThat(result.facts()).noneMatch(fact -> fact.factId().equals(f.factId()));
                assertThat(result.issues()).anyMatch(issue -> issue.source()==Source.DEPOSIT_ORDER
                    && issue.reason().equals("MISSING_AUTHORITATIVE_SOURCE"));
                assertThat(result.coverage()).filteredOn(c -> c.source()==Source.DEPOSIT_ORDER)
                    .allSatisfy(c -> assertThat(c.observedStatus()).isEqualTo(Status.UNKNOWN));
                assertUnknownCoverage(result);
            } else {
                assertCapturedHistory(f,saved,result);
                assertThat(result.facts()).hasSize(1);assertThat(result.issues()).isEmpty();
                assertThat(result.facts().get(0).succeededAt()).isEqualTo(orderAt);
            }
            assertThat(evidence(f)).isEqualTo(saved);
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"MISSING","OLD"})
    void ordinaryCrossSecondHistoryWithoutNewProofRemainsUnknown(String corruption) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true);capture.record(prepared);
            corruptOwnProof(f,corruption);
            Snapshot result=finance.readHistory(List.of(f.customer));
            assertThat(result.facts()).isEmpty();
            assertThat(result.issues()).extracting(Issue::reason).contains("CONFLICTING_SUCCESS_TIME");
            assertThat(result.coverage()).filteredOn(c -> c.source()==Source.WALLET_ORDER)
                .allSatisfy(c -> assertThat(c.observedStatus()).isEqualTo(Status.UNKNOWN));
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"JSON","SCHEMA","MISSING_BEFORE","EVIDENCE_MODE"})
    void committedMalformedFinancialProofAndMetadataKeepIndependentLegacyWithoutRollbackOnly(String corruption) {
        Map<String,Long> before=outsideCounts();
        List<Long> accounts=new ArrayList<>();List<Fixture> fixtures=new ArrayList<>();
        try {
            transaction.executeWithoutResult(status -> {
                long customer=newAccount(accounts,0);
                Fixture bad=pending(Source.WALLET_ORDER,customer);fixtures.add(bad);
                Prepared prepared=prepare(bad);settle(bad,prepared,true);capture.record(prepared);
                Fixture legacy=pending(Source.CARD_TOPUP,customer);fixtures.add(legacy);
                settle(legacy,prepare(legacy),false); // Valid ordinary legacy evidence, with no capture row.
                switch(corruption) {
                    // The column enforces syntactically valid JSON; this is a malformed financial object.
                    case "JSON" -> jdbc.update("UPDATE nx_support_payment_attribution SET source_fact_json=JSON_SET(source_fact_json,'$.factId',123) WHERE fact_id=? AND customer_id=?",bad.factId(),customer);
                    case "SCHEMA" -> jdbc.update("UPDATE nx_support_payment_attribution SET capture_schema_version='future-schema' WHERE fact_id=? AND customer_id=?",bad.factId(),customer);
                    case "MISSING_BEFORE" -> jdbc.update("UPDATE nx_support_payment_attribution SET attribution_evidence_json=JSON_REMOVE(attribution_evidence_json,'$.beforeSource') WHERE fact_id=? AND customer_id=?",bad.factId(),customer);
                    case "EVIDENCE_MODE" -> jdbc.update("UPDATE nx_support_payment_attribution SET attribution_evidence_json=JSON_SET(attribution_evidence_json,'$.captureMode','OLD_SOURCE') WHERE fact_id=? AND customer_id=?",bad.factId(),customer);
                    default -> throw new AssertionError(corruption);
                }
            });
            Fixture bad=fixtures.get(0),legacy=fixtures.get(1);
            // Calling outside any transaction exercises the actual outer readonly RR proxy and its commit.
            Snapshot result=finance.readHistory(List.of(bad.customer));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(result.facts()).extracting(Fact::factId).containsExactly(legacy.factId());
            assertThat(result.issues()).extracting(Issue::reason).contains("INVALID_PERSISTED_SOURCE_PROOF");
            assertThat(result.coverage()).filteredOn(c -> c.source()==Source.WALLET_ORDER)
                .allSatisfy(c -> assertThat(c.observedStatus()).isEqualTo(Status.UNKNOWN));
            assertUnknownCoverage(result);
            assertThat(finance.readHistory(List.of(bad.customer)).facts()).isEqualTo(result.facts());
        } finally {
            cleanupOwnSourceFixtures(fixtures);cleanupOwnAccounts(accounts);assertRollbackReadback(before,accounts);
        }
    }

    @Test
    void actualHistorySelectFailureKeepsRealLegacyFactsAndReturnsUnknownWithoutRollbackOnly() {
        Map<String,Long> before=outsideCounts();List<Long> accounts=new ArrayList<>();List<Fixture> fixtures=new ArrayList<>();
        try {
            transaction.executeWithoutResult(status -> {
                Fixture legacy=pending(Source.CARD_TOPUP,newAccount(accounts,0));fixtures.add(legacy);
                settle(legacy,prepare(legacy),false);
            });
            Fixture legacy=fixtures.get(0);
            SupportPaymentCaptureHistoryMapper failed=ids -> {
                // Real MySQL read-error injection on an owned scope; no shared schema, service or connection is changed.
                jdbc.queryForList("SELECT support_capture_fixture_missing_column FROM nx_support_payment_attribution WHERE customer_id=?",legacy.customer);
                throw new AssertionError("The intentional missing-column SELECT unexpectedly succeeded");
            };
            Snapshot result=historyReader(failed).readHistory(List.of(legacy.customer));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(result.facts()).extracting(Fact::factId).containsExactly(legacy.factId());
            assertThat(result.issues()).extracting(Issue::reason).contains("SOURCE_READ_FAILED");
            assertThat(result.toString()).doesNotContain("support_capture_fixture_missing_column");
            assertUnknownCoverage(result);
        } finally {
            cleanupOwnSourceFixtures(fixtures);cleanupOwnAccounts(accounts);assertRollbackReadback(before,accounts);
        }
    }

    @Test
    void actualForeignCapturedRowCannotCrossTheRequestedCustomerScope() {
        rollbackFixtures(accounts -> {
            Fixture owned=pending(Source.CARD_TOPUP,newAccount(accounts,0));
            settle(owned,prepare(owned),false);
            Fixture foreign=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared prepared=prepare(foreign);settle(foreign,prepared,true);capture.record(prepared);
            // Deliberately misroute this one test mapper to real persisted foreign rows, rather than synthesize an envelope.
            SupportPaymentCaptureHistoryMapper misrouted=ids -> historyMapper.readNewFinancialProofs(List.of(foreign.customer));
            assertThatThrownBy(() -> historyReader(misrouted).readHistory(List.of(owned.customer)))
                .hasMessage("INVALID_CAPTURE_HISTORY_SCOPE");
        });
    }

    @Test
    void historyRefundCapRejectsSelfConsistentOverRefundAndRetainsOriginalPayment() {
        rollbackFixtures(accounts -> {
            Fixture refund=pending(Source.ORDER_REFUND,newAccount(accounts,0));
            Prepared prepared=prepare(refund);settle(refund,prepared,true);capture.record(prepared);
            // Adversarial changes affect only these fixture rows. Original capture/write guards remain unchanged.
            jdbc.update("UPDATE nx_wallet_ledger SET amount=11 WHERE id=? AND user_id=?",refund.ledger,refund.customer);
            jdbc.update("UPDATE nx_support_payment_attribution SET amount=11,source_fact_json=JSON_SET(source_fact_json,'$.amount',11) WHERE fact_id=? AND customer_id=?",
                refund.factId(),refund.customer);
            Snapshot result=finance.readHistory(List.of(refund.customer));
            assertThat(result.facts()).extracting(Fact::factId).containsExactly(refund.original.factId());
            assertThat(result.facts().get(0).amount()).isEqualByComparingTo("10");
            assertThat(result.issues()).extracting(Issue::reason).contains("REFUND_EXCEEDS_ORIGINAL_AMOUNT");
            assertThat(result.coverage()).filteredOn(c -> c.source()==Source.ORDER_REFUND)
                .allSatisfy(c -> assertThat(c.observedStatus()).isEqualTo(Status.UNKNOWN));
        });
    }

    @Test
    void providerConfirmedCardStillRequiresActualSettledReceipt() {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.CARD_TOPUP,newAccount(accounts,0));
            Prepared prepared=prepare(f);
            settle(f,prepared,true);
            jdbc.update("UPDATE nx_topup_card_settlement SET status='PROCESSING' WHERE payment_no=?",f.key);
            assertThatThrownBy(() -> capture.record(prepared)).hasMessage("MISSING_CARD_SETTLEMENT");
            assertThat(countForCustomer("nx_support_payment_attribution",f.customer)).isZero();
            verifyNoInteractions(audit);
        });
    }

    @Test
    void separateSqlNowStatementsCrossSecondsAndRealZeroPrecisionRoundingPreserveTheRawFinancialTimes() {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true);
            assertThat(jdbc.queryForObject("SELECT datetime_precision FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_wallet_ledger' AND column_name='created_at'",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT datetime_precision FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_order' AND column_name='paid_at'",Integer.class)).isEqualTo(6);
            LocalDateTime highPrecision=f.base.withNano(900_000_000);
            String probeNo="PROBE-"+f.name;
            jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,created_at) VALUES(?,?,'ORDER_PURCHASE','USDT','OUT',1,0,'SUCCESS',?)",
                f.customer,probeNo,highPrecision);
            LocalDateTime rounded=databaseLocalDateTime("SELECT created_at FROM nx_wallet_ledger WHERE biz_no=? AND asset='USDT' AND direction='OUT'",probeNo);
            // Read the actual mode; neither truncation nor rounding is guessed from Java's withNano.
            String sqlMode=jdbc.queryForObject("SELECT @@session.sql_mode",String.class);
            assertThat(rounded).isEqualTo(sqlMode.contains("TIME_TRUNCATE_FRACTIONAL")?f.base:f.base.plusSeconds(1));
            LocalDateTime ledgerTime=databaseLocalDateTime("SELECT created_at FROM nx_wallet_ledger WHERE id=?",f.ledger);
            assertThat(jdbc.queryForObject("SELECT SLEEP(1.1)",Integer.class)).isZero();
            jdbc.update("UPDATE nx_order SET paid_at=NOW(6) WHERE order_no=?",f.order);
            jdbc.update("UPDATE nx_payment_record SET paid_at=NOW(6) WHERE payment_no=?",f.payment);
            LocalDateTime paidTime=databaseLocalDateTime("SELECT paid_at FROM nx_order WHERE order_no=?",f.order);
            LocalDateTime confirmationTime=databaseLocalDateTime("SELECT paid_at FROM nx_payment_record WHERE payment_no=?",f.payment);
            assertThat(paidTime.withNano(0)).isAfter(ledgerTime);
            capture.record(prepared);
            JsonNode financial=tree(evidence(f).get("source_fact_json"));
            var iso=java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");
            assertThat(financial.path("ledgerRecordedAt").asText()).isEqualTo(iso.format(ledgerTime));
            assertThat(financial.path("succeededAt").asText()).isEqualTo(iso.format(paidTime));
            assertThat(financial.path("sourceConfirmationAt").asText()).isEqualTo(iso.format(confirmationTime));
        });
    }

    @Test
    void creditedCregisEventUnderHeldWalletCannotBecomeNewAfterAStatusReset() {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.DEPOSIT_ORDER,newAccount(accounts,0));
            settle(f,prepare(f),false);
            // A legacy held balance plus reset event status still carries durable old credit markers.
            jdbc.update("UPDATE nx_user_wallet SET cregis_risk_held=10 WHERE user_id=?",f.customer);
            jdbc.update("UPDATE nx_cregis_deposit_event SET status='RISK_HOLD' WHERE project_id=? AND cid=?",
                Long.parseLong(f.partition),f.cid);
            var before=finance.beforeSource(f.customer,f.source,f.key,f.partition);
            assertThat(before.oldSource()).isTrue();
            assertThat(before.existingLedgerId()).isEqualTo(f.ledger);
            jdbc.update("UPDATE nx_cregis_deposit_event SET status='CREDITED' WHERE project_id=? AND cid=?",
                Long.parseLong(f.partition),f.cid);
            capture.record(prepare(f));
            assertOldUnknownWithoutPresentWitnesses(evidence(f));
        });
    }

    @Test
    void existingOldSuccessGetsOnlyUnknownOwnershipAndNoCurrentAdminOrGroupWitness() {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            settle(f,prepare(f),false);
            var before=finance.beforeSource(f.customer,f.source,f.key);
            assertThat(before.oldSource()).isTrue();
            assertThat(before.existingFactId()).isEqualTo("PURCHASE:"+f.order);
            capture.record(prepare(f));
            assertOldUnknownWithoutPresentWitnesses(evidence(f));
            verify(audit,times(1)).recordRequired(any(AuditLogWriteRequest.class));
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"MISSING","OLD","AMOUNT","CUSTOMER","TIME","PARTITION"})
    void crossSecondReplayRejectsMissingOldOrFinanciallyMismatchedPersistedProof(String corruption) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared first=prepare(f);settle(f,first,true);capture.record(first);
            corruptOwnProof(f,corruption);
            clearInvocations(audit);
            assertThatThrownBy(() -> capture.record(prepare(f))).hasMessage("CONFLICTING_SUCCESS_TIME");
            verifyNoInteractions(audit);
        });
    }

    @Test
    void capturedCregisProofCannotReplayAcrossTheTrustedProjectPartition() {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.DEPOSIT_ORDER,newAccount(accounts,0));
            Prepared first=prepare(f);settle(f,first,true);capture.record(first);
            Map<String,String> original=evidence(f);
            String otherProject=Long.toString(Long.parseLong(f.partition)+1);
            clearInvocations(audit);
            assertThatThrownBy(() -> capture.record(capture.prepare(f.customer,f.source,f.key,otherProject)))
                .hasMessage("CONFLICTING_SUCCESS_TIME");
            assertThat(evidence(f)).isEqualTo(original);
            verifyNoInteractions(audit);
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"SOURCE","CUSTOMER","VIETQR_INTENT"})
    void realCurrentReadsRejectSourceCustomerOrCanonicalReceiptBindingChangedAfterPreparation(String violation) {
        rollbackFixtures(accounts -> {
            Fixture f=pending("VIETQR_INTENT".equals(violation)?Source.VIETQR:Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared prepared=prepare(f);settle(f,prepared,true);
            String reason;
            if("SOURCE".equals(violation)) {
                jdbc.update("UPDATE nx_order SET order_type='TRADE_IN' WHERE order_no=?",f.order);
                reason="MISSING_SETTLED_SOURCE"; // The canonical root filter rejects the changed source type first.
            } else if("CUSTOMER".equals(violation)) {
                long other=newAccount(accounts,0);
                jdbc.update("UPDATE nx_order SET user_id=? WHERE order_no=?",other,f.order);
                reason="SOURCE_CUSTOMER_MISMATCH";
            } else {
                jdbc.update("UPDATE nx_vietqr_reconciliation SET intent_no=? WHERE reconciliation_no=?","INT-"+unique(),f.receipt);
                reason="SOURCE_INTENT_CHANGED";
            }
            assertThatThrownBy(() -> capture.record(prepared)).hasMessage(reason);
            assertThat(countForCustomer("nx_support_payment_attribution",f.customer)).isZero();
            verifyNoInteractions(audit);
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"MISSING","OLD","AMOUNT","CUSTOMER","TIME"})
    void laterRefundCannotBorrowUntrustedOriginalCrossSecondPurchaseProof(String corruption) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.ORDER_REFUND,newAccount(accounts,0));
            Prepared refund=prepare(f);
            corruptOwnProof(f.original,corruption);
            settle(f,refund,true);
            clearInvocations(audit);
            assertThatThrownBy(() -> capture.record(refund)).hasMessage("CONFLICTING_SUCCESS_TIME");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_payment_attribution WHERE source='ORDER_REFUND' AND customer_id=?",
                Long.class,f.customer)).isZero();
            verifyNoInteractions(audit);
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"CHRONOLOGY","SUM","CUSTOMER"})
    void validOriginalProofNeverRelaxesRefundChronologySumOrCustomer(String violation) {
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.ORDER_REFUND,newAccount(accounts,0));
            Prepared refund=prepare(f);settle(f,refund,true);
            String reason;
            if("CHRONOLOGY".equals(violation)) {
                jdbc.update("UPDATE nx_wallet_ledger SET created_at=? WHERE id=?",f.original.base.minusSeconds(1),f.ledger);
                reason="FRESH_LEDGER_WRITE_MISMATCH";
            } else if("SUM".equals(violation)) {
                jdbc.update("UPDATE nx_wallet_ledger SET amount=11 WHERE id=?",f.ledger);
                reason="FRESH_LEDGER_WRITE_MISMATCH";
            } else {
                long another=newAccount(accounts,0);
                jdbc.update("UPDATE nx_wallet_ledger SET user_id=? WHERE id=?",another,f.ledger);
                // A successful receipt cannot be rebound to another customer.
                reason="FRESH_LEDGER_WRITE_MISMATCH";
            }
            clearInvocations(audit);
            assertThatThrownBy(() -> capture.record(refund)).hasMessage(reason);
            verifyNoInteractions(audit);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void actualSameSecondMicrosecondPurchaseAndSecondRefundPreserveChronologyAtSourcePrecision(boolean previousWholeSecond) {
        rollbackFixtures(accounts -> {
            Fixture original=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared purchasePrepared=prepare(original);
            // This is an explicit clock-window experiment, solely in this rollback fixture.
            // Align to the start of the NEXT actual database second; neither ledger tuple is edited.
            assertThat(jdbc.queryForObject("SELECT SLEEP((1000000-MICROSECOND(NOW(6))+20000)/1000000.0)",Integer.class)).isZero();
            settle(original,purchasePrepared,true,false);
            capture.record(purchasePrepared);
            LocalDateTime purchaseAt=databaseLocalDateTime("SELECT paid_at FROM nx_order WHERE order_no=?",original.order);
            assertThat(purchaseAt.getNano()).as("Actual DATETIME(6) purchase retains its fractional second").isPositive();
            Map<String,String> purchaseEvidence=evidence(original);
            assertThat(purchaseEvidence.get("fractional_second_digits")).isEqualTo("6");
            Fixture refund=new Fixture(Source.ORDER_REFUND,original.customer,unique(),original.base);
            refund.original=original;refund.order=original.order;refund.key="E4-REFUND-"+original.order;
            if(previousWholeSecond) {
                // Deliberately malformed LEGACY refund: no new receipt is claimed or manufactured.
                // Its own valid old projection reaches checkRefund's whole-second chronology gate.
                LocalDateTime earlier=purchaseAt.withNano(0).minusSeconds(1);
                jdbc.update("UPDATE nx_user_wallet SET usdt_available=usdt_available+10 WHERE user_id=?",refund.customer);
                jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,created_at,updated_at) VALUES(?,?,'ORDER_REFUND','USDT','IN',10,500,'SUCCESS',?,?)",
                    refund.customer,refund.key,earlier,earlier);
                refund.ledger=jdbc.queryForObject("SELECT id FROM nx_wallet_ledger WHERE biz_no=? AND user_id=? AND asset='USDT' AND direction='IN'",Long.class,refund.key,refund.customer);
                assertThat(databaseLocalDateTime("SELECT created_at FROM nx_wallet_ledger WHERE id=?",refund.ledger)).isEqualTo(earlier);
                assertThat(refundMapper.insertBill(refund.customer,"E4-BILL-"+refund.order,new BigDecimal("10.000000"))).isEqualTo(1);
                jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",refund.order);
                jdbc.update("UPDATE nx_payment_record SET payment_status='REFUNDED' WHERE order_no=?",refund.order);
                assertThat(finance.beforeSource(refund.customer,refund.source,refund.key).oldSource()).isTrue();
                clearInvocations(audit);
                Prepared oldRefund=prepare(refund);
                assertThatThrownBy(() -> capture.record(oldRefund)).hasMessage("REFUND_PREDATES_ORIGINAL_PAYMENT");
                assertThat(countForCustomer("nx_support_payment_attribution",refund.customer)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_payment_attribution WHERE customer_id=? AND fact_id=?",Long.class,refund.customer,refund.factId())).isZero();
                assertThat(evidence(original)).isEqualTo(purchaseEvidence);
                verifyNoInteractions(audit);
            } else {
                Prepared refundPrepared=prepare(refund);
                settle(refund,refundPrepared,true,false); // Actual INSERT and source NOW, deliberately no SLEEP.
                LocalDateTime refundAt=databaseLocalDateTime("SELECT created_at FROM nx_wallet_ledger WHERE id=?",refund.ledger);
                assertThat(refundAt.getNano()).isZero();
                assertThat(refundAt).as("If the real execution crosses seconds, this experiment must fail rather than pretend it covered the boundary")
                    .isEqualTo(purchaseAt.withNano(0));
                assertThat(refundAt).as("Zero precision loses the already-paid microseconds within this same real second").isBefore(purchaseAt);
                capture.record(refundPrepared);
                Map<String,String> refundEvidence=evidence(refund);
                assertThat(refundEvidence.get("capture_mode")).isEqualTo("NEW_SUCCESS");
                assertThat(refundEvidence.get("fractional_second_digits")).isEqualTo("0");
                assertThat(evidence(original)).isEqualTo(purchaseEvidence);
                capture.record(prepare(refund));
                assertThat(evidence(refund)).isEqualTo(refundEvidence);
                verify(audit,times(2)).recordRequired(any(AuditLogWriteRequest.class));
            }
        });
    }

    @Test
    void sandboxAndZeroActualPurchaseAreExcludedWithoutAttributionOrAudit() {
        rollbackFixtures(accounts -> {
            Fixture sandbox=pending(Source.WALLET_ORDER,newAccount(accounts,1));
            Prepared sandboxPrepared=prepare(sandbox);settle(sandbox,sandboxPrepared,true);capture.record(sandboxPrepared);
            Fixture zero=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            jdbc.update("UPDATE nx_order SET amount_usdt=0 WHERE order_no=?",zero.order);
            Prepared zeroPrepared=prepare(zero);
            jdbc.update("UPDATE nx_order SET payment_status='PAID',order_status='PAID',paid_at=NOW(6) WHERE order_no=?",zero.order);
            capture.record(zeroPrepared);
            assertThat(countForCustomer("nx_support_payment_attribution",sandbox.customer)).isZero();
            assertThat(countForCustomer("nx_support_payment_attribution",zero.customer)).isZero();
            verifyNoInteractions(audit);
        });
    }

    @Test
    void requiredAuditFailureRollsBackNewAccountBirthWalletSourceLedgerAndAttributionTogether() {
        Map<String,Long> before=outsideCounts();
        List<Long> accounts=new ArrayList<>();
        doThrow(new IllegalStateException("capture-audit-failure")).when(audit).recordRequired(any(AuditLogWriteRequest.class));
        try {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                long customer=newAccount(accounts,0);birth.registerNewAccount(customer);
                Fixture f=pending(Source.WALLET_ORDER,customer);
                Prepared prepared=prepare(f);settle(f,prepared,true);
                assertThat(countForCustomer("nx_support_payment_history_birth",customer)).isEqualTo(1);
                assertThat(countForCustomer("nx_wallet_ledger",customer)).isEqualTo(1);
                capture.record(prepared);
            })).hasMessage("capture-audit-failure");
        } finally {
            assertRollbackReadback(before,accounts);
        }
        verify(audit,times(1)).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void actualRequiresNewRejectsOuterPreparedButOuterResumeSucceedsAndCompletionReuseFails() {
        var completed=new AtomicReference<Prepared>();
        var completedBefore=new AtomicReference<FinanceSupportPaymentFactsFacade.BeforeSource>();
        rollbackFixtures(accounts -> {
            Fixture f=pending(Source.WALLET_ORDER,newAccount(accounts,0));
            Prepared outer=prepare(f);
            var sourceBefore=finance.beforeSource(f.customer,f.source,f.key);
            Object outerResource=physicalResource();
            TransactionTemplate inner=new TransactionTemplate(manager);
            inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            inner.setTimeout(10);
            inner.executeWithoutResult(status -> {
                try {
                    assertThat(physicalResource()).isNotSameAs(outerResource);
                    assertThatThrownBy(() -> capture.record(outer)).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
                    assertThatThrownBy(() -> finance.readSettled(sourceBefore)).hasMessage("INVALID_BEFORE_SOURCE_TRANSACTION");
                } finally { status.setRollbackOnly(); }
            });
            assertThat(physicalResource()).isSameAs(outerResource);
            settle(f,outer,true);capture.record(outer);
            assertThat(evidence(f).get("capture_mode")).isEqualTo("NEW_SUCCESS");
            completed.set(outer);completedBefore.set(sourceBefore);
        });
        transaction.executeWithoutResult(status -> {
            try {
                assertThatThrownBy(() -> capture.record(completed.get())).hasMessage("SUPPORT_PAYMENT_PREPARED_INVALID");
                assertThatThrownBy(() -> finance.readSettled(completedBefore.get())).hasMessage("INVALID_BEFORE_SOURCE_TRANSACTION");
            } finally { status.setRollbackOnly(); }
        });
    }

    @Test
    void committedBirthAndNewCaptureSurviveIndependentReadAndFreshTransactionOldReplayIsByteIdentical() {
        Map<String,Long> before=outsideCounts();
        List<Long> accounts=new ArrayList<>();
        AtomicReference<Fixture> fixture=new AtomicReference<>();
        try {
            transaction.executeWithoutResult(status -> {
                long customer=newAccount(accounts,0);birth.registerNewAccount(customer);
                Fixture f=pending(Source.WALLET_ORDER,customer);fixture.set(f);
                Prepared prepared=prepare(f);settle(f,prepared,true);capture.record(prepared);
            });
            Fixture f=fixture.get();
            Map<String,String> persisted=outsideEvidence(f);
            Map<String,String> originalBirth=outside.queryForObject("SELECT * FROM nx_support_payment_history_birth WHERE customer_id=?",
                (rs,n)->strings(rs),f.customer);
            assertThat(persisted.get("capture_mode")).isEqualTo("NEW_SUCCESS");
            assertThat(originalBirth.get("environment_status")).isEqualTo("PRODUCTION");
            assertThat(originalBirth.get("sandbox_at_birth")).isEqualTo("0");
            for(int read=0;read<2;read++) {
                Snapshot persistedHistory=transaction.execute(status -> finance.readHistory(List.of(f.customer)));
                assertThat(persistedHistory.firstHistory()).singleElement().satisfies(h -> {
                    assertThat(h.customerId()).isEqualTo(f.customer);assertThat(h.status()).isEqualTo(Status.READY);
                });
                assertThat(persistedHistory.facts()).singleElement().satisfies(payment -> {
                    assertThat(payment.factId()).isEqualTo(f.factId());
                    assertThat(payment.amount()).isEqualByComparingTo("10");
                });
            }
            transaction.executeWithoutResult(status -> {
                capture.record(prepare(f));
                jdbc.update("UPDATE nx_user SET sandbox=1 WHERE id=?",f.customer);
                birth.registerNewAccount(f.customer);
            });
            assertThat(outsideEvidence(f)).isEqualTo(persisted);
            Map<String,String> replayedBirth=outside.queryForObject("SELECT * FROM nx_support_payment_history_birth WHERE customer_id=?",
                (rs,n)->strings(rs),f.customer);
            assertThat(replayedBirth).isEqualTo(originalBirth);
            verify(audit,times(1)).recordRequired(any(AuditLogWriteRequest.class));
        } finally {
            // The exact committed fresh fixture IDs are removed in dependency order.
            cleanupOwnAccounts(accounts);
            assertRollbackReadback(before,accounts);
        }
    }

    @ParameterizedTest
    @CsvSource({"WALLET_ORDER,WALLET_ORDER","VIETQR,HDPAY","CARD_TOPUP,VIETQR","HDPAY,CARD_TOPUP"})
    void twoDifferentCustomersCommitCompleteCapturesAfterConcurrentPreparationWithoutMissingLedgerGapDeadlock(
            Source firstSource,Source secondSource) throws Exception {
        Map<String,Long> before=outsideCounts();
        List<Long> accounts=new ArrayList<>();
        List<Fixture> fixtures=new ArrayList<>();
        ExecutorService workers=Executors.newFixedThreadPool(2);
        Throwable primaryFailure=null;
        try {
            // Commit only fresh accounts/wallets/pending source roots, before the money transactions start.
            // Neither namespace has a payment or ledger. No extra fence is installed to hide missing-key gaps.
            transaction.executeWithoutResult(status -> {
                for(int i=0;i<2;i++) {
                    long customer=newAccount(accounts,0);
                    birth.registerNewAccount(customer);
                    fixtures.add(pending(i==0?firstSource:secondSource,customer));
                }
            });
            assertThat(fixtures.get(0).customer).isNotEqualTo(fixtures.get(1).customer);
            assertThat(fixtures.get(0).key).isNotEqualTo(fixtures.get(1).key);
            CyclicBarrier bothPrepared=new CyclicBarrier(2);
            List<Future<Map<String,String>>> writes=new ArrayList<>();
            for(Fixture f:fixtures) {
                writes.add(workers.submit(() -> transaction.execute(status -> {
                    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    Object resource=physicalResource();
                    Prepared prepared=prepare(f);
                    awaitBothPrepared(bothPrepared);
                    // Production beforeSource SELECTs have already read both absent ledger keys under RR.
                    // Keep the full wallet/source/ledger write path and let a real deadlock fail this test.
                    // There is deliberately no retry, serialized write fence, or DuplicateKey-only surrogate.
                    settle(f,prepared,true);
                    capture.record(prepared);
                    assertThat(physicalResource()).isSameAs(resource);
                    return evidence(f);
                })));
            }
            workers.shutdown();
            List<Map<String,String>> committedRows=new ArrayList<>();
            for(Future<Map<String,String>> write:writes) committedRows.add(write.get(45,TimeUnit.SECONDS));
            for(int i=0;i<fixtures.size();i++) {
                Fixture f=fixtures.get(i);
                Map<String,String> committed=committedRows.get(i);
                // These reads use the distinct, unbound DataSource: success requires a real commit.
                assertThat(outsideEvidence(f)).isEqualTo(committed);
                assertThat(committed.get("capture_mode")).isEqualTo("NEW_SUCCESS");
                assertThat(committed.get("customer_id")).isEqualTo(Long.toString(f.customer));
                assertThat(committed.get("source_business_id")).isEqualTo(f.key);
                assertThat(committed.get("ledger_id")).isEqualTo(Long.toString(f.ledger));
                BigDecimal available=outside.queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",
                    BigDecimal.class,f.customer);
                assertThat(available).isEqualByComparingTo(orderSource(f.source)?"490":"510");
                String type=switch(f.source) {
                    case WALLET_ORDER->"ORDER_PURCHASE";case CARD_TOPUP->"CARD_TOPUP";
                    case VIETQR,HDPAY->"VIETQR_DEPOSIT";default->throw new AssertionError(f.source);
                };
                BigDecimal amount=outside.queryForObject("SELECT amount FROM nx_wallet_ledger WHERE id=? AND user_id=? AND biz_no=? AND direction=? AND asset='USDT' AND biz_type=? AND status='SUCCESS'",
                    BigDecimal.class,f.ledger,f.customer,f.canonicalKey(),orderSource(f.source)?"OUT":"IN",type);
                assertThat(amount).isEqualByComparingTo("10");
                assertCommittedSource(f);
                assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_payment_attribution WHERE customer_id=?",
                    Long.class,f.customer)).isEqualTo(1);
            }
            transaction.executeWithoutResult(status -> {
                for(Fixture f:fixtures) capture.record(prepare(f));
            });
            for(int i=0;i<fixtures.size();i++) assertThat(outsideEvidence(fixtures.get(i))).isEqualTo(committedRows.get(i));
            verify(audit,times(2)).recordRequired(any(AuditLogWriteRequest.class));
        } catch(Exception | Error failure) {
            primaryFailure=failure;
            throw failure;
        } finally {
            // A deadlock victim may roll back while the other payment commits. Wait for BOTH before cleanup.
            // Cleanup must never race a still-running money transaction or touch another account's rows.
            try {
                awaitCaptureWorkers(workers);
                cleanupOwnSourceFixtures(fixtures);
                cleanupOwnAccounts(accounts);
                assertRollbackReadback(before,accounts);
            } catch(Exception | Error cleanupFailure) {
                // Keep the actual transaction/deadlock failure as the primary evidence.
                // If workers cannot terminate, awaitCaptureWorkers throws before any fixture deletion.
                if(primaryFailure==null)throw cleanupFailure;
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void awaitBothPrepared(CyclicBarrier barrier) {
        try { barrier.await(15,TimeUnit.SECONDS); }
        catch(InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent capture preparation interrupted",failure);
        } catch(BrokenBarrierException | TimeoutException failure) {
            throw new IllegalStateException("Both money transactions must prepare before either writes",failure);
        }
    }

    private void assertCommittedSource(Fixture f) {
        long rows=switch(f.source) {
            case WALLET_ORDER -> outside.queryForObject("SELECT COUNT(*) FROM nx_order o JOIN nx_payment_record p ON p.payment_no=o.payment_no AND p.order_no=o.order_no AND p.user_id=o.user_id WHERE o.user_id=? AND o.order_no=? AND o.payment_status='PAID' AND p.payment_status='CONFIRMED' AND p.wallet_ledger_id=? AND o.amount_usdt=10 AND p.amount_usdt=10",
                Long.class,f.customer,f.order,f.ledger);
            case CARD_TOPUP -> outside.queryForObject("SELECT COUNT(*) FROM nx_topup_card_settlement s JOIN nx_payment_record p ON p.payment_no=s.payment_no AND p.user_id=s.user_id WHERE s.settlement_event_id=? AND s.user_id=? AND s.payment_no=? AND s.status='SETTLED' AND p.payment_status='CONFIRMED' AND p.wallet_ledger_id=? AND p.amount_usdt=10",
                Long.class,f.name,f.customer,f.key,f.ledger);
            case VIETQR -> outside.queryForObject("SELECT COUNT(*) FROM nx_vietqr_reconciliation r JOIN nx_vietqr_intent i ON i.intent_no=r.intent_no AND i.user_id=r.user_id WHERE r.reconciliation_no=? AND r.user_id=? AND r.intent_no=? AND r.status='CREDITED' AND r.view_type='MATCHED' AND r.credited_usdt=10 AND i.status='CREDITED' AND i.credited_usdt=10 AND i.payment_rail='MANUAL' AND i.settlement_target_type='WALLET_TOPUP'",
                Long.class,f.receipt,f.customer,f.partition);
            case HDPAY -> outside.queryForObject("SELECT COUNT(*) FROM nx_hdpay_payin_order h JOIN nx_vietqr_intent i ON i.intent_no=h.merchant_order_id WHERE h.merchant_order_id=? AND i.user_id=? AND h.settlement_status='CREDITED' AND h.settled_usdt=10 AND h.wallet_ledger_biz_no=? AND i.status='CREDITED' AND i.credited_usdt=10 AND i.payment_rail='HDPAY' AND i.settlement_target_type='WALLET_TOPUP'",
                Long.class,f.key,f.customer,f.key);
            default -> throw new AssertionError(f.source);
        };
        assertThat(rows).isEqualTo(1);
    }

    private void cleanupOwnSourceFixtures(List<Fixture> fixtures) {
        transaction.executeWithoutResult(status -> {
            for(Fixture f:fixtures) {
                assertThat(f.name).startsWith("SC");
                switch(f.source) {
                    case VIETQR -> {
                        jdbc.update("DELETE FROM nx_vietqr_reconciliation WHERE reconciliation_no=? AND (user_id IS NULL OR user_id=?) AND (intent_no IS NULL OR intent_no=?)",f.receipt,f.customer,f.partition);
                        jdbc.update("DELETE FROM nx_vietqr_intent WHERE intent_no=? AND user_id=?",f.partition,f.customer);
                    }
                    case HDPAY -> {
                        jdbc.update("DELETE FROM nx_hdpay_payin_order WHERE merchant_order_id=?",f.key);
                        jdbc.update("DELETE FROM nx_vietqr_intent WHERE intent_no=? AND user_id=?",f.key,f.customer);
                    }
                    case CARD_TOPUP -> jdbc.update("DELETE FROM nx_topup_card_settlement WHERE settlement_event_id=? AND payment_no=? AND user_id=?",f.name,f.key,f.customer);
                    case WALLET_ORDER -> { }
                    default -> throw new AssertionError(f.source);
                }
            }
        });
    }

    private static void awaitCaptureWorkers(ExecutorService workers) throws InterruptedException {
        workers.shutdown();
        if(!workers.awaitTermination(45,TimeUnit.SECONDS)) {
            workers.shutdownNow();
            if(!workers.awaitTermination(15,TimeUnit.SECONDS))
                throw new IllegalStateException("Capture transactions are still active; exact fixture cleanup cannot safely race them");
        }
    }

    private void applyStructureOnlyTwice() {
        long users=jdbc.queryForObject("SELECT COUNT(*) FROM nx_user",Long.class);
        boolean existed=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_support_payment_history_birth'",
            Long.class)>0;
        List<Map<String,Object>> births=existed?jdbc.queryForList("SELECT * FROM nx_support_payment_history_birth ORDER BY customer_id"):List.of();
        boolean billExisted=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_wallet_bill'",
            Long.class)>0;
        long bills=billExisted?jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_bill",Long.class):0;
        for(int attempt=0;attempt<2;attempt++) {
            ResourceDatabasePopulator ddl=new ResourceDatabasePopulator(
                new FileSystemResource("scripts/migrations/20261009_e4_wallet_bill_schema_precision_forward.sql"),
                new FileSystemResource("scripts/migrations/20261008_support_payment_attribution.sql"),
                new FileSystemResource("scripts/migrations/20261008_support_payment_history_birth.sql"));
            ddl.execute(dataSource);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user",Long.class)).isEqualTo(users);
            assertThat(jdbc.queryForList("SELECT * FROM nx_support_payment_history_birth ORDER BY customer_id")).isEqualTo(births);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_bill",Long.class)).isEqualTo(bills);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T service) {
        ProxyFactory factory=new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }

    private FinanceSupportPaymentFactsFacade historyReader(SupportPaymentCaptureHistoryMapper mapper) {
        SupportPaymentCaptureHistoryFacade reader=proxy(new SupportPaymentCaptureHistoryService(mapper,birthMapper));
        return proxy(new SupportPaymentSourceService(sourceMapper,history,dataSource,json,reader));
    }

    private void assertCapturedHistory(Fixture fixture,Map<String,String> saved,Snapshot result) {
        JsonNode financial=tree(saved.get("source_fact_json"));
        var matches=result.facts().stream().filter(fact -> fact.factId().equals(fixture.factId())).toList();
        assertThat(matches).hasSize(1);
        Fact fact=matches.get(0);
        assertThat(fact.customerId()).isEqualTo(fixture.customer);
        assertThat(fact.source()).isEqualTo(fixture.source);
        assertThat(fact.ledgerId()).isEqualTo(fixture.ledger);
        assertThat(fact.sourceBusinessId()).isEqualTo(fixture.canonicalKey());
        assertThat(fact.amount()).isEqualByComparingTo(new BigDecimal(saved.get("amount")));
        assertThat(fact.currency()).isEqualTo(saved.get("currency"));
        assertThat(fact.succeededAt()).isEqualTo(LocalDateTime.parse(financial.path("succeededAt").asText()));
        assertThat(fact.ledgerRecordedAt()).isEqualTo(LocalDateTime.parse(financial.path("ledgerRecordedAt").asText()));
        assertThat(fact.successTimeField()).isEqualTo(financial.path("successTimeField").asText());
        assertThat(fact.fractionalSecondDigits()).isEqualTo(financial.path("fractionalSecondDigits").intValue());
        if(financial.path("sourceConfirmationAt").isNull())assertThat(fact.sourceConfirmationAt()).isNull();
        else assertThat(fact.sourceConfirmationAt()).isEqualTo(LocalDateTime.parse(financial.path("sourceConfirmationAt").asText()));
        if(financial.path("providerPaidAt").isNull())assertThat(fact.providerPaidAt()).isNull();
        else assertThat(fact.providerPaidAt()).isEqualTo(LocalDateTime.parse(financial.path("providerPaidAt").asText()));
        if(fixture.source==Source.ORDER_REFUND)
            assertThat(fact.originalFactId()).isEqualTo(fixture.original.factId());
        assertThat(fact.historicalEnvironmentStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(result.businessZone()).isEqualTo("Asia/Shanghai");
        assertUnknownCoverage(result);
    }

    private static void assertUnknownCoverage(Snapshot result) {
        assertThat(result.coverage()).hasSize(Source.values().length).allSatisfy(coverage -> {
            assertThat(coverage.historyStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(coverage.refundStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(coverage.historicalEnvironmentStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(coverage.supportedFrom()).isNull();
        });
    }

    private Result enrichedStatsAs(long actor,Query query) {
        var previous=SecurityContextHolder.getContext();var context=SecurityContextHolder.createEmptyContext();
        var authorities=query.mode()==ReadMode.ALL?List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("platform_a1_read")):List.<org.springframework.security.core.authority.SimpleGrantedAuthority>of();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(actor),"fixture-only",authorities));
        SecurityContextHolder.setContext(context);
        try {return enrichedStatistics.summarize(query);}
        finally {SecurityContextHolder.setContext(previous);}
    }

    private Result freshEnrichedStatsAs(long actor,Query query) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TransactionTemplate reader=new TransactionTemplate(manager);reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reader.setReadOnly(true);reader.setTimeout(30);
        return reader.execute(status->{Object resource=readerRrResource();Result result=enrichedStatsAs(actor,query);assertThat(physicalResource()).isSameAs(resource);return result;});
    }

    private static Long nativeAcquisition(SupportAnalyticsStats.DeviceSummary stock,SupportAnalyticsStats.Acquisition acquisition) {
        return stock.partitions().stream().filter(p->p.dimension().equals("ACQUISITION")&&p.value().equals(acquisition.name())).findFirst().orElseThrow().devices().confirmedValue();
    }

    private void nativeQualification(long actor,String kind,String state) {
        assertThat(statsFixtureAdminIds).contains(actor);
        assertThat(jdbc.update("""
            INSERT INTO nx_support_account_qualification_history(admin_id,qualification_kind,state,starts_at,version,reason,operation_id)
            VALUES(?,?,?,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY),1,'owned enriched metrics fixture',?)
            """,actor,kind,state,unique())).isEqualTo(1);
    }

    private void nativeLoginEvent(long customer,long seq,LocalDateTime at) {
        assertThat(jdbc.queryForObject("SELECT nickname FROM nx_user WHERE id=?",String.class,customer)).isEqualTo("support-capture-fixture");
        // Persist the real interactive-login row shape; this tests statistics, not authentication/session issuance.
        String source="INTERACTIVE_LOGIN:"+UUID.randomUUID();
        assertThat(jdbc.update("INSERT INTO nx_support_activity_event(customer_id,seq,source_ref,occurred_at) VALUES(?,?,?,?)",customer,seq,source,at)).isEqualTo(1);
        nativeActivityFixtureIds.add(jdbc.queryForObject("SELECT id FROM nx_support_activity_event WHERE customer_id=? AND seq=? AND source_ref=?",Long.class,customer,seq,source));
    }

    private Map<String,List<Map<String,String>>> outsideMetricActivityRows() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        // The additional activity/rules baseline also uses one complete fresh-connection snapshot.
        return outside.execute((ConnectionCallback<Map<String,List<Map<String,String>>>>) connection->{
            Map<String,List<Map<String,String>>> result=new LinkedHashMap<>();
            try(var statement=connection.createStatement()) {
                for(String table:List.of("nx_support_rules","nx_support_activity_coverage")) {
                    var rows=new ArrayList<Map<String,String>>();
                    try(var selected=statement.executeQuery("SELECT * FROM "+table+" ORDER BY id")){while(selected.next())rows.add(strings(selected));}
                    result.put(table,rows);
                }
                for(String table:List.of("nx_support_activity_event","nx_support_activity_state"))
                    try(var selected=statement.executeQuery("SELECT COUNT(*) rowCount FROM "+table)){assertThat(selected.next()).isTrue();result.put(table,List.of(strings(selected)));assertThat(selected.next()).isFalse();}
            }
            return result;
        });
    }

    private void cleanupEnrichedOwnedRows(List<Long> accounts) {
        transaction.executeWithoutResult(status->{
            for(long customer:accounts) {
                var names=jdbc.queryForList("SELECT nickname FROM nx_user WHERE id=?",String.class,customer);
                if(names.isEmpty())continue;assertThat(names).containsExactly("support-capture-fixture");
                jdbc.update("DELETE FROM nx_support_agent_user_assignment WHERE user_id=?",customer);
                jdbc.update("DELETE FROM nx_support_customer_route_history WHERE customer_id=?",customer);
                for(long device:readerFixtureDeviceIds) {
                    jdbc.update("DELETE FROM nx_user_device_runtime WHERE user_device_id=? AND EXISTS(SELECT 1 FROM nx_user_device d WHERE d.id=? AND d.user_id=?)",device,device,customer);
                    jdbc.update("DELETE FROM nx_user_device WHERE id=? AND user_id=? AND name='support-reader-fixture'",device,customer);
                }
            }
            for(long actor:statsFixtureAdminIds) {
                var names=jdbc.queryForList("SELECT username FROM nx_admin WHERE id=?",String.class,actor);if(names.isEmpty())continue;
                assertThat(names).singleElement().satisfies(name->assertThat(name).startsWith("stats-SC"));
                jdbc.update("DELETE FROM nx_support_group_member_history WHERE agent_admin_id=?",actor);
            }
            for(long group:statsFixtureGroupIds) {
                var names=jdbc.queryForList("SELECT name FROM nx_support_group WHERE id=?",String.class,group);if(names.isEmpty())continue;
                assertThat(names).singleElement().satisfies(name->assertThat(name).startsWith("stats-SC"));
                jdbc.update("DELETE FROM nx_support_group_owner_history WHERE group_id=?",group);jdbc.update("DELETE FROM nx_support_group WHERE id=?",group);
            }
            for(long actor:statsFixtureAdminIds) {
                jdbc.update("DELETE FROM nx_support_agent_profile WHERE admin_id=?",actor);jdbc.update("DELETE FROM nx_support_account_qualification_history WHERE admin_id=?",actor);
                jdbc.update("DELETE FROM nx_admin_role_relation WHERE admin_id=?",actor);jdbc.update("DELETE FROM nx_admin WHERE id=? AND username LIKE 'stats-SC%'",actor);
            }
        });
    }

    private void rollbackFixtures(Consumer<List<Long>> body) {
        Map<String,Long> before=outsideCounts();
        List<Long> accounts=new ArrayList<>();
        try {
            transaction.executeWithoutResult(status -> {
                try { body.accept(accounts); }
                finally { status.setRollbackOnly(); }
            });
        } finally { assertRollbackReadback(before,accounts); }
    }

    private Map<String,Long> outsideCounts() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        // One fresh connection per complete snapshot avoids exhausting Windows client sockets.
        return outside.execute((ConnectionCallback<Map<String,Long>>) connection -> {
            Map<String,Long> result=new LinkedHashMap<>();
            try(var statement=connection.createStatement()) {
                for(String table:TABLES) try(var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)) {
                    assertThat(rows.next()).isTrue();
                    result.put(table,rows.getLong(1));
                    assertThat(rows.next()).isFalse();
                }
            }
            return result;
        });
    }

    private void assertRollbackReadback(Map<String,Long> before,List<Long> accounts) {
        assertThat(outsideCounts()).isEqualTo(before);
        for(long customer:accounts) {
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_user WHERE id=?",Long.class,customer)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_payment_history_birth WHERE customer_id=?",Long.class,customer)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_payment_attribution WHERE customer_id=?",Long.class,customer)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_wallet_bill WHERE user_id=?",Long.class,customer)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_agent_user_assignment WHERE user_id=?",Long.class,customer)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_customer_route_history WHERE customer_id=?",Long.class,customer)).isZero();
        }
        for(long admin:statsFixtureAdminIds) {
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_admin WHERE id=?",Long.class,admin)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_admin_role_relation WHERE admin_id=?",Long.class,admin)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_agent_profile WHERE admin_id=?",Long.class,admin)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_account_qualification_history WHERE admin_id=?",Long.class,admin)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_group_member_history WHERE agent_admin_id=?",Long.class,admin)).isZero();
        }
        for(long group:statsFixtureGroupIds) {
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_group WHERE id=?",Long.class,group)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_group_owner_history WHERE group_id=?",Long.class,group)).isZero();
        }
        for(long customer:statsFixtureCustomerIds) {
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_user_wallet WHERE user_id=?",Long.class,customer)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=?",Long.class,customer)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_payment_record WHERE user_id=?",Long.class,customer)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_order WHERE user_id=?",Long.class,customer)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_deposit_order WHERE user_id=?",Long.class,customer)).isZero();
        }
        for(long device:readerFixtureDeviceIds) {
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_user_device WHERE id=?",Long.class,device)).isZero();
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_user_device_runtime WHERE user_device_id=?",Long.class,device)).isZero();
        }
        for(long runtime:readerFixtureRuntimeIds)
            assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_user_device_runtime WHERE id=?",Long.class,runtime)).isZero();
    }

    private long statsActor(String qualification) {
        String role=qualification.equals("ALL")?"SUPER_ADMIN":"SUPPORT";
        List<Long> roles=jdbc.queryForList("SELECT id FROM nx_admin_role WHERE role_code=? AND status=1 AND is_deleted=0",Long.class,role);
        assertThat(roles).as("Reuse the existing active canonical role; never seed or modify shared RBAC").hasSize(1);
        String username="stats-"+unique();
        assertThat(jdbc.update("INSERT INTO nx_admin(username,password_hash,nickname,status,version,is_deleted) VALUES(?,'fixture-only','support-stats-fixture',1,1,0)",username)).isEqualTo(1);
        long actor=jdbc.queryForObject("SELECT id FROM nx_admin WHERE username=?",Long.class,username);
        statsFixtureAdminIds.add(actor);
        assertThat(jdbc.update("INSERT INTO nx_admin_role_relation(admin_id,role_id) VALUES(?,?)",actor,roles.get(0))).isEqualTo(1);
        if(!qualification.equals("ALL")) {
            assertThat(jdbc.update("""
                INSERT INTO nx_support_account_qualification_history(admin_id,qualification_kind,state,starts_at,version,reason,operation_id)
                VALUES(?,?,'ENABLED',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY),1,'owned stats fixture',?)
                """,actor,qualification,unique())).isEqualTo(1);
            if(qualification.equals("SERVICE"))
                assertThat(jdbc.update("""
                    INSERT INTO nx_support_agent_profile(admin_id,seat_type,position,service_types,tags,enabled,version)
                    VALUES(?,'DEDICATED','owned stats fixture','advisor','',1,1)
                    """,actor)).isEqualTo(1);
        }
        return actor;
    }

    private long statsGroup(long owner) {
        assertThat(statsFixtureAdminIds).contains(owner);
        String name="stats-"+unique();
        assertThat(jdbc.update("""
            INSERT INTO nx_support_group(name,supervisor_admin_id,status,version,created_at,updated_at)
            VALUES(?,?,'ENABLED',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """,name,owner)).isEqualTo(1);
        long group=jdbc.queryForObject("SELECT id FROM nx_support_group WHERE name=? AND supervisor_admin_id=?",Long.class,name,owner);
        statsFixtureGroupIds.add(group);
        assertThat(jdbc.update("""
            INSERT INTO nx_support_group_owner_history(group_id,supervisor_admin_id,starts_at,version,reason,operation_id)
            VALUES(?,?,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY),1,'owned stats fixture',?)
            """,group,owner,unique())).isEqualTo(1);
        return group;
    }

    private void statsMember(long agent,long group) {
        assertThat(statsFixtureAdminIds).contains(agent);assertThat(statsFixtureGroupIds).contains(group);
        boolean prior=jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_group_member_history WHERE agent_admin_id=?",Long.class,agent)>0;
        assertThat(jdbc.update("""
            INSERT INTO nx_support_group_member_history(agent_admin_id,group_id,starts_at,version,reason,operation_id)
            VALUES(?,?,CASE WHEN ? THEN UTC_TIMESTAMP(6) ELSE DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY) END,1,'owned stats fixture',?)
            """,agent,group,prior,unique())).isEqualTo(1);
    }

    private void statsBind(long customer,long agent,boolean future) {
        boolean prior=jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_agent_user_assignment WHERE user_id=?",Long.class,customer)>0;
        LocalDateTime starts=databaseLocalDateTime(future?"SELECT DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY)"
            :prior?"SELECT UTC_TIMESTAMP()":"SELECT DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY)");
        statsBind(customer,agent,starts);
    }

    private void statsBind(long customer,long agent,LocalDateTime starts) {
        assertThat(statsFixtureAdminIds).contains(agent);
        assertThat(jdbc.queryForObject("SELECT nickname FROM nx_user WHERE id=?",String.class,customer)).isEqualTo("support-capture-fixture");
        if(!statsFixtureCustomerIds.contains(customer))statsFixtureCustomerIds.add(customer);
        assertThat(jdbc.update("""
            INSERT INTO nx_support_agent_user_assignment(agent_admin_id,user_id,status,starts_at,version,source,segment_root_id,depth,operation_id)
            VALUES(?,?,'ACTIVE',?,1,'MANUAL',?,0,?)
            """,agent,customer,starts,customer,unique())).isEqualTo(1);
    }

    private void statsRoute(long customer,long group) {
        assertThat(statsFixtureGroupIds).contains(group);
        assertThat(jdbc.queryForObject("SELECT nickname FROM nx_user WHERE id=?",String.class,customer)).isEqualTo("support-capture-fixture");
        if(!statsFixtureCustomerIds.contains(customer))statsFixtureCustomerIds.add(customer);
        assertThat(jdbc.update("""
            INSERT INTO nx_support_customer_route_history(customer_id,group_id,starts_at,version,reason,operation_id)
            VALUES(?,?,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY),1,'owned stats fixture',?)
            """,customer,group,unique())).isEqualTo(1);
    }

    private Result statsAs(long actor,Query query) {
        var previous=SecurityContextHolder.getContext();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(actor),"fixture-only",List.of()));
        SecurityContextHolder.setContext(context);
        try { return statistics.summarize(query); }
        finally { SecurityContextHolder.setContext(previous); }
    }

    private static Query statsQuery(ReadMode mode,Long group,Basis basis,String currency) {
        return new Query(mode,group,null,basis,null,null,"Asia/Shanghai",currency);
    }

    private Query statsPeriod(ReadMode mode,Long group) {
        LocalDateTime now=databaseLocalDateTime("SELECT NOW(6)");
        return new Query(mode,group,null,Basis.PERIOD_EVENT,now.minusDays(1),now.plusDays(1),"Asia/Shanghai","USDT");
    }

    private static SupportAnalyticsStats.CurrencyTotals statsCurrency(Result result,String currency) {
        return result.financialSummary().currencies().stream().filter(c -> c.currency().equals(currency)).findFirst().orElseThrow();
    }

    private static void assertStatsUncertified(Result result) {
        assertThat(result.financialSummary().firstCandidates().confirmedValue()).isNull();
        assertThat(result.financialSummary().firstCandidates().status()).isEqualTo(SupportAnalyticsStats.Status.UNKNOWN);
        assertThat(result.currentCustomers()).allSatisfy(c -> assertThat(c.first().status()).isEqualTo(SupportAnalyticsStats.Status.UNKNOWN));
        assertThat(result.coverage()).hasSize(Source.values().length).allSatisfy(c -> {
            assertThat(c.historyStatus()).isEqualTo("UNKNOWN");assertThat(c.refundStatus()).isEqualTo("UNKNOWN");
            assertThat(c.historicalEnvironmentStatus()).isEqualTo("UNKNOWN");assertThat(c.supportedFrom()).isNull();
        });
        assertThat(result.financialSummary().currencies()).allSatisfy(c -> {
            for(var money:List.of(c.deposits(),c.purchases(),c.purchaseRefunds(),c.net())) {
                assertThat(money.confirmedAmount()).isNull();assertThat(money.confirmedEvents()).isNull();
            }
            assertThat(c.net().observedAmount()).isNull();assertThat(c.net().status()).isEqualTo(SupportAnalyticsStats.Status.UNKNOWN);
        });
    }

    private Object readerRrResource() {
        Object resource=physicalResource();
        assertThat(jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ");
        return resource;
    }

    private void readerSponsor(long customer,long sponsor) {
        assertThat(jdbc.update("UPDATE nx_user SET sponsor_user_id=? WHERE id=? AND nickname='support-capture-fixture'",
            sponsor,customer)).isEqualTo(1);
    }

    private long readerDevice(long customer,String ownership,String lifecycle,String channel,String order,String type) {
        String instance=unique();
        assertThat(jdbc.queryForObject("SELECT nickname FROM nx_user WHERE id=?",String.class,customer))
            .isEqualTo("support-capture-fixture");
        assertThat(jdbc.update("""
            INSERT INTO nx_user_device(user_id,instance_no,name,device_type,ownership_status,source_channel,
                source_order_no,source_environment,run_id,status,hashrate,activated_at,deactivated_at,is_deleted)
            VALUES(?,?,'support-reader-fixture',?,?,?,?, 'PRODUCTION','',?,12.345678,
                CASE WHEN ? IN ('INVENTORY','PENDING','PENDING_ACTIVATION') THEN NULL ELSE DATE_SUB(NOW(),INTERVAL 1 DAY) END,
                CASE WHEN ? IN ('DEACTIVATED','INACTIVE','INVENTORY','RECYCLED','RETIRED') THEN NOW() ELSE NULL END,0)
            """,customer,instance,type,ownership,channel,order,lifecycle,lifecycle,lifecycle)).isEqualTo(1);
        long device=jdbc.queryForObject("SELECT id FROM nx_user_device WHERE instance_no=? AND user_id=?",
            Long.class,instance,customer);
        readerFixtureDeviceIds.add(device);
        return device;
    }

    private void readerRuntime(long device,String reported,LocalDateTime heartbeat,int deleted) {
        assertThat(readerFixtureDeviceIds).contains(device);
        assertThat(jdbc.update("""
            INSERT INTO nx_user_device_runtime(user_device_id,online_status,heartbeat_at,paused_reason,
                active_task_no,network_reachable,is_deleted)
            VALUES(?,?,?,'maintenance',?,1,?)
            """,device,reported,heartbeat,"reader-task-"+device,deleted)).isEqualTo(1);
        long runtime=jdbc.queryForObject("SELECT id FROM nx_user_device_runtime WHERE user_device_id=?",Long.class,device);
        readerFixtureRuntimeIds.add(runtime);
    }

    private long newAccount(List<Long> accounts,int sandbox) {
        String identity=unique();
        jdbc.update("INSERT INTO nx_user(country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox) VALUES('0',?,'127.0.0.1','fixture-only','support-capture-fixture',?,'ACTIVE',?)",
            identity,identity,sandbox);
        long customer=jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,identity);
        accounts.add(customer);
        jdbc.update("INSERT INTO nx_user_wallet(user_id,usdt_available,cumulative_deposit_usdt) VALUES(?,500,0)",customer);
        return customer;
    }

    private Fixture pending(Source source,long customer) {
        Fixture f=new Fixture(source,customer,unique(),databaseLocalDateTime("SELECT NOW(6)").withNano(0));
        switch(source) {
            case DEPOSIT_ORDER -> {
                f.cid=positiveUnique();f.partition=Long.toString(positiveUnique());f.key="CR-"+f.cid;
                jdbc.update("INSERT INTO nx_cregis_deposit_event(user_id,project_id,cid,txid,log_index,address,gross_amount,fee_amount,net_amount,block_number,block_hash,confirmations,status) VALUES(?,?,?, ?,0,?,10,0,10,1,?,20,'RISK_HOLD')",
                    customer,Long.parseLong(f.partition),f.cid,f.name,f.name,f.name);
                jdbc.update("UPDATE nx_user_wallet SET cregis_risk_held=10 WHERE user_id=?",customer);
            }
            case CARD_TOPUP -> {
                f.key=f.name;f.order="CARD-"+f.name;
                payment(f,"CapturePSP","CONFIRMED",null,f.base);
                jdbc.update("INSERT INTO nx_topup_card_settlement(settlement_event_id,request_hash,admission_event_id,payment_no,order_no,user_id,provider,provider_payment_id,amount_usdt,fee_amount_usdt,fee_rate_pct,status) VALUES(?,REPEAT('a',64),?,?,?,?,'CapturePSP',?,10,0,0,'PROCESSING')",
                    f.name,"admission-"+f.name,f.key,f.order,customer,f.name);
            }
            case VIETQR,HDPAY -> {
                String intent="INT-"+f.name;f.receipt="REC-"+f.name;
                f.key=source==Source.VIETQR?"D1-VIETQR-"+f.receipt:intent;
                f.partition=source==Source.VIETQR?intent:null;
                jdbc.update("INSERT INTO nx_vietqr_intent(intent_no,user_id,create_idempotency_key,create_request_hash,requested_usdt,payable_vnd,credited_usdt,received_vnd,locked_fx_rate_vnd_per_usdt,fx_quote_version,bank_account_id,memo_code,status,expires_at,payment_rail,settlement_target_type) VALUES(?,?,?,REPEAT('a',64),10,250000,0,NULL,25000,1,1,?,'AWAITING_PAYMENT',?,?, 'WALLET_TOPUP')",
                    intent,customer,f.name,f.name,f.base.plusDays(1),source==Source.VIETQR?"MANUAL":"HDPAY");
                if(source==Source.VIETQR) jdbc.update("INSERT INTO nx_vietqr_reconciliation(reconciliation_no,view_type,status,locked_fx_rate_vnd_per_usdt) VALUES(?,'ORPHAN','OPEN',25000)",f.receipt);
                else jdbc.update("INSERT INTO nx_hdpay_payin_order(merchant_order_id,amount_vnd,submission_status,settlement_status,request_hash) VALUES(?,250000,'CREATED','UNSETTLED',REPEAT('a',64))",intent);
            }
            case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP -> {
                f.key=f.name;f.order=f.name;f.orderType=source==Source.WALLET_ORDER?"SINGLE":source.name();
                order(f,false,null);
            }
            case TRIAL_CONVERT -> {
                f.claim="CL-"+f.name;f.key="USER:"+customer;f.order="TC-"+f.name;f.orderType="TRIAL_CONVERT";
                jdbc.update("INSERT INTO nx_user_device(user_id,instance_no,name,device_type,source_channel,source_environment,run_id,status,is_deleted) VALUES(?,?,'capture-trial','BOX','TRIAL','PRODUCTION','','ACTIVE',0)",customer,f.name);
                f.device=jdbc.queryForObject("SELECT id FROM nx_user_device WHERE instance_no=?",Long.class,f.name);
                jdbc.update("INSERT INTO nx_trial_claim(user_id,claim_no,user_device_id,device_name,status,claimed_at,expires_at) VALUES(?,?,?,'capture-trial','ACTIVE',?,?)",
                    customer,f.claim,f.device,f.base.minusDays(1),f.base.plusDays(1));
            }
            case ORDER_REFUND -> {
                f.original=pending(Source.WALLET_ORDER,customer);
                Prepared original=prepare(f.original);settle(f.original,original,true);capture.record(original);
                f.order=f.original.order;f.key="E4-REFUND-"+f.order;f.base=databaseLocalDateTime("SELECT NOW(6)").withNano(0);
            }
            default -> throw new IllegalArgumentException("Not an eligible source");
        }
        return f;
    }

    private void settle(Fixture f,Prepared prepared,boolean crossSecond) {
        settle(f,prepared,crossSecond,crossSecond);
    }

    private void settle(Fixture f,Prepared prepared,boolean crossSecond,boolean waitForNextSecond) {
        settle(f,prepared,crossSecond,waitForNextSecond,false);
    }

    private void settle(Fixture f,Prepared prepared,boolean crossSecond,boolean waitForNextSecond,boolean cregisEventBeforeOrder) {
        if(cregisEventBeforeOrder && (f.source!=Source.DEPOSIT_ORDER || !crossSecond || waitForNextSecond))
            throw new IllegalArgumentException("Cregis event-first fixture requires separate actual SQL times");
        // Normal cross-second fixtures deliberately separate the actual source statements.
        // The explicit same-second precision experiment opts out without editing either receipt tuple.
        if(f.source==Source.ORDER_REFUND && waitForNextSecond) databasePause();
        BigDecimal before=jdbc.queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=?",BigDecimal.class,f.customer);
        BigDecimal after=before.add(new BigDecimal(orderSource(f.source)?"-10.000000":"10.000000"));
        jdbc.update("UPDATE nx_user_wallet SET usdt_available=?,cumulative_deposit_usdt=cumulative_deposit_usdt+? WHERE user_id=?",
            after,f.source==Source.ORDER_REFUND || orderSource(f.source)?0:10,f.customer);
        assertThat(capture.insertLedger(prepared,new BigDecimal("10.000000"),after,"support capture fixture "+f.name)).isEqualTo(1);
        f.ledger=jdbc.queryForObject("SELECT id FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND asset='USDT' AND direction=?",
            Long.class,f.customer,f.canonicalKey(),orderSource(f.source)?"OUT":"IN");
        LocalDateTime ledgerAt=databaseLocalDateTime("SELECT created_at FROM nx_wallet_ledger WHERE id=?",f.ledger);
        if(waitForNextSecond) databasePause();
        // The positive path uses actual database time after the actual canonical INSERT.
        // The false path models a legacy success whose source timestamps equal its stored ledger second.
        LocalDateTime successAt=crossSecond?databaseLocalDateTime(orderSource(f.source)?"SELECT NOW(6)":"SELECT NOW()"):ledgerAt;
        switch(f.source) {
            case DEPOSIT_ORDER -> {
                // CregisDepositService links the event before inserting the deposit order; each uses its own NOW().
                if(cregisEventBeforeOrder) {
                    successTimeWrite(f,true,"UPDATE nx_cregis_deposit_event SET status='CREDITED',ledger_id=?,credited_at=/*SOURCE_TIME*/? WHERE project_id=? AND cid=?",1,
                        f.ledger,successAt,Long.parseLong(f.partition),f.cid);
                    databasePause();
                }
                successTimeWrite(f,crossSecond,"INSERT INTO nx_deposit_order(user_id,deposit_no,chain_name,chain_tx_hash,asset,amount,status,ledger_id,credited_at,created_at) VALUES(?,?,'BEP20',?,'USDT',10,'CREDITED',?,/*SOURCE_TIME*/?,?)",4,
                    f.customer,f.key,f.name,f.ledger,successAt,f.base);
                if(!cregisEventBeforeOrder)
                    successTimeWrite(f,crossSecond,"UPDATE nx_cregis_deposit_event SET status='CREDITED',ledger_id=?,credited_at=/*SOURCE_TIME*/? WHERE project_id=? AND cid=?",1,
                        f.ledger,successAt,Long.parseLong(f.partition),f.cid);
                jdbc.update("UPDATE nx_user_wallet SET cregis_risk_held=0 WHERE user_id=?",f.customer);
            }
            case CARD_TOPUP -> {
                successTimeWrite(f,crossSecond,"UPDATE nx_payment_record SET wallet_ledger_id=?,paid_at=/*SOURCE_TIME*/? WHERE payment_no=?",1,f.ledger,successAt,f.key);
                jdbc.update("UPDATE nx_topup_card_settlement SET status='SETTLED' WHERE payment_no=?",f.key);
            }
            case VIETQR,HDPAY -> {
                String intent=f.source==Source.VIETQR?f.partition:f.key;
                jdbc.update("UPDATE nx_vietqr_intent SET status='CREDITED',credited_usdt=10,received_vnd=250000 WHERE intent_no=?",intent);
                if(f.source==Source.VIETQR) successTimeWrite(f,crossSecond,"UPDATE nx_vietqr_reconciliation SET intent_no=?,user_id=?,view_type='MATCHED',status='CREDITED',payable_vnd=250000,received_vnd=250000,credited_usdt=10,received_at=/*SOURCE_TIME*/? WHERE reconciliation_no=?",2,
                    intent,f.customer,successAt,f.receipt);
                else successTimeWrite(f,crossSecond,"UPDATE nx_hdpay_payin_order SET settlement_status='CREDITED',settled_usdt=10,wallet_ledger_biz_no=?,settled_at=/*SOURCE_TIME*/? WHERE merchant_order_id=?",1,intent,successAt,intent);
            }
            case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP -> {
                successTimeWrite(f,crossSecond,"UPDATE nx_order SET payment_status='PAID',order_status='PAID',paid_at=/*SOURCE_TIME*/? WHERE order_no=?",0,successAt,f.order);
                if(f.source==Source.WALLET_ORDER) {
                    f.payment="PAY-"+f.name;
                    payment(f,"NEXGRID_WALLET","CONFIRMED",f.ledger,crossSecond?databaseLocalDateTime("SELECT NOW(6)"):ledgerAt);
                    if(crossSecond)jdbc.update("UPDATE nx_payment_record SET paid_at=NOW(6) WHERE payment_no=?",f.payment);
                    jdbc.update("UPDATE nx_order SET payment_no=? WHERE order_no=?",f.payment,f.order);
                }
            }
            case TRIAL_CONVERT -> {
                order(f,true,successAt);
                if(crossSecond)jdbc.update("UPDATE nx_order SET paid_at=NOW(6) WHERE order_no=?",f.order);
                jdbc.update("UPDATE nx_user_device SET source_order_no=?,source_channel='ORDER' WHERE id=?",f.order,f.device);
                successTimeWrite(f,crossSecond,"UPDATE nx_trial_claim SET status='REDEEMED',settled_at=/*SOURCE_TIME*/?,settlement_amount_usdt=10 WHERE claim_no=?",0,
                    crossSecond?databaseLocalDateTime("SELECT NOW(6)"):ledgerAt,f.claim);
            }
            case ORDER_REFUND -> {
                // Exercise the existing E4 production INSERT, including UUID_SHORT and canonical bill identity.
                assertThat(refundMapper.insertBill(f.customer,"E4-BILL-"+f.order,new BigDecimal("10.000000"))).isEqualTo(1);
                jdbc.update("UPDATE nx_order SET payment_status='REFUNDED',order_status='REFUNDED' WHERE order_no=?",f.order);
                jdbc.update("UPDATE nx_payment_record SET payment_status='REFUNDED' WHERE order_no=?",f.order);
            }
            default -> throw new IllegalArgumentException("Not an eligible source");
        }
    }

    private void successTimeWrite(Fixture f,boolean actualNow,String sql,int timeParameter,Object... parameters) {
        if(actualNow) {
            List<Object> bound=new ArrayList<>(java.util.Arrays.asList(parameters));
            bound.remove(timeParameter);
            jdbc.update(sql.replace("/*SOURCE_TIME*/?",orderSource(f.source)?"NOW(6)":"NOW()"),bound.toArray());
        } else jdbc.update(sql.replace("/*SOURCE_TIME*/?","?"),parameters);
    }

    private void databasePause() { assertThat(jdbc.queryForObject("SELECT SLEEP(1.1)",Integer.class)).isZero(); }

    private void payment(Fixture f,String provider,String status,Long ledger,LocalDateTime paid) {
        String no=f.payment==null?f.key:f.payment;
        jdbc.update("INSERT INTO nx_payment_record(payment_no,order_no,user_id,provider,provider_payment_id,amount_usdt,currency,payment_status,wallet_ledger_id,paid_at) VALUES(?,?,?,?,?,10,'USDT',?,?,?)",
            no,f.order,f.customer,provider,f.name,status,ledger,paid);
    }

    private void order(Fixture f,boolean paid,LocalDateTime paidAt) {
        jdbc.update("INSERT INTO nx_order(user_id,order_no,product_id,order_type,amount_usdt,payment_status,order_status,paid_at,created_at) VALUES(?,?,42,?,10,?,?,?,?)",
            f.customer,f.order,f.orderType,paid?"PAID":"PENDING",paid?"PAID":"PENDING_PAYMENT",paidAt,f.base);
    }

    private LocalDateTime databaseLocalDateTime(String sql,Object... parameters) {
        // DATETIME is a wall-clock value. getTimestamp followed by toLocalDateTime would first
        // interpret it in the connection zone and then render it in the JVM zone (Shanghai/Tokyo).
        // Keep JDBC 4.2 LocalDateTime throughout fixture input and raw database readback instead.
        return jdbc.queryForObject(sql,(rs,n)->rs.getObject(1,LocalDateTime.class),parameters);
    }

    private Prepared prepare(Fixture f) { return capture.prepare(f.customer,f.source,f.key,f.partition); }
    private Map<String,String> evidence(Fixture f) { return row(jdbc,f); }
    private Map<String,String> outsideEvidence(Fixture f) { return row(outside,f); }
    private Map<String,String> row(JdbcTemplate reader,Fixture f) {
        return reader.queryForObject("SELECT a.*,HEX(CAST(source_fact_json AS BINARY)) source_json_bytes,HEX(CAST(attribution_evidence_json AS BINARY)) evidence_json_bytes FROM nx_support_payment_attribution a WHERE fact_id=?",
            (rs,n)->strings(rs),f.factId());
    }
    private static Map<String,String> strings(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String,String> result=new TreeMap<>();
        for(int i=1;i<=rs.getMetaData().getColumnCount();i++) result.put(rs.getMetaData().getColumnLabel(i),rs.getString(i));
        return result;
    }
    private void assertOldUnknownWithoutPresentWitnesses(Map<String,String> row) {
        assertThat(row.get("capture_mode")).isEqualTo("OLD_SOURCE");
        for(String layer:List.of("agent","group","owner")) {
            assertThat(row.get(layer+"_status")).isEqualTo("UNKNOWN");
            assertThat(row.get(layer.equals("group")?"group_id":layer+"_admin_id")).isNull();
        }
        JsonNode evidence=tree(row.get("attribution_evidence_json"));
        assertThat(evidence.path("beforeSource").path("oldSource").asBoolean()).isTrue();
        assertThat(evidence.has("admins") || evidence.has("groups") || evidence.has("members")
            || evidence.has("owners") || evidence.has("qualifications") || evidence.has("bindings") || evidence.has("routes")
            || evidence.has("bindingHistory") || evidence.has("assignmentHistory") || evidence.has("routeHistory")
            || evidence.has("memberHistory") || evidence.has("ownerHistory")).isFalse();
    }
    private void corruptOwnProof(Fixture f,String corruption) {
        switch(corruption) {
            case "MISSING" -> jdbc.update("DELETE FROM nx_support_payment_attribution WHERE fact_id=? AND customer_id=?",f.factId(),f.customer);
            case "OLD" -> jdbc.update("UPDATE nx_support_payment_attribution SET capture_mode='OLD_SOURCE',agent_status='UNKNOWN',group_status='UNKNOWN',owner_status='UNKNOWN',agent_admin_id=NULL,group_id=NULL,owner_admin_id=NULL,attribution_evidence_json=JSON_SET(attribution_evidence_json,'$.captureMode','OLD_SOURCE','$.beforeSource.oldSource',CAST('true' AS JSON)) WHERE fact_id=? AND customer_id=?",f.factId(),f.customer);
            case "AMOUNT" -> jdbc.update("UPDATE nx_support_payment_attribution SET source_fact_json=JSON_SET(source_fact_json,'$.amount',11) WHERE fact_id=? AND customer_id=?",f.factId(),f.customer);
            case "CUSTOMER" -> jdbc.update("UPDATE nx_support_payment_attribution SET source_fact_json=JSON_SET(source_fact_json,'$.customerId',?) WHERE fact_id=? AND customer_id=?",f.customer+1,f.factId(),f.customer);
            case "TIME" -> jdbc.update("UPDATE nx_support_payment_attribution SET source_fact_json=JSON_SET(source_fact_json,'$.sourceConfirmationAt','2000-01-01T00:00:00.000000') WHERE fact_id=? AND customer_id=?",f.factId(),f.customer);
            case "PARTITION" -> jdbc.update("UPDATE nx_support_payment_attribution SET source_partition='foreign-partition' WHERE fact_id=? AND customer_id=?",f.factId(),f.customer);
            default -> throw new IllegalArgumentException("Unknown corruption");
        }
    }
    private Object physicalResource() {
        Object resource=TransactionSynchronizationManager.getResource(dataSource);
        assertThat(resource).isInstanceOf(ConnectionHolder.class);
        Connection connection=((ConnectionHolder)resource).getConnection();
        try { assertThat(connection.getAutoCommit()).isFalse(); }
        catch(java.sql.SQLException failure) { throw new IllegalStateException(failure); }
        return resource;
    }
    private long countForCustomer(String table,long customer) {
        if(!TABLES.contains(table)) throw new IllegalArgumentException("Unowned table");
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+
            (table.startsWith("nx_support_payment_")?"customer_id":"user_id")+"=?",Long.class,customer);
    }
    private int auditCalls() { return org.mockito.Mockito.mockingDetails(audit).getInvocations().size(); }
    private JsonNode tree(String value) {
        try { return json.readTree(value); }
        catch(Exception failure) { throw new IllegalStateException("Invalid captured JSON",failure); }
    }
    private void cleanupOwnAccounts(List<Long> accounts) {
        transaction.executeWithoutResult(status -> {
            for(long customer:accounts) {
                var names=jdbc.queryForList("SELECT nickname FROM nx_user WHERE id=?",String.class,customer);
                if(names.isEmpty())continue; // An earlier transaction failure already rolled this exact fixture back.
                assertThat(names).containsExactly("support-capture-fixture");
                jdbc.update("DELETE FROM nx_support_payment_attribution WHERE customer_id=?",customer);
                jdbc.update("DELETE FROM nx_support_payment_history_birth WHERE customer_id=?",customer);
                jdbc.update("DELETE FROM nx_wallet_bill WHERE user_id=?",customer);
                jdbc.update("DELETE FROM nx_payment_record WHERE user_id=?",customer);
                jdbc.update("DELETE FROM nx_order WHERE user_id=?",customer);
                jdbc.update("DELETE FROM nx_wallet_ledger WHERE user_id=?",customer);
                jdbc.update("DELETE FROM nx_user_wallet WHERE user_id=?",customer);
                jdbc.update("DELETE FROM nx_user WHERE id=? AND nickname='support-capture-fixture'",customer);
            }
        });
    }
    private static String requiredEnvironment(String key) {
        String value=System.getenv(key);
        if(value==null || value.isBlank()) throw new IllegalStateException("Missing capture acceptance setting: "+key);
        return value;
    }
    private static String unique() { return "SC"+UUID.randomUUID().toString().replace("-","").substring(0,24); }
    private static long positiveUnique() { return (UUID.randomUUID().getMostSignificantBits()&Long.MAX_VALUE)%900_000_000_000L+100_000_000_000L; }
    private static boolean orderSource(Source source) {
        return source==Source.WALLET_ORDER || source==Source.TRADE_IN || source==Source.CAPACITY_KEEP || source==Source.TRIAL_CONVERT;
    }
    private static final class Fixture {
        final Source source;
        final long customer;
        final String name;
        LocalDateTime base;
        String key,partition,order,orderType,payment,claim,receipt;
        long cid,device,ledger;
        Fixture original;
        Fixture(Source source,long customer,String name,LocalDateTime base) {
            this.source=source;this.customer=customer;this.name=name;this.base=base;
        }
        String canonicalKey() { return source==Source.TRIAL_CONVERT?claim+":CHARGE":key; }
        String factId() { return orderSource(source)?"PURCHASE:"+order:source==Source.ORDER_REFUND?"ORDER_REFUND:"+ledger:"DEPOSIT:"+ledger; }
    }
}
