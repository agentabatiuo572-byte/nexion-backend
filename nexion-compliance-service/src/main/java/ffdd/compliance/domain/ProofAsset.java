package ffdd.compliance.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_proof_asset")
public class ProofAsset extends BaseEntity {
    private Long userId;
    private String proofNo;
    private String proofType;
    private String objectKey;
    private String status;
    private String fileName;
    private String contentType;
    private Long sizeBytes;
    private String checksum;
    private String relatedBizType;
    private String relatedBizNo;
    private String submittedBy;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String rejectReason;
    private String reviewNote;
    private String metadataJson;
}
