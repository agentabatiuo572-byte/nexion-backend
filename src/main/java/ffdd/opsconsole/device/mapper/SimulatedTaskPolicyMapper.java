package ffdd.opsconsole.device.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SimulatedTaskPolicyMapper {
    @Select("SELECT epoch FROM nx_sim_policy_epoch WHERE id=1")
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    long epoch();
    @Select("SELECT (SELECT COUNT(*) FROM nx_sim_task_type_version)+(SELECT COUNT(*) FROM nx_sim_product_task_policy_version)+(SELECT COUNT(*) FROM nx_sim_e3_version)")
    long historySize();
    @Select("SELECT COALESCE((SELECT SUM(OCTET_LENGTH(name)+OCTET_LENGTH(task_type_id)+OCTET_LENGTH(task_class)+128) FROM nx_sim_task_type_version),0)+COALESCE((SELECT SUM(OCTET_LENGTH(product_key)+OCTET_LENGTH(allowed_task_type_ids)+COALESCE(OCTET_LENGTH(allowed_task_classes),0)+128) FROM nx_sim_product_task_policy_version),0)+COALESCE((SELECT SUM(OCTET_LENGTH(config_json)+64) FROM nx_sim_e3_version),0)")
    long historyPayloadBytes();
    @Select("SELECT "+TYPE_COLUMNS+" FROM nx_sim_task_type_version ORDER BY task_type_id,effective_at,version LIMIT 20001")
    List<TypeVersion> typeHistory();
    @Select("SELECT "+PRODUCT_COLUMNS+" FROM nx_sim_product_task_policy_version ORDER BY product_key,effective_at,version LIMIT 20001")
    List<ProductVersion> productHistory();
    @Select("SELECT version,effective_at effectiveAt,config_json configJson FROM nx_sim_e3_version ORDER BY effective_at,version LIMIT 20001")
    List<E3Version> e3History();

    String TYPE_COLUMNS="task_type_id taskTypeId,version,effective_at effectiveAt,name,task_class taskClass,"
            +"duration_seconds durationSeconds,base_reward baseReward,CASE asset WHEN 'NEX' THEN 'UVT' ELSE asset END asset,enabled";
    String PRODUCT_COLUMNS="product_key productKey,version,effective_at effectiveAt,allowed_task_type_ids allowedTaskTypeIdsJson,"
            +"allowed_task_classes allowedTaskClassesJson,monthly_target_usdt monthlyTargetUsdt,monthly_target_band_pct monthlyTargetBandPct";

    @Select("SELECT "+TYPE_COLUMNS+" FROM nx_sim_task_type_version WHERE task_type_id=#{id} AND effective_at<=#{at} ORDER BY effective_at DESC,version DESC LIMIT 1")
    TypeVersion typeAt(@Param("id")String id,@Param("at")LocalDateTime at);
    @Select("<script>SELECT "+TYPE_COLUMNS+" FROM (SELECT t.*, ROW_NUMBER() OVER (PARTITION BY task_type_id "
            +"ORDER BY effective_at DESC,version DESC) AS version_rank FROM nx_sim_task_type_version t "
            +"WHERE effective_at&lt;=#{at} AND task_type_id IN "
            +"<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            +") ranked WHERE version_rank=1</script>")
    List<TypeVersion> typesAt(@Param("ids")List<String> ids,@Param("at")LocalDateTime at);
    // Rank before filtering by class so a later reclassification cannot revive an older version.
    @Select("<script>SELECT "+TYPE_COLUMNS+" FROM (SELECT t.*, ROW_NUMBER() OVER (PARTITION BY task_type_id "
            +"ORDER BY effective_at DESC,version DESC) AS version_rank FROM nx_sim_task_type_version t "
            +"WHERE effective_at&lt;=#{at}) ranked WHERE version_rank=1 AND task_class IN "
            +"<foreach collection='classes' item='c' open='(' separator=',' close=')'>#{c}</foreach> ORDER BY task_type_id</script>")
    List<TypeVersion> typesInClassesAt(@Param("classes")List<String> classes,@Param("at")LocalDateTime at);
    @Select("SELECT "+TYPE_COLUMNS+" FROM nx_sim_task_type_version WHERE task_type_id=#{id} ORDER BY version DESC LIMIT 1 FOR UPDATE")
    TypeVersion lockType(String id);
    @Select("SELECT "+TYPE_COLUMNS+" FROM nx_sim_task_type_version t WHERE NOT EXISTS(SELECT 1 FROM nx_sim_task_type_version n WHERE n.task_type_id=t.task_type_id AND n.version>t.version) ORDER BY t.task_type_id")
    List<TypeVersion> currentTypes();
    @Insert("INSERT INTO nx_sim_task_type_version(task_type_id,version,effective_at,name,task_class,duration_seconds,base_reward,asset,enabled) VALUES(#{taskTypeId},#{version},#{effectiveAt},#{name},#{taskClass},#{durationSeconds},#{baseReward},#{asset},#{enabled})")
    int insertType(TypeVersion version);

    @Select("SELECT "+PRODUCT_COLUMNS+" FROM nx_sim_product_task_policy_version WHERE product_key=#{key} AND effective_at<=#{at} ORDER BY effective_at DESC,version DESC LIMIT 1")
    ProductVersion productAt(@Param("key")String key,@Param("at")LocalDateTime at);
    @Select("SELECT "+PRODUCT_COLUMNS+" FROM nx_sim_product_task_policy_version WHERE product_key=#{key} ORDER BY version DESC LIMIT 1 FOR UPDATE")
    ProductVersion lockProduct(String key);
    @Insert("INSERT INTO nx_sim_product_task_policy_version(product_key,version,effective_at,allowed_task_type_ids,allowed_task_classes,monthly_target_usdt,monthly_target_band_pct) VALUES(#{productKey},#{version},#{effectiveAt},#{allowedTaskTypeIdsJson},#{allowedTaskClassesJson},#{monthlyTargetUsdt},#{monthlyTargetBandPct})")
    int insertProduct(ProductVersion version);
    @Select("SELECT allowed_task_type_ids_json FROM nx_admin_device_sku WHERE sku_id=#{key}")
    String draftTypes(String key);
    @Select("SELECT allowed_task_classes_json FROM nx_admin_device_sku WHERE sku_id=#{key}")
    String draftClasses(String key);
    @Update("UPDATE nx_admin_device_sku SET allowed_task_type_ids_json=#{ids},allowed_task_classes_json=#{classes} WHERE sku_id=#{key}")
    int updateDraft(@Param("key")String key,@Param("ids")String ids,@Param("classes")String classes);

    record TargetConfig(String productKey, BigDecimal monthlyTargetUsdt, BigDecimal monthlyTargetBandPct) { }
    @Select("SELECT product_no productKey,monthly_target_usdt monthlyTargetUsdt,monthly_target_band_pct monthlyTargetBandPct FROM nx_product WHERE product_no=#{key} AND is_deleted=0")
    TargetConfig targetConfig(String key);
    @Select("""
            SELECT product_no productKey,monthly_target_usdt monthlyTargetUsdt,monthly_target_band_pct monthlyTargetBandPct
              FROM nx_product WHERE monthly_target_usdt IS NOT NULL AND is_deleted=0
            UNION
            SELECT v.product_key COLLATE utf8mb4_0900_ai_ci productKey,v.monthly_target_usdt monthlyTargetUsdt,v.monthly_target_band_pct monthlyTargetBandPct
              FROM nx_sim_product_task_policy_version v WHERE v.monthly_target_usdt IS NOT NULL
                AND NOT EXISTS(SELECT 1 FROM nx_sim_product_task_policy_version n WHERE n.product_key=v.product_key AND n.version>v.version)
                AND EXISTS(SELECT 1 FROM nx_user_device d WHERE d.product_code=v.product_key COLLATE utf8mb4_0900_ai_ci AND d.is_deleted=0
                  AND d.ownership_status='OWNED' AND d.source_environment='PRODUCTION' AND d.run_id='')
            ORDER BY productKey
            """)
    List<TargetConfig> targetProducts();

    @Select("SELECT version,effective_at effectiveAt,config_json configJson FROM nx_sim_e3_version WHERE effective_at<=#{at} ORDER BY effective_at DESC,version DESC LIMIT 1")
    E3Version e3At(LocalDateTime at);
    @Select("SELECT version,effective_at effectiveAt,config_json configJson FROM nx_sim_e3_version ORDER BY version DESC LIMIT 1 FOR UPDATE")
    E3Version lockE3Version();
    @Select("SELECT config_key FROM nx_compute_e3_config WHERE is_deleted=0 ORDER BY id FOR UPDATE")
    List<String> lockE3Configuration();
    @Insert("INSERT INTO nx_sim_e3_version(version,effective_at,config_json) VALUES(#{version},#{effectiveAt},#{configJson})")
    int insertE3(E3Version version);

    @Select("SELECT MIN(effective_at) FROM nx_sim_product_task_policy_version WHERE product_key=#{key} AND effective_at>#{after} AND effective_at<=#{notAfter}")
    LocalDateTime nextProduct(@Param("key")String key,@Param("after")LocalDateTime after,@Param("notAfter")LocalDateTime notAfter);
    @Select("<script>SELECT MIN(effective_at) FROM nx_sim_task_type_version WHERE task_type_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> AND effective_at&gt;#{after} AND effective_at&lt;=#{notAfter}</script>")
    LocalDateTime nextType(@Param("ids")List<String> ids,@Param("after")LocalDateTime after,@Param("notAfter")LocalDateTime notAfter);
    @Select("<script>SELECT MIN(effective_at) FROM nx_sim_task_type_version WHERE task_class IN <foreach collection='classes' item='c' open='(' separator=',' close=')'>#{c}</foreach> AND effective_at&gt;#{after} AND effective_at&lt;=#{notAfter}</script>")
    LocalDateTime nextTypeInClasses(@Param("classes")List<String> classes,@Param("after")LocalDateTime after,@Param("notAfter")LocalDateTime notAfter);
    @Select("SELECT MIN(effective_at) FROM nx_sim_e3_version WHERE effective_at>#{after} AND effective_at<=#{notAfter}")
    LocalDateTime nextE3(@Param("after")LocalDateTime after,@Param("notAfter")LocalDateTime notAfter);

    record TypeVersion(String taskTypeId,long version,LocalDateTime effectiveAt,String name,String taskClass,
            int durationSeconds,BigDecimal baseReward,String asset,boolean enabled) { }
    record ProductVersion(String productKey,long version,LocalDateTime effectiveAt,String allowedTaskTypeIdsJson,String allowedTaskClassesJson, BigDecimal monthlyTargetUsdt, BigDecimal monthlyTargetBandPct) { }
    record E3Version(long version,LocalDateTime effectiveAt,String configJson) { }
}
