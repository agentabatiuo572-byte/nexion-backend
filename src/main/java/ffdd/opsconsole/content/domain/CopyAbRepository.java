package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.CopyCreateRequest;
import ffdd.opsconsole.content.dto.CopyDraftSaveRequest;
import ffdd.opsconsole.content.dto.CopyPositionCreateRequest;
import ffdd.opsconsole.content.dto.CopyVersionPublishRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CopyAbRepository {
    void ensureSeedData(LocalDateTime now);

    List<CopyContentRow> listCopies();

    Optional<CopyContentRow> findCopy(String copyKey);

    List<CopyVersionRow> listVersions(String copyKey);

    Optional<CopyVersionRow> findVersion(String copyKey, String version);

    List<CopyExperimentRow> listExperiments();

    Optional<CopyExperimentRow> findExperiment(String experimentId);

    List<CopyFrameworkParamView> listFrameworkParams();

    void saveDraft(String copyKey, CopyDraftSaveRequest request, LocalDateTime now);

    CopyContentRow publishVersion(String copyKey, CopyVersionPublishRequest request, LocalDateTime now);

    /** 新建文案位:insert nx_content_copy + 一个 PUBLISHED 首版 version 行。 */
    CopyContentRow createCopy(CopyCreateRequest request, LocalDateTime now);

    CopyContentRow rollbackVersion(String copyKey, String version, String operator, LocalDateTime now);

    CopyContentRow archiveCurrent(String copyKey, String operator, LocalDateTime now);

    void updateFrameworkParam(String paramKey, String value, String operator, LocalDateTime now);

    void updateExperimentState(String experimentId, String state, String operator, LocalDateTime now);

    /** 文案位置槽位配置(独立表 nx_content_copy_position)。 */
    List<CopyPositionView> listPositions();

    void createPosition(CopyPositionCreateRequest request, LocalDateTime now);

    void deletePosition(String positionKey, LocalDateTime now);
}
