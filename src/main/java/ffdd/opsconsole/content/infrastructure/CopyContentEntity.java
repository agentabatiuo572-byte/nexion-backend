package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_content_copy")
public class CopyContentEntity extends BaseEntity {
    private String copyKey;
    private String description;
    private String surface;
    private String currentVersion;
    private String status;
    private String i18nKey;
    private String experimentId;
    private String lastChange;
    private String draftVersion;
    private String draftZh;
    private String draftEn;
    private String draftVi;
    private String copyPosition;
    private String draftCopyPosition;
    private String draftSurface;
    private String draftAudience;
    private String draftAudienceJson;
    private String draftTrafficSplit;
    private String draftNote;
    private Long revision;
    private String lastOperator;
}
