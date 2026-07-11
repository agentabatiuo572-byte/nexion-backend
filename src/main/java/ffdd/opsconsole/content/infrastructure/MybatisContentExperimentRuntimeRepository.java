package ffdd.opsconsole.content.infrastructure;

import ffdd.opsconsole.content.domain.ContentExperimentRuntimeRepository;
import ffdd.opsconsole.content.mapper.ContentExperimentRuntimeMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisContentExperimentRuntimeRepository implements ContentExperimentRuntimeRepository {
    private final ContentExperimentRuntimeMapper mapper;

    @Override
    public Optional<UserAudienceProfile> findUserAudienceProfile(long userId) {
        var row = mapper.findUserAudienceProfile(userId);
        return row == null ? Optional.empty() : Optional.of(new UserAudienceProfile(
                value(row.getUserId()), row.getStatus(), row.getLanguage(), row.getRegisteredAt()));
    }

    @Override
    public Optional<RunningExperiment> findRunningExperiment(String copyKey) {
        var row = mapper.findRunningExperiment(copyKey);
        return row == null ? Optional.empty() : Optional.of(new RunningExperiment(
                row.getExperimentId(), row.getCopyKey(), row.getAudienceSnapshotJson()));
    }

    @Override
    public Optional<RunningExperiment> findRunningExperimentForUpdate(String copyKey) {
        var row = mapper.findRunningExperimentForUpdate(copyKey);
        return row == null ? Optional.empty() : Optional.of(new RunningExperiment(
                row.getExperimentId(), row.getCopyKey(), row.getAudienceSnapshotJson()));
    }

    @Override
    public List<Variant> listVariants(String experimentId) {
        List<ContentExperimentRuntimeRows.VariantRow> rows = mapper.listVariants(experimentId);
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(row -> new Variant(
                        row.getVariantName(), row.getCopyVersion(), value(row.getSplitPct()), value(row.getSortOrder())))
                .toList();
    }

    @Override
    public Optional<Assignment> findAssignment(String experimentId, long userId) {
        var row = mapper.findAssignment(experimentId, userId);
        return row == null ? Optional.empty() : Optional.of(new Assignment(
                row.getExperimentId(), value(row.getUserId()), row.getVariantName(), row.getCopyVersion(),
                value(row.getBucketNo()), row.getExposedAt()));
    }

    @Override
    public boolean isRunningExperiment(String experimentId) {
        return mapper.countRunningExperiment(experimentId) == 1L;
    }

    @Override
    public boolean isEligibleConversionOrder(long userId, String orderNo) {
        return mapper.countEligibleConversionOrder(userId, orderNo) == 1L;
    }

    @Override
    public void insertAssignmentIfAbsent(Assignment assignment) {
        mapper.insertAssignmentIfAbsent(
                assignment.experimentId(), assignment.userId(), assignment.variant(), assignment.copyVersion(),
                assignment.bucket(), LocalDateTime.now());
    }

    @Override
    public boolean markExposedIfFirst(String experimentId, long userId, LocalDateTime exposedAt) {
        return mapper.markExposedIfFirst(experimentId, userId, exposedAt) == 1;
    }

    @Override
    public Optional<CopyBody> findPublishedCopy(String copyKey) {
        return copy(mapper.findPublishedCopy(copyKey));
    }

    @Override
    public Optional<CopyBody> findCopyVersion(String copyKey, String version) {
        return copy(mapper.findCopyVersion(copyKey, version));
    }

    @Override
    public boolean insertConversionIfAbsent(
            String experimentId,
            long userId,
            String conversionKey,
            String variant,
            LocalDateTime convertedAt) {
        return mapper.insertConversionIfAbsent(experimentId, userId, conversionKey, variant, convertedAt) == 1;
    }

    private Optional<CopyBody> copy(ContentExperimentRuntimeRows.CopyBodyRow row) {
        return row == null ? Optional.empty() : Optional.of(new CopyBody(
                row.getCopyKey(), row.getVersion(), row.getZhText(), row.getEnText(), row.getViText()));
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }
}
