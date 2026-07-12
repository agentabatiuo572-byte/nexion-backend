package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.DisclosureJurisdictionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DisclosureJurisdictionMapper extends BaseMapper<DisclosureJurisdictionEntity> {
    @Select("""
            SELECT * FROM nx_disclosure_jurisdiction
             WHERE jurisdiction_code = UPPER(#{jurisdiction}) AND is_deleted = 0
             LIMIT 1 FOR UPDATE
            """)
    DisclosureJurisdictionEntity selectForUpdate(@Param("jurisdiction") String jurisdiction);
}
