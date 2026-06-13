package ffdd.team.service;

import ffdd.common.exception.BizException;
import ffdd.team.dto.CommissionRuleUpdateRequest;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TeamCommissionRuleService {
    private final JdbcTemplate jdbcTemplate;

    public TeamCommissionRuleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> list(String commissionType) {
        String normalizedType = normalizeType(commissionType);
        return jdbcTemplate.query("""
                SELECT id, commission_type, layer_no, rank_code, usdt_rate, nex_per_usd,
                       fixed_nex, daily_cap_usdt, cooldown_days, status, updated_at
                  FROM nx_commission_rule
                 WHERE commission_type = ?
                   AND is_deleted = 0
                 ORDER BY COALESCE(layer_no, 9999), COALESCE(rank_code, ''), id
                """, this::mapRule, normalizedType);
    }

    public Map<String, Object> update(Long id, CommissionRuleUpdateRequest request) {
        if (id == null || id <= 0) {
            throw new BizException("Commission rule id is required");
        }
        validate(request);
        int updated = jdbcTemplate.update("""
                UPDATE nx_commission_rule
                   SET usdt_rate = COALESCE(?, usdt_rate),
                       nex_per_usd = COALESCE(?, nex_per_usd),
                       fixed_nex = COALESCE(?, fixed_nex),
                       daily_cap_usdt = COALESCE(?, daily_cap_usdt),
                       cooldown_days = COALESCE(?, cooldown_days),
                       status = COALESCE(?, status),
                       updated_at = NOW()
                 WHERE id = ?
                   AND is_deleted = 0
                """,
                request.getUsdtRate(),
                request.getNexPerUsd(),
                request.getFixedNex(),
                request.getDailyCapUsdt(),
                request.getCooldownDays(),
                request.getStatus(),
                id);
        if (updated == 0) {
            throw new BizException(404, "Commission rule not found");
        }
        return find(id);
    }

    private Map<String, Object> find(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT id, commission_type, layer_no, rank_code, usdt_rate, nex_per_usd,
                       fixed_nex, daily_cap_usdt, cooldown_days, status, updated_at
                  FROM nx_commission_rule
                 WHERE id = ?
                   AND is_deleted = 0
                 LIMIT 1
                """, this::mapRule, id);
        if (rows.isEmpty()) {
            throw new BizException(404, "Commission rule not found");
        }
        return rows.get(0);
    }

    private Map<String, Object> mapRule(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal usdtRate = rs.getBigDecimal("usdt_rate");
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("commissionType", rs.getString("commission_type"));
        row.put("layerNo", rs.getObject("layer_no"));
        row.put("rankCode", rs.getString("rank_code"));
        row.put("usdtRate", usdtRate);
        row.put("usdtRatePct", usdtRate.multiply(new BigDecimal("100")));
        row.put("nexPerUsd", rs.getBigDecimal("nex_per_usd"));
        row.put("fixedNex", rs.getBigDecimal("fixed_nex"));
        row.put("dailyCapUsdt", rs.getBigDecimal("daily_cap_usdt"));
        row.put("cooldownDays", rs.getInt("cooldown_days"));
        row.put("status", rs.getInt("status"));
        row.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime());
        return row;
    }

    private String normalizeType(String commissionType) {
        if (!StringUtils.hasText(commissionType)) {
            return "UNILEVEL";
        }
        return commissionType.trim().toUpperCase(Locale.ROOT);
    }

    private void validate(CommissionRuleUpdateRequest request) {
        if (request == null) {
            throw new BizException("Commission rule payload is required");
        }
        requireNonNegative(request.getUsdtRate(), "USDT rate");
        requireNonNegative(request.getNexPerUsd(), "NEX per USD");
        requireNonNegative(request.getFixedNex(), "fixed NEX");
        requireNonNegative(request.getDailyCapUsdt(), "daily cap");
        if (request.getCooldownDays() != null && request.getCooldownDays() < 0) {
            throw new BizException("Cooldown days must be non-negative");
        }
        if (request.getStatus() != null && request.getStatus() != 0 && request.getStatus() != 1) {
            throw new BizException("Status must be 0 or 1");
        }
    }

    private void requireNonNegative(BigDecimal value, String label) {
        if (value != null && value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(label + " must be non-negative");
        }
    }
}
