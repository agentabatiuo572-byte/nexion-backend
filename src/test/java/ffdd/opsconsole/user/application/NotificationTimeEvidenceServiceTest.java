package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.mapper.NotificationTimeEvidenceMapper;
import ffdd.opsconsole.user.mapper.NotificationTimeEvidenceMapper.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationTimeEvidenceServiceTest {
    private final UserOpsRepository users = mock(UserOpsRepository.class);
    private final NotificationTimeEvidenceMapper mapper = mock(NotificationTimeEvidenceMapper.class);
    private final AdminOperatorRoleResolver roles = mock(AdminOperatorRoleResolver.class);
    private final ObjectMapper json = new ObjectMapper();
    private final NotificationTimeEvidenceService service = new NotificationTimeEvidenceService(users, mapper, roles, json,
            Clock.fixed(Instant.parse("2026-09-18T04:30:00Z"), DateTimeFormatConfig.BUSINESS_ZONE));
    private static final String SOURCE = "a".repeat(32);
    private static final String BIZ = "NOVA-welcome-" + SOURCE;
    private static final long TS = Instant.parse("2026-09-18T03:38:00Z").toEpochMilli();

    private EventRow event(String id, String aggregate, String aggregateId, String name, long ts, Map<String, Object> fields) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>(fields);
        payload.put("event_id", id); payload.put("event_name", name); payload.put("ts", ts);
        payload.put("user_id", 7L); payload.put("is_server_authoritative", true);
        return new EventRow(id, aggregate, aggregateId, name, true, json.writeValueAsString(payload),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), DateTimeFormatConfig.BUSINESS_ZONE));
    }
    private List<EventRow> deliveryFacts() throws Exception {
        return List.of(event("b".repeat(32), "NOVA_NOTIFICATION", "99", "nova.push_sent", TS, Map.of("notification_id", 99, "channel", "welcome")),
                event("c".repeat(32), "NOTIFICATION", "99", "notification.delivered", TS + 100, Map.of("notification_id", 99, "kind", "nova_welcome", "campaign_id", BIZ)));
    }
    private void setup() throws Exception {
        when(roles.resolveCode()).thenReturn("SUPER_ADMIN");
        when(users.findUserIdByLookupKey("U7")).thenReturn(Optional.of(7L));
        when(mapper.notification(7L, 99L)).thenReturn(new NotificationRow(99L, 7L, BIZ, "NOVA_WELCOME", "DELIVERED", LocalDateTime.of(2026, 9, 18, 3, 38)));
        when(mapper.deliveries("99")).thenReturn(deliveryFacts());
        when(mapper.registration(SOURCE)).thenReturn(List.of(event(SOURCE, "USER_REGISTRATION", "7", "auth.register_completed", TS - 1000, Map.of())));
        when(mapper.receipts(SOURCE)).thenReturn(List.of(new ReceiptRow(SOURCE, "auth.register_completed", "DELIVERED", 1)));
    }
    @Test void matchesIndependentEpochWithoutChangingStoredTimeOrExposingPayload() throws Exception {
        setup(); var result = service.preview("U7", 99L);
        assertThat(result.getData().status()).isEqualTo("MATCHED");
        assertThat(result.getData().storedCreatedAt()).isEqualTo(LocalDateTime.of(2026, 9, 18, 3, 38));
        assertThat(result.getData().deliveryFactTime()).isEqualTo(LocalDateTime.of(2026, 9, 18, 11, 38));
        assertThat(result.getData().facts()).hasSize(3);
        assertThat(Arrays.stream(result.getData().getClass().getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("payload", "userId", "phone", "title", "body", "bizNo");
    }
    @ParameterizedTest @ValueSource(strings={"SUPPORT", "FINANCE", "CUSTOM", "null"})
    void projectionDeniedBeforeAnyUserOrEvidenceRead(String role) {
        when(roles.resolveCode()).thenReturn(role);
        assertThat(service.preview("U7",99L).getCode()).isEqualTo(403);
        verifyNoInteractions(users,mapper);
    }
    @Test void exactUserNotificationMismatchIsNotFoundWithoutEvidenceLookup() throws Exception {
        setup(); when(mapper.notification(7L,99L)).thenReturn(null);
        assertThat(service.preview("U7",99L).getCode()).isEqualTo(404);
        verify(mapper, never()).deliveries(anyString());
    }
    @Test void missingFactsDoNotInventRecovery() throws Exception {
        setup(); when(mapper.deliveries("99")).thenReturn(List.of());
        assertThat(service.preview("U7",99L).getData().status()).isEqualTo("NO_EVIDENCE");
    }
    @Test void readWelcomeStillRequiresTheSameIndependentDeliveryEvidence() throws Exception {
        setup();
        when(mapper.notification(7L,99L)).thenReturn(new NotificationRow(99L,7L,BIZ,"NOVA_WELCOME","READ",LocalDateTime.of(2026,9,18,3,38)));
        var view = service.preview("U7",99L).getData();
        assertThat(view.status()).isEqualTo("MATCHED");
        assertThat(view.facts()).hasSize(3);
        when(mapper.receipts(SOURCE)).thenReturn(List.of(new ReceiptRow(SOURCE,"auth.register_completed","READ",1)));
        view = service.preview("U7",99L).getData();
        assertThat(view.status()).isEqualTo("CONFLICT");
        assertThat(view.deliveryFactTime()).isNull();
        assertThat(view.facts()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"QUEUED", "PENDING", "FAILED", "CANCELLED", "read", "READ "})
    void undeliveredOrNonCanonicalStatusCannotReadEvidence(String status) throws Exception {
        setup();
        when(mapper.notification(7L,99L)).thenReturn(new NotificationRow(99L,7L,BIZ,"NOVA_WELCOME",status,LocalDateTime.of(2026,9,18,3,38)));
        assertThat(service.preview("U7",99L).getData().status()).isEqualTo("UNSUPPORTED");
        verify(mapper,never()).deliveries(anyString());
        verify(mapper,never()).registration(anyString());
        verify(mapper,never()).receipts(anyString());
    }
    @Test void duplicateFactsAreNotSilentlyChosen() throws Exception {
        setup(); List<EventRow> events = new ArrayList<>(deliveryFacts()); events.add(events.get(0));
        when(mapper.deliveries("99")).thenReturn(events);
        assertThat(service.preview("U7",99L).getData().status()).isEqualTo("CONFLICT");
    }
    @ParameterizedTest @ValueSource(strings={"cross-user", "wrong-campaign", "fractional-time", "future-time", "wrong-db-time", "malformed-json"})
    void conflictingEvidenceNeverProducesProposedTime(String kind) throws Exception {
        setup(); List<EventRow> events = new ArrayList<>(deliveryFacts()); EventRow old = events.get(1);
        var payload = json.readTree(old.payload());
        var object = (com.fasterxml.jackson.databind.node.ObjectNode)payload;
        switch(kind) {
            case "cross-user" -> object.put("user_id",8);
            case "wrong-campaign" -> object.put("campaign_id","other");
            case "fractional-time" -> object.put("ts",TS+0.5);
            case "future-time" -> object.put("ts",TS+86400000L);
        }
        events.set(1,new EventRow(old.eventId(),old.aggregateType(),old.aggregateId(),old.eventName(),true,
                kind.equals("malformed-json") ? "{" : json.writeValueAsString(payload),
                kind.equals("wrong-db-time") ? old.eventTs().minusHours(8) : old.eventTs()));
        when(mapper.deliveries("99")).thenReturn(events);
        var view = service.preview("U7",99L).getData();
        assertThat(view.status()).isEqualTo("CONFLICT"); assertThat(view.deliveryFactTime()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"sensitive@example.invalid", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"})
    void malformedOrReusedEventIdCannotBeExposedAsEvidence(String eventId) throws Exception {
        setup(); var events = new ArrayList<>(deliveryFacts()); var old = events.get(1);
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(old.payload()); payload.put("event_id",eventId);
        events.set(1,new EventRow(eventId,old.aggregateType(),old.aggregateId(),old.eventName(),true,json.writeValueAsString(payload),old.eventTs()));
        when(mapper.deliveries("99")).thenReturn(events);
        var view=service.preview("U7",99L).getData();
        assertThat(view.status()).isEqualTo("CONFLICT"); assertThat(view.facts()).isEmpty();
    }
    @Test void mapperSurfaceAndTransactionAreReadOnly() throws Exception {
        assertThat(NotificationTimeEvidenceService.class.getMethod("preview",String.class,Long.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly()).isTrue();
        for(var method:NotificationTimeEvidenceMapper.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(org.apache.ibatis.annotations.Select.class)).isNotNull();
        }
        String query = String.join("", NotificationTimeEvidenceMapper.class.getMethod("notification",long.class,long.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        assertThat(query).contains("id=#{notificationId}","user_id=#{userId}","is_deleted=0");
    }
}

