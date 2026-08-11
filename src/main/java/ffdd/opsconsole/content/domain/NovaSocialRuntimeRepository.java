package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NovaSocialRuntimeRepository {
    void ensureRuntimeTables();

    boolean claimSlot(String slotKey, String leaseOwner, LocalDateTime leaseUntil, LocalDateTime now);

    boolean completeSlot(String slotKey, String leaseOwner, LocalDateTime now);

    Optional<LocalDateTime> latestNotificationAt();

    Optional<LocalDateTime> latestNotificationAt(String notificationType);

    List<NovaBusinessEventFact> pendingBusinessFacts(
            String channel, List<String> eventNames, int limit);

    boolean claimBusinessFact(
            String channel, String sourceEventId, String eventName, LocalDateTime now);

    Optional<NovaBusinessFanoutProgress> businessFanoutProgress(String channel, String sourceEventId);

    Optional<Long> fanoutBatchUpperUserId(long afterUserId, int limit);

    boolean advanceBusinessFanout(
            String channel, String sourceEventId, long expectedCursorUserId,
            long nextCursorUserId, int delivered, LocalDateTime now);

    void completeBusinessFact(
            String channel,
            String sourceEventId,
            String status,
            String reason,
            int notificationCount,
            LocalDateTime now);

    int enqueueNotifications(
            long eventId,
            String bizNo,
            String titleZh,
            String bodyZh,
            String titleVi,
            String bodyVi,
            String titleEn,
            String bodyEn,
            String ctaHref,
            LocalDateTime cooldownSince,
            LocalDateTime now);

    int enqueueBusinessNotifications(
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
            LocalDateTime now);

    int enqueueBusinessNotificationBatch(
            String channel, String notificationType, String bizNo,
            long afterUserId, long upperUserId,
            String titleZh, String bodyZh, String titleVi, String bodyVi,
            String titleEn, String bodyEn, String ctaHref,
            LocalDateTime cooldownSince, LocalDateTime now);

    int markNotificationsDelivered(String bizNo, LocalDateTime now);

    List<NotificationEventFact> notificationFacts(String bizNo, String currentPhase, LocalDateTime now);

    int markDispatchedIfStillActive(long eventId, LocalDateTime now);
}
