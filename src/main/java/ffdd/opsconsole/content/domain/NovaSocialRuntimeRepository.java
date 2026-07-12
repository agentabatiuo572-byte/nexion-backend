package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;
import java.util.Optional;

public interface NovaSocialRuntimeRepository {
    void ensureRuntimeTables();

    boolean claimSlot(String slotKey, String leaseOwner, LocalDateTime leaseUntil, LocalDateTime now);

    boolean completeSlot(String slotKey, String leaseOwner, LocalDateTime now);

    Optional<LocalDateTime> latestNotificationAt();

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

    int markDispatchedIfStillActive(long eventId, LocalDateTime now);
}
