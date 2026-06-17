package ffdd.opsconsole.device.infrastructure;

import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.device.domain.DeviceOpsRepository;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.dto.DeviceOpsQueryRequest;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class JdbcDeviceOpsRepository implements DeviceOpsRepository {
    private static final String DEVICE_SELECT = """
            SELECT
              d.id,d.user_id,d.instance_no,d.name,d.product_tier,d.product_code,d.status,d.dc_location,
              d.hashrate,d.daily_usdt,d.daily_nex,d.last_seen_at,d.activated_at,d.deactivated_at,d.pending_deactivate,
              r.online_status,r.gpu_usage,r.gpu_temp_c,r.gpu_power_w,r.paused_reason,r.active_task_no,r.heartbeat_at
            FROM nx_user_device d
            LEFT JOIN nx_user_device_runtime r ON r.user_device_id=d.id AND r.is_deleted=0
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcDeviceOpsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Object> overviewCounters() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalDevices", count("""
                SELECT COUNT(*) FROM nx_user_device WHERE is_deleted=0
                """));
        overview.put("onlineDevices", count("""
                SELECT COUNT(*) FROM nx_user_device WHERE is_deleted=0 AND status IN ('ONLINE','BUSY')
                """));
        overview.put("offlineDevices", count("""
                SELECT COUNT(*) FROM nx_user_device WHERE is_deleted=0 AND status='OFFLINE'
                """));
        overview.put("recycledDevices", count("""
                SELECT COUNT(*) FROM nx_user_device
                WHERE is_deleted=0 AND (status IN ('RECYCLED','DEACTIVATED','INACTIVE','RETIRED') OR deactivated_at IS NOT NULL)
                """));
        overview.put("pendingRecycleDevices", count("""
                SELECT COUNT(*) FROM nx_user_device WHERE is_deleted=0 AND pending_deactivate=1
                """));
        overview.put("abnormalDevices", count("""
                SELECT COUNT(*)
                FROM nx_user_device d
                LEFT JOIN nx_user_device_runtime r ON r.user_device_id=d.id AND r.is_deleted=0
                WHERE d.is_deleted=0 AND (
                  d.status NOT IN ('ONLINE','BUSY')
                  OR r.online_status IN ('OFFLINE','ERROR','ABNORMAL','LOST')
                  OR r.heartbeat_at < DATE_SUB(NOW(), INTERVAL 10 MINUTE)
                )
                """));
        overview.put("datacenters", datacenterSummaries());
        return overview;
    }

    @Override
    public PageResult<DeviceOpsView> pageDevices(DeviceOpsQueryRequest request) {
        QueryParts parts = queryParts(request);
        long pageNum = normalizePage(request == null ? null : request.pageNum());
        long pageSize = normalizeSize(request == null ? null : request.pageSize());
        long offset = (pageNum - 1) * pageSize;

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM nx_user_device d " + parts.where(), Long.class, parts.params().toArray());
        List<Object> dataParams = new ArrayList<>(parts.params());
        dataParams.add(pageSize);
        dataParams.add(offset);
        List<DeviceOpsView> records = jdbcTemplate.query(
                DEVICE_SELECT + parts.where() + " ORDER BY COALESCE(d.last_seen_at,d.updated_at,d.created_at) DESC LIMIT ? OFFSET ?",
                this::mapDevice,
                dataParams.toArray());
        return new PageResult<>(total == null ? 0L : total, pageNum, pageSize, records);
    }

    @Override
    public Optional<DeviceOpsView> findDevice(Long deviceId) {
        List<DeviceOpsView> rows = jdbcTemplate.query(
                DEVICE_SELECT + " WHERE d.is_deleted=0 AND d.id=?",
                this::mapDevice,
                deviceId);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<DeviceOpsView> restoreDevice(Long deviceId, LocalDateTime restoredAt) {
        jdbcTemplate.update("""
                UPDATE nx_user_device
                SET status='OFFLINE', pending_deactivate=0, deactivated_at=NULL, last_seen_at=?, updated_at=NOW()
                WHERE id=? AND is_deleted=0
                """, restoredAt, deviceId);
        jdbcTemplate.update("""
                UPDATE nx_user_device_runtime
                SET online_status='OFFLINE', paused_reason=NULL, heartbeat_at=?, updated_at=NOW()
                WHERE user_device_id=? AND is_deleted=0
                """, restoredAt, deviceId);
        return findDevice(deviceId);
    }

    @Override
    public Map<String, String> e3Config() {
        Map<String, String> config = defaultE3Config();
        jdbcTemplate.query(
                "SELECT config_key,config_value FROM nx_compute_e3_config WHERE is_deleted=0 ORDER BY sort_order,id",
                (RowCallbackHandler) rs -> config.put(rs.getString("config_key"), rs.getString("config_value")));
        jdbcTemplate.query("""
                SELECT scope_type,start_month,end_month,monthly_decay_rate,floor_efficiency
                FROM nx_device_lifecycle_rule
                WHERE is_deleted=0 AND status=1 AND scope_type='DEFAULT'
                ORDER BY start_month
                """, (RowCallbackHandler) rs -> {
                    int startMonth = rs.getInt("start_month");
                    if (startMonth == 1) {
                        config.put("degradeEarly", rateToPercent(rs.getBigDecimal("monthly_decay_rate")));
                        config.put("stageEarlyEnd", String.valueOf(rs.getInt("end_month")));
                    } else if (startMonth == 4) {
                        config.put("degradeMid", rateToPercent(rs.getBigDecimal("monthly_decay_rate")));
                        config.put("stageMidEnd", String.valueOf(rs.getInt("end_month")));
                    } else if (startMonth >= 9) {
                        config.put("degradeLate", rateToPercent(rs.getBigDecimal("monthly_decay_rate")));
                    }
                    config.put("minEfficiency", rateToPercent(rs.getBigDecimal("floor_efficiency")));
                });
        return config;
    }

    @Override
    public void upsertE3Config(String key, String value, String valueType, String operator) {
        int updated = jdbcTemplate.update("""
                UPDATE nx_compute_e3_config
                SET config_value=?, value_type=?, updated_by=?, updated_at=NOW()
                WHERE config_key=? AND is_deleted=0
                """, value, valueType, operator, key);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO nx_compute_e3_config(config_key,config_value,value_type,updated_by,sort_order,is_deleted)
                    VALUES(?,?,?,?,?,0)
                    """, key, value, valueType, operator, sortForConfig(key));
        }
    }

    @Override
    public List<Map<String, Object>> datacenterSummaries() {
        return jdbcTemplate.query("""
                SELECT
                  COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED') dc_location,
                  COUNT(*) total_devices,
                  SUM(CASE WHEN d.status IN ('ONLINE','BUSY') THEN 1 ELSE 0 END) online_devices,
                  SUM(CASE WHEN d.pending_deactivate=1 THEN 1 ELSE 0 END) pending_recycle_devices,
                  SUM(CASE WHEN d.status NOT IN ('ONLINE','BUSY') OR r.online_status IN ('OFFLINE','ERROR','ABNORMAL','LOST') THEN 1 ELSE 0 END) abnormal_devices,
                  COALESCE(AVG(r.gpu_usage),0) avg_gpu_usage,
                  COALESCE(AVG(r.gpu_temp_c),0) avg_gpu_temp_c,
                  COALESCE(AVG(r.gpu_power_w),0) avg_gpu_power_w,
                  MAX(s.dispatch_paused) dispatch_paused,
                  MAX(s.paused_reason) paused_reason,
                  MAX(s.paused_at) paused_at,
                  MAX(s.resumed_at) resumed_at
                FROM nx_user_device d
                LEFT JOIN nx_user_device_runtime r ON r.user_device_id=d.id AND r.is_deleted=0
                LEFT JOIN nx_compute_dc_ops_state s
                  ON s.dc_location=COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED') AND s.is_deleted=0
                WHERE d.is_deleted=0
                GROUP BY COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED')
                ORDER BY total_devices DESC, dc_location ASC
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dcLocation", rs.getString("dc_location"));
            row.put("totalDevices", rs.getLong("total_devices"));
            row.put("onlineDevices", rs.getLong("online_devices"));
            row.put("pendingRecycleDevices", rs.getLong("pending_recycle_devices"));
            row.put("abnormalDevices", rs.getLong("abnormal_devices"));
            row.put("avgGpuUsage", rs.getBigDecimal("avg_gpu_usage"));
            row.put("avgGpuTempC", rs.getBigDecimal("avg_gpu_temp_c"));
            row.put("avgGpuPowerW", rs.getBigDecimal("avg_gpu_power_w"));
            row.put("dispatchPaused", rs.getInt("dispatch_paused") == 1);
            row.put("pausedReason", rs.getString("paused_reason"));
            row.put("pausedAt", timestamp(rs, "paused_at"));
            row.put("resumedAt", timestamp(rs, "resumed_at"));
            return row;
        });
    }

    @Override
    public void pauseDatacenter(String dcLocation, String reason, String operator, LocalDateTime now) {
        upsertDatacenterState(dcLocation, true, reason, operator, now);
        List<Object> params = new ArrayList<>();
        params.add(reason);
        params.add(now);
        params.add(dcLocation);
        jdbcTemplate.update("""
                INSERT INTO nx_user_device_runtime(user_device_id,online_status,region,paused_reason,heartbeat_at)
                SELECT d.id,d.status,COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED'),?,COALESCE(d.last_seen_at,?)
                FROM nx_user_device d
                WHERE d.is_deleted=0 AND COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED')=?
                ON DUPLICATE KEY UPDATE paused_reason=VALUES(paused_reason), updated_at=NOW()
                """, params.toArray());
    }

    @Override
    public void resumeDatacenter(String dcLocation, String operator, LocalDateTime now) {
        upsertDatacenterState(dcLocation, false, null, operator, now);
        jdbcTemplate.update("""
                UPDATE nx_user_device_runtime r
                JOIN nx_user_device d ON d.id=r.user_device_id
                SET r.paused_reason=NULL, r.updated_at=NOW()
                WHERE d.is_deleted=0 AND r.is_deleted=0 AND COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED')=?
                """, dcLocation);
    }

    private void upsertDatacenterState(String dcLocation, boolean paused, String reason, String operator, LocalDateTime now) {
        int updated = jdbcTemplate.update("""
                UPDATE nx_compute_dc_ops_state
                SET dispatch_paused=?, paused_reason=?, paused_at=CASE WHEN ?=1 THEN ? ELSE paused_at END,
                    resumed_at=CASE WHEN ?=0 THEN ? ELSE resumed_at END, updated_by=?, updated_at=NOW()
                WHERE dc_location=? AND is_deleted=0
                """, paused ? 1 : 0, reason, paused ? 1 : 0, now, paused ? 1 : 0, now, operator, dcLocation);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO nx_compute_dc_ops_state(dc_location,dispatch_paused,paused_reason,paused_at,resumed_at,updated_by,is_deleted)
                    VALUES(?,?,?,?,?,?,0)
                    """, dcLocation, paused ? 1 : 0, reason, paused ? now : null, paused ? null : now, operator);
        }
    }

    private QueryParts queryParts(DeviceOpsQueryRequest request) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE d.is_deleted=0");
        if (request != null && StringUtils.hasText(request.status())) {
            where.append(" AND d.status=?");
            params.add(request.status().trim().toUpperCase());
        }
        if (request != null && StringUtils.hasText(request.dcLocation())) {
            where.append(" AND COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED')=?");
            params.add(request.dcLocation().trim());
        }
        if (request != null && StringUtils.hasText(request.keyword())) {
            String keyword = "%" + request.keyword().trim() + "%";
            where.append(" AND (d.instance_no LIKE ? OR d.name LIKE ? OR d.product_code LIKE ? OR d.source_order_no LIKE ?)");
            params.add(keyword);
            params.add(keyword);
            params.add(keyword);
            params.add(keyword);
        }
        return new QueryParts(where.toString(), params);
    }

    private DeviceOpsView mapDevice(ResultSet rs, int rowNum) throws SQLException {
        return new DeviceOpsView(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("instance_no"),
                rs.getString("name"),
                rs.getString("product_tier"),
                rs.getString("product_code"),
                rs.getString("status"),
                rs.getString("dc_location"),
                rs.getBigDecimal("hashrate"),
                rs.getBigDecimal("daily_usdt"),
                rs.getBigDecimal("daily_nex"),
                timestamp(rs, "last_seen_at"),
                timestamp(rs, "activated_at"),
                timestamp(rs, "deactivated_at"),
                rs.getInt("pending_deactivate"),
                rs.getString("online_status"),
                rs.getBigDecimal("gpu_usage"),
                rs.getBigDecimal("gpu_temp_c"),
                rs.getBigDecimal("gpu_power_w"),
                rs.getString("paused_reason"),
                rs.getString("active_task_no"),
                timestamp(rs, "heartbeat_at"));
    }

    private LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private Map<String, String> defaultE3Config() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("degradeEarly", "4");
        config.put("degradeMid", "6");
        config.put("degradeLate", "10");
        config.put("stageEarlyEnd", "3");
        config.put("stageMidEnd", "8");
        config.put("cycleMonths", "12");
        config.put("minEfficiency", "22");
        config.put("salvagePct", "30");
        config.put("minHoldingMonths", "1");
        config.put("promoMult", "1.0");
        config.put("promoCooldownDays", "14");
        config.put("promoMaxPerSession", "1");
        config.put("promoDelaySeconds", "6");
        config.put("promoMinAgeDays", "30");
        config.put("inventorySoftMax", "0");
        return config;
    }

    private String rateToPercent(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .multiply(BigDecimal.valueOf(100))
                .stripTrailingZeros()
                .toPlainString();
    }

    private int sortForConfig(String key) {
        return switch (key) {
            case "promoMult" -> 10;
            case "promoCooldownDays" -> 20;
            case "promoMaxPerSession" -> 30;
            case "promoDelaySeconds" -> 40;
            case "promoMinAgeDays" -> 50;
            case "inventorySoftMax" -> 60;
            default -> 100;
        };
    }

    private long normalizePage(Long pageNum) {
        return Math.max(1L, pageNum == null ? 1L : pageNum);
    }

    private long normalizeSize(Long pageSize) {
        return Math.max(1L, Math.min(100L, pageSize == null ? 20L : pageSize));
    }

    private record QueryParts(String where, List<Object> params) {
    }
}
