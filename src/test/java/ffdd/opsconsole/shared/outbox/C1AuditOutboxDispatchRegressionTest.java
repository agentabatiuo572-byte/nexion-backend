package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import ffdd.opsconsole.developer.application.DeveloperWebhookCanonicalOutboxDispatchScheduler;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;

/** Isolates dispatch reachability; it does not claim a production C1 consumer exists yet. */
class C1AuditOutboxDispatchRegressionTest {
    @ParameterizedTest
    @ValueSource(strings = {"admin_user_list_exported", "\u00c1DMIN_USER_LIST_EXPORTED", "ADMIN_USER_LIST_EXPORTED "})
    void collationAliasesSelectedAsC1AreHandedToTheDispatcherWithTheExactC1Type(String storedType) {
        EventOutboxService service = mock(EventOutboxService.class);
        EventOutboxDispatcher dispatcher = mock(EventOutboxDispatcher.class);
        EventOutboxMessage row = new EventOutboxMessage();
        row.setId(1L);
        row.setEventId("alias-event");
        row.setEventType(storedType);
        when(service.listPendingByEventType("ADMIN_USER_LIST_EXPORTED", 100)).thenReturn(List.of(row));

        new EventOutboxDispatchScheduler(service, dispatcher).dispatchPending();

        // The exact C1 event type is the only type the C1 consumer accepts; the
        // dispatcher owns the envelope validation and terminal-state write.
        verify(dispatcher).dispatch(row, "ADMIN_USER_LIST_EXPORTED");
    }

    @ParameterizedTest
    @CsvSource({
            "ADMIN_USER_PROFILE_VIEWED, admin.user_profile_viewed, USER_PROFILE",
            "ADMIN_USER_LIST_EXPORTED, admin.user_list_exported, USER_PROFILE_EXPORT",
            // Existing C3 route is the positive control for this reachability fixture.
            "admin.bill_adjusted, admin.bill_adjusted, WALLET_LEDGER"
    })
    void registeredAuditFactMustReachAvailableConsumerAndLeavePending(
            String eventType, String eventName, String aggregateType) {
        EventOutboxMessage row = new EventOutboxMessage();
        row.setId(1L);
        row.setEventId("c1-audit-regression");
        row.setEventType(eventType);
        row.setEventName(eventName);
        row.setAggregateType(aggregateType);
        row.setAggregateId("fixture");
        row.setStatus("PENDING");
        row.setRetryCount(0);
        row.setCreatedAt(LocalDateTime.of(2026, 9, 1, 0, 0));
        row.setNextRetryAt(null); // Already eligible, with no backoff barrier.
        row.setAnalyticsEvent(true);

        EventOutboxMapper mapper = mock(EventOutboxMapper.class);
        when(mapper.findLifecycleState(eventName)).thenReturn("full");
        when(mapper.findDispatchState(anyLong(), anyInt(), anyLong()))
                .thenReturn(new EventOutboxMapper.DispatchState(row.getId(), null));
        when(mapper.listPendingByEventType(anyString(), anyInt())).thenAnswer(call ->
                "PENDING".equals(row.getStatus()) && eventType.equals(call.getArgument(0))
                        ? List.of(row) : List.of());
        when(mapper.listPendingByCanonicalType(anyString(), anyLong(), anyInt())).thenAnswer(call -> {
            String canonical = call.getArgument(0);
            long cursor = call.getArgument(1);
            return "PENDING".equals(row.getStatus()) && row.getId() > cursor
                    && (eventType.equalsIgnoreCase(canonical) || eventName.equalsIgnoreCase(canonical))
                    ? List.of(row) : List.of();
        });
        AtomicInteger deliveries = new AtomicInteger();
        ApplicationEventPublisher availableConsumer = event -> {
            assertThat(event).isSameAs(row);
            deliveries.incrementAndGet();
        };
        when(mapper.markPublished(eq(row.getId()), eq("PUBLISHED"), anyLong())).thenAnswer(call -> {
            assertThat(deliveries.get()).as("no terminal success before consumer delivery").isPositive();
            row.setStatus("PUBLISHED");
            return 1;
        });
        EventOutboxService service = new EventOutboxService(mapper, new ObjectMapper(),
                new OutboxProperties(), mock(A4RuntimePolicyService.class));
        EventOutboxDispatcher dispatcher = dispatcher(mapper, service, availableConsumer);
        EventOutboxDispatchScheduler shared = new EventOutboxDispatchScheduler(service, dispatcher);
        DeveloperWebhookCanonicalOutboxDispatchScheduler webhooks =
                new DeveloperWebhookCanonicalOutboxDispatchScheduler(service, dispatcher);

        // Both actual schedulers run repeatedly; the fixture honors their family/cursor selection.
        for (int tick = 0; tick < 10; tick++) {
            shared.dispatchPending();
            webhooks.dispatchPending();
        }

        assertThat(row.getStatus())
                .as("%s remains PENDING with deliveries=%s after both dispatchers ran", eventType, deliveries.get())
                .isEqualTo("PUBLISHED");
        assertThat(deliveries.get()).isEqualTo(1);
    }

    private EventOutboxDispatcher dispatcher(EventOutboxMapper mapper, EventOutboxService service,
                                            ApplicationEventPublisher publisher) {
        HikariDataSource dataSource = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet lockResult = mock(ResultSet.class);
        try {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(lockResult);
            when(lockResult.next()).thenReturn(true);
            when(lockResult.getLong(1)).thenReturn(42L, 1L); // ACQUIRE then RELEASE
            when(lockResult.wasNull()).thenReturn(false);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
        EventConsumerDeliveryProperties deliveryProperties = new EventConsumerDeliveryProperties();
        return new EventOutboxDispatcher(dataSource, mapper, service, deliveryProperties, publisher);
    }
}
