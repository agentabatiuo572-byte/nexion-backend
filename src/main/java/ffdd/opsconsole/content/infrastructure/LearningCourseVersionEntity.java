package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_learning_course_version")
public class LearningCourseVersionEntity extends BaseEntity {
    private String courseId;
    private String versionLabel;
    private String status;
    private String payloadJson;
    private Long revision;
}
