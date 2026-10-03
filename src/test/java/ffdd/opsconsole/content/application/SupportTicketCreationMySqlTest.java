package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.CustomerProfileRepository;
import ffdd.opsconsole.content.domain.SupportAgentRepository;
import ffdd.opsconsole.content.domain.SupportKnowledgeRepository;
import ffdd.opsconsole.content.domain.SupportSlaView;
import ffdd.opsconsole.content.domain.SupportTicketDetail;
import ffdd.opsconsole.content.dto.ConversationTicketRequest;
import ffdd.opsconsole.content.dto.SupportTicketCreateRequest;
import ffdd.opsconsole.content.infrastructure.MybatisConversationRepository;
import ffdd.opsconsole.content.infrastructure.MybatisSupportTicketRepository;
import ffdd.opsconsole.content.mapper.ConversationMapper;
import ffdd.opsconsole.content.mapper.ConversationMessageMapper;
import ffdd.opsconsole.content.mapper.SupportAgentMapper;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportTicketCreationMapper;
import ffdd.opsconsole.content.mapper.SupportTicketMapper;
import ffdd.opsconsole.content.mapper.SupportTicketMessageMapper;
import ffdd.opsconsole.content.web.OpsConversationController;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.finance.application.OpsFinanceService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.application.OpsRiskService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.config.MybatisMetaObjectHandler;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyExpiryTransitionExecutor;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyTransactionExecutor;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.user.application.OpsUserService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real entry points, transaction proxies and production SQL against an owned disposable MySQL schema. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class SupportTicketCreationMySqlTest {
    @Test
    void appAndAdminCompeteForLastDailySlotAfterBothPinRepeatableReadSnapshots() throws Exception {
        try (var runtime = new Runtime()) {
            for (int index = 0; index < 9; index++) runtime.seed(1, "old-" + index, "CLOSED", false, false, 7200 + index);
            CountDownLatch snapshots = new CountDownLatch(2);
            Set<Long> seenThreads = ConcurrentHashMap.newKeySet();
            doAnswer(call -> {
                if (seenThreads.add(Thread.currentThread().getId())) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(runtime.jdbc.queryForObject("SELECT @@transaction_isolation", String.class))
                            .isEqualTo("REPEATABLE-READ");
                    assertThat(runtime.ticketCount(1)).isEqualTo(9);
                    snapshots.countDown();
                }
                return call.callRealMethod();
            }).when(runtime.ownership).lockCustomer(1L);
            var pool = Executors.newFixedThreadPool(2);
            try (Connection blocker = runtime.jdbc.getDataSource().getConnection()) {
                blocker.setAutoCommit(false);
                try (var statement = blocker.prepareStatement("SELECT id FROM nx_user WHERE id=1 FOR UPDATE")) {
                    try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
                }
                var app = pool.submit(() -> outcome(() -> runtime.repeatableRead(
                        () -> runtime.app(1, "race-app", "App request", "App body"))));
                var admin = pool.submit(() -> outcome(() -> runtime.repeatableRead(
                        () -> runtime.admin(1, "race-admin", "Admin request", "Admin body"))));
                assertThat(snapshots.await(10, TimeUnit.SECONDS)).as("both requests pinned old snapshots before the mutex").isTrue();
                blocker.commit();
                assertThat(List.of(app.get(20, TimeUnit.SECONDS), admin.get(20, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder("SUCCESS", "SUPPORT_TICKET_CREATE_DAILY_LIMIT");
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(runtime.ticketCount(1)).isEqualTo(10);
            assertThat(runtime.jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin_idempotency_record WHERE status='SUCCEEDED'", Integer.class)).isEqualTo(1);
            assertThat(runtime.jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin_idempotency_record WHERE status='FAILED'", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void successfulKeyReplaysOriginalResponseWithoutSpendingAnotherSlot() throws Exception {
        try (var runtime = new Runtime()) {
            var first = runtime.app(1, "same-key", "Original", "Original body");
            var replay = runtime.app(1, "same-key", "Original", "Original body");
            JsonNode firstJson = runtime.json.readTree(runtime.json.writeValueAsBytes(first));
            JsonNode replayJson = runtime.json.readTree(runtime.json.writeValueAsBytes(replay));
            assertThat(replayJson).isEqualTo(firstJson);
            assertThat(runtime.ticketCount(1)).isEqualTo(1);
            assertThat(runtime.messageCount()).isEqualTo(1);
            assertThat(runtime.receipt("same-key")).isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void newKeySameNormalizedContentPointsToOriginalFullBodyDespiteUpdatedHeader() throws Exception {
        try (var runtime = new Runtime()) {
            String body = "x".repeat(550) + " original\n\t message";
            var first = runtime.app(1, "original", "Cafe\u0301\u00a0question", body);
            String ticketNo = first.getData().ticket().ticketNo();
            runtime.jdbc.update("UPDATE nx_support_ticket SET last_message='later agent reply' WHERE ticket_no=?", ticketNo);
            var rejection = assertThrows(SupportTicketCreationRejectedException.class,
                    () -> runtime.app(1, "replacement-key", "Caf\u00e9 question", "x".repeat(550) + " original message"));
            assertThat(rejection.getCode()).isEqualTo(409);
            assertThat(rejection.policy().reasonCode()).isEqualTo("SUPPORT_TICKET_CREATE_DUPLICATE");
            assertThat(rejection.policy().existingTicketNo()).isEqualTo(ticketNo);
            assertThat(runtime.ticketCount(1)).isEqualTo(1);
            assertThat(runtime.receipt("replacement-key")).isEqualTo("FAILED");
        }
    }

    @Test
    void closedArchivedAndSoftDeletedRowsStillSpendDailyBudget() throws Exception {
        try (var runtime = new Runtime()) {
            for (int index = 0; index < 10; index++) {
                runtime.seed(1, "history-" + index, "CLOSED", index % 2 == 0, index % 3 == 0, 3600 + index);
            }
            var policy = runtime.policy.policy(1L);
            assertThat(policy.createdInWindow()).isEqualTo(10);
            assertThat(policy.activeTickets()).isZero();
            assertThat(policy.reasonCode()).isEqualTo("SUPPORT_TICKET_CREATE_DAILY_LIMIT");
            assertThat(outcome(() -> runtime.app(1, "over-daily", "Another", "New body")))
                    .isEqualTo("SUPPORT_TICKET_CREATE_DAILY_LIMIT");
            assertThat(runtime.ticketCount(1)).isEqualTo(10);
        }
    }

    @Test
    void openInProgressAndPendingArchivedRowsStillSpendActiveBudget() throws Exception {
        try (var runtime = new Runtime()) {
            runtime.seed(1, "open", "OPEN", false, false, 3600);
            runtime.seed(1, "progress", "IN_PROGRESS", false, false, 3601);
            runtime.seed(1, "pending", "PENDING_USER", true, false, 3602);
            var policy = runtime.policy.policy(1L);
            assertThat(policy.activeTickets()).isEqualTo(3);
            assertThat(policy.reasonCode()).isEqualTo("SUPPORT_TICKET_CREATE_ACTIVE_LIMIT");
            assertThat(outcome(() -> runtime.admin(1, "over-active", "Another", "New body")))
                    .isEqualTo("SUPPORT_TICKET_CREATE_ACTIVE_LIMIT");
            assertThat(runtime.ticketCount(1)).isEqualTo(3);
        }
    }

    @Test
    void sameFailedKeyCanSucceedWhenCooldownExpires() throws Exception {
        try (var runtime = new Runtime()) {
            runtime.app(1, "first", "First", "First body");
            var rejected = assertThrows(SupportTicketCreationRejectedException.class,
                    () -> runtime.app(1, "retry-after-cooldown", "Second", "Second body"));
            assertThat(rejected.policy().reasonCode()).isEqualTo("SUPPORT_TICKET_CREATE_COOLDOWN");
            assertThat(rejected.policy().retryAfterSeconds()).isEqualTo(60);
            assertThat(runtime.receipt("retry-after-cooldown")).isEqualTo("FAILED");
            runtime.clock.advance(60);
            assertThat(runtime.app(1, "retry-after-cooldown", "Second", "Second body").getCode()).isZero();
            assertThat(runtime.receipt("retry-after-cooldown")).isEqualTo("SUCCEEDED");
            assertThat(runtime.ticketCount(1)).isEqualTo(2);
        }
    }

    @Test
    void rejectedAppConversionRollsBackConversationClosureAndSystemMessage() throws Exception {
        try (var runtime = new Runtime()) {
            runtime.app(1, "occupy", "First", "First body");
            runtime.conversation("app-conversation", 1);
            assertThat(outcome(() -> runtime.appService.convertConversationToTicket(1L, "app-conversation", "app-convert",
                    new AppSupportService.ConvertToTicketRequest("other", "Converted", "OPEN", 0L))))
                    .isEqualTo("SUPPORT_TICKET_CREATE_COOLDOWN");
            runtime.assertConversationUnchanged("app-conversation");
            assertThat(runtime.receipt("app-convert")).isEqualTo("FAILED");
            assertThat(runtime.ticketCount(1)).isEqualTo(1);
        }
    }

    @Test
    void rejectedAdminConversionRollsBackConversationClosureAndSystemMessage() throws Exception {
        try (var runtime = new Runtime()) {
            runtime.app(1, "occupy", "First", "First body");
            runtime.conversation("admin-conversation", 1);
            runtime.afterAdmissionReads.set(() -> assertThat(runtime.jdbc.queryForObject(
                    "SELECT @@transaction_isolation", String.class)).isEqualTo("READ-COMMITTED"));
            assertThat(outcome(() -> runtime.asAdmin(() -> runtime.conversationController.convertToTicket(
                    "admin-conversation", "admin-convert", new ConversationTicketRequest("other", "NORMAL", "Converted",
                            null, null, "OPEN", 0L, "acceptance conversion", "acceptance")))))
                    .isEqualTo("SUPPORT_TICKET_CREATE_COOLDOWN");
            runtime.assertConversationUnchanged("admin-conversation");
            assertThat(runtime.receipt("admin-convert")).isEqualTo("FAILED");
            assertThat(runtime.ticketCount(1)).isEqualTo(1);
        }
    }

    @Test
    void accountsHaveIndependentCooldownAndDuplicateBudgets() throws Exception {
        try (var runtime = new Runtime()) {
            CountDownLatch completedReads = new CountDownLatch(2);
            runtime.afterAdmissionReads.set(() -> {
                assertThat(runtime.jdbc.queryForObject("SELECT @@transaction_isolation", String.class))
                        .isEqualTo("READ-COMMITTED");
                completedReads.countDown();
                try {
                    assertThat(completedReads.await(10, TimeUnit.SECONDS))
                            .as("both accounts completed the real range reads before either insert").isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> runtime.app(1, "account-one", "Same title", "Same body"));
                var second = pool.submit(() -> runtime.app(2, "account-two", "Same title", "Same body"));
                assertThat(first.get(20, TimeUnit.SECONDS).getCode()).isZero();
                assertThat(second.get(20, TimeUnit.SECONDS).getCode()).isZero();
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(runtime.ticketCount(1)).isEqualTo(1);
            assertThat(runtime.ticketCount(2)).isEqualTo(1);
        }
    }

    @Test
    void messageInsertFailureRollsBackHeaderAndSuccessfulReceiptThenSameKeyRetries() throws Exception {
        try (var runtime = new Runtime()) {
            runtime.jdbc.execute("CREATE TRIGGER reject_ticket_message BEFORE INSERT ON nx_support_ticket_message "
                    + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TEST_MESSAGE_INSERT_FAILED'");
            assertThatThrownBy(() -> runtime.app(1, "retry-message", "Message failure", "Failure body"))
                    .hasStackTraceContaining("TEST_MESSAGE_INSERT_FAILED");
            assertThat(runtime.ticketCount(1)).isZero();
            assertThat(runtime.messageCount()).isZero();
            assertThat(runtime.receipt("retry-message")).isEqualTo("FAILED");
            runtime.jdbc.execute("DROP TRIGGER reject_ticket_message");
            assertThat(runtime.app(1, "retry-message", "Message failure", "Failure body").getCode()).isZero();
            assertThat(runtime.ticketCount(1)).isEqualTo(1);
            assertThat(runtime.messageCount()).isEqualTo(1);
            assertThat(runtime.receipt("retry-message")).isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void exactTwentyFourHourBoundaryRestoresBudgetAndDuplicateEligibility() throws Exception {
        try (var runtime = new Runtime()) {
            for (int index = 0; index < 10; index++) runtime.seed(1, "boundary-" + index, "CLOSED", false, false, 86400);
            assertThat(runtime.policy.policy(1L).createdInWindow()).isZero();
            assertThat(runtime.app(1, "boundary-create", "boundary-0", "Seed body").getCode()).isZero();
            assertThat(runtime.ticketCount(1)).isEqualTo(11);
        }
    }

    @Test
    void migrationSeedsDefaultsAndIndexesIdempotentlyWithoutOverwritingManagedValues() throws Exception {
        try (var runtime = new Runtime()) {
            runtime.createTable(Files.readString(Path.of("scripts/schema.sql")), "nx_config_item");
            runtime.jdbc.execute("ALTER TABLE nx_support_ticket DROP INDEX idx_support_ticket_creation_window, DROP INDEX idx_support_ticket_creation_active");
            var migration = new FileSystemResource("scripts/migrations/20261003_support_ticket_creation_policy.sql");
            try (Connection session = runtime.jdbc.getDataSource().getConnection()) {
                ScriptUtils.executeSqlScript(session, migration);
                assertThat(runtime.jdbc.queryForMap("SELECT config_value,status,is_deleted FROM nx_config_item WHERE config_key='support.ticket.creation.cooldown_seconds'"))
                        .containsEntry("config_value", "60").containsEntry("status", 1).containsEntry("is_deleted", 0);
                assertThat(runtime.jdbc.queryForObject("SELECT config_value FROM nx_config_item WHERE config_key='support.ticket.creation.max_per_24h'", String.class)).isEqualTo("10");
                assertThat(runtime.jdbc.queryForObject("SELECT config_value FROM nx_config_item WHERE config_key='support.ticket.creation.max_active'", String.class)).isEqualTo("3");
                runtime.jdbc.update("UPDATE nx_config_item SET config_value='120',status=0 WHERE config_key='support.ticket.creation.cooldown_seconds'");
                runtime.jdbc.update("UPDATE nx_config_item SET config_value='25',is_deleted=1 WHERE config_key='support.ticket.creation.max_per_24h'");
                ScriptUtils.executeSqlScript(session, migration);
            }
            assertThat(runtime.jdbc.queryForMap("SELECT config_value,status FROM nx_config_item WHERE config_key='support.ticket.creation.cooldown_seconds'"))
                    .containsEntry("config_value", "120").containsEntry("status", 0);
            assertThat(runtime.jdbc.queryForMap("SELECT config_value,is_deleted FROM nx_config_item WHERE config_key='support.ticket.creation.max_per_24h'"))
                    .containsEntry("config_value", "25").containsEntry("is_deleted", 1);
            assertThat(runtime.jdbc.queryForObject("SELECT COUNT(*) FROM nx_config_item WHERE config_group='support_ticket_creation'", Integer.class)).isEqualTo(3);
            assertThat(runtime.jdbc.queryForList("SELECT INDEX_NAME,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_order FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_ticket' AND INDEX_NAME IN ('idx_support_ticket_creation_window','idx_support_ticket_creation_active') GROUP BY INDEX_NAME"))
                    .containsExactlyInAnyOrder(
                            Map.of("INDEX_NAME", "idx_support_ticket_creation_window", "columns_in_order", "user_id,created_at,id"),
                            Map.of("INDEX_NAME", "idx_support_ticket_creation_active", "columns_in_order", "user_id,is_deleted,status,created_at,id"));
        }
    }

    private static String outcome(Supplier<? extends ApiResult<?>> action) {
        try {
            ApiResult<?> response = action.get();
            assertThat(response.getCode()).as(response.getMessage()).isZero();
            return "SUCCESS";
        } catch (SupportTicketCreationRejectedException rejected) {
            return rejected.policy().reasonCode();
        }
    }

    static final class Runtime implements AutoCloseable {
        final M1SupportAvailabilityMySqlFixture fixture;
        final JdbcTemplate jdbc;
        final MutableClock clock = new MutableClock();
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final AtomicReference<Runnable> afterAdmissionReads = new AtomicReference<>(() -> {});
        final SupportOwnershipService ownership;
        final SupportTicketCreationPolicyService policy;
        final DataSourceTransactionManager manager;
        final AppSupportService appService;
        final OpsSupportTicketService adminService;
        final OpsConversationService conversationService;
        final OpsConversationController conversationController;
        final SupportTicketOwnerService ticketOwners;
        final SupportBindingService bindings;
        final SupportBindingMapper bindingMapper;
        final MybatisSupportTicketRepository tickets;
        final AuditLogService audit = mock(AuditLogService.class);
        final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

        Runtime() throws Exception {
            this(false);
        }

        Runtime(boolean enforceOwnership) throws Exception {
            M1SupportAvailabilityMySqlFixture.requireServerUrlWithoutCatalog(System.getenv("NEXION_TEST_DB_SERVER_URL"));
            fixture = M1SupportAvailabilityMySqlFixture.openFromEnvironment();
            jdbc = fixture.jdbc();
            try {
                createSchema();
                var configuration = new MybatisConfiguration(new Environment("ticket-admission-test",
                        new SpringManagedTransactionFactory(), jdbc.getDataSource()));
                configuration.setMapUnderscoreToCamelCase(true);
                var global = new GlobalConfig();
                global.setDbConfig(new GlobalConfig.DbConfig());
                global.setMetaObjectHandler(new MybatisMetaObjectHandler(clock));
                GlobalConfigUtils.setGlobalConfig(configuration, global);
                for (Class<?> mapper : List.of(SupportTicketCreationMapper.class, SupportTicketMapper.class,
                        SupportTicketMessageMapper.class, SupportBindingMapper.class, AdminIdempotencyRecordMapper.class,
                        ConversationMapper.class, ConversationMessageMapper.class)) configuration.addMapper(mapper);
                var template = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
                manager = new DataSourceTransactionManager(jdbc.getDataSource());
                var interceptor = new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource());
                bindingMapper = template.getMapper(SupportBindingMapper.class);
                ownership = spy(new SupportOwnershipService(bindingMapper));
                // Authorization policy is outside this regression; the real customer lock is never mocked.
                // Owner regressions explicitly opt into the real authorization implementation.
                if (!enforceOwnership) {
                doAnswer(call -> {
                    if ((Boolean) call.getArgument(1)) ownership.lockCustomer(call.getArgument(0));
                    return null;
                }).when(ownership).requireWriter(anyLong(), anyBoolean());
                doReturn(99L).when(ownership).actorId();
                doNothing().when(ownership).requireHandled(anyString());
                doAnswer(call -> {
                    ownership.lockCustomer(ownership.conversationCustomer(call.getArgument(0)));
                    return null;
                }).when(ownership).writeConversation(anyString(), anyBoolean());
                }
                PlatformConfigFacade config = mock(PlatformConfigFacade.class);
                when(config.activeValue(anyString())).thenAnswer(call -> Optional.of(switch ((String) call.getArgument(0)) {
                    case "support.ticket.creation.cooldown_seconds" -> "60";
                    case "support.ticket.creation.max_per_24h" -> "10";
                    case "support.ticket.creation.max_active" -> "3";
                    default -> "";
                }));
                var sqlCreation = template.getMapper(SupportTicketCreationMapper.class);
                // This observer preserves every production query and returned row; it only coordinates races.
                var observedCreation = mock(SupportTicketCreationMapper.class,
                        org.mockito.AdditionalAnswers.delegatesTo(sqlCreation));
                doAnswer(call -> {
                    var rows = sqlCreation.currentActive(call.getArgument(0));
                    afterAdmissionReads.get().run();
                    return rows;
                }).when(observedCreation).currentActive(anyLong());
                policy = proxied(new SupportTicketCreationPolicyService(ownership, observedCreation, config, clock), interceptor);
                ticketOwners = proxied(new SupportTicketOwnerService(ownership, bindingMapper,
                        template.getMapper(SupportTicketMapper.class), clock), interceptor);
                tickets = new MybatisSupportTicketRepository(ownership, template.getMapper(SupportTicketMapper.class),
                        template.getMapper(SupportTicketMessageMapper.class), policy, ticketOwners);
                var conversations = new MybatisConversationRepository(template.getMapper(ConversationMapper.class),
                        template.getMapper(ConversationMessageMapper.class), ownership);
                var receipts = template.getMapper(AdminIdempotencyRecordMapper.class);
                var expiry = proxied(new AdminIdempotencyExpiryTransitionExecutor(receipts), interceptor);
                var executor = proxied(new AdminIdempotencyTransactionExecutor(receipts, json, expiry), interceptor);
                var idempotency = new AdminIdempotencyService(executor, clock);
                var guard = mock(ProductionSupportPathGuard.class);
                when(guard.productionSupportAutomationAllowed()).thenReturn(true);
                bindings = proxied(new SupportBindingService(bindingMapper, ownership, idempotency, audit, events,
                        mock(SupportAgentRepository.class), guard, ticketOwners), interceptor);
                var knowledge = mock(SupportKnowledgeRepository.class);
                when(knowledge.listSla()).thenReturn(List.of(new SupportSlaView("other", 15, 24, "test", "test", 1L, now())));
                var agents = mock(OpsSupportAgentService.class);
                appService = proxied(new AppSupportService(tickets, conversations, knowledge, idempotency, audit,
                        mock(ApplicationEventPublisher.class), clock, guard, receipts, json,
                        mock(SupportAgentRepository.class), config, ownership, mock(SupportHumanMessageService.class)), interceptor);
                adminService = proxied(new OpsSupportTicketService(tickets, conversations, knowledge, agents, config,
                        audit, idempotency, clock, ownership, OpsReadTimeSeedPolicy.disabledForDirectConstruction(), json), interceptor);
                conversationService = proxied(new OpsConversationService(conversations, tickets, agents, config, audit,
                        clock, OpsReadTimeSeedPolicy.disabledForDirectConstruction(), mock(OpsUserService.class),
                        mock(OpsFinanceService.class), mock(OpsDeviceService.class), mock(OpsRiskService.class),
                        mock(CustomerProfileRepository.class), guard, ownership, mock(SupportHumanMessageService.class),
                        mock(SupportReplyService.class), mock(SupportCustomerProfileService.class)), interceptor);
                conversationController = proxied(new OpsConversationController(conversationService, ownership, guard,
                        mock(ApplicationEventPublisher.class), idempotency), interceptor);
                assertThat(AopUtils.isAopProxy(appService)).isTrue();
                assertThat(AopUtils.isAopProxy(adminService)).isTrue();
                assertThat(AopUtils.isAopProxy(conversationService)).isTrue();
                assertThat(AopUtils.isAopProxy(conversationController)).isTrue();
                assertThat(AopUtils.isAopProxy(policy)).isTrue();
                assertThat(AopUtils.isAopProxy(executor)).isTrue();
                assertThat(AopUtils.isAopProxy(ticketOwners)).isTrue();
                assertThat(AopUtils.isAopProxy(bindings)).isTrue();
            } catch (Exception | Error failure) {
                fixture.close();
                throw failure;
            }
        }

        ApiResult<SupportTicketDetail> app(long account, String key, String title, String body) {
            return appService.createTicket(account, key, new AppSupportService.CreateTicketRequest("other", title, body));
        }

        <T> T repeatableRead(Supplier<T> action) {
            var transaction = new TransactionTemplate(manager);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return transaction.execute(status -> action.get());
        }

        ApiResult<SupportTicketDetail> admin(long account, String key, String title, String body) {
            return asAdmin(() -> adminService.create(key, new SupportTicketCreateRequest(account, "other", "NORMAL",
                    title, body, null, null, "acceptance", "acceptance creation")));
        }

        <T> T asAdmin(Supplier<T> action) {
            return asActor(99L, action);
        }

        <T> T asActor(long actor, Supplier<T> action) {
            var previous = SecurityContextHolder.getContext();
            var context = SecurityContextHolder.createEmptyContext();
            var authentication = new UsernamePasswordAuthenticationToken(String.valueOf(actor), "unused",
                    List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("service_m3_read")));
            authentication.setDetails(Map.of("subjectType", "ADMIN", "username", "agent-" + actor));
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            try { return action.get(); }
            finally { SecurityContextHolder.setContext(previous); }
        }

        LocalDateTime now() { return LocalDateTime.now(clock); }
        int ticketCount(long account) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_ticket WHERE user_id=?", Integer.class, account);
        }
        int messageCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_ticket_message", Integer.class); }
        String receipt(String key) {
            return jdbc.queryForObject("SELECT status FROM nx_admin_idempotency_record WHERE idempotency_key=?", String.class, key);
        }
        void seed(long account, String number, String status, boolean archived, boolean deleted, long ageSeconds) {
            jdbc.update("INSERT INTO nx_support_ticket(ticket_no,user_id,category,title,status,archived,is_deleted,created_at,source_conversation_no) VALUES(?,?,'other',?,?,?,?,?,'DIRECT')",
                    number, account, number, status, archived, deleted, now().minusSeconds(ageSeconds));
            jdbc.update("INSERT INTO nx_support_ticket_message(ticket_id,ticket_no,sender_type,content,created_at) SELECT id,ticket_no,'user','Seed body',created_at FROM nx_support_ticket WHERE ticket_no=?", number);
        }
        void conversation(String number, long account) {
            jdbc.update("INSERT INTO nx_conversation(conversation_no,user_id,status,last_message,last_message_at) VALUES(?,?,'OPEN','Original conversation',?)", number, account, now());
        }
        void assertConversationUnchanged(String number) {
            assertThat(jdbc.queryForMap("SELECT status,version,last_message FROM nx_conversation WHERE conversation_no=?", number))
                    .containsEntry("status", "OPEN").containsEntry("version", 0L).containsEntry("last_message", "Original conversation");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no=?", Integer.class, number)).isZero();
        }

        void createSchema() throws Exception {
            String schema = Files.readString(Path.of("scripts/schema.sql"));
            for (String table : List.of("nx_user", "nx_admin", "nx_admin_role", "nx_admin_role_relation", "nx_admin_idempotency_record", "nx_support_ticket",
                    "nx_support_ticket_message", "nx_conversation", "nx_conversation_transfer", "nx_conversation_message",
                    "nx_conversation_message_receipt")) createTable(schema, table);
            jdbc.execute("ALTER TABLE nx_support_ticket ADD COLUMN source_conversation_no VARCHAR(100) NULL");
            jdbc.execute("ALTER TABLE nx_conversation ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE");
            jdbc.execute(String.join("\n", SupportAgentMapper.class.getMethod("createAssignmentTable").getAnnotation(Update.class).value()));
            jdbc.execute(String.join("\n", SupportAgentMapper.class.getMethod("createProfileTable").getAnnotation(Update.class).value()));
            jdbc.execute("ALTER TABLE nx_support_agent_user_assignment ADD COLUMN version BIGINT NOT NULL DEFAULT 1, "
                    + "ADD COLUMN source VARCHAR(16), ADD COLUMN segment_root_id BIGINT, ADD COLUMN depth INT, "
                    + "ADD COLUMN parent_assignment_id BIGINT, ADD COLUMN rule_version BIGINT, ADD COLUMN operation_id VARCHAR(128)");
            String bindingsSchema = Files.readString(Path.of("scripts/migrations/20260929_support_binding_s3.sql"));
            for (String table : List.of("nx_support_rules", "nx_support_binding_pool", "nx_support_reply_cursor"))
                createTable(bindingsSchema, table);
            jdbc.execute("ALTER TABLE nx_support_rules ADD COLUMN unbound_assignment_mode VARCHAR(16) NOT NULL DEFAULT 'SUPERVISOR', "
                    + "ADD COLUMN mode_effective_at DATETIME(6) NULL");
            jdbc.execute("ALTER TABLE nx_support_binding_pool ADD COLUMN auto_eligible BOOLEAN NOT NULL DEFAULT FALSE, "
                    + "ADD COLUMN auto_rule_version BIGINT NULL, ADD COLUMN auto_attempt_state VARCHAR(24) NOT NULL DEFAULT 'NONE', "
                    + "ADD COLUMN attempts INT NOT NULL DEFAULT 0, ADD COLUMN last_attempt_at DATETIME(6) NULL, "
                    + "ADD COLUMN last_outcome VARCHAR(64) NULL, ADD COLUMN operation_id VARCHAR(128) NULL");
            jdbc.update("INSERT INTO nx_support_rules(id,inheritance_mode) VALUES(1,'UNLIMITED')");
            jdbc.update("INSERT INTO nx_admin_role(id,role_code,role_name) VALUES(1,'SUPPORT','Support')");
            for (long actor : List.of(99L, 100L, 101L)) {
                jdbc.update("INSERT INTO nx_admin(id,username,password_hash,nickname) VALUES(?,?,'test',?)",
                        actor, "agent-" + actor, "Advisor " + actor);
                jdbc.update("INSERT INTO nx_admin_role_relation(admin_id,role_id) VALUES(?,1)", actor);
                jdbc.update("INSERT INTO nx_support_agent_profile(admin_id,seat_type,position,service_types,tags) VALUES(?,?,?,'advisor,support','test')",
                        actor, actor == 101 ? "MANAGER" : "DEDICATED", "test");
            }
            createTable(Files.readString(Path.of("scripts/migrations/20260929_support_message_s4.sql")), "nx_support_human_message");
            createTable(Files.readString(Path.of("scripts/migrations/20260725_m3_conversation_idle_timeout.sql")), "nx_conversation_timeout_event");
            for (long account : List.of(1L, 2L)) jdbc.update("INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code) VALUES(?,'+0',?,'127.0.0.1','test','test',?)",
                    account, String.valueOf(account), "test-" + account);
        }
        void createTable(String source, String table) {
            var match = Pattern.compile("CREATE TABLE IF NOT EXISTS " + Pattern.quote(table) + "\\s*\\([\\s\\S]*?;").matcher(source);
            if (!match.find()) throw new IllegalStateException("Missing fixture table DDL: " + table);
            jdbc.execute(match.group());
        }
        @Override public void close() throws Exception { fixture.close(); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxied(T target, TransactionInterceptor interceptor) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(interceptor);
        return (T) proxy.getProxy();
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        void advance(long seconds) { current.updateAndGet(value -> value.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return current.get(); }
    }
}
