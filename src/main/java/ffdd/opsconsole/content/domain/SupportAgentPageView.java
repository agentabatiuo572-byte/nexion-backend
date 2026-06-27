package ffdd.opsconsole.content.domain;

import java.util.List;

public record SupportAgentPageView(
        long total,
        long pageNum,
        long pageSize,
        List<SupportAgentProfileView> records,
        List<SupportAgentAssignmentView> advisorAssignments,
        List<String> positions,
        List<String> serviceTypes,
        List<String> sources) {
}
