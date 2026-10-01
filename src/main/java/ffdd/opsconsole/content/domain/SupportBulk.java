package ffdd.opsconsole.content.domain;

import java.util.List;

public final class SupportBulk {
    private SupportBulk() {}
    public record Customer(Long id, Long expectedAssignmentId) {}
    public record Excluded(Long id, String reason) {}
    public record Preview(String selectionId, Long actorId, Object filters, List<Customer> customers,
            List<Excluded> excluded, int count, String evaluatedAt, String expiresAt) {}
    public record Counts(long total, long pending, long sent, long failed, long skipped, long cancelled,
            long unknown) {
        public Counts {
            if (total != pending + sent + failed + skipped + cancelled || unknown > pending)
                throw new IllegalStateException("SUPPORT_BULK_COUNT_MISMATCH");
        }
    }
}
