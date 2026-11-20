package ffdd.opsconsole.content.domain;

import java.util.List;
import java.util.Map;

public record SupportKnowledgeOverview(
        List<SupportFaqView> faqs,
        List<SupportSlaView> sla,
        List<String> categories,
        List<String> surfaces,
        List<String> statuses,
        List<String> queues,
        List<String> escalations,
        List<String> sources,
        Map<String, String> faqLastModifiedBy) {

    public SupportKnowledgeOverview(List<SupportFaqView> faqs, List<SupportSlaView> sla,
                                    List<String> categories, List<String> surfaces, List<String> statuses,
                                    List<String> queues, List<String> escalations, List<String> sources) {
        this(faqs, sla, categories, surfaces, statuses, queues, escalations, sources, Map.of());
    }
}
