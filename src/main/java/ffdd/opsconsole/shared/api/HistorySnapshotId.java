package ffdd.opsconsole.shared.api;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.function.LongSupplier;

/**
 * A per-account insert high-water mark, not an as-of snapshot of later edits/deletions.
 * The supplier must include retained soft-deleted rows so issued boundaries remain valid.
 * All page queries must additionally retain their user scope and visibility filters.
 */
public final class HistorySnapshotId {
    private HistorySnapshotId() { }
    public static long resolve(String requested, LongSupplier maxIssued) {
        if (requested == null) return Math.max(0, maxIssued.getAsLong());
        if (!requested.matches("0|[1-9][0-9]{0,18}")) throw new BizException(422, "HISTORY_SNAPSHOT_INVALID");
        final long boundary;
        try { boundary = Long.parseLong(requested); }
        catch (NumberFormatException ex) { throw new BizException(422, "HISTORY_SNAPSHOT_INVALID"); }
        if (boundary > Math.max(0, maxIssued.getAsLong())) throw new BizException(422, "HISTORY_SNAPSHOT_INVALID");
        return boundary;
    }
}
