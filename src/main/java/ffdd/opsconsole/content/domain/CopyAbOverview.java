package ffdd.opsconsole.content.domain;

import java.util.List;

public record CopyAbOverview(
        CopyAbStats stats,
        List<CopyVersionOptionView> versionOptions,
        List<CopyContentRow> copies,
        List<CopyVersionRow> versions,
        List<CopyExperimentRow> experiments,
        List<CopyFrameworkParamView> frameworkParams,
        List<CopyPositionView> positions,
        List<String> surfaces,
        List<String> audiences,
        List<String> trafficSplits,
        List<String> sources) {
}
