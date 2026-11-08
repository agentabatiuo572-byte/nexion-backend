package ffdd.opsconsole.device.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DeviceMonthlyTargetMapper {
    record Row(long deviceId, LocalDateTime cycleStart, LocalDateTime cycleEnd, int cycleIndex,
            long userId, String instanceNo, String algorithm, BigDecimal u, String weightsJson,
            BigDecimal p, BigDecimal lo, BigDecimal hi, BigDecimal allocatedUsdt, BigDecimal creditedUsdt,
            long firstProductVersion, long firstE3Version, BigDecimal firstMonthlyTargetUsdt,
            BigDecimal firstMonthlyTargetBandPct, BigDecimal firstCapacityPct, boolean configChanged) { }
    record Key(long deviceId, LocalDateTime cycleStart) { }
    String COLUMNS = "user_device_id deviceId,cycle_start cycleStart,cycle_end cycleEnd,cycle_index cycleIndex,"
            + "user_id userId,instance_no instanceNo,algorithm,u,weights_json weightsJson,p,lo,hi,allocated_usdt allocatedUsdt,"
            + "credited_usdt creditedUsdt,first_product_version firstProductVersion,first_e3_version firstE3Version,"
            + "first_monthly_target_usdt firstMonthlyTargetUsdt,first_monthly_target_band_pct firstMonthlyTargetBandPct,"
            + "first_capacity_pct firstCapacityPct,config_changed configChanged";
    record Current(long deviceId,long userId,String instanceNo,String productCode,String deviceType,
            LocalDateTime purchasedAt,LocalDateTime activatedAt,BigDecimal dailyUsdt,
            BigDecimal monthlyTargetUsdt,BigDecimal monthlyTargetBandPct,LocalDateTime targetEffectiveAt) { }
    @Select("<script>"+"""
            SELECT d.id deviceId,d.user_id userId,d.instance_no instanceNo,d.product_code productCode,d.device_type deviceType,
              d.purchased_at purchasedAt,d.activated_at activatedAt,d.daily_usdt dailyUsdt,
              p.monthly_target_usdt monthlyTargetUsdt,p.monthly_target_band_pct monthlyTargetBandPct,
              (SELECT v.effective_at FROM nx_sim_product_task_policy_version v WHERE v.product_key=d.product_code COLLATE utf8mb4_unicode_ci
                ORDER BY v.version DESC LIMIT 1) targetEffectiveAt
            FROM nx_user_device d LEFT JOIN nx_product p ON p.product_no=d.product_code
            WHERE d.id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            """+"</script>")
    List<Current> current(@Param("ids")List<Long> ids);
    @Select("SELECT COALESCE(MAX(version),0) FROM nx_sim_e3_version")
    long currentE3Version();
    @Select("SELECT " + COLUMNS + " FROM nx_sim_device_target_cycle WHERE user_device_id=#{deviceId} AND cycle_start=#{cycleStart} FOR UPDATE")
    Row lock(@Param("deviceId") long deviceId,@Param("cycleStart") LocalDateTime cycleStart);
    @Select("<script>SELECT " + COLUMNS + " FROM nx_sim_device_target_cycle WHERE (user_device_id,cycle_start) IN "
            + "<foreach collection='keys' item='k' open='(' separator=',' close=')'>(#{k.deviceId},#{k.cycleStart})</foreach></script>")
    List<Row> batch(@Param("keys") List<Key> keys);
    @Insert("""
            INSERT INTO nx_sim_device_target_cycle(user_device_id,cycle_start,cycle_end,cycle_index,user_id,instance_no,
              algorithm,u,weights_json,first_product_version,first_e3_version,first_monthly_target_usdt,first_monthly_target_band_pct,first_capacity_pct)
            VALUES(#{deviceId},#{cycleStart},#{cycleEnd},#{cycleIndex},#{userId},#{instanceNo},#{algorithm},#{u},#{weightsJson},
              #{firstProductVersion},#{firstE3Version},#{firstMonthlyTargetUsdt},#{firstMonthlyTargetBandPct},#{firstCapacityPct})
            ON DUPLICATE KEY UPDATE user_device_id=VALUES(user_device_id)
            """)
    int insert(Row row);
    @Update("""
            UPDATE nx_sim_device_target_cycle SET p=#{p},lo=#{lo},hi=#{hi},allocated_usdt=#{allocatedUsdt},config_changed=#{configChanged}
            WHERE user_device_id=#{deviceId} AND cycle_start=#{cycleStart} AND user_id=#{userId} AND instance_no=#{instanceNo}
            """)
    int allocate(Row row);
    @Update("""
            UPDATE nx_sim_device_target_cycle SET credited_usdt=credited_usdt+#{amount}
            WHERE user_device_id=#{deviceId} AND cycle_start=#{cycleStart} AND user_id=#{userId} AND instance_no=#{instanceNo}
            """)
    int credit(@Param("deviceId")long deviceId,@Param("cycleStart")LocalDateTime cycleStart,@Param("userId")long userId,
            @Param("instanceNo")String instanceNo,@Param("amount")BigDecimal amount);
}
