package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.LearningCourseVersionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface LearningCourseVersionMapper extends BaseMapper<LearningCourseVersionEntity> {
    @Select("""
            SELECT *
              FROM nx_learning_course_version
             WHERE course_id = #{courseId}
               AND is_deleted = 0
             ORDER BY id
             FOR UPDATE
            """)
    List<LearningCourseVersionEntity> lockCourseVersions(@Param("courseId") String courseId);

    /** The unique key still reserves soft-deleted labels, so next-version reads must include them. */
    @Select("""
            SELECT version_label
              FROM nx_learning_course_version
             WHERE course_id = #{courseId}
            """)
    List<String> listAllVersionLabels(@Param("courseId") String courseId);
}
