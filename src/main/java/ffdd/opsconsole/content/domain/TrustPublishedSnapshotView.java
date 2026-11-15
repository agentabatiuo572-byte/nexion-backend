package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;
import java.util.List;

/** I4-only admin projection of the same snapshot the App public endpoint currently serves. */
public record TrustPublishedSnapshotView(
        LocalDateTime sampledAt,
        boolean available,
        String reason,
        String source,
        String sourceEnvironment,
        String runId,
        List<SectionStatus> sections) {

    public record SectionStatus(
            String sectionKey,
            String version,
            boolean available,
            String reason,
            List<FieldStatus> fields) {
    }

    /** cleared=true means a server withholding rule replaced a non-empty public value. */
    public record FieldStatus(String key, boolean cleared, String reason) {
    }
}
