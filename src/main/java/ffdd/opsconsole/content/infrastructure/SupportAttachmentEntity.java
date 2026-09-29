package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** UUID key and retained history deliberately do not use BaseEntity's auto-ID/soft-delete fields. */
@Data
@TableName("nx_support_attachment")
public class SupportAttachmentEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long customerId;
    private String uploaderType;
    private Long uploaderId;
    private Long assignmentId;
    private String clientUploadId;
    private String requestHash;
    private String mime;
    private Long bytes;
    private Integer width;
    private Integer height;
    private String objectKey;
    private String state;
    private LocalDateTime expiresAt;
    private Long messageId;
    private LocalDateTime createdAt;
}
