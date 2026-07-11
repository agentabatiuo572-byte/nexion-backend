package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.CopyCreateRequest;
import ffdd.opsconsole.content.dto.CopyDraftSaveRequest;
import ffdd.opsconsole.content.dto.CopyPositionCreateRequest;
import ffdd.opsconsole.content.dto.CopyPositionUpdateRequest;
import ffdd.opsconsole.content.dto.CopyVersionPublishRequest;
import ffdd.opsconsole.content.dto.CopyVersionOptionCreateRequest;
import ffdd.opsconsole.content.dto.CopyVersionOptionUpdateRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CopyAbRepository {
    void ensureSeedData(LocalDateTime now);

    List<CopyContentRow> listCopies();

    Optional<CopyContentRow> findCopy(String copyKey);

    /** Locks the copy row for a version-changing transaction. */
    default Optional<CopyContentRow> findCopyForUpdate(String copyKey) {
        return findCopy(copyKey);
    }

    List<CopyVersionRow> listVersions(String copyKey);

    Optional<CopyVersionRow> findVersion(String copyKey, String version);

    /** Includes soft-deleted rows so automatically assigned version numbers are never reused. */
    List<String> listAllVersionNumbers(String copyKey);

    List<CopyExperimentRow> listExperiments();

    Optional<CopyExperimentRow> findExperiment(String experimentId);

    /** True when an experiment references the version, or contains an unversioned legacy variant. */
    boolean isVersionReferencedByExperiment(String copyKey, String version);

    List<CopyFrameworkParamView> listFrameworkParams();

    List<CopyVersionOptionView> listVersionOptions();

    Optional<CopyVersionOptionView> findVersionOption(String versionKey);

    default Optional<CopyVersionOptionView> findVersionOptionForUpdate(String versionKey) {
        return findVersionOption(versionKey);
    }

    CopyVersionOptionView createVersionOption(CopyVersionOptionCreateRequest request, LocalDateTime now);

    CopyVersionOptionView updateVersionOption(String versionKey, CopyVersionOptionUpdateRequest request, LocalDateTime now);

    void deleteVersionOption(String versionKey, String operator, LocalDateTime now);

    /** Includes deleted version-history tombstones: a catalog key can never be removed once used. */
    boolean isVersionOptionReferenced(String versionKey);

    void saveDraft(String copyKey, CopyDraftSaveRequest request, LocalDateTime now);

    CopyContentRow publishVersion(String copyKey, CopyVersionPublishRequest request, LocalDateTime now);

    /** 新建文案位:insert nx_content_copy + 一个 PUBLISHED 首版 version 行。 */
    CopyContentRow createCopy(CopyCreateRequest request, LocalDateTime now);

    CopyContentRow rollbackVersion(String copyKey, String version, String operator, LocalDateTime now);

    CopyContentRow archiveCurrent(String copyKey, String operator, LocalDateTime now);

    CopyContentRow deleteDraftVersion(String copyKey, String version, String operator, LocalDateTime now);

    void updateFrameworkParam(String paramKey, String value, String operator, LocalDateTime now);

    void updateExperimentState(String experimentId, String state, String operator, LocalDateTime now);

    /** 文案位置槽位配置(独立表 nx_content_copy_position)。 */
    List<CopyPositionView> listPositions();

    Optional<CopyPositionView> findPosition(String positionKey);

    default Optional<CopyPositionView> findPositionForUpdate(String positionKey) {
        return findPosition(positionKey);
    }

    CopyPositionView createPosition(CopyPositionCreateRequest request, LocalDateTime now);

    CopyPositionView updatePosition(String positionKey, CopyPositionUpdateRequest request, LocalDateTime now);

    void deletePosition(String positionKey, LocalDateTime now);

    boolean isPositionReferenced(String positionKey);
}
