package ffdd.opsconsole.platform.dto;

import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import java.util.List;

public record EventCenterOverview(
        EventCenterStats stats,
        List<EventFamily> eventFamilies,
        List<String> registeredDomains,
        List<String> pendingDomains,
        List<String> sunsetDomains,
        List<EventCommonField> commonFields,
        List<EventDimensionParam> dimensionParams,
        List<EventKpiFormula> kpiFormulas,
        List<EventDomainExtensionBatch> domainExtensions,
        List<AuditLogRecord> recentLogs,
        List<AuditStatsBucket> topActions,
        List<String> guardrails) {

    public record EventCenterStats(
            String todayEvents,
            long todayAuditEvents,
            int registeredDomains,
            int pendingDomains,
            int batchDone,
            int batchTotal,
            String schemaVersion) {
    }

    public record EventFamily(
            String key,
            String title,
            String sub,
            String sample,
            String serverAuth,
            String todayCount,
            List<EventDetailRow> events) {
    }

    public record EventDetailRow(String item, String desc) {
    }

    public record EventCommonField(String key, String name, String sub, String value) {
    }

    public record EventDimensionParam(String key, String name, String sub, String value, boolean locked) {
    }

    public record EventKpiFormula(int n, String kpi, String formula) {
    }

    public record EventDomainExtensionBatch(
            String id,
            String title,
            String state,
            String proposer,
            String impact,
            List<EventDomainItem> newDomains,
            List<EventDetailRow> details) {
    }

    public record EventDomainItem(String name, boolean n) {
    }
}
