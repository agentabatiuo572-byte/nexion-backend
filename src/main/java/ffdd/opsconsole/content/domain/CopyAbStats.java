package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;

public record CopyAbStats(
        int managedCopies,
        int runningExps,
        /** Legacy compact display string; despite its name it sums all experiments, not a weekly window. */
        String weeklyExposures,
        /** Legacy display string: "+<max non-empty variant CVR>%"; empty data remains "+0%". */
        String topLift,
        /** Exact cumulative experiment impressions across all returned experiments. */
        long cumulativeExperimentImpressions,
        /** Highest non-empty variant conversion rate as a percentage value; null when no variant has a value. */
        BigDecimal highestVariantCvrPct) {

    /** Existing four-value callers retain their wire shape and empty top-lift behavior. */
    public CopyAbStats(int managedCopies, int runningExps, String weeklyExposures, String topLift) {
        this(managedCopies, runningExps, weeklyExposures, topLift, 0L, null);
    }
}
