package ffdd.opsconsole.content.domain;

/**
 * I4 financial field projection.
 *
 * <p>{@code delta} is the legacy wire name used by the existing PC reader. The
 * backend has no calculated QoQ source: the value is the content field's
 * label (or null), so it must not be parsed as a number or presented as a
 * measured quarter-over-quarter change. {@code label} is the explicit
 * label-semantic name for new consumers; it carries the same content label.
 */
public record FinancialFieldView(
        String key,
        String value,
        String delta,
        String label) {

    /** Existing three-argument callers keep the legacy shape with no explicit label. */
    public FinancialFieldView(String key, String value, String delta) {
        this(key, value, delta, null);
    }
}
