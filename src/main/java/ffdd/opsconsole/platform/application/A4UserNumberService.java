package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.content.application.SupportOwnershipService;
import ffdd.opsconsole.platform.mapper.A4OutboxDiagnosticsMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Optional, authorized projection of the canonical user number for A4 operational facts.
 * The underlying payload identity and read scope are never inferred from an administrator id.
 */
@Service
@RequiredArgsConstructor
public class A4UserNumberService {
    private final A4OutboxDiagnosticsMapper mapper;
    private final SupportOwnershipService ownership;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String userNo(Long userId) {
        if (userId == null || userId <= 0) return null;
        if (!SupportOwnershipService.hasAuthority("user_c1hub_read")) return null;
        Map<Long, String> visible = userNos(List.of(userId));
        return visible.get(userId);
    }

    /**
     * Batch form used by the read-only backlog. It deliberately suspends the caller's
     * read-only transaction so a support-scope failure cannot mark that transaction
     * rollback-only; a lookup failure yields no identifiers, never a user number.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<Long, String> userNos(Collection<Long> userIds) {
        if (!SupportOwnershipService.hasAuthority("user_c1hub_read")) return Map.of();
        List<Long> ids = userIds == null ? List.of() : userIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        try {
            Map<Long, String> numbers = new HashMap<>();
            for (A4OutboxDiagnosticsMapper.UserNoRow row : mapper.userNos(ids)) {
                if (row != null && row.userId() != null && StringUtils.hasText(row.userNo())) {
                    numbers.put(row.userId(), row.userNo().trim());
                }
            }
            if (numbers.isEmpty()) return Map.of();
            if (ownership.currentSupportReader()) {
                Set<Long> readable = ownership.readableCustomerIds(ids);
                numbers.keySet().removeIf(id -> !readable.contains(id));
            }
            return numbers;
        } catch (RuntimeException unavailable) {
            // This is an optional decoration on existing operational facts. A permission/scope
            // or lookup failure must not turn an already valid read or redrive result into an error.
            return Map.of();
        }
    }
}
