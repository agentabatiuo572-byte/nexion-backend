package ffdd.opsconsole.content.dto;

import java.util.List;

public record SupportAgentProfileUpdateRequest(
        String position,
        List<String> serviceTypes,
        List<String> tags,
        Integer maxConcurrent,
        Boolean enabled,
        Boolean transferable,
        Boolean busy,
        Long expectedVersion,
        String operator,
        String reason,
        Boolean maxConcurrentUnlimited) {
    public SupportAgentProfileUpdateRequest(
            String position, List<String> serviceTypes, List<String> tags, Integer maxConcurrent,
            Boolean enabled, Boolean transferable, Boolean busy, Long expectedVersion, String operator, String reason) {
        this(position, serviceTypes, tags, maxConcurrent, enabled, transferable, busy,
                expectedVersion, operator, reason, null);
    }

    public SupportAgentProfileUpdateRequest(
            String position, List<String> serviceTypes, List<String> tags, Integer maxConcurrent,
            Boolean enabled, Boolean transferable, Boolean busy, String operator, String reason) {
        this(position, serviceTypes, tags, maxConcurrent, enabled, transferable, busy, 1L, operator, reason);
    }

    /** Retain the command digest of existing requests unless they explicitly set unlimited. */
    public String commandFingerprint() {
        String previous = "SupportAgentProfileUpdateRequest[position=" + position + ", serviceTypes=" + serviceTypes
                + ", tags=" + tags + ", maxConcurrent=" + maxConcurrent + ", enabled=" + enabled
                + ", transferable=" + transferable + ", busy=" + busy + ", expectedVersion=" + expectedVersion
                + ", operator=" + operator + ", reason=" + reason + "]";
        return Boolean.TRUE.equals(maxConcurrentUnlimited) ? previous + "|maxConcurrentUnlimited=true" : previous;
    }
}
