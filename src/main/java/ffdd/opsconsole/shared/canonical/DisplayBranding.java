package ffdd.opsconsole.shared.canonical;

/** Read-only compatibility for exact system-generated labels; never use for CAS values. */
public final class DisplayBranding {
    private DisplayBranding() { }

    public static String sessionName(String value) {
        if (value == null || value.isBlank()) return "UVEL App / H5";
        return switch (value.trim()) {
            case "NexGrid H5" -> "UVEL H5";
            case "NexGrid Phone App" -> "UVEL Phone App";
            case "NexGrid App / H5" -> "UVEL App / H5";
            default -> value.trim();
        };
    }
}
