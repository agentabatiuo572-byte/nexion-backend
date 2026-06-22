package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.CopyDraftSaveRequest;
import ffdd.opsconsole.content.dto.CopyVersionPublishRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CopyAbRepository {
    List<CopyContentRow> listCopies();

    Optional<CopyContentRow> findCopy(String copyKey);

    List<CopyVersionRow> listVersions(String copyKey);

    Optional<CopyVersionRow> findVersion(String copyKey, String version);

    List<CopyExperimentRow> listExperiments();

    Optional<CopyExperimentRow> findExperiment(String experimentId);

    List<CopyFrameworkParamView> listFrameworkParams();

    void saveDraft(String copyKey, CopyDraftSaveRequest request, LocalDateTime now);

    CopyContentRow publishVersion(String copyKey, CopyVersionPublishRequest request, LocalDateTime now);

    CopyContentRow rollbackVersion(String copyKey, String version, String operator, LocalDateTime now);

    CopyContentRow archiveCurrent(String copyKey, String operator, LocalDateTime now);

    void updateFrameworkParam(String paramKey, String value, String operator, LocalDateTime now);

    void updateExperimentState(String experimentId, String state, String operator, LocalDateTime now);
}
