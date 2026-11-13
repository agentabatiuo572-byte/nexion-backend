package ffdd.opsconsole.bi.domain;

import java.util.List;
import java.util.Map;

public record BiReportPage(
        long total,
        long pageNum,
        long pageSize,
        List<BiReportView> records,
        Map<String, ApprovalEligibility> approvalEligibility,
        Map<String, String> requesterDisplayNames) {

    public BiReportPage(long total, long pageNum, long pageSize, List<BiReportView> records,
                        Map<String, ApprovalEligibility> approvalEligibility) {
        this(total, pageNum, pageSize, records, approvalEligibility, Map.of());
    }

    public enum ApprovalEligibility {
        ELIGIBLE, SELF, MAKER_UNVERIFIED;

        public static ApprovalEligibility forActor(Long makerId, long adminId) {
            if (makerId == null || makerId < 1) {
                return MAKER_UNVERIFIED;
            }
            return makerId == adminId ? SELF : ELIGIBLE;
        }
    }
}
