package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.CopyExperimentVariantMetric;
import ffdd.opsconsole.content.infrastructure.CopyExperimentVariantEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CopyExperimentVariantMapper extends BaseMapper<CopyExperimentVariantEntity> {
    @Select("""
            SELECT v.variant_name AS name,
                   v.copy_version AS version,
                   COUNT(DISTINCT a.user_id) AS impressions,
                   COUNT(DISTINCT c.user_id) AS conversions
              FROM nx_content_experiment_variant v
              LEFT JOIN nx_content_experiment_assignment a
               ON a.experiment_id = v.experiment_id
               AND a.variant_name = v.variant_name
               AND a.exposed_at IS NOT NULL
              LEFT JOIN nx_content_experiment_conversion c
                ON c.experiment_id = a.experiment_id
               AND c.user_id = a.user_id
               AND c.variant_name = v.variant_name
             WHERE v.experiment_id = #{experimentId}
               AND v.is_deleted = 0
             GROUP BY v.id, v.variant_name, v.copy_version, v.sort_order
             ORDER BY v.sort_order, v.id
            """)
    List<CopyExperimentVariantMetric> listRuntimeMetrics(@Param("experimentId") String experimentId);
}
