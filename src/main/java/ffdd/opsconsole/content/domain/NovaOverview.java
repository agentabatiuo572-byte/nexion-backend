package ffdd.opsconsole.content.domain;

import java.util.List;

public record NovaOverview(
        NovaStats stats,
        List<NovaChannelView> channels,
        List<NovaEventDrivenView> eventDriven,
        List<NovaTemplateView> templates,
        List<NovaSocialDistributionItem> socialDistribution,
        List<NovaSocialEventView> socialEvents,
        List<NovaOptionView> socialEventTypes,
        List<NovaOptionView> socialEventStatuses,
        List<String> templateStatuses,
        List<NovaOptionView> templateCtaOptions,
        List<String> sources) {
}
