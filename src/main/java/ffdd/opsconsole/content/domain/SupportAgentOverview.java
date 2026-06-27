package ffdd.opsconsole.content.domain;

import java.util.List;
import java.util.Map;

public record SupportAgentOverview(
        List<SupportAgentProfileView> agents,
        List<SupportAgentAssignmentView> advisorAssignments,
        List<Map<String, Object>> transferTargets,
        List<String> positions,
        List<String> serviceTypes,
        List<String> sources) {
}
