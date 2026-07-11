package ffdd.opsconsole.content.infrastructure;

import java.time.LocalDateTime;
import lombok.Data;

/** Mutable MyBatis projections kept outside the runtime domain contract. */
public final class ContentExperimentRuntimeRows {
    private ContentExperimentRuntimeRows() {
    }

    @Data
    public static class UserAudienceRow {
        private Long userId;
        private String status;
        private String language;
        private LocalDateTime registeredAt;
    }

    @Data
    public static class ExperimentRow {
        private String experimentId;
        private String copyKey;
        private String audienceSnapshotJson;
    }

    @Data
    public static class VariantRow {
        private String variantName;
        private String copyVersion;
        private Integer splitPct;
        private Integer sortOrder;
    }

    @Data
    public static class AssignmentRow {
        private String experimentId;
        private Long userId;
        private String variantName;
        private String copyVersion;
        private Integer bucketNo;
        private LocalDateTime exposedAt;
    }

    @Data
    public static class CopyBodyRow {
        private String copyKey;
        private String version;
        private String zhText;
        private String enText;
        private String viText;
    }
}
