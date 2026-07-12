package ffdd.opsconsole.content.domain;

import java.util.List;

public record NovaSocialSyncResult(
        int discovered,
        int inserted,
        int duplicates,
        List<SourceResult> sources) {
    public record SourceResult(
            String sourceType,
            String sourceTable,
            String status,
            int discovered,
            int inserted,
            int duplicates,
            String message) {
    }
}
