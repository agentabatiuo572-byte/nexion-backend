package ffdd.opsconsole.user.infrastructure;

import ffdd.opsconsole.user.facade.UserWeeklyRegionFacade.RegisteredCallingCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Narrow locked account projection; null sandbox retains the existing production convention. */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface UserWeeklyRegionMapper {
    @Select("""
            SELECT id userId,country_code countryCode,COALESCE(sandbox,0) sandbox
              FROM nx_user
             WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0
               AND COALESCE(sandbox,0)=#{sandbox} FOR UPDATE
            """)
    RegisteredCallingCode lockActive(@Param("userId") Long userId, @Param("sandbox") boolean sandbox);
}
