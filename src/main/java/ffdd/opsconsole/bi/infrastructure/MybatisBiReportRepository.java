package ffdd.opsconsole.bi.infrastructure;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.bi.domain.BiReportCreateCommand;
import ffdd.opsconsole.bi.domain.BiReportRepository;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.mapper.BiReportMapper;
import ffdd.opsconsole.shared.api.PageResult;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisBiReportRepository implements BiReportRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final BiReportMapper mapper;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void ensureSchema() {
        mapper.createReportTable();
        mapper.createDashboardPayloadTable();
        ensureDashboardSeed("L1");
        ensureDashboardSeed("L2");
        ensureDashboardSeed("L3");
        ensureDashboardSeed("L4");
        ensureDashboardSeed("L5");
        ensureDashboardSeed("L6");
        ensureReportSeeds();
    }

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalReports", mapper.countTotalReports());
        overview.put("sensitiveReports", mapper.countSensitiveReports());
        overview.put("pendingConfirm", mapper.countPendingConfirm());
        overview.put("readyReports", mapper.countReadyReports());
        return overview;
    }

    @Override
    public Map<String, Object> dashboard(String moduleCode) {
        String normalized = normalizeModule(moduleCode);
        ensureDashboardSeed(normalized);
        List<BiReportMapper.DashboardPayloadRow> rows = mapper.dashboardPayloads(normalized);
        Map<String, Object> dashboard = new LinkedHashMap<>();
        for (BiReportMapper.DashboardPayloadRow row : rows) {
            Object payload = readPayload(row.payloadJson());
            if ("root".equals(row.sectionKey()) && payload instanceof Map<?, ?> map) {
                map.forEach((key, value) -> dashboard.put(String.valueOf(key), value));
            } else {
                dashboard.put(row.sectionKey(), payload);
            }
        }
        return dashboard;
    }

    @Override
    public void saveDashboard(String moduleCode, Map<String, Object> dashboard) {
        String normalized = normalizeModule(moduleCode);
        mapper.upsertDashboardPayload(normalized, "root", toJson(dashboard == null ? Map.of() : dashboard), 0);
    }

    @Override
    public PageResult<BiReportView> reports(String type, List<String> statuses, int pageNum, int pageSize) {
        String normalizedType = StringUtils.hasText(type) ? type.trim() : null;
        List<String> normalizedStatuses = statuses == null ? List.of() : statuses;
        long total = mapper.countReports(normalizedType, normalizedStatuses);
        int offset = Math.max(0, (pageNum - 1) * pageSize);
        List<BiReportView> records = mapper.reports(normalizedType, normalizedStatuses, pageSize, offset);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public Optional<BiReportView> findReport(String reportId) {
        return Optional.ofNullable(mapper.findReport(reportId));
    }

    @Override
    public BiReportView createReport(BiReportCreateCommand command) {
        mapper.upsertReportSeed(new BiReportMapper.ReportSeed(
                "L5",
                command.reportId(),
                command.name(),
                command.type(),
                command.cycle(),
                command.format(),
                command.scope(),
                command.fields(),
                command.rowCount(),
                command.containsPii(),
                command.maskingPolicy(),
                command.status(),
                command.note()));
        return mapper.findReport(command.reportId());
    }

    @Override
    public void updateAction(String reportId, String action, String nextStatus, String reason) {
        mapper.updateAction(reportId, action, nextStatus, reason);
    }

    private void ensureDashboardSeed(String moduleCode) {
        String normalized = normalizeModule(moduleCode);
        if (mapper.countDashboardPayloads(normalized) > 0) {
            return;
        }
        mapper.upsertDashboardPayload(normalized, "root", toJson(BiDashboardSeeds.dashboard(normalized)), 0);
    }

    private void ensureReportSeeds() {
        if (mapper.countTotalReports() > 0) {
            return;
        }
        BiDashboardSeeds.reports().forEach(mapper::upsertReportSeed);
    }

    private String normalizeModule(String moduleCode) {
        return StringUtils.hasText(moduleCode) ? moduleCode.trim().toUpperCase() : "L1";
    }

    private Object readPayload(String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("BI_DASHBOARD_PAYLOAD_PARSE_FAILED", ex);
        }
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("BI_DASHBOARD_PAYLOAD_WRITE_FAILED", ex);
        }
    }
}
