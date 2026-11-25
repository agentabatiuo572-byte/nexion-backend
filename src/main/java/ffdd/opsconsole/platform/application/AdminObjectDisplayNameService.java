package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Optional admin-object display names for A2 tickets. Lookup failures are
 * deliberately isolated from the ticket read/decision transaction.
 */
@Service
public class AdminObjectDisplayNameService {
    private final AdminMapper adminMapper;

    public AdminObjectDisplayNameService(AdminMapper adminMapper) {
        this.adminMapper = adminMapper;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, String> byUsernames(Collection<String> usernames) {
        if (usernames == null || usernames.isEmpty()) return Map.of();
        List<String> normalized = usernames.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (normalized.isEmpty()) return Map.of();
        try {
            Map<String, String> names = new LinkedHashMap<>();
            for (AdminMapper.AdminDisplayName row : adminMapper.findDisplayNamesByUsernames(normalized)) {
                if (row != null && StringUtils.hasText(row.username()) && StringUtils.hasText(row.displayName())) {
                    names.put(row.username(), row.displayName().trim());
                }
            }
            return names;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<Long, String> byIds(Collection<Long> ids) {
        List<Long> normalized = positiveIds(ids);
        if (normalized.isEmpty()) return Map.of();
        try {
            Map<Long, String> names = new LinkedHashMap<>();
            for (AdminMapper.AdminIdDisplayName row : adminMapper.findDisplayNamesByIds(normalized)) {
                if (row != null && row.id() != null && StringUtils.hasText(row.displayName())) {
                    names.put(row.id(), row.displayName().trim());
                }
            }
            return names;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<Long, String> actorNamesByIds(Collection<Long> ids) {
        List<Long> normalized = positiveIds(ids);
        if (normalized.isEmpty()) return Map.of();
        try {
            Map<Long, String> names = new LinkedHashMap<>();
            for (AdminMapper.AdminIdIdentity row : adminMapper.findIdentitiesByIds(normalized)) {
                String username = row.username() == null ? null : row.username().trim();
                String displayName = StringUtils.hasText(row.displayName()) ? row.displayName().trim() : username;
                if (StringUtils.hasText(displayName)) {
                    names.put(row.id(), displayName.equals(username) ? AdminActorResolver.maskPhone(displayName) : displayName);
                }
            }
            return names;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static List<Long> positiveIds(Collection<Long> ids) {
        return ids == null ? List.of() : ids.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }
}
