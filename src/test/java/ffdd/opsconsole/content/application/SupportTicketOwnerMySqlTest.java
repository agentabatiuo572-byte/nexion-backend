package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;

import ffdd.opsconsole.content.dto.ConversationTicketRequest;
import ffdd.opsconsole.content.dto.SupportBindingRequest;
import ffdd.opsconsole.content.dto.SupportTicketAssigneeRequest;
import ffdd.opsconsole.content.dto.SupportTicketCreateRequest;
import ffdd.opsconsole.content.dto.SupportTicketNoteRequest;
import ffdd.opsconsole.content.dto.SupportTicketPriorityRequest;
import ffdd.opsconsole.content.dto.SupportTicketQueryRequest;
import ffdd.opsconsole.content.dto.SupportTicketReplyRequest;
import ffdd.opsconsole.content.dto.SupportTicketStatusRequest;
import ffdd.opsconsole.shared.exception.BizException;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Production ownership, binding, ticket SQL and retained idempotency in a disposable loopback schema. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class SupportTicketOwnerMySqlTest {
    enum Entry { APP_DIRECT, APP_CONVERSION, ADMIN_DIRECT, ADMIN_CONVERSION }

    @Test
    void startupPreflightAcceptsEquivalentSchemaWithoutHistoricalMigrationMarker() throws Exception {
        try (var runtime = new SupportTicketCreationMySqlTest.Runtime(true)) {
            assertThat(runtime.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_migration'", Integer.class)).isZero();
            String query = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/support_ticket_owner_preflight.sql"));
            assertThat(runtime.jdbc.queryForObject(query, String.class)).isEqualTo("READY");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startupPreflightIdentifiesMissingBindingTableOrColumnWithoutWrites(boolean missingTable) throws Exception {
        try (var runtime = new SupportTicketCreationMySqlTest.Runtime(true)) {
            if (missingTable) runtime.jdbc.execute("DROP TABLE nx_support_binding_pool");
            else runtime.jdbc.execute("ALTER TABLE nx_support_agent_user_assignment DROP COLUMN source");
            String query = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/support_ticket_owner_preflight.sql"));
            assertThat(runtime.jdbc.queryForObject(query, String.class)).startsWith("MISSING: ")
                    .contains(missingTable ? "nx_support_binding_pool.customer_id" : "nx_support_agent_user_assignment.source");
            assertThat(runtime.ticketCount(1)).isZero();
            assertThat(runtime.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_migration'", Integer.class)).isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void allCreationEntriesDeriveTheCurrentAdvisorAndServerName(Entry entry) throws Exception {
        try (var runtime = new SupportTicketCreationMySqlTest.Runtime(true)) {
            bind(runtime, 1, 99);
            if (entry == Entry.APP_DIRECT) {
                assertThat(runtime.app(1, "owner-app", "App title", "App body").getCode()).isZero();
            } else if (entry == Entry.ADMIN_DIRECT) {
                assertThat(runtime.asAdmin(() -> runtime.adminService.create("owner-admin",
                        new SupportTicketCreateRequest(1L, "other", "NORMAL", "Admin title", "Admin body",
                                99L, "Forged display name", "forged-operator", "owner acceptance"))).getCode()).isZero();
            } else {
                runtime.conversation("owner-conversation", 1);
                if (entry == Entry.APP_CONVERSION) {
                    assertThat(runtime.appService.convertConversationToTicket(1L, "owner-conversation", "owner-convert-app",
                            new AppSupportService.ConvertToTicketRequest("other", "Converted", "OPEN", 0L)).getCode()).isZero();
                } else {
                    assertThat(runtime.asAdmin(() -> runtime.conversationController.convertToTicket("owner-conversation",
                            "owner-convert-admin", conversion(99L))).getCode()).isZero();
                }
                assertThat(runtime.jdbc.queryForObject("SELECT status FROM nx_conversation WHERE conversation_no='owner-conversation'", String.class))
                        .isEqualTo("CLOSED");
                assertThat(runtime.jdbc.queryForObject("SELECT source_conversation_no FROM nx_support_ticket", String.class))
                        .isEqualTo("owner-conversation");
            }
            assertOwner(runtime, 99L, "Advisor 99", 0L);
            assertThat(runtime.jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_binding_pool", Integer.class)).isZero();
            assertThat(runtime.ticketCount(1)).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitOtherAdvisorIsRejectedAndConversionIsRolledBack(boolean conversion) throws Exception {
        try (var runtime = new SupportTicketCreationMySqlTest.Runtime(true)) {
            bind(runtime, 1, 99);
            runtime.conversation("reject-conversation", 1);
            assertThatThrownBy(() -> runtime.asAdmin(() -> conversion
                    ? runtime.conversationController.convertToTicket("reject-conversation", "reject-owner-conversion", conversion(100L))
                    : runtime.adminService.create("reject-owner-direct", new SupportTicketCreateRequest(1L, "other", "NORMAL",
                            "Rejected", "Rejected body", 100L, "Advisor 100", "test", "owner acceptance"))))
                    .isInstanceOf(BizException.class).hasMessage("SUPPORT_TICKET_OWNER_BINDING_MISMATCH")
                    .extracting(failure -> ((BizException) failure).getCode()).isEqualTo(409);
            assertThat(runtime.ticketCount(1)).isZero();
            assertThat(runtime.messageCount()).isZero();
            runtime.assertConversationUnchanged("reject-conversation");
            assertThat(runtime.bindingMapper.currentAgent(1L)).isEqualTo(99L);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void busyOrDisabledExistingAdvisorStillOwnsAppCreatedTickets(boolean disabled) throws Exception {
        try (var runtime = new SupportTicketCreationMySqlTest.Runtime(true)) {
            bind(runtime, 1, 99);
            runtime.jdbc.update("UPDATE nx_support_agent_profile SET busy=1,enabled=? WHERE admin_id=99", disabled ? 0 : 1);
            if (disabled) runtime.jdbc.update("UPDATE nx_admin SET status=0 WHERE id=99");
            assertThat(runtime.app(1, "unavailable-owner", "Existing binding", "Retain ownership").getCode()).isZero();
            assertOwner(runtime, 99L, "Advisor 99", 0L);
            assertThat(runtime.bindingMapper.currentAgent(1L)).isEqualTo(99L);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unboundCreationPreservesPoolReasonAndFirstSupervisorBindingFillsOwner(boolean existingPool) throws Exception {
        try (var runtime = new SupportTicketCreationMySqlTest.Runtime(true)) {
            if (existingPool) runtime.jdbc.update("INSERT INTO nx_support_binding_pool(customer_id,reason,version,entered_at) VALUES(1,'DEPTH_LIMIT',7,UTC_TIMESTAMP(6))");
            assertThat(runtime.app(1, "unbound-create", "Unbound", "Still needs support").getCode()).isZero();
            assertOwner(runtime, null, "Unassigned", 0L);
            assertThat(runtime.jdbc.queryForMap("SELECT reason,version FROM nx_support_binding_pool WHERE customer_id=1"))
                    .containsEntry("reason", existingPool ? "DEPTH_LIMIT" : "MIGRATION_REVIEW")
                    .containsEntry("version", existingPool ? 7L : 1L);
            var messages = allMessages(runtime);
            assertThat(runtime.asActor(101L, () -> runtime.bindings.transfer("first-binding", transferRequest(runtime, 100L))).getCode()).isZero();
            assertOwner(runtime, 100L, "Advisor 100", 1L);
            assertThat(allMessages(runtime)).isEqualTo(messages);
            assertThat(runtime.jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_binding_pool", Integer.class)).isZero();
        }
    }

    @Test
    void transferSynchronizesActiveClosedAndArchivedHeadersPreservingHistoricalMessages() throws Exception {
        try (var runtime = new SupportTicketCreationMySqlTest.Runtime(true)) {
            bind(runtime, 1, 99);
            runtime.app(1, "history-active", "Active", "Original customer message");
            runtime.seed(1, "history-closed", "CLOSED", false, false, 7200);
            runtime.seed(1, "history-archived", "CLOSED", true, false, 7300);
            runtime.seed(1, "history-deleted", "CLOSED", false, true, 7400);
            runtime.jdbc.update("UPDATE nx_support_ticket SET assigned_admin_id=99,assigned_admin_name='Advisor 99',version=5");
            runtime.jdbc.update("INSERT INTO nx_support_ticket_message(ticket_id,ticket_no,sender_id,sender_type,sender_name,content) "
                    + "SELECT id,ticket_no,99,'agent','Advisor 99','Historical advisor response' FROM nx_support_ticket WHERE is_deleted=0");
            var messages = allMessages(runtime);
            var request = transferRequest(runtime, 100L);
            assertThat(runtime.asActor(101L, () -> runtime.bindings.transfer("history-transfer", request)).getCode()).isZero();
            assertOwner(runtime, 100L, "Advisor 100", 6L);
            assertThat(runtime.jdbc.queryForMap("SELECT assigned_admin_id,version FROM nx_support_ticket WHERE ticket_no='history-deleted'"))
                    .containsEntry("assigned_admin_id", 99L).containsEntry("version", 5L);
            assertThat(allMessages(runtime)).isEqualTo(messages);
            assertThat(runtime.jdbc.queryForList("SELECT status FROM nx_support_agent_user_assignment ORDER BY id", String.class))
                    .containsExactly("INACTIVE", "ACTIVE");
            for (String scope : List.of("active", "resolved", "archived")) {
                var former = runtime.asActor(101L, () -> runtime.tickets.pageTickets(query(scope, 99L)));
                var current = runtime.asActor(101L, () -> runtime.tickets.pageTickets(query(scope, 100L)));
                assertThat(former.getTotal()).as(scope + " former owner filter").isZero();
                assertThat(former.getRecords()).isEmpty();
                assertThat(current.getTotal()).as(scope + " current owner filter").isEqualTo(1);
                assertThat(current.getRecords()).singleElement().satisfies(ticket -> {
                    assertThat(ticket.assignedAdminId()).isEqualTo(100L);
                    assertThat(ticket.assignedAdminName()).isEqualTo("Advisor 100");
                    var detail = runtime.asActor(101L, () -> runtime.adminService.detail(ticket.ticketNo()));
                    assertThat(detail.getCode()).isZero();
                    assertThat(detail.getData().ticket().assignedAdminId()).isEqualTo(ticket.assignedAdminId());
                    assertThat(detail.getData().ticket().assignedAdminName()).isEqualTo(ticket.assignedAdminName());
                    assertThat(detail.getData().ticket().version()).isEqualTo(6L);
                });
            }
            assertThat(runtime.asActor(101L, () -> runtime.bindings.transfer("history-transfer", request)).getCode()).isZero();
            assertOwner(runtime, 100L, "Advisor 100", 6L);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oldAdvisorLosesCustomerReplyButKeepsTicketCollaborationAndCannotAssignOneTicket(boolean privateSource) throws Exception {
        try (var runtime = new SupportTicketCreationMySqlTest.Runtime(true)) {
            bind(runtime, 1, 99);
            String number;
            if (privateSource) {
                runtime.conversation("private-conversation", 1);
                assertThat(runtime.appService.convertConversationToTicket(1L, "private-conversation", "private-conversion",
                        new AppSupportService.ConvertToTicketRequest("other", "Private title", "OPEN", 0L)).getCode()).isZero();
                number = runtime.jdbc.queryForObject("SELECT ticket_no FROM nx_support_ticket WHERE user_id=1", String.class);
            } else {
                number = runtime.app(1, "permissions-create", "Permissions", "Customer body").getData().ticket().ticketNo();
            }
            runtime.asActor(101L, () -> runtime.bindings.transfer("permissions-transfer", transferRequest(runtime, 100L)));
            var formerDetail = runtime.asAdmin(() -> runtime.adminService.detail(number)).getData();
            var currentDetail = runtime.asActor(100L, () -> runtime.adminService.detail(number)).getData();
            assertThat(formerDetail.ticket().contentRestricted()).isEqualTo(privateSource);
            assertThat(currentDetail.ticket().contentRestricted()).isFalse();
            assertThat(formerDetail.ticket().assignedAdminId()).isEqualTo(100L);
            assertThat(currentDetail.ticket().assignedAdminId()).isEqualTo(100L);
            if (privateSource) {
                assertThat(formerDetail.ticket().title()).isEqualTo(ffdd.opsconsole.content.domain.SupportTicketView.RESTRICTED_TEXT);
                assertThat(currentDetail.ticket().title()).isEqualTo("Private title");
                assertThat(formerDetail.messages()).allSatisfy(message ->
                        assertThat(message.content()).isEqualTo(ffdd.opsconsole.content.domain.SupportTicketView.RESTRICTED_TEXT));
                assertThat(currentDetail.messages()).anySatisfy(message ->
                        assertThat(message.content()).isEqualTo("Original conversation"));
            }
            assertThatThrownBy(() -> runtime.asAdmin(() -> runtime.adminService.reply(number, "old-advisor-reply",
                    new SupportTicketReplyRequest("Forbidden reply", "test", "permission acceptance", "OPEN", 1L))))
                    .isInstanceOf(BizException.class).hasMessage("SUPPORT_CUSTOMER_NOT_FOUND");
            assertThat(runtime.asActor(100L, () -> runtime.adminService.reply(number, "new-advisor-reply",
                    new SupportTicketReplyRequest("New advisor reply", "test", "permission acceptance", "OPEN", 1L))).getCode()).isZero();
            assertThat(runtime.asAdmin(() -> runtime.adminService.updatePriority(number, "old-advisor-priority",
                    new SupportTicketPriorityRequest("HIGH", "test", "collaboration acceptance", "PENDING_USER", 2L))).getCode()).isZero();
            assertThat(runtime.asAdmin(() -> runtime.adminService.addInternalNote(number, "old-advisor-note",
                    new SupportTicketNoteRequest("Internal handover note", "test", "collaboration acceptance", "PENDING_USER", 3L))).getCode()).isZero();
            assertThat(runtime.asAdmin(() -> runtime.adminService.updateStatus(number, "old-advisor-status",
                    new SupportTicketStatusRequest("IN_PROGRESS", "test", "collaboration acceptance", "PENDING_USER", 4L))).getCode()).isZero();
            assertThatThrownBy(() -> runtime.asAdmin(() -> runtime.adminService.assign(number, "single-ticket-assign",
                    new SupportTicketAssigneeRequest(99L, "Forged", "test", "assignment acceptance", "IN_PROGRESS", 5L))))
                    .isInstanceOf(BizException.class).hasMessage("SUPPORT_TICKET_OWNER_MANAGED_BY_BINDING");
            assertOwner(runtime, 100L, "Advisor 100", 5L);
            assertThat(runtime.jdbc.queryForMap("SELECT sender_id,sender_type FROM nx_support_ticket_message WHERE content='New advisor reply'"))
                    .containsEntry("sender_id", 100L).containsEntry("sender_type", "agent");
            assertThat(runtime.jdbc.queryForMap("SELECT sender_id,sender_type,sender_name FROM nx_support_ticket_message WHERE content='Internal handover note'"))
                    .containsEntry("sender_id", null).containsEntry("sender_type", "internal").containsEntry("sender_name", "agent-99");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createAndTransferSerializeInBothOrdersEvenWithOldRepeatableReadSnapshots(boolean transferFirst) throws Exception {
        try (var runtime = new SupportTicketCreationMySqlTest.Runtime(true)) {
            bind(runtime, 1, 99);
            var request = transferRequest(runtime, 100L);
            CountDownLatch firstLocked = new CountDownLatch(1);
            CountDownLatch secondAttempted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicLong firstThread = new AtomicLong(-1);
            AtomicBoolean firstObserved = new AtomicBoolean();
            doAnswer(call -> {
                boolean first = Thread.currentThread().getId() == firstThread.get();
                if (!first) secondAttempted.countDown();
                Object result = call.callRealMethod();
                if (first && firstObserved.compareAndSet(false, true)) {
                    firstLocked.countDown();
                    assertThat(releaseFirst.await(10, TimeUnit.SECONDS)).isTrue();
                }
                return result;
            }).when(runtime.ownership).lockCustomer(anyLong());
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> {
                    firstThread.set(Thread.currentThread().getId());
                    return transferFirst
                            ? runtime.asActor(101L, () -> runtime.bindings.transfer("race-transfer", request)).getCode()
                            : runtime.app(1, "race-create", "Concurrent", "Concurrent body").getCode();
                });
                assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> runtime.repeatableRead(() -> {
                    assertThat(runtime.ticketCount(1)).as("pin snapshot before first operation commits").isZero();
                    return transferFirst
                            ? runtime.app(1, "race-create", "Concurrent", "Concurrent body").getCode()
                            : runtime.asActor(101L, () -> runtime.bindings.transfer("race-transfer", request)).getCode();
                }));
                assertThat(secondAttempted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(second.isDone()).as("second operation waits on customer mutex").isFalse();
                releaseFirst.countDown();
                assertThat(first.get(20, TimeUnit.SECONDS)).isZero();
                assertThat(second.get(20, TimeUnit.SECONDS)).isZero();
            } finally {
                releaseFirst.countDown();
                pool.shutdownNow();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
            assertOwner(runtime, 100L, "Advisor 100", transferFirst ? 0L : 1L);
            assertThat(runtime.bindingMapper.currentAgent(1L)).isEqualTo(100L);
            assertThat(runtime.ticketCount(1)).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void synchronizationSqlOrSynchronousEventFailureRollsBackAllStateAndSameKeyRetries(boolean eventFailure) throws Exception {
        try (var runtime = new SupportTicketCreationMySqlTest.Runtime(true)) {
            bind(runtime, 1, 99);
            runtime.app(1, "rollback-create", "Rollback", "Original content");
            runtime.jdbc.update("INSERT INTO nx_support_binding_pool(customer_id,reason,version,entered_at) VALUES(1,'MIGRATION_REVIEW',3,UTC_TIMESTAMP(6))");
            var request = transferRequest(runtime, 100L);
            var assignments = runtime.jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id");
            var headers = runtime.jdbc.queryForList("SELECT * FROM nx_support_ticket ORDER BY id");
            var poolRows = runtime.jdbc.queryForList("SELECT * FROM nx_support_binding_pool");
            var messages = allMessages(runtime);
            if (eventFailure) {
                doAnswer(call -> {
                    assertThat(runtime.bindingMapper.currentAgent(1L)).isEqualTo(100L);
                    assertOwner(runtime, 100L, "Advisor 100", 1L);
                    throw new IllegalStateException("TEST_SYNC_EVENT_FAILED");
                }).when(runtime.events).publishEvent(any(Object.class));
            } else {
                runtime.jdbc.execute("CREATE TRIGGER reject_owner_sync BEFORE UPDATE ON nx_support_ticket FOR EACH ROW "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TEST_OWNER_SYNC_FAILED'");
            }
            assertThatThrownBy(() -> runtime.asActor(101L, () -> runtime.bindings.transfer("rollback-transfer", request)))
                    .hasStackTraceContaining(eventFailure ? "TEST_SYNC_EVENT_FAILED" : "TEST_OWNER_SYNC_FAILED");
            assertThat(runtime.jdbc.queryForList("SELECT * FROM nx_support_agent_user_assignment ORDER BY id")).isEqualTo(assignments);
            assertThat(runtime.jdbc.queryForList("SELECT * FROM nx_support_ticket ORDER BY id")).isEqualTo(headers);
            assertThat(runtime.jdbc.queryForList("SELECT * FROM nx_support_binding_pool")).isEqualTo(poolRows);
            assertThat(allMessages(runtime)).isEqualTo(messages);
            assertThat(runtime.receipt("rollback-transfer")).isEqualTo("FAILED");
            if (eventFailure) doNothing().when(runtime.events).publishEvent(any(Object.class));
            else runtime.jdbc.execute("DROP TRIGGER reject_owner_sync");
            assertThat(runtime.asActor(101L, () -> runtime.bindings.transfer("rollback-transfer", request)).getCode()).isZero();
            assertOwner(runtime, 100L, "Advisor 100", 1L);
            assertThat(runtime.bindingMapper.currentAgent(1L)).isEqualTo(100L);
            assertThat(runtime.jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_binding_pool", Integer.class)).isZero();
            assertThat(runtime.receipt("rollback-transfer")).isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void registrationInheritanceSynchronizesPreexistingTicketInItsCallingTransaction() throws Exception {
        try (var runtime = new SupportTicketCreationMySqlTest.Runtime(true)) {
            bind(runtime, 1, 99);
            runtime.seed(2, "inherited-ticket", "CLOSED", true, false, 7200);
            runtime.repeatableRead(() -> { runtime.bindings.register(2L, 1L); return null; });
            assertThat(runtime.bindingMapper.current(2L).source()).isEqualTo("INHERITED");
            assertThat(runtime.jdbc.queryForMap("SELECT assigned_admin_id,assigned_admin_name,version FROM nx_support_ticket WHERE user_id=2"))
                    .containsEntry("assigned_admin_id", 99L).containsEntry("assigned_admin_name", "Advisor 99").containsEntry("version", 1L);
        }
    }

    @Test
    void ownerMigrationRepairsOldHeadersAndUnboundPoolOnlyOnce() throws Exception {
        try (var runtime = new SupportTicketCreationMySqlTest.Runtime(true)) {
            bind(runtime, 1, 99);
            runtime.seed(1, "old-bound", "CLOSED", true, false, 7200);
            runtime.seed(2, "old-unbound", "OPEN", false, false, 7300);
            runtime.seed(1, "old-active", "OPEN", false, false, 7400);
            runtime.seed(1, "old-closed", "CLOSED", false, false, 7500);
            runtime.seed(1, "old-correct", "OPEN", false, false, 7600);
            runtime.seed(1, "old-deleted", "CLOSED", false, true, 7700);
            runtime.jdbc.update("UPDATE nx_support_ticket SET assigned_admin_id=100,assigned_admin_name='Stale name',version=8");
            runtime.jdbc.update("UPDATE nx_support_ticket SET assigned_admin_id=99,assigned_admin_name='Advisor 99' WHERE ticket_no='old-correct'");
            runtime.jdbc.update("UPDATE nx_support_ticket SET created_at=DATE_ADD('2024-01-01 08:00:00',INTERVAL id DAY),"
                    + "updated_at=DATE_ADD('2024-01-02 09:00:00',INTERVAL id DAY),"
                    + "last_message_at=IF(ticket_no='old-unbound',NULL,DATE_ADD('2024-01-02 08:30:00',INTERVAL id DAY)),"
                    + "closed_at=IF(status='CLOSED',DATE_ADD('2024-01-02 08:45:00',INTERVAL id DAY),NULL),"
                    + "archived_at=IF(archived=1,DATE_ADD('2024-01-02 08:50:00',INTERVAL id DAY),NULL),"
                    + "last_message='Historical activity',message_count=1,user_unread_count=2,ops_unread_count=3");
            runtime.jdbc.update("UPDATE nx_support_ticket_message m JOIN nx_support_ticket t ON t.id=m.ticket_id "
                    + "SET m.created_at=t.created_at,m.updated_at=t.updated_at");
            var history = ticketFieldsExceptOwner(runtime);
            var activityOrder = runtime.jdbc.queryForList("SELECT ticket_no FROM nx_support_ticket ORDER BY updated_at DESC,id DESC", String.class);
            var messages = allMessages(runtime);
            var migration = new FileSystemResource("scripts/migrations/20261003_support_ticket_binding_owner.sql");
            try (Connection connection = runtime.jdbc.getDataSource().getConnection()) {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET SESSION time_zone='+00:00'");
                    try (var result = statement.executeQuery("SELECT @@session.time_zone")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString(1)).isEqualTo("+00:00");
                    }
                }
                ScriptUtils.executeSqlScript(connection, migration);
                assertThat(ticketFieldsExceptOwner(runtime)).as("technical backfill preserves all activity timestamps and ticket content").isEqualTo(history);
                assertThat(runtime.jdbc.queryForList("SELECT ticket_no FROM nx_support_ticket ORDER BY updated_at DESC,id DESC", String.class))
                        .isEqualTo(activityOrder);
                for (String number : List.of("old-bound", "old-active", "old-closed", "old-correct")) {
                    assertThat(runtime.jdbc.queryForMap("SELECT assigned_admin_id,assigned_admin_name,version FROM nx_support_ticket WHERE ticket_no=?", number))
                            .containsEntry("assigned_admin_id", 99L).containsEntry("assigned_admin_name", "Advisor 99")
                            .containsEntry("version", number.equals("old-correct") ? 8L : 9L);
                }
                assertThat(runtime.jdbc.queryForMap("SELECT assigned_admin_id,assigned_admin_name,version FROM nx_support_ticket WHERE user_id=2"))
                        .containsEntry("assigned_admin_id", null).containsEntry("assigned_admin_name", "Unassigned").containsEntry("version", 9L);
                assertThat(runtime.jdbc.queryForMap("SELECT assigned_admin_id,assigned_admin_name,version FROM nx_support_ticket WHERE ticket_no='old-deleted'"))
                        .containsEntry("assigned_admin_id", 100L).containsEntry("assigned_admin_name", "Stale name").containsEntry("version", 8L);
                assertThat(runtime.jdbc.queryForObject("SELECT reason FROM nx_support_binding_pool WHERE customer_id=2", String.class))
                        .isEqualTo("MIGRATION_REVIEW");
                runtime.jdbc.update("UPDATE nx_support_binding_pool SET reason='DEPTH_LIMIT',version=4 WHERE customer_id=2");
                var headers = runtime.jdbc.queryForList("SELECT * FROM nx_support_ticket ORDER BY id");
                ScriptUtils.executeSqlScript(connection, migration);
                assertThat(runtime.jdbc.queryForList("SELECT * FROM nx_support_ticket ORDER BY id")).isEqualTo(headers);
            }
            assertThat(allMessages(runtime)).isEqualTo(messages);
            assertThat(runtime.jdbc.queryForMap("SELECT reason,version FROM nx_support_binding_pool WHERE customer_id=2"))
                    .containsEntry("reason", "DEPTH_LIMIT").containsEntry("version", 4L);
        }
    }

    private static List<Map<String, Object>> ticketFieldsExceptOwner(SupportTicketCreationMySqlTest.Runtime runtime) {
        var headers = runtime.jdbc.queryForList("SELECT * FROM nx_support_ticket ORDER BY id");
        headers.forEach(header -> List.of("assigned_admin_id", "assigned_admin_name", "version").forEach(header::remove));
        return headers;
    }

    private static void bind(SupportTicketCreationMySqlTest.Runtime runtime, long customer, long agent) {
        runtime.jdbc.update("INSERT INTO nx_support_agent_user_assignment(agent_admin_id,user_id,status,starts_at,source,segment_root_id,depth,version) "
                + "VALUES(?,?,'ACTIVE',UTC_TIMESTAMP(6),'MANUAL',?,0,1)", agent, customer, customer);
    }

    private static SupportBindingRequest transferRequest(SupportTicketCreationMySqlTest.Runtime runtime, long target) {
        var assignment = runtime.bindingMapper.current(1L);
        return new SupportBindingRequest(target, List.of(new SupportBindingRequest.Customer(1L,
                assignment == null ? null : assignment.id(), assignment == null ? runtime.bindingMapper.poolVersion(1L) : assignment.version())),
                "owner transfer acceptance");
    }

    private static ConversationTicketRequest conversion(Long requestedOwner) {
        return new ConversationTicketRequest("other", "NORMAL", "Converted", requestedOwner, "Forged display name",
                "OPEN", 0L, "owner conversion acceptance", "forged-operator");
    }

    private static SupportTicketQueryRequest query(String scope, Long advisor) {
        return new SupportTicketQueryRequest(scope, null, null, null, advisor, 1L, null, 1L, 50L);
    }

    private static void assertOwner(SupportTicketCreationMySqlTest.Runtime runtime, Long owner, String name, long version) {
        var headers = runtime.jdbc.queryForList("SELECT assigned_admin_id,assigned_admin_name,version FROM nx_support_ticket WHERE user_id=1 AND is_deleted=0");
        assertThat(headers).isNotEmpty().allSatisfy(header -> assertThat(header)
                .containsEntry("assigned_admin_id", owner).containsEntry("assigned_admin_name", name).containsEntry("version", version));
    }

    private static List<Map<String, Object>> allMessages(SupportTicketCreationMySqlTest.Runtime runtime) {
        return runtime.jdbc.queryForList("SELECT * FROM nx_support_ticket_message ORDER BY id");
    }
}
