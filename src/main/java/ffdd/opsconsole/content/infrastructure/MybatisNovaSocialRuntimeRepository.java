package ffdd.opsconsole.content.infrastructure;

import ffdd.opsconsole.content.domain.NovaSocialRuntimeRepository;
import ffdd.opsconsole.content.mapper.NovaSocialRuntimeMapper;
import java.time.LocalDateTime;
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
    public int enqueueNotifications(long eventId, String bizNo, String titleZh, String bodyZh,
                                    String titleVi, String bodyVi, String titleEn, String bodyEn,
                                    String ctaHref, LocalDateTime cooldownSince, LocalDateTime now) {
        return mapper.enqueueNotifications(eventId, bizNo, titleZh, bodyZh, titleVi, bodyVi,
                titleEn, bodyEn, ctaHref, cooldownSince, now);
    }

    @Override
    public int markDispatchedIfStillActive(long eventId, LocalDateTime now) {
        return mapper.markDispatchedIfStillActive(eventId, now);
    }
}
