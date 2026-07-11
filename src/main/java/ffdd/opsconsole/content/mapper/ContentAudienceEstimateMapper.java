package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ContentAudienceEstimateMapper extends BaseMapper<UserEntity> {
    @Select("""
            <script>
            SELECT COUNT(*)
             FROM nx_user
             WHERE is_deleted = 0
               AND UPPER(COALESCE(status, 'ACTIVE')) = 'ACTIVE'
             <if test='locales != null and locales.size() > 0'>
               AND LOWER(SUBSTRING_INDEX(REPLACE(COALESCE(language, ''), '_', '-'), '-', 1)) IN
               <foreach collection='locales' item='locale' open='(' separator=',' close=')'>
                 #{locale}
               </foreach>
             </if>
             <if test='registrationDaysMin != null'>
               AND created_at &lt;= DATE_SUB(NOW(), INTERVAL #{registrationDaysMin} DAY)
             </if>
             <if test='registrationDaysMax != null'>
               AND created_at &gt;= DATE_SUB(NOW(), INTERVAL #{registrationDaysMax} DAY)
             </if>
            </script>
            """)
    long countEstimatedAudience(
            @Param("locales") List<String> locales,
            @Param("registrationDaysMin") Integer registrationDaysMin,
            @Param("registrationDaysMax") Integer registrationDaysMax);
}
