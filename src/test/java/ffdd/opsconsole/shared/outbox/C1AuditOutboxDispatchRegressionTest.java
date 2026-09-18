package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.developer.application.DeveloperWebhookCanonicalOutboxDispatchScheduler;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
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
    void collationAliasesSelectedAsC1CannotBePublishedAsUnhandledEvents(String storedType) {
        EventOutboxService service = mock(EventOutboxService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        EventOutboxMessage row = new EventOutboxMessage();
        row.setEventId("alias-event"); row.setEventType(storedType);
        when(service.listPendingByEventType("ADMIN_USER_LIST_EXPORTED", 100)).thenReturn(List.of(row));
        new EventOutboxDispatchScheduler(service, publisher).dispatchPending();
        verify(service).markFailed("alias-event", "C1_AUDIT_ENVELOPE_INVALID");
        verifyNoInteractions(publisher);
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
        when(mapper.markPublished(eq(row.getEventId()), eq("PUBLISHED"))).thenAnswer(call -> {
            assertThat(deliveries.get()).as("no terminal success before consumer delivery").isPositive();
            row.setStatus("PUBLISHED");
            return 1;
        });
        EventOutboxService service = new EventOutboxService(mapper, new ObjectMapper(),
                new OutboxProperties(), mock(A4RuntimePolicyService.class));
        EventOutboxDispatchScheduler shared = new EventOutboxDispatchScheduler(service, availableConsumer);
        DeveloperWebhookCanonicalOutboxDispatchScheduler webhooks =
                new DeveloperWebhookCanonicalOutboxDispatchScheduler(service, availableConsumer);

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
}
