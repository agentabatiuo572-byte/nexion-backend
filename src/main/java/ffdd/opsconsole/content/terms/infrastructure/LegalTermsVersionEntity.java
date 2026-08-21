package ffdd.opsconsole.content.terms.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_legal_terms_version")
public class LegalTermsVersionEntity extends BaseEntity {
    private String locale;
    private String jurisdiction;
    private String versionLabel;
    private LocalDateTime effectiveAt;
    private String status;
    private String title;
    private String summary;
    private String sectionsJson;
    private Long revision;
    private String lastOperator;
    private LocalDateTime publishedAt;
    private LocalDateTime revokedAt;
}
