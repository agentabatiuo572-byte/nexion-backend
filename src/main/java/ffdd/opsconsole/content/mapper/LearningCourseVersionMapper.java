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
}
