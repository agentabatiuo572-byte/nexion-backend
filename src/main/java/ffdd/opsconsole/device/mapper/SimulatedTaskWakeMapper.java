package ffdd.opsconsole.device.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SimulatedTaskWakeMapper {
    @Select("SELECT user_device_id FROM nx_simulated_device_runtime WHERE product_code=#{product} AND state='WAITING_CONFIG' AND user_device_id>#{after} ORDER BY user_device_id LIMIT 500")
    List<Long> waiting(@Param("product")String product,@Param("after")long after);
    @Update("<script>UPDATE nx_simulated_device_runtime SET next_action_at=LEAST(COALESCE(next_action_at,#{at}),#{at}) WHERE user_device_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> AND state='WAITING_CONFIG' ORDER BY user_device_id</script>")
    int wake(@Param("ids")List<Long> ids,@Param("at")LocalDateTime at);
    @Select("<script>SELECT p.product_key FROM nx_sim_product_task_policy_version p WHERE NOT EXISTS(SELECT 1 FROM nx_sim_product_task_policy_version n WHERE n.product_key=p.product_key AND n.version>p.version) AND JSON_OVERLAPS(p.allowed_pool_codes,CAST(#{pools} AS JSON)) ORDER BY p.product_key</script>")
    List<String> products(String pools);
    @Update("""
        UPDATE nx_simulated_device_runtime SET stop_at=LEAST(COALESCE(stop_at,#{at}),#{at}),
          last_error='DEVICE_PAUSED',next_action_at=#{at},state='CLOSING'
        WHERE user_id=#{user} AND (#{device} IS NULL OR user_device_id=#{device})
          AND state NOT IN ('STOPPED','EXPIRED') ORDER BY user_device_id
        """)
    int pause(@Param("user")long user,@Param("device")Long device,@Param("at")LocalDateTime at);
    @Update("""
        UPDATE nx_simulated_device_runtime SET next_action_at=#{at}
        WHERE user_id=#{user} AND (#{device} IS NULL OR user_device_id=#{device})
          AND state NOT IN ('STOPPED','EXPIRED') ORDER BY user_device_id
        """)
    int resume(@Param("user")long user,@Param("device")Long device,@Param("at")LocalDateTime at);
}
