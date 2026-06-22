package ffdd.opsconsole.content.dto;

import java.util.Map;

public record SupportLoadConfigUpdateRequest(
        Boolean autoBalance,
        Integer defaultCap,
        Integer burstCap,
        Integer warnPct,
        Boolean quietHourBalance,
        String overflowQueue,
        Map<String, SupportAgentLoadStateRequest> agentState,
        String operator,
        String reason) {
}
