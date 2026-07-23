package ffdd.opsconsole.overview.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper") // Read-only account-age aggregation has no safe single-entity CRUD surface.
public interface OpsPhaseMapper {
    @Select("""
            SELECT TIMESTAMPDIFF(MONTH, created_at, NOW()) AS monthAge,
                   COUNT(*) AS userCount
              FROM nx_user
             WHERE is_deleted = 0
             GROUP BY TIMESTAMPDIFF(MONTH, created_at, NOW())
             ORDER BY monthAge
            """)
    List<Map<String, Object>> selectAccountAgeMonthBuckets();
}
