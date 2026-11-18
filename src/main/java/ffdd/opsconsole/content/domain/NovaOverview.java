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
        List<ChannelRuntimeSource> channelRuntimeSources,
        /** Rolling seven-day counts from the canonical NOVA_NOTIFICATION/nova.push_sent facts. */
        PushDeliveryView pushDelivery) {

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
                socialEventStatuses, templateStatuses, templateCtaOptions, runtimeSourceOptions, sources, List.of(), null);
    }

    /** Existing thirteen-value callers keep the resolved runtime sources but carry no delivery projection. */
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
            List<String> sources,
            List<ChannelRuntimeSource> channelRuntimeSources) {
        this(stats, channels, eventDriven, templates, socialDistribution, socialEvents, socialEventTypes,
                socialEventStatuses, templateStatuses, templateCtaOptions, runtimeSourceOptions, sources,
                channelRuntimeSources, null);
    }

    /** The exact catalog source used by the business dispatcher, for one channel. */
    public record ChannelRuntimeSource(String channel, List<String> eventNames, boolean targeted) {
    }

    /** One rolling window, sourced only from canonical user notification facts. */
    public record PushDeliveryView(String from, String to, int days, String eventName, String sourceAggregate,
                                   String basis, List<ChannelPushSentStat> channels) {
    }

    public record ChannelPushSentStat(String channel, long pushSentCount) {
    }
}
