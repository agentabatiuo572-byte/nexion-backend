package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.content.application.AppConversationManagementService.ClearRequest;
import ffdd.opsconsole.content.domain.*;
import ffdd.opsconsole.content.dto.ConversationReplyRequest;
import ffdd.opsconsole.content.infrastructure.MybatisConversationRepository;
import ffdd.opsconsole.content.infrastructure.MybatisSupportAgentRepository;
import ffdd.opsconsole.content.mapper.*;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.*;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Independent R20 review: actual InnoDB, production MyBatis statements, Spring transaction proxies,
 * retained idempotency and audit writes. No application boot, scheduler, HTTP server or fixture launcher.
 *
 * Explicit opt-in is the ONLY skip condition. Once enabled, missing/incorrect configuration is a failure.
 * The parent launches/owns MySQL and runs Maven. This class resets its fixed tables in the approved
 * disposable schema before each case; do not run parent API seeding concurrently with this class.
 *
 * External object bytes and unrelated product/config services are test doubles. Attachment authorization,
 * current assignment, message writes, watermarks, receipts and all idempotency phases use real SQL.
 * HTTP/JWT, live WebSocket delivery, object-storage networking and database restart belong to parent QA.
 */
@EnabledIfEnvironmentVariable(named = "R20_MYSQL_IT", matches = "(?i)true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class R20UserViewClearMySqlReviewTest {
    private static final long USER = 42L;
    private static final String CID = "R20-A";
    private static final String SIBLING = "R20-A-OLDER";
    private static final String OTHER_PEER = "R20-B";
    private static final String EMPTY = "R20-EMPTY";
    private static final String MEDIA_ID = "00000000-0000-4000-8000-000000000011";
    private static final String MEDIA_KEY = "private/support/r20-review/old-image";
    private static final byte[] MEDIA_BYTES = "R20 immutable staff attachment".getBytes(StandardCharsets.UTF_8);
    private static final String URL = "jdbc:mysql://127.0.0.1:33327/r20_user_clear"
            + "?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false";
    private static final String DATA_DIR = "D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/mysql-data";

    private CheckedDataSource source;
    private Connection suiteLease;
    private JdbcTemplate jdbc;
    private Graph graph;
    private final LockProbe probe = new LockProbe();

    @BeforeAll
    void requireExplicitOwnedMysql() throws Exception {
        // Never log credentials, read the parent's secret file, or fall back to application configuration.
        assertThat(requiredEnvironment("R20_MYSQL_URL")).as("only the approved R20 URL").isEqualTo(URL);
        assertThat(requiredEnvironment("R20_MYSQL_USER")).as("schema-scoped fixture account").isEqualTo("r20_runner");
        source = new CheckedDataSource(URL, "r20_runner", requiredEnvironment("R20_MYSQL_PASSWORD"));
        suiteLease = source.getConnection();
        try (var statement = suiteLease.createStatement();
             var result = statement.executeQuery("SELECT GET_LOCK('r20:user-view-clear:independent-review',0)")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).as("another review suite must not reset these tables").isEqualTo(1);
        }
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(12);
    }

    @BeforeEach
    void resetOnlyApprovedFixtureTables() throws Exception {
        SecurityContextHolder.clearContext();
        // Every connection repeats schema, account, port and datadir checks before executing fixture SQL.
        for (String table : TABLES) jdbc.execute("DROP TABLE IF EXISTS " + table);
        for (String ddl : DDL) jdbc.execute(ddl + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        try (Connection connection = source.getConnection()) {
            var migration = new EncodedResource(new FileSystemResource(
                    Path.of("scripts/migrations/20261027_app_conversation_user_clear.sql")), StandardCharsets.UTF_8);
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
        }
        seed();
        graph = new Graph(source, probe);
    }

    @AfterEach
    void clearOnlyThreadAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    void releaseLeaseWithoutStoppingOrDroppingParentsDatabase() throws Exception {
        if (suiteLease != null) {
            try (var statement = suiteLease.createStatement()) {
                statement.execute("SELECT RELEASE_LOCK('r20:user-view-clear:independent-review')");
            } finally {
                suiteLease.close();
            }
        }
    }

    @Test
    void persistedClearSurvivesNewServiceGraphAndPreservesStaffOriginals() {
        var originals = staffRows();
        var receipt = ok(graph.management.clear(USER, CID, "r20-clear-persist", bothPeers()));
        assertThat(receipt.throughMessageId()).isEqualTo(20L);
        assertThat(receipt.conversationNos()).containsExactlyInAnyOrder(CID, SIBLING);
        assertThat(staffRows()).isEqualTo(originals);
        assertThat(count("nx_audit_log", "action='APP_CONVERSATION_USER_CLEARED'")).isEqualTo(1);
        assertThat(count("nx_admin_idempotency_record", "status='SUCCEEDED'")).isEqualTo(1);

        // New DataSource, SqlSessionFactory, services and proxies: no previous Java/session cache survives.
        var reconnected = new Graph(new CheckedDataSource(URL, "r20_runner",
                requiredEnvironment("R20_MYSQL_PASSWORD")), new LockProbe());
        var management = ok(reconnected.management.management(USER));
        assertThat(management.clearUserHistory()).isTrue();
        assertThat(management.clearedPeers()).containsExactly(receipt);
        assertThat(ok(reconnected.management.management(99L)).clearedPeers()).isEmpty();
        for (String no : List.of(CID, SIBLING)) {
            var detail = ok(reconnected.app.conversation(USER, no));
            assertThat(detail.messages()).isEmpty();
            assertThat(detail.conversation().userClearedThroughMessageId()).isEqualTo(20L);
            assertThat(detail.conversation().unreadCount()).isZero();
            assertThat(detail.conversation().lastMessage()).isNull();
            assertThat(detail.conversation().lastPublicMessageId()).isZero();
            assertThat(detail.historyTruncated()).isFalse();
            assertThat(detail.nextCursor()).isNull();
        }
        assertThat(ok(reconnected.app.conversation(USER, OTHER_PEER)).messages())
                .extracting(ContentConversationMessageView::id).containsExactly(30L);
        assertThat(ok(reconnected.app.conversation(99L, "R20-FOREIGN")).messages())
                .extracting(ContentConversationMessageView::id).containsExactly(40L);
        assertMissingAttachment(reconnected, () -> reconnected.attachments.content(MEDIA_ID, "USER", USER));
        asStaff(7L, () -> {
            assertThat(reconnected.attachments.content(MEDIA_ID, "ADMIN", 7L).bytes()).isEqualTo(MEDIA_BYTES);
            return null;
        });
        assertThat(reconnected.repository.messages(SIBLING)).extracting(ContentConversationMessageView::id)
                .containsExactly(20L, 21L); // Internal staff note is retained too.
        assertThat(staffRows()).isEqualTo(originals);
    }

    @Test
    void filtersBeforeCursorLimitAndOldHistoryCannotReenterIncludingZeroBoundary() {
        ok(graph.management.clear(USER, CID, "r20-clear-cursor", bothPeers()));
        var zero = ok(graph.management.clear(USER, EMPTY, "r20-clear-empty",
                request("conversation:" + EMPTY, Map.of(EMPTY, 3L))));
        assertThat(zero.throughMessageId()).isZero();
        // Hidden ids 6, 5, 4 are ahead of visible id 3. Filtering after LIMIT would return a false empty page.
        var cursor = ok(graph.app.conversationCursor(USER, null, null, 1L));
        assertThat(cursor.getTotal()).isEqualTo(1);
        assertThat(cursor.getRecords()).extracting(ContentConversationView::conversationNo).containsExactly(OTHER_PEER);
        assertThat(ok(graph.app.conversationCursor(USER, null, 3L, 1L)).getRecords()).isEmpty();
        assertThat(ok(graph.app.conversations(USER, null, 1L, 1L)).getTotal()).isEqualTo(1);
        for (long oldCursor : List.of(10L, 11L, 20L, 999L)) {
            var page = ok(graph.app.conversation(USER, CID, oldCursor));
            assertThat(page.messages()).isEmpty();
            assertThat(page.nextCursor()).isNull();
            assertThat(page.historyTruncated()).isFalse();
        }
        assertThat(ok(graph.app.conversation(USER, EMPTY)).conversation().userClearedThroughMessageId()).isZero();
        assertThat(graph.app.conversation(99L, CID).getCode()).isEqualTo(404);
    }

    @Test
    void originalClearAndSendReplaysKeepOldBoundaryWithoutHidingNewMessage() {
        var sendRequest = new AppSupportService.ReplyRequest("original outgoing", "OPEN", 3L,
                "TEXT", null, "r20-original-client", 70L);
        var first = ok(graph.app.replyConversation(USER, CID, "r20-original-send", sendRequest));
        long sent = first.messages().get(first.messages().size() - 1).id();
        var clearRequest = request("agent:7", Map.of(CID, first.conversation().version(), SIBLING, 3L));
        var clearance = ok(graph.management.clear(USER, CID, "r20-original-clear", clearRequest));
        assertThat(clearance.throughMessageId()).isEqualTo(sent);
        long next = staffSend(7L, 70L, CID, "new incoming after clear", "r20-new-incoming");
        assertThat(next).isGreaterThan(sent);
        long totalAfterNewMessage = count("nx_conversation_message", "1=1");
        // Exercise retained success even after the command's processing lease has expired.
        jdbc.update("UPDATE nx_admin_idempotency_record SET expires_at=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE idempotency_key=?",
                "r20-original-clear");
        assertThat(ok(graph.management.clear(USER, CID, "r20-original-clear", clearRequest))).isEqualTo(clearance);
        assertThat(ok(graph.app.replyConversation(USER, CID, "r20-original-send", sendRequest)).messages())
                .extracting(ContentConversationMessageView::id).containsExactly(next);
        var readback = ok(graph.app.commandResult(USER, "r20-original-send"));
        var detail = graph.json.convertValue(readback.get("result"), ContentConversationDetail.class);
        assertThat(detail.messages()).extracting(ContentConversationMessageView::id).containsExactly(next);
        var clearReadback = ok(graph.app.commandResult(USER, "r20-original-clear"));
        assertThat(clearReadback.get("resultType")).isEqualTo("CONVERSATION_USER_CLEAR");
        assertThat(graph.json.convertValue(clearReadback.get("result"), ConversationUserClear.class)).isEqualTo(clearance);
        assertThat(count("nx_conversation_message", "1=1")).isEqualTo(totalAfterNewMessage);
        assertThat(ok(graph.app.conversation(USER, CID)).conversation().unreadCount()).isEqualTo(1);
        var read = ok(graph.app.markConversationRead(USER, CID, next, "OPEN",
                ok(graph.app.conversation(USER, CID)).conversation().version()));
        assertThat(read.conversation().unreadCount()).isZero();
        assertThat(jdbc.queryForList("SELECT message_id FROM nx_conversation_message_receipt ORDER BY message_id", Long.class))
                .containsExactly(next); // Clear did not mark old staff messages read.
    }

    @Test
    void wrongAccountPeerMemberOrVersionCannotPartiallyClear() {
        var before = staffRows();
        assertThat(graph.management.clear(99L, CID, "r20-foreign-clear", bothPeers()).getCode()).isEqualTo(404);
        assertThat(graph.management.clear(USER, CID, "r20-wrong-peer",
                request("agent:8", Map.of(CID, 3L))).getCode()).isEqualTo(404);
        assertThat(graph.management.clear(USER, CID, "r20-missing-member",
                request("agent:7", Map.of(CID, 3L))).getCode()).isEqualTo(409);
        assertThat(graph.management.clear(USER, CID, "r20-stale-version",
                request("agent:7", Map.of(CID, 2L, SIBLING, 3L))).getCode()).isEqualTo(409);
        assertThat(count("nx_app_conversation_user_clear", "1=1")).isZero();
        assertThat(count("nx_app_conversation_user_clear_member", "1=1")).isZero();
        assertThat(count("nx_audit_log", "action='APP_CONVERSATION_USER_CLEARED'")).isZero();
        assertThat(staffRows()).isEqualTo(before);
    }

    @Test
    void requiredAuditFailureRollsBackBothMarkersAndDoesNotCommitSuccessfulIdempotency() {
        var before = staffRows();
        jdbc.execute("CREATE TRIGGER r20_review_reject_clear_audit BEFORE INSERT ON nx_audit_log FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='R20 intentional audit failure'");
        assertThatThrownBy(() -> graph.management.clear(USER, CID, "r20-audit-rollback", bothPeers()))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("nx_app_conversation_user_clear", "1=1")).isZero();
        assertThat(count("nx_app_conversation_user_clear_member", "1=1")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM nx_admin_idempotency_record WHERE idempotency_key=?",
                String.class, "r20-audit-rollback")).isEqualTo("FAILED");
        assertThat(staffRows()).isEqualTo(before);
    }

    @Test
    void attachmentContentStartedBeforeClearMustRecheckAfterCustomerLock() throws Exception {
        var outcome = afterClearWinsCustomerLock(() -> graph.attachments.content(MEDIA_ID, "USER", USER));
        assertThat(outcome.failure()).as("old attachment bytes must not escape after the clear won the mutex")
                .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.getCode()).isEqualTo(404));
        assertThat(outcome.value()).isNull();
        verify(graph.storage, never()).get(MEDIA_KEY);
        assertThat(ok(graph.app.conversation(USER, CID)).messages()).isEmpty();
    }

    @Test
    void attachmentMetadataStartedBeforeClearMustRecheckAfterCustomerLock() throws Exception {
        var outcome = afterClearWinsCustomerLock(() -> graph.attachments.metadata(MEDIA_ID, "USER", USER));
        assertThat(outcome.failure()).isInstanceOfSatisfying(BizException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(404));
        assertThat(outcome.value()).isNull();
        verifyNoInteractions(graph.storage);
    }

    @Test
    void markReadStartedBeforeClearMustNotReturnOldTranscriptOrWriteOldReceipts() throws Exception {
        var outcome = afterClearWinsCustomerLock(() -> graph.app.markConversationRead(USER, CID, 11L, "OPEN", 3L));
        assertThat(outcome.failure()).isNull();
        assertThat(outcome.value()).isNotNull();
        assertThat(outcome.value().getCode()).as("the cleared target is no longer a visible agent message").isEqualTo(404);
        assertThat(outcome.value().getData()).isNull();
        assertThat(count("nx_conversation_message_receipt", "1=1")).isZero();
        assertThat(ok(graph.app.conversation(USER, CID)).messages()).isEmpty();
    }

    @Test
    void changedStaffCannotWriteNewMessagesIntoPreviousStaffCidButCurrentPeerStillWorks() {
        var before = graph.repository.messages(CID);
        transferFixtureAssignmentToB();
        asStaff(8L, () -> {
            var request = agentReply("must not appear under A", 3L, "r20-b-wrong-cid", 80L);
            assertThatThrownBy(() -> graph.ops.reply(CID, "r20-b-wrong-command", request))
                    .isInstanceOfSatisfying(BizException.class, failure -> {
                        assertThat(failure.getCode()).isEqualTo(409);
                        assertThat(failure.getMessage()).isEqualTo("CONVERSATION_PEER_CHANGED");
                    });
            return null;
        });
        assertThat(graph.repository.messages(CID)).isEqualTo(before);
        assertThat(count("nx_support_human_message", "client_message_id='r20-b-wrong-cid'")).isZero();
        long sentByB = staffSend(8L, 80L, OTHER_PEER, "legitimate B reply", "r20-b-own-cid");
        var a = ok(graph.app.conversation(USER, CID));
        var b = ok(graph.app.conversation(USER, OTHER_PEER));
        assertThat(a.conversation().ownerAgentId()).isEqualTo("7");
        assertThat(a.messages()).extracting(ContentConversationMessageView::id).doesNotContain(sentByB);
        assertThat(b.conversation().ownerAgentId()).isEqualTo("8");
        assertThat(b.messages()).extracting(ContentConversationMessageView::id).contains(sentByB).doesNotContain(10L, 11L);
    }

    @Test
    void changedPeerRejectsNewAppReplyButRetainedOriginalReplyStillRecoversWithoutDuplicate() {
        var original = new AppSupportService.ReplyRequest("before transfer", "OPEN", 3L,
                "TEXT", null, "r20-before-transfer", 70L);
        ok(graph.app.replyConversation(USER, CID, "r20-before-transfer-command", original));
        long before = count("nx_conversation_message", "1=1");
        transferFixtureAssignmentToB();
        assertThatThrownBy(() -> graph.app.replyConversation(USER, CID, "r20-after-transfer-command",
                new AppSupportService.ReplyRequest("new text for B", "OPEN", 4L,
                        "TEXT", null, "r20-after-transfer", 80L)))
                .isInstanceOfSatisfying(BizException.class, failure -> {
                    assertThat(failure.getCode()).isEqualTo(409);
                    assertThat(failure.getMessage()).isEqualTo("CONVERSATION_PEER_CHANGED");
                });
        var replay = ok(graph.app.replyConversation(USER, CID, "r20-before-transfer-command", original));
        assertThat(replay.conversation().ownerAgentId()).isEqualTo("7");
        assertThat(count("nx_conversation_message", "1=1")).isEqualTo(before);
        assertThat(count("nx_support_human_message", "client_message_id='r20-before-transfer'")).isEqualTo(1);
        var legitimate = ok(graph.app.replyConversation(USER, OTHER_PEER, "r20-after-transfer-own-command",
                new AppSupportService.ReplyRequest("current peer", "OPEN", 3L,
                        "TEXT", null, "r20-current-peer", 80L)));
        assertThat(legitimate.conversation().ownerAgentId()).isEqualTo("8");
        assertThat(legitimate.messages()).extracting(ContentConversationMessageView::content).contains("current peer");
    }

    @Test
    void archivedSiblingIsCoveredAndMemberWatermarkSurvivesLegacyReassignment() {
        jdbc.update("UPDATE nx_conversation SET archived=1 WHERE conversation_no=?", SIBLING);
        var receipt = ok(graph.management.clear(USER, CID, "r20-hidden-sibling",
                request("agent:7", Map.of(CID, 3L))));
        assertThat(receipt.conversationNos()).containsExactlyInAnyOrder(CID, SIBLING);
        assertThat(receipt.throughMessageId()).isEqualTo(20L);
        // An existing legacy ownership rewrite must not undo the durable CID boundary.
        jdbc.update("UPDATE nx_conversation SET owner_agent_id='8',owner_agent_name='same display name' WHERE conversation_no=?", CID);
        transferFixtureAssignmentToB();
        assertThat(ok(graph.app.conversation(USER, CID)).messages()).isEmpty();
        long newer = staffSend(8L, 80L, CID, "new after legacy reassignment", "r20-after-reassignment");
        var detail = ok(graph.app.conversation(USER, CID));
        assertThat(detail.messages()).extracting(ContentConversationMessageView::id).containsExactly(newer);
        assertThat(detail.conversation().userClearedThroughMessageId()).isEqualTo(20L);
        assertThat(detail.conversation().ownerAgentId()).isEqualTo("8");
        assertMissingAttachment(graph, () -> graph.attachments.content(MEDIA_ID, "USER", USER));
    }

    private <T> Outcome<T> afterClearWinsCustomerLock(Supplier<T> operation) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var gate = new ReadGate();
        var future = new AtomicReference<Future<Outcome<T>>>();
        try {
            var competing = new TransactionTemplate(graph.transactions);
            competing.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            competing.executeWithoutResult(status -> {
                ok(graph.management.clear(USER, CID, "r20-competing-clear", bothPeers()));
                // Production clear has written its markers, but its customer/header locks remain held.
                future.set(worker.submit(() -> {
                    probe.current.set(gate);
                    // SecurityContextHolder is thread-local: the real attachment guard must see a USER
                    // on this executor thread, rather than exiting at LOGIN_REQUIRED before any SQL.
                    try { return new Outcome<>(asUser(USER, operation), null); }
                    catch (RuntimeException failure) { return new Outcome<>(null, failure); }
                    finally { probe.current.remove(); SecurityContextHolder.clearContext(); }
                }));
                try {
                    assertThat(gate.lockAttempt.await(8, TimeUnit.SECONDS))
                            .as("reader must reach the actual customer-lock mapper while clear is uncommitted").isTrue();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted waiting for real mapper lock", failure);
                }
                assertThat(gate.initialReadCompleted.get())
                        .as("reader must have completed a nonlocking production read before the clear commits").isTrue();
                assertThat(future.get().isDone()).as("reader cannot finish while competing clear owns the mutex").isFalse();
            }); // Commit, then let the reader's real SELECT ... FOR UPDATE proceed.
            assertThat(jdbc.queryForObject("SELECT through_message_id FROM nx_app_conversation_user_clear WHERE user_id=42 AND peer_key='agent:7'",
                    Long.class)).isEqualTo(20L);
            return future.get().get(12, TimeUnit.SECONDS);
        } finally {
            if (future.get() != null && !future.get().isDone()) future.get().cancel(true);
            worker.shutdownNow();
            assertThat(worker.awaitTermination(15, TimeUnit.SECONDS)).as("no reader may survive into the next table reset").isTrue();
        }
    }

    private record Outcome<T>(T value, RuntimeException failure) {}

    private static final class ReadGate {
        final CountDownLatch lockAttempt = new CountDownLatch(1);
        final AtomicBoolean initialReadCompleted = new AtomicBoolean();
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    public static final class LockProbe implements Interceptor {
        final ThreadLocal<ReadGate> current = new ThreadLocal<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var gate = current.get();
            String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (gate != null && statement.equals(SupportBindingMapper.class.getName() + ".lockCustomer"))
                gate.lockAttempt.countDown();
            Object result = invocation.proceed(); // Always execute real production SQL; no canned mapper responses.
            if (gate != null && (statement.equals(SupportAttachmentMapper.class.getName() + ".customer")
                    || statement.equals(SupportAcceptanceSandboxMapper.class.getName() + ".sandboxUser")))
                gate.initialReadCompleted.set(true);
            return result;
        }
    }

    private static final class Graph {
        final ObjectMapper json = applicationObjectMapper();
        final DataSourceTransactionManager transactions;
        final AppConversationManagementService management;
        final AppSupportService app;
        final OpsConversationService ops;
        final SupportAttachmentService attachments;
        final MybatisConversationRepository repository;
        final ObjectStorageService storage = mock(ObjectStorageService.class);

        Graph(CheckedDataSource dataSource, LockProbe probe) {
            transactions = new DataSourceTransactionManager(dataSource);
            var configuration = new MybatisConfiguration(new Environment(
                    "r20-independent-mysql-review", new SpringManagedTransactionFactory(), dataSource));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            configuration.setDefaultStatementTimeout(12);
            configuration.addInterceptor(probe);
            for (Class<?> mapper : List.of(AppConversationClearMapper.class, ConversationMapper.class,
                    ConversationMessageMapper.class, SupportAttachmentMapper.class, SupportBindingMapper.class,
                    SupportGroupMapper.class, SupportHumanMessageMapper.class, SupportAcceptanceSandboxMapper.class,
                    SupportAgentMapper.class, AuditLogMapper.class, AdminMapper.class, AdminIdempotencyRecordMapper.class))
                configuration.addMapper(mapper);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
            var bindings = session.getMapper(SupportBindingMapper.class);
            var ownership = proxy(new SupportOwnershipService(bindings, session.getMapper(SupportGroupMapper.class)),
                    SupportOwnershipService.class);
            var environment = new MockEnvironment();
            environment.setActiveProfiles("dev");
            var guard = new ProductionSupportPathGuard(environment, session.getMapper(SupportAcceptanceSandboxMapper.class));
            repository = new MybatisConversationRepository(session.getMapper(ConversationMapper.class),
                    session.getMapper(ConversationMessageMapper.class), ownership);
            var visibility = new AppConversationVisibilityService(session.getMapper(AppConversationClearMapper.class), repository);
            var expiry = proxy(new AdminIdempotencyExpiryTransitionExecutor(session.getMapper(AdminIdempotencyRecordMapper.class)),
                    AdminIdempotencyExpiryTransitionExecutor.class);
            var executor = proxy(new AdminIdempotencyTransactionExecutor(session.getMapper(AdminIdempotencyRecordMapper.class), json, expiry),
                    AdminIdempotencyTransactionExecutor.class);
            var clock = Clock.systemUTC();
            var idempotency = new AdminIdempotencyService(executor, clock);
            var policy = mock(A2RuntimePolicy.class);
            when(policy.retentionMonths()).thenReturn(24);
            when(policy.schemaVersion()).thenReturn("v4");
            var auditProperties = new AuditProperties();
            auditProperties.setFailFast(true);
            var audit = new AuditLogService(session.getMapper(AuditLogMapper.class), new AuditLogSanitizer(json),
                    new ApplicationNameProperties(), auditProperties, session.getMapper(AdminMapper.class), policy);
            ApplicationEventPublisher events = event -> { /* Parent separately verifies real AFTER_COMMIT websocket delivery. */ };
            when(storage.get(MEDIA_KEY)).thenAnswer(call -> new ByteArrayInputStream(MEDIA_BYTES));
            attachments = proxy(new SupportAttachmentService(session.getMapper(SupportAttachmentMapper.class), bindings,
                    ownership, storage, mock(SupportAttachmentPolicy.class), guard, transactions,
                    mock(SupportBulkMapper.class), json), SupportAttachmentService.class);
            var human = proxy(new SupportHumanMessageService(session.getMapper(SupportHumanMessageMapper.class), bindings,
                    ownership, mock(SupportMaintenanceService.class), attachments), SupportHumanMessageService.class);
            var config = mock(PlatformConfigFacade.class);
            when(config.activeValue("I.session.cat.advisor.enabled")).thenReturn(Optional.of("on"));
            management = proxy(new AppConversationManagementService(session.getMapper(AppConversationClearMapper.class),
                    ownership, guard, idempotency, json, audit, events), AppConversationManagementService.class);
            app = proxy(new AppSupportService(mock(SupportTicketRepository.class), repository,
                    mock(SupportKnowledgeRepository.class), idempotency, audit, events, clock, guard,
                    session.getMapper(AdminIdempotencyRecordMapper.class), json,
                    new MybatisSupportAgentRepository(session.getMapper(SupportAgentMapper.class)), config,
                    ownership, human, visibility, org.mockito.Mockito.mock(ffdd.opsconsole.content.application.SupportUnboundReceipt.class)), AppSupportService.class);
            ops = proxy(new OpsConversationService(repository, mock(SupportTicketRepository.class),
                    mock(OpsSupportAgentService.class), config, audit, clock,
                    mock(ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.class),
                    mock(ffdd.opsconsole.user.application.OpsUserService.class),
                    mock(ffdd.opsconsole.finance.application.OpsFinanceService.class),
                    mock(ffdd.opsconsole.device.application.OpsDeviceService.class),
                    mock(ffdd.opsconsole.risk.application.OpsRiskService.class), mock(CustomerProfileRepository.class),
                    guard, ownership, human, new SupportReplyService(bindings, ownership),
                    mock(SupportCustomerProfileService.class)), OpsConversationService.class);
        }

        private <T> T proxy(T target, Class<T> type) {
            var factory = new ProxyFactory(target);
            factory.setProxyTargetClass(true);
            factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            return type.cast(factory.getProxy());
        }

        private static ObjectMapper applicationObjectMapper() {
            // Match Spring's web/Boot mapper defaults and the application's actual date-time customizer.
            // A bare ObjectMapper rejects computed output-only DTO fields such as getLinkTarget().
            var builder = Jackson2ObjectMapperBuilder.json();
            new DateTimeFormatConfig().nexionDateTimeJacksonCustomizer().customize(builder);
            ObjectMapper mapper = builder.build();
            assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                    .as("Spring web default used by the application, not a relaxed production guard").isFalse();
            return mapper;
        }
    }

    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Explicit R20_MYSQL_IT=true requires " + key);
        return value;
    }

    private static final class CheckedDataSource extends DriverManagerDataSource {
        CheckedDataSource(String url, String username, String password) {
            super(url, username, password);
            setDriverClassName("com.mysql.cj.jdbc.Driver");
            var options = new Properties();
            options.setProperty("useSSL", "false");
            options.setProperty("allowPublicKeyRetrieval", "true");
            options.setProperty("characterEncoding", "UTF-8");
            options.setProperty("serverTimezone", "UTC");
            options.setProperty("connectTimeout", "5000");
            options.setProperty("socketTimeout", "15000");
            setConnectionProperties(options);
        }
        @Override public Connection getConnection() throws SQLException { return checked(super.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return checked(super.getConnection(user, password));
        }
        private Connection checked(Connection connection) throws SQLException {
            try (var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT DATABASE(),@@port,@@datadir,CURRENT_USER()")) {
                if (!row.next() || !"r20_user_clear".equals(row.getString(1)) || row.getInt(2) != 33327
                        || !DATA_DIR.equalsIgnoreCase(row.getString(3).replace('\\', '/').replaceAll("/+$", ""))
                        || !row.getString(4).startsWith("r20_runner@"))
                    throw new SQLException("R20 disposable database ownership check failed; refusing fixture writes");
                // Exercise the normal server baseline. Production @Transactional isolation can override this.
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                return connection;
            } catch (SQLException | RuntimeException | Error failure) {
                connection.close();
                throw failure;
            }
        }
    }

    private static <T> T ok(ApiResult<T> result) {
        assertThat(result).isNotNull();
        assertThat(result.getCode()).as("API result: %s", result.getMessage()).isZero();
        assertThat(result.getData()).isNotNull();
        return result.getData();
    }

    private static ClearRequest request(String peer, Map<String, Long> versions) {
        return new ClearRequest("USER_VIEW", peer, versions.keySet().stream().sorted().toList(), versions);
    }

    private static ClearRequest bothPeers() { return request("agent:7", Map.of(CID, 3L, SIBLING, 3L)); }

    private static <T> T asStaff(long actor, Supplier<T> operation) {
        var prior = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var auth = new UsernamePasswordAuthenticationToken(Long.toString(actor), null,
                List.of(new SimpleGrantedAuthority("service_m3_read"), new SimpleGrantedAuthority("service_m3_write")));
        auth.setDetails(Map.of("subjectType", "ADMIN"));
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        try { return operation.get(); }
        finally { SecurityContextHolder.setContext(prior); }
    }

    private static <T> T asUser(long actor, Supplier<T> operation) {
        var prior = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var auth = new UsernamePasswordAuthenticationToken(Long.toString(actor), null, List.of());
        auth.setDetails(Map.of("subjectType", "USER"));
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        try { return operation.get(); }
        finally { SecurityContextHolder.setContext(prior); }
    }

    private static ConversationReplyRequest agentReply(String body, long version, String client, long assignment) {
        return new ConversationReplyRequest(body, "OPEN", version, "independent R20 fixture reply", "ignored",
                List.of(), null, "TEXT", null, "SERVICE", client, assignment);
    }

    private long staffSend(long staff, long assignment, String no, String body, String client) {
        long version = ok(graph.app.conversation(USER, no)).conversation().version();
        asStaff(staff, () -> ok(graph.ops.reply(no, client + "-command", agentReply(body, version, client, assignment))));
        return jdbc.queryForObject("SELECT message_id FROM nx_support_human_message WHERE actor_type='ADMIN' AND actor_id=? AND client_message_id=?",
                Long.class, staff, client);
    }

    private static void assertMissingAttachment(Graph current, Supplier<?> operation) {
        assertThatThrownBy(() -> asUser(USER, operation)).isInstanceOfSatisfying(BizException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(404));
        verify(current.storage, never()).get(MEDIA_KEY);
    }

    private long count(String table, String predicate) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + predicate, Long.class);
    }

    private List<List<Map<String, Object>>> staffRows() {
        return List.of(jdbc.queryForList("SELECT * FROM nx_conversation_message ORDER BY id"),
                jdbc.queryForList("SELECT * FROM nx_support_human_message ORDER BY message_id"),
                jdbc.queryForList("SELECT * FROM nx_support_attachment ORDER BY id"),
                jdbc.queryForList("SELECT * FROM nx_conversation_message_receipt ORDER BY message_id"));
    }

    private void transferFixtureAssignmentToB() {
        // Seed the durable post-transfer facts under the SAME customer mutex; no mocked assignment getter.
        // Testing the complete group-transfer endpoint itself is outside this user-clear review.
        var transaction = new TransactionTemplate(graph.transactions);
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM nx_user WHERE id=42 FOR UPDATE", Long.class);
            jdbc.update("UPDATE nx_support_agent_user_assignment SET status='ENDED',ends_at=UTC_TIMESTAMP(6) WHERE user_id=42 AND status='ACTIVE'");
            jdbc.update("INSERT INTO nx_support_agent_user_assignment(id,user_id,agent_admin_id,starts_at,status) VALUES(80,42,8,UTC_TIMESTAMP(6),'ACTIVE')");
        });
    }

    private void seed() {
        jdbc.update("INSERT INTO nx_user(id,sandbox) VALUES(42,0),(99,0)");
        jdbc.update("INSERT INTO nx_admin(id,username,nickname) VALUES(7,'r20-agent-a','same display name'),(8,'r20-agent-b','same display name')");
        jdbc.update("INSERT INTO nx_admin_role(id,role_code) VALUES(1,'SUPER_ADMIN')");
        jdbc.update("INSERT INTO nx_admin_role_relation(id,admin_id,role_id) VALUES(1,7,1),(2,8,1)");
        jdbc.update("INSERT INTO nx_support_agent_profile(id,admin_id) VALUES(1,7),(2,8)");
        jdbc.update("INSERT INTO nx_support_account_qualification_history(id,admin_id,qualification_kind,state,starts_at) VALUES"
                + "(1,7,'SERVICE','ENABLED','2020-01-01'),(2,8,'SERVICE','ENABLED','2020-01-01')");
        jdbc.update("INSERT INTO nx_support_agent_user_assignment(id,user_id,agent_admin_id,starts_at,status) VALUES(70,42,7,'2020-01-01','ACTIVE')");
        jdbc.update("INSERT INTO nx_support_activity_coverage(id,observed_through_at) VALUES(1,'2020-01-01')");
        jdbc.update("INSERT INTO nx_conversation(id,conversation_no,user_id,owner_agent_id,owner_agent_name) VALUES"
                + "(5,'R20-A',42,'7','same display name'),(4,'R20-A-OLDER',42,'7','same display name'),"
                + "(3,'R20-B',42,'8','same display name'),(2,'R20-FOREIGN',99,'7','same display name'),"
                + "(6,'R20-EMPTY',42,NULL,'unassigned')");
        jdbc.update("INSERT INTO nx_conversation_message(id,conversation_id,conversation_no,sender_id,sender_type,sender_name,content) VALUES"
                + "(10,5,'R20-A',42,'user','User','original user text'),(11,5,'R20-A',7,'agent','Agent','old attachment caption'),"
                + "(20,4,'R20-A-OLDER',7,'agent','Agent','old sibling text'),(21,4,'R20-A-OLDER',7,'system','Staff','internal staff note'),"
                + "(30,3,'R20-B',8,'agent','Agent','other staff history'),(40,2,'R20-FOREIGN',7,'agent','Agent','other account history')");
        jdbc.update("INSERT INTO nx_support_human_message(message_id,customer_id,assignment_id,actor_type,actor_id,client_message_id,kind,intent,attachment_id,payload_hash) "
                + "VALUES(11,42,70,'ADMIN',7,'r20-seeded-image','IMAGE','SERVICE',?,'seed')", MEDIA_ID);
        jdbc.update("INSERT INTO nx_support_attachment(id,customer_id,uploader_type,uploader_id,assignment_id,client_upload_id,request_hash,mime,bytes,width,height,object_key,state,expires_at,message_id) "
                + "VALUES(?,42,'ADMIN',7,70,'r20-upload-image','seed','image/png',?,2,2,?,'ATTACHED',DATE_ADD(NOW(),INTERVAL 1 DAY),11)",
                MEDIA_ID, MEDIA_BYTES.length, MEDIA_KEY);
    }

    private static final List<String> TABLES = List.of(
            "nx_app_conversation_user_clear_member", "nx_app_conversation_user_clear", "nx_audit_log",
            "nx_admin_idempotency_record", "nx_support_attachment", "nx_support_human_message",
            "nx_conversation_message_receipt", "nx_conversation_message", "nx_conversation_transfer",
            "nx_conversation_timeout_event", "nx_support_reply_cursor", "nx_conversation",
            "nx_support_activity_coverage", "nx_support_agent_user_assignment", "nx_support_account_qualification_history",
            "nx_support_agent_profile", "nx_admin_account_state", "nx_admin_role_relation", "nx_admin_role",
            "nx_admin", "nx_user", "nx_product");

    // Only the production columns queried by this bounded service graph. No scripts/schema.sql USE nexion.
    private static final List<String> DDL = List.of(
            "CREATE TABLE nx_user(id BIGINT PRIMARY KEY,sandbox INT NOT NULL DEFAULT 0,is_deleted INT NOT NULL DEFAULT 0)",
            "CREATE TABLE nx_admin(id BIGINT PRIMARY KEY,username VARCHAR(100),nickname VARCHAR(100),status INT DEFAULT 1,is_deleted INT DEFAULT 0)",
            "CREATE TABLE nx_admin_role(id BIGINT PRIMARY KEY,role_code VARCHAR(40),status INT DEFAULT 1,is_deleted INT DEFAULT 0)",
            "CREATE TABLE nx_admin_role_relation(id BIGINT PRIMARY KEY,admin_id BIGINT,role_id BIGINT,is_deleted INT DEFAULT 0)",
            "CREATE TABLE nx_support_agent_profile(id BIGINT PRIMARY KEY,admin_id BIGINT,enabled INT DEFAULT 1,is_deleted INT DEFAULT 0)",
            "CREATE TABLE nx_support_account_qualification_history(id BIGINT PRIMARY KEY,admin_id BIGINT,qualification_kind VARCHAR(20),state VARCHAR(20),starts_at DATETIME(6),ends_at DATETIME(6),version BIGINT DEFAULT 0)",
            "CREATE TABLE nx_support_agent_user_assignment(id BIGINT PRIMARY KEY,user_id BIGINT,agent_admin_id BIGINT,status VARCHAR(20),starts_at DATETIME(6),ends_at DATETIME(6),version BIGINT DEFAULT 0,source VARCHAR(20) DEFAULT 'MANUAL',segment_root_id BIGINT,depth INT DEFAULT 0,parent_assignment_id BIGINT,rule_version BIGINT,is_deleted INT DEFAULT 0)",
            "CREATE TABLE nx_support_activity_coverage(id BIGINT PRIMARY KEY,observed_through_at DATETIME(6))",
            "CREATE TABLE nx_conversation(id BIGINT PRIMARY KEY AUTO_INCREMENT,conversation_no VARCHAR(40) NOT NULL UNIQUE,user_id BIGINT,conversation_type VARCHAR(16) DEFAULT 'advisor',status VARCHAR(16) DEFAULT 'OPEN',owner_agent_id VARCHAR(40),owner_agent_name VARCHAR(100),unread_count INT DEFAULT 9,last_message VARCHAR(2000) DEFAULT 'old preview',last_message_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,version BIGINT DEFAULT 3,is_deleted INT DEFAULT 0,archived BOOLEAN DEFAULT 0)",
            "CREATE TABLE nx_conversation_message(id BIGINT PRIMARY KEY AUTO_INCREMENT,conversation_id BIGINT,conversation_no VARCHAR(40),sender_id BIGINT,sender_type VARCHAR(16),sender_name VARCHAR(100),content VARCHAR(2000),created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,is_deleted INT DEFAULT 0,INDEX conversation_message(conversation_no,id))",
            "CREATE TABLE nx_conversation_message_receipt(message_id BIGINT PRIMARY KEY,conversation_no VARCHAR(40),receipt_status VARCHAR(16),read_by VARCHAR(64),read_at DATETIME,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)",
            "CREATE TABLE nx_support_human_message(message_id BIGINT PRIMARY KEY,customer_id BIGINT,assignment_id BIGINT,kind VARCHAR(20),intent VARCHAR(20),client_message_id VARCHAR(128),attachment_id VARCHAR(40),committed_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),payload_hash VARCHAR(64),sku_id VARCHAR(40),sku_name VARCHAR(100),link_target_json JSON,actor_type VARCHAR(16),actor_id BIGINT,UNIQUE actor_client(actor_type,actor_id,client_message_id))",
            "CREATE TABLE nx_support_attachment(id VARCHAR(36) PRIMARY KEY,customer_id BIGINT,uploader_type VARCHAR(16),uploader_id BIGINT,assignment_id BIGINT,client_upload_id VARCHAR(128),request_hash VARCHAR(64),mime VARCHAR(128),bytes BIGINT,width INT,height INT,object_key VARCHAR(255),state VARCHAR(16),expires_at DATETIME,message_id BIGINT)",
            "CREATE TABLE nx_admin_account_state(admin_id BIGINT PRIMARY KEY,avatar_asset_id VARCHAR(40),avatar_version BIGINT,is_deleted INT DEFAULT 0)",
            "CREATE TABLE nx_product(id BIGINT PRIMARY KEY,product_no VARCHAR(40),name VARCHAR(100),is_deleted INT,store_visible INT,price_usdt DECIMAL(20,2),store_status VARCHAR(16),status VARCHAR(16),estimated_daily_usdt DECIMAL(20,2),daily_nex DECIMAL(20,2))",
            "CREATE TABLE nx_conversation_transfer(id BIGINT PRIMARY KEY AUTO_INCREMENT,conversation_no VARCHAR(40),from_agent_id VARCHAR(40),from_agent_name VARCHAR(100),to_type VARCHAR(20),to_id VARCHAR(40),to_name VARCHAR(100),reason VARCHAR(200),transferred_at DATETIME,status VARCHAR(20),is_deleted INT DEFAULT 0)",
            "CREATE TABLE nx_conversation_timeout_event(id BIGINT PRIMARY KEY AUTO_INCREMENT,conversation_no VARCHAR(40),event_type VARCHAR(16),created_at DATETIME)",
            "CREATE TABLE nx_support_reply_cursor(conversation_no VARCHAR(40) PRIMARY KEY,through_message_id BIGINT,reply_message_id BIGINT)",
            "CREATE TABLE nx_admin_idempotency_record(id BIGINT PRIMARY KEY AUTO_INCREMENT,scope VARCHAR(128) NOT NULL,idempotency_key VARCHAR(128) NOT NULL,request_hash VARCHAR(64) NOT NULL,status VARCHAR(16) NOT NULL,response_json JSON,error_message VARCHAR(500),expires_at DATETIME NOT NULL,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,is_deleted INT DEFAULT 0,UNIQUE scope_key(scope,idempotency_key),INDEX expiry(status,is_deleted,expires_at,id))",
            "CREATE TABLE nx_audit_log(id BIGINT PRIMARY KEY AUTO_INCREMENT,trace_id VARCHAR(100),service_name VARCHAR(100),action VARCHAR(100),resource_type VARCHAR(100),resource_id VARCHAR(100),biz_no VARCHAR(100),user_id BIGINT,actor_id BIGINT,actor_type VARCHAR(20),actor_username VARCHAR(100),client_ip VARCHAR(100),method VARCHAR(20),path VARCHAR(255),result VARCHAR(20),risk_level VARCHAR(20),detail_json JSON,created_at DATETIME,is_deleted INT DEFAULT 0,retention_policy_months INT,expire_at DATETIME)"
    );
}
