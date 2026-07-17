package ffdd.opsconsole.market.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface GenesisSimulationMapper extends BaseMapper<Object> {
    @Select("SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key = 'G4_CONFIG' FOR UPDATE")
    String lockConfigMutation();

    @Insert("""
            INSERT INTO nx_genesis_admin_simulation (
              simulation_no, side, quantity, unit_price, reason, operator,
              record_type, status, created_at, updated_at, is_deleted
            ) VALUES (#{simulationNo}, #{side}, #{quantity}, #{unitPrice}, #{reason}, #{operator},
                      'SIMULATED', 'ACTIVE', NOW(), NOW(), 0)
            """)
    int insertSimulation(@Param("simulationNo") String simulationNo, @Param("side") String side,
                         @Param("quantity") BigDecimal quantity, @Param("unitPrice") BigDecimal unitPrice,
                         @Param("reason") String reason, @Param("operator") String operator);

    @Select("""
            SELECT id, simulation_no AS simulationNo, side, quantity, unit_price AS unitPrice,
                   quantity * unit_price AS notional, reason, operator, record_type AS recordType,
                   status, created_at AS createdAt
              FROM nx_genesis_admin_simulation
             WHERE is_deleted = 0 ORDER BY id DESC LIMIT #{limit}
            """)
    List<Map<String, Object>> list(@Param("limit") int limit);

    @Update("""
            UPDATE nx_genesis_admin_simulation
               SET status = 'ARCHIVED', is_deleted = 1, updated_at = NOW()
             WHERE id = #{id} AND is_deleted = 0
            """)
    int archive(@Param("id") Long id);
}
