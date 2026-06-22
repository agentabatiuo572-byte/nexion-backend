package ffdd.opsconsole.content.domain;

import java.util.List;

public record SupportKnowledgeOverview(
        List<SupportFaqView> faqs,
        List<SupportSlaView> sla,
        List<String> categories,
        List<String> surfaces,
        List<String> statuses,
        List<String> queues,
        List<String> escalations,
        List<String> sources) {
}
