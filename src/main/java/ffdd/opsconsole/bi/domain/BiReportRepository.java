package ffdd.opsconsole.bi.domain;

import ffdd.opsconsole.shared.api.PageResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface BiReportRepository {
    Map<String, Object> overview();

    Map<String, Object> dashboard(String moduleCode);

    void saveDashboard(String moduleCode, Map<String, Object> dashboard);

    PageResult<BiReportView> reports(String type, List<String> statuses, int pageNum, int pageSize);

    Optional<BiReportView> findReport(String reportId);

    BiReportView createReport(BiReportCreateCommand command);

    void updateAction(String reportId, String action, String nextStatus, String reason);
}
