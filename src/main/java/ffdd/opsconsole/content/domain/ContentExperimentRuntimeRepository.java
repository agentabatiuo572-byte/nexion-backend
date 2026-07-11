package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ContentExperimentRuntimeRepository {
    Optional<UserAudienceProfile> findUserAudienceProfile(long userId);

    Optional<RunningExperiment> findRunningExperiment(String copyKey);

    /** Locks the RUNNING experiment until delivery commits, serializing with stop/adopt. */
    Optional<RunningExperiment> findRunningExperimentForUpdate(String copyKey);

    List<Variant> listVariants(String experimentId);

    Optional<Assignment> findAssignment(String experimentId, long userId);

    boolean isRunningExperiment(String experimentId);

    boolean isEligibleConversionOrder(long userId, String orderNo);

    void insertAssignmentIfAbsent(Assignment assignment);

    boolean markExposedIfFirst(String experimentId, long userId, LocalDateTime exposedAt);

    Optional<CopyBody> findPublishedCopy(String copyKey);

    Optional<CopyBody> findCopyVersion(String copyKey, String version);

    boolean insertConversionIfAbsent(
            String experimentId,
            long userId,
            String conversionKey,
            String variant,
            LocalDateTime convertedAt);

    record UserAudienceProfile(
            long userId,
            String status,
            String language,
            LocalDateTime registeredAt) {
    }

    record RunningExperiment(
            String experimentId,
            String copyKey,
            String audienceSnapshotJson) {
    }

    record Variant(
            String name,
            String copyVersion,
            int splitPct,
            int sortOrder) {
    }

    record Assignment(
            String experimentId,
            long userId,
            String variant,
            String copyVersion,
            int bucket,
            LocalDateTime exposedAt) {
    }

    record CopyBody(
            String copyKey,
            String version,
            String zh,
            String en,
            String vi) {
    }
}
