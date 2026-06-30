package ffdd.opsconsole.bi.infrastructure;


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
    private final BiReportMapper mapper;

    @PostConstruct
    void ensureSchema() {
        mapper.createReportTable();
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
        return Map.of();
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

}
