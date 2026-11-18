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
        List<NovaOptionView> runtimeSourceOptions,
        List<String> sources,
        /** Resolved business-runtime channels only; this catalog does not evaluate social category, retirement or enabled state. */
        List<ChannelRuntimeSource> channelRuntimeSources) {

    /** Existing twelve-value callers keep their wire shape with no projected runtime sources. */
    public NovaOverview(
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
            List<NovaOptionView> runtimeSourceOptions,
            List<String> sources) {
        this(stats, channels, eventDriven, templates, socialDistribution, socialEvents, socialEventTypes,
                socialEventStatuses, templateStatuses, templateCtaOptions, runtimeSourceOptions, sources, List.of());
    }

    /** The exact catalog source used by the business dispatcher, for one channel. */
    public record ChannelRuntimeSource(String channel, List<String> eventNames, boolean targeted) {
    }
}
