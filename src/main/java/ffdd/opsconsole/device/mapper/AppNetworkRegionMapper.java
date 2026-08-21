package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppNetworkRegionMapper extends BaseMapper<Object> {
    @Select("""
            SELECT dc.dc_location AS id,
                   dc.region_label AS regionLabel,
                   dc.location,
                   COALESCE(NULLIF(dc.display_name, ''), dc.region_label) AS displayName,
                   COALESCE(dev.activeNodes, 0) AS activeNodes,
                   COALESCE(tasks.activeJobs, 0) AS activeJobs,
                   COALESCE(tasks.jobsPerHour, 0) AS jobsPerHour,
                   geo.latitude,
                   geo.longitude,
                   CASE WHEN mine.userDevices > 0 THEN 1 ELSE 0 END AS userRegion
              FROM nx_compute_datacenter dc
              LEFT JOIN (
                    SELECT d.dc_location,
                           COUNT(*) AS activeNodes
                      FROM nx_user viewer
                      JOIN nx_user_device d ON d.is_deleted = 0
                      JOIN nx_user owner ON owner.id = d.user_id AND owner.is_deleted = 0
                     WHERE viewer.id = #{userId} AND viewer.is_deleted = 0
                       AND viewer.sandbox = 0 AND owner.sandbox = 0
                       AND UPPER(d.ownership_status) = 'OWNED'
                       AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
                       AND d.deactivated_at IS NULL AND d.pending_deactivate = 0
                     GROUP BY d.dc_location
              ) dev ON dev.dc_location = dc.dc_location
              LEFT JOIN (
                    SELECT d.dc_location,
                           SUM(CASE WHEN UPPER(t.status) IN ('ASSIGNED','RUNNING','PROCESSING') THEN 1 ELSE 0 END) AS activeJobs,
                           SUM(CASE WHEN UPPER(t.status) = 'COMPLETED'
                                     AND t.completed_at >= DATE_SUB(NOW(), INTERVAL 1 HOUR) THEN 1 ELSE 0 END) AS jobsPerHour
                      FROM nx_compute_task t
                      JOIN nx_user_device d ON d.id = t.user_device_id AND d.is_deleted = 0
                      JOIN nx_user owner ON owner.id = d.user_id AND owner.is_deleted = 0
                      JOIN nx_user viewer ON viewer.id = #{userId} AND viewer.is_deleted = 0
                     WHERE t.is_deleted = 0 AND viewer.sandbox = 0 AND owner.sandbox = 0
                       AND COALESCE(t.source_environment, 'PRODUCTION') = 'PRODUCTION'
                     GROUP BY d.dc_location
              ) tasks ON tasks.dc_location = dc.dc_location
              LEFT JOIN (
                    SELECT d.dc_location, AVG(r.latitude) AS latitude, AVG(r.longitude) AS longitude
                      FROM nx_user_device d
                      JOIN nx_user owner ON owner.id = d.user_id AND owner.is_deleted = 0
                      JOIN nx_user viewer ON viewer.id = #{userId} AND viewer.is_deleted = 0
                      JOIN nx_user_device_runtime r ON r.user_device_id = d.id AND r.is_deleted = 0
                     WHERE d.is_deleted = 0 AND viewer.sandbox = 0 AND owner.sandbox = 0
                       AND r.latitude BETWEEN -90 AND 90 AND r.longitude BETWEEN -180 AND 180
                     GROUP BY d.dc_location
              ) geo ON geo.dc_location = dc.dc_location
              LEFT JOIN (
                    SELECT dc_location, COUNT(*) AS userDevices
                      FROM nx_user_device d
                      JOIN nx_user viewer ON viewer.id = #{userId}
                        AND viewer.status = 'ACTIVE' AND viewer.is_deleted = 0 AND viewer.sandbox = 0
                     WHERE d.user_id = #{userId} AND d.is_deleted = 0 AND viewer.sandbox = 0
                       AND UPPER(d.ownership_status) = 'OWNED'
                       AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
                     GROUP BY d.dc_location
              ) mine ON mine.dc_location = dc.dc_location
             WHERE dc.is_deleted = 0 AND LOWER(dc.status) = 'active'
             ORDER BY dc.sort_order, dc.dc_location
            """)
    List<RegionRow> regions(@Param("userId") Long userId);

    @Select("SELECT sandbox FROM nx_user WHERE id = #{userId} AND status = 'ACTIVE' AND is_deleted = 0 LIMIT 1")
    UserScope userScope(@Param("userId") Long userId);

    record UserScope(Integer sandbox) { }
    record RegionRow(String id, String regionLabel, String location, String displayName,
                     Long activeNodes, Long activeJobs, Long jobsPerHour,
                     Double latitude, Double longitude, Integer userRegion) { }
}
