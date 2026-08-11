package ffdd.opsconsole.content.infrastructure;

import ffdd.opsconsole.content.domain.NovaSocialRuntimeRepository;
import ffdd.opsconsole.content.mapper.NovaSocialRuntimeMapper;
import ffdd.opsconsole.content.domain.NotificationEventFact;
import ffdd.opsconsole.content.domain.NovaBusinessEventFact;
import ffdd.opsconsole.content.domain.NovaBusinessFanoutProgress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisNovaSocialRuntimeRepository implements NovaSocialRuntimeRepository {
    private final NovaSocialRuntimeMapper mapper;
    private final AtomicBoolean runtimeTablesEnsured = new AtomicBoolean();

    @Override
    public synchronized void ensureRuntimeTables() {
        if (!runtimeTablesEnsured.get()) {
            mapper.createRuntimeSlotTable();
            mapper.createBusinessEventReceiptTable();
            if (mapper.fanoutCursorColumnCount() == 0) {
                mapper.addFanoutCursorColumn();
            }
            ensureNovaEventSchema("nova.push_sent", List.of(
                    property("notification_id", "id"),
                    property("channel", "enum"),
                    property("priority", "enum")));
            ensureNovaEventSchema("nova.push_clicked", List.of(
                    property("notification_id", "id"),
                    property("channel", "enum"),
                    property("action", "enum"),
                    property("route", "string")));
            runtimeTablesEnsured.set(true);
        }
    }

    @Override
    public boolean claimSlot(String slotKey, String leaseOwner, LocalDateTime leaseUntil, LocalDateTime now) {
        return mapper.insertSlotClaim(slotKey, leaseOwner, leaseUntil, now) == 1
                || mapper.takeoverExpiredSlot(slotKey, leaseOwner, leaseUntil, now) == 1;
    }

    @Override
    public boolean completeSlot(String slotKey, String leaseOwner, LocalDateTime now) {
        return mapper.completeSlot(slotKey, leaseOwner, now) == 1;
    }

    @Override
    public Optional<LocalDateTime> latestNotificationAt() {
        return Optional.ofNullable(mapper.latestNotificationAt());
    }

    @Override
    public Optional<LocalDateTime> latestNotificationAt(String notificationType) {
        return Optional.ofNullable(mapper.latestNotificationAtByType(notificationType));
    }

    @Override
    public List<NovaBusinessEventFact> pendingBusinessFacts(
            String channel, List<String> eventNames, int limit) {
        return mapper.pendingBusinessFacts(channel, eventNames, limit);
    }

    @Override
    public boolean claimBusinessFact(
            String channel, String sourceEventId, String eventName, LocalDateTime now) {
        return mapper.claimBusinessFact(channel, sourceEventId, eventName, now) == 1;
    }

    @Override
    public Optional<NovaBusinessFanoutProgress> businessFanoutProgress(String channel, String sourceEventId) {
        return Optional.ofNullable(mapper.businessFanoutProgress(channel, sourceEventId));
    }

    @Override
    public Optional<Long> fanoutBatchUpperUserId(long afterUserId, int limit) {
        return Optional.ofNullable(mapper.fanoutBatchUpperUserId(afterUserId, limit));
    }

    @Override
    public boolean advanceBusinessFanout(String channel, String sourceEventId, long expectedCursorUserId,
                                         long nextCursorUserId, int delivered, LocalDateTime now) {
        return mapper.advanceBusinessFanout(channel, sourceEventId, expectedCursorUserId,
                nextCursorUserId, delivered, now) == 1;
    }

    @Override
    public void completeBusinessFact(
            String channel,
            String sourceEventId,
            String status,
            String reason,
            int notificationCount,
            LocalDateTime now) {
        if (mapper.completeBusinessFact(
                channel, sourceEventId, status, reason, notificationCount, now) != 1) {
            throw new IllegalStateException("NOVA_BUSINESS_EVENT_RECEIPT_STATE_CONFLICT");
        }
    }

    @Override
    public int enqueueNotifications(long eventId, String bizNo, String titleZh, String bodyZh,
                                    String titleVi, String bodyVi, String titleEn, String bodyEn,
                                    String ctaHref, LocalDateTime cooldownSince, LocalDateTime now) {
        return mapper.enqueueNotifications(eventId, bizNo, titleZh, bodyZh, titleVi, bodyVi,
                titleEn, bodyEn, ctaHref, cooldownSince, now);
    }

    @Override
    public int enqueueBusinessNotifications(
            String channel,
            String notificationType,
            String sourceEventId,
            Long userId,
            String bizNo,
            String titleZh,
            String bodyZh,
            String titleVi,
            String bodyVi,
            String titleEn,
            String bodyEn,
            String ctaHref,
            LocalDateTime cooldownSince,
            LocalDateTime now) {
        return mapper.enqueueBusinessNotifications(
                channel, notificationType, sourceEventId, userId, bizNo,
                titleZh, bodyZh, titleVi, bodyVi, titleEn, bodyEn,
                ctaHref, cooldownSince, now);
    }

    @Override
    public int enqueueBusinessNotificationBatch(
            String channel, String notificationType, String bizNo, long afterUserId, long upperUserId,
            String titleZh, String bodyZh, String titleVi, String bodyVi,
            String titleEn, String bodyEn, String ctaHref,
            LocalDateTime cooldownSince, LocalDateTime now) {
        return mapper.enqueueBusinessNotificationBatch(channel, notificationType, bizNo,
                afterUserId, upperUserId, titleZh, bodyZh, titleVi, bodyVi,
                titleEn, bodyEn, ctaHref, cooldownSince, now);
    }

    @Override
    public int markNotificationsDelivered(String bizNo, LocalDateTime now) {
        return mapper.markNotificationsDelivered(bizNo, now);
    }

    @Override
    public List<NotificationEventFact> notificationFacts(String bizNo, String currentPhase, LocalDateTime now) {
        return mapper.notificationFacts(bizNo, currentPhase, now);
    }

    @Override
    public int markDispatchedIfStillActive(long eventId, LocalDateTime now) {
        return mapper.markDispatchedIfStillActive(eventId, now);
    }

    private void ensureNovaEventSchema(String eventName, List<SchemaProperty> properties) {
        mapper.insertNovaEventSchema(eventName);
        properties.forEach(property -> mapper.insertNovaEventProperty(
                eventName, property.name(), property.type(), true));
    }

    private SchemaProperty property(String name, String type) {
        return new SchemaProperty(name, type);
    }

    private record SchemaProperty(String name, String type) {
    }
}
