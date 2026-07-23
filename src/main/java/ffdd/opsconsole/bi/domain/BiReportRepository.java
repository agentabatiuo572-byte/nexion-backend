package ffdd.opsconsole.bi.domain;

import ffdd.opsconsole.shared.api.PageResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface BiReportRepository {
    Map<String, Object> overview();

    Map<String, Object> dashboard(String moduleCode);

    Map<String, Object> kpiDashboard(String window, String cohort, String phase, String locale, String ref);

    Map<String, Object> kpiDrilldown(int kpiId, String window, String cohort, String phase, String locale, String ref);

    Map<String, Object> kpiTrend(int kpiId, String window, String cohort, String phase, String locale, String ref);

    Map<String, Object> operationsDashboard(String period, String phase, String from, String to);

    List<Map<String, Object>> networkTreeRows(String period, int depth, int limit);

    default long countRegisteredServerEvent(String eventName) {
        return 0L;
    }

    PageResult<BiReportView> reports(String type, List<String> statuses, int pageNum, int pageSize);

    Optional<BiReportView> findReport(String reportId);

    BiReportView createReport(BiReportCreateCommand command);

    void saveSnapshotCsv(String reportId, String snapshotCsv);

    Optional<String> findSnapshotCsv(String reportId);

    void saveDownloadToken(String reportId, String tokenHash, LocalDateTime expiresAt);

    boolean isDownloadTokenValid(String reportId, String tokenHash, LocalDateTime now);

    boolean updateActionIfStatus(String reportId, String action, String expectedStatus, String nextStatus, String reason);
}
