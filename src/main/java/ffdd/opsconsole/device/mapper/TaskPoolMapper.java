package ffdd.opsconsole.device.mapper;

import ffdd.opsconsole.device.mapper.SimulatedTaskPolicyMapper.TypeVersion;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TaskPoolMapper {
    record Pool(String code,String kind,Integer level,String name,int sortOrder) { }
    record Reference(String productKey,String productName,String poolsJson,String state) { }
    record DeviceCount(String productKey,long count) { }
    @Select("SELECT code,kind,level,name,sort_order sortOrder FROM nx_sim_task_pool ORDER BY sort_order,code")
    List<Pool> pools();
    @Select("SELECT code,kind,level,name,sort_order sortOrder FROM nx_sim_task_pool WHERE code=#{code} FOR UPDATE")
    Pool lock(String code);
    @Select("SELECT "+SimulatedTaskPolicyMapper.TYPE_COLUMNS+" FROM (SELECT t.*,ROW_NUMBER() OVER(PARTITION BY task_type_id ORDER BY effective_at DESC,version DESC) version_rank FROM nx_sim_task_type_version t WHERE effective_at<=#{at}) ranked WHERE version_rank=1 AND pool_code IS NOT NULL ORDER BY task_type_id")
    List<TypeVersion> types(LocalDateTime at);
    @Select("""
        SELECT s.sku_id COLLATE utf8mb4_unicode_ci productKey,s.name COLLATE utf8mb4_unicode_ci productName,
          s.allowed_pool_codes_json poolsJson,'DRAFT' state FROM nx_admin_device_sku s
          WHERE s.is_deleted=0 AND s.status<>'on' AND s.allowed_pool_codes_json IS NOT NULL
        UNION ALL
        SELECT v.product_key productKey,COALESCE(s.name,p.name,v.product_key) COLLATE utf8mb4_unicode_ci productName,
          v.allowed_pool_codes poolsJson,
          CASE WHEN s.status='on' AND s.is_deleted=0 THEN 'PUBLISHED' ELSE 'DELISTED_WITH_DEVICES' END state
          FROM nx_sim_product_task_policy_version v
          LEFT JOIN nx_admin_device_sku s ON s.sku_id=v.product_key COLLATE utf8mb4_0900_ai_ci
          LEFT JOIN nx_product p ON p.product_no=v.product_key COLLATE utf8mb4_0900_ai_ci
          WHERE v.product_key<>'phone' AND v.effective_at<=#{at}
            AND NOT EXISTS(SELECT 1 FROM nx_sim_product_task_policy_version n WHERE n.product_key=v.product_key
              AND n.effective_at<=#{at} AND (n.effective_at>v.effective_at OR n.effective_at=v.effective_at AND n.version>v.version))
            AND ((s.status='on' AND s.is_deleted=0) OR EXISTS(SELECT 1 FROM nx_user_device d
              WHERE d.product_code=v.product_key COLLATE utf8mb4_0900_ai_ci AND d.is_deleted=0 AND d.ownership_status='OWNED'
                AND d.source_environment='PRODUCTION' AND d.run_id=''))
        """)
    List<Reference> references(LocalDateTime at);
    @Select("""
        SELECT product_code productKey,COUNT(*) count FROM nx_user_device WHERE is_deleted=0
          AND ownership_status='OWNED' AND source_environment='PRODUCTION' AND run_id=''
          AND UPPER(device_type) NOT IN ('PHONE','MOBILE') AND activated_at IS NOT NULL AND deactivated_at IS NULL
          AND status IN ('ACTIVE','ONLINE','BUSY','RUNNING') GROUP BY product_code
        """)
    List<DeviceCount> deviceCounts();
    @Select("SELECT DISTINCT TRIM(datacenter) FROM nx_admin_device_sku WHERE is_deleted=0 AND NULLIF(TRIM(datacenter),'') IS NOT NULL ORDER BY TRIM(datacenter)")
    List<String> datacenterLabels();
}
