package ffdd.opsconsole.bi.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface BiReportRepository {
    Map<String, Object> overview();

    List<BiReportView> reports(String type, String status, int limit);

    Optional<BiReportView> findReport(String reportId);

    void updateAction(String reportId, String action, String nextStatus, String reason);
}
