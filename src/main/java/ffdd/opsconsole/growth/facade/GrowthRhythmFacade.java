package ffdd.opsconsole.growth.facade;

public interface GrowthRhythmFacade {
    GrowthRhythmSnapshot snapshot();

    default GrowthRhythmSnapshot snapshotAtMonth(int month) {
        throw new UnsupportedOperationException("H1_MONTH_SNAPSHOT_NOT_IMPLEMENTED");
    }

    default String phaseForMonth(int month) {
        throw new UnsupportedOperationException("H1_PHASE_MAPPING_NOT_IMPLEMENTED");
    }
}
