package ffdd.opsconsole.content.domain;

public record CopyAbStats(
        int managedCopies,
        int runningExps,
        String weeklyExposures,
        String topLift) {
}
