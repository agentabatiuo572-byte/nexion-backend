package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.DisclosureGateActionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Select;

public interface DisclosureGateActionMapper extends BaseMapper<DisclosureGateActionEntity> {
    @Select("""
            SELECT id
              FROM nx_disclosure_gate_action
             WHERE is_deleted = 0
             ORDER BY id
             FOR UPDATE
            """)
    List<Long> lockAllActive();
}
