package ffdd.opsconsole.device.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SimulatedTaskPolicyMapper {
    String TYPE_COLUMNS="task_type_id taskTypeId,version,effective_at effectiveAt,name,task_class taskClass,"
            +"duration_seconds durationSeconds,base_reward baseReward,asset,enabled";
    String PRODUCT_COLUMNS="product_key productKey,version,effective_at effectiveAt,allowed_task_type_ids allowedTaskTypeIdsJson";

    @Select("SELECT "+TYPE_COLUMNS+" FROM nx_sim_task_type_version WHERE task_type_id=#{id} AND effective_at<=#{at} ORDER BY effective_at DESC,version DESC LIMIT 1")
    TypeVersion typeAt(@Param("id")String id,@Param("at")LocalDateTime at);
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
    @Insert("INSERT INTO nx_sim_product_task_policy_version(product_key,version,effective_at,allowed_task_type_ids) VALUES(#{productKey},#{version},#{effectiveAt},#{allowedTaskTypeIdsJson})")
    int insertProduct(ProductVersion version);
    @Select("SELECT allowed_task_type_ids_json FROM nx_admin_device_sku WHERE sku_id=#{key}")
    String draftTypes(String key);
    @Update("UPDATE nx_admin_device_sku SET allowed_task_type_ids_json=#{json} WHERE sku_id=#{key}")
    int updateDraft(@Param("key")String key,@Param("json")String json);

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
    @Select("SELECT MIN(effective_at) FROM nx_sim_e3_version WHERE effective_at>#{after} AND effective_at<=#{notAfter}")
    LocalDateTime nextE3(@Param("after")LocalDateTime after,@Param("notAfter")LocalDateTime notAfter);

    record TypeVersion(String taskTypeId,long version,LocalDateTime effectiveAt,String name,String taskClass,
            int durationSeconds,BigDecimal baseReward,String asset,boolean enabled) { }
    record ProductVersion(String productKey,long version,LocalDateTime effectiveAt,String allowedTaskTypeIdsJson) { }
    record E3Version(long version,LocalDateTime effectiveAt,String configJson) { }
}
