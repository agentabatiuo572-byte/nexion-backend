package ffdd.opsconsole.finance.mapper;

import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.*;
import org.apache.ibatis.annotations.*;

/** Statements are server-owned SQL; all request values use bound parameters. */
public interface PurchasePaymentMapper extends BaseMapper<Object> {
    @SelectProvider(type=PromotionMapper.StatementProvider.class,method="statement")
    @Lang(PromotionMapper.PreparedSqlDriver.class) @Options(useCache=false)
    @Results({@Result(column="expires_at",property="expires_at",javaType=java.sql.Timestamp.class),
        @Result(column="confirmed_at",property="confirmed_at",javaType=java.sql.Timestamp.class),
        @Result(column="created_at",property="created_at",javaType=java.sql.Timestamp.class),
        @Result(column="updated_at",property="updated_at",javaType=java.sql.Timestamp.class)})
    List<Map<String,Object>> rows(@Param("sql") String sql,@Param("args") Object... args);
    @UpdateProvider(type=PromotionMapper.StatementProvider.class,method="statement")
    @Lang(PromotionMapper.PreparedSqlDriver.class)
    int write(@Param("sql") String sql,@Param("args") Object... args);
    default Map<String,Object> one(String sql,Object... args) {
        var rows=rows(sql,args);
        if(rows.size()>1)throw new IllegalStateException("PURCHASE_PAYMENT_EXPECTED_SINGLE_ROW");
        return rows.isEmpty()?null:rows.get(0);
    }
}
