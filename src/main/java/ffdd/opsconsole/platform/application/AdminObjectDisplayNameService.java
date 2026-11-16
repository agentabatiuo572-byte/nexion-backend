package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.auth.mapper.AdminMapper;
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
    public Map<Long, String> byIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        List<Long> normalized = ids.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
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
}
