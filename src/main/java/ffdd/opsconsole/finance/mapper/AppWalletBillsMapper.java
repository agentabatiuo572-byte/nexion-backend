package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppWalletBillsMapper extends BaseMapper<Object> {
    @Select("SELECT sandbox FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    UserScope userScope(@Param("userId") Long userId);

    @Select("""
            SELECT id,biz_no bizNo,biz_type bizType,UPPER(asset) asset,UPPER(direction) direction,
                   amount,balance_after balanceAfter,UPPER(status) status,remark,created_at createdAt
              FROM nx_wallet_ledger
             WHERE user_id=#{userId} AND is_deleted=0
             ORDER BY created_at DESC,id DESC LIMIT #{limit}
            """)
    List<LedgerRow> rows(@Param("userId") Long userId, @Param("limit") int limit);

    record UserScope(Integer sandbox) { }
    record LedgerRow(Long id, String bizNo, String bizType, String asset, String direction,
                     BigDecimal amount, BigDecimal balanceAfter, String status, String remark,
                     LocalDateTime createdAt) { }
}
