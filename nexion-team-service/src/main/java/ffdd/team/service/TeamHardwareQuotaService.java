package ffdd.team.service;

import ffdd.common.exception.BizException;
import ffdd.team.dto.TeamHardwareQuotaUpdateRequest;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class TeamHardwareQuotaService {
    private final JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> listForUser(Long userId) {
        Map<String, Object> metrics = userMetrics(userId);
        int activeDirects = asInt(metrics.get("activeDirects"));
        BigDecimal teamVolume = amount(metrics.get("teamVolumeUsd"));
        return quotaRows(true, userId).stream()
                .map(row -> withEligibility(row, activeDirects, teamVolume))
                .toList();
    }

    public List<Map<String, Object>> listForAdmin() {
        return quotaRows(false, null).stream()
                .map(row -> withEligibility(row, 0, BigDecimal.ZERO))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(Long id, TeamHardwareQuotaUpdateRequest request) {
        if (id == null || id <= 0) {
            throw new BizException("Hardware quota id is required");
        }
        if (request == null) {
            throw new BizException("Hardware quota payload is required");
        }
        int updated = jdbcTemplate.update("""
                UPDATE nx_team_hardware_quota_tier
                   SET product_no = COALESCE(?, product_no),
                       display_name = COALESCE(?, display_name),
                       note = COALESCE(?, note),
                       direct_refs = COALESCE(?, direct_refs),
                       month_volume_usd = COALESCE(?, month_volume_usd),
                       monthly_quota = COALESCE(?, monthly_quota),
                       unlock_mode = COALESCE(?, unlock_mode),
                       status = COALESCE(?, status),
                       sort_order = COALESCE(?, sort_order),
                       updated_at = NOW()
                 WHERE id = ?
                   AND is_deleted = 0
                """,
                blankToNull(request.getProductNo()),
                blankToNull(request.getDisplayName()),
                blankToNull(request.getNote()),
                nonNegative(request.getDirectRefs(), "Direct refs"),
                nonNegative(request.getMonthVolumeUsd(), "Month volume"),
                nonNegative(request.getMonthlyQuota(), "Monthly quota"),
                unlockMode(request.getUnlockMode()),
                validStatus(request.getStatus()),
                nonNegative(request.getSortOrder(), "Sort order"),
                id);
        if (updated == 0) {
            throw new BizException(404, "Hardware quota tier not found");
        }
        return listForAdmin().stream()
                .filter(row -> id.equals(asLong(row.get("id"))))
                .findFirst()
                .orElseThrow(() -> new BizException(404, "Hardware quota tier not found"));
    }

    private List<Map<String, Object>> quotaRows(boolean activeOnly, Long userId) {
        return jdbcTemplate.query("""
                SELECT q.id,
                       q.quota_code,
                       q.product_no,
                       COALESCE(NULLIF(q.display_name, ''), p.name, q.product_no) AS displayName,
                       COALESCE(NULLIF(q.note, ''), p.tagline, '') AS note,
                       q.direct_refs,
                       q.month_volume_usd,
                       q.monthly_quota,
                       q.unlock_mode,
                       q.status,
                       q.sort_order,
                       p.name AS productName,
                       p.price_usdt,
                       p.stock,
                       p.sold_count,
                       p.cover_url,
                       p.store_status,
                       COALESCE(u.used_quota, 0) AS used_quota,
                       COALESCE(u.reserved_quota, 0) AS reserved_quota,
                       COALESCE(u.redeemed_quota, 0) AS redeemed_quota,
                       COALESCE(uu.user_used_quota, 0) AS user_used_quota
                  FROM nx_team_hardware_quota_tier q
             LEFT JOIN nx_product p
                    ON p.product_no = q.product_no
                   AND p.is_deleted = 0
             LEFT JOIN (
                    SELECT quota_tier_id,
                           SUM(CASE WHEN usage_type IN ('RESERVED', 'REDEEMED') THEN quantity ELSE 0 END) AS used_quota,
                           SUM(CASE WHEN usage_type = 'RESERVED' THEN quantity ELSE 0 END) AS reserved_quota,
                           SUM(CASE WHEN usage_type = 'REDEEMED' THEN quantity ELSE 0 END) AS redeemed_quota
                      FROM nx_team_hardware_quota_usage
                     WHERE is_deleted = 0
                       AND status = 'ACTIVE'
                       AND occurred_at >= DATE_FORMAT(CURRENT_DATE, '%Y-%m-01')
                     GROUP BY quota_tier_id
                  ) u ON u.quota_tier_id = q.id
             LEFT JOIN (
                    SELECT quota_tier_id,
                           SUM(CASE WHEN usage_type IN ('RESERVED', 'REDEEMED') THEN quantity ELSE 0 END) AS user_used_quota
                      FROM nx_team_hardware_quota_usage
                     WHERE is_deleted = 0
                       AND status = 'ACTIVE'
                       AND user_id = ?
                       AND occurred_at >= DATE_FORMAT(CURRENT_DATE, '%Y-%m-01')
                     GROUP BY quota_tier_id
                  ) uu ON uu.quota_tier_id = q.id
                 WHERE q.is_deleted = 0
                   AND (? = 0 OR q.status = 1)
                 ORDER BY q.sort_order ASC, q.id ASC
                """, this::mapQuotaRow, userId == null ? -1L : userId, activeOnly ? 1 : 0);
    }

    private Map<String, Object> userMetrics(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BizException("User id is required");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT COUNT(DISTINCT CASE WHEN level = 1 THEN member_user_id END) AS activeDirects,
                       COALESCE(SUM(volume), 0) AS teamVolumeUsd
                  FROM nx_team_member
                 WHERE user_id = ?
                   AND is_deleted = 0
                """, userId);
        if (rows.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("activeDirects", 0);
            fallback.put("teamVolumeUsd", BigDecimal.ZERO);
            return fallback;
        }
        return rows.get(0);
    }

    private Map<String, Object> withEligibility(Map<String, Object> row, int activeDirects, BigDecimal teamVolume) {
        int directRequired = asInt(row.get("directRequired"));
        BigDecimal volumeRequired = amount(row.get("volumeRequired"));
        String mode = String.valueOf(row.getOrDefault("unlockMode", "ALL")).toUpperCase();
        boolean directMet = activeDirects >= directRequired;
        boolean volumeMet = teamVolume.compareTo(volumeRequired) >= 0;
        boolean unlocked = "ANY".equals(mode) ? directMet || volumeMet : directMet && volumeMet;
        int monthlyQuota = asInt(row.get("monthlyQuota"));
        int usedQuota = asInt(row.get("usedQuota"));
        int remainingQuota = Math.max(0, monthlyQuota - usedQuota);
        row.put("activeDirects", activeDirects);
        row.put("teamVolumeUsd", teamVolume);
        row.put("directMet", directMet);
        row.put("volumeMet", volumeMet);
        row.put("unlocked", unlocked);
        row.put("remainingQuota", remainingQuota);
        return row;
    }

    private Map<String, Object> mapQuotaRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("quotaCode", rs.getString("quota_code"));
        row.put("productNo", rs.getString("product_no"));
        row.put("displayName", rs.getString("displayName"));
        row.put("productName", rs.getString("productName"));
        row.put("note", rs.getString("note"));
        row.put("directRequired", rs.getInt("direct_refs"));
        row.put("volumeRequired", rs.getBigDecimal("month_volume_usd"));
        row.put("monthlyQuota", rs.getInt("monthly_quota"));
        row.put("unlockMode", rs.getString("unlock_mode"));
        row.put("status", rs.getInt("status"));
        row.put("sortOrder", rs.getInt("sort_order"));
        row.put("priceUsdt", rs.getBigDecimal("price_usdt"));
        row.put("productStock", rs.getInt("stock"));
        row.put("soldCount", rs.getInt("sold_count"));
        row.put("usedQuota", rs.getInt("used_quota"));
        row.put("reservedQuota", rs.getInt("reserved_quota"));
        row.put("redeemedQuota", rs.getInt("redeemed_quota"));
        row.put("userUsedQuota", rs.getInt("user_used_quota"));
        row.put("coverUrl", rs.getString("cover_url"));
        row.put("storeStatus", rs.getString("store_status"));
        return row;
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String unlockMode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        if (!"ALL".equals(normalized) && !"ANY".equals(normalized)) {
            throw new BizException("Unlock mode must be ALL or ANY");
        }
        return normalized;
    }

    private Integer validStatus(Integer value) {
        if (value == null) {
            return null;
        }
        if (value != 0 && value != 1) {
            throw new BizException("Status must be 0 or 1");
        }
        return value;
    }

    private Integer nonNegative(Integer value, String name) {
        if (value != null && value < 0) {
            throw new BizException(name + " must be non-negative");
        }
        return value;
    }

    private BigDecimal nonNegative(BigDecimal value, String name) {
        if (value != null && value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(name + " must be non-negative");
        }
        return value;
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return 0;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return null;
    }

    private BigDecimal amount(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            return new BigDecimal(text);
        }
        return BigDecimal.ZERO;
    }
}
