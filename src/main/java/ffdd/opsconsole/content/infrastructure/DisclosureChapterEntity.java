package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_disclosure_chapter")
public class DisclosureChapterEntity extends BaseEntity {
    private String jurisdictionCode;
    private String versionLabel;
    private String chapterNo;
    private String zhTitle;
    private String enTitle;
    private String zhBody;
    private String enBody;
    private Integer sortOrder;
    private String lastOperator;
}
