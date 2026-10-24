package ffdd.opsconsole.growth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.promotion.mapper.PromotionMapper.PreparedSqlDriver;
import ffdd.opsconsole.promotion.mapper.PromotionMapper.StatementProvider;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** H4 stores UTC wall times in DATETIME; it must not inherit promotion instant mappings. */
public interface H4EventSqlMapper extends BaseMapper<Object> {
    @SelectProvider(type=StatementProvider.class,method="statement")
    @Lang(PreparedSqlDriver.class) @Options(useCache=false)
    @Results({
        @Result(column="starts_at",property="starts_at",javaType=LocalDateTime.class),
        @Result(column="ends_at",property="ends_at",javaType=LocalDateTime.class),
        @Result(column="activated_at",property="activated_at",javaType=LocalDateTime.class),
        @Result(column="disabled_at",property="disabled_at",javaType=LocalDateTime.class),
        @Result(column="settled_at",property="settled_at",javaType=LocalDateTime.class),
        @Result(column="created_at",property="created_at",javaType=LocalDateTime.class),
        @Result(column="updated_at",property="updated_at",javaType=LocalDateTime.class),
        @Result(column="eligible_since",property="eligible_since",javaType=LocalDateTime.class),
        @Result(column="qualified_at",property="qualified_at",javaType=LocalDateTime.class),
        @Result(column="joined_at",property="joined_at",javaType=LocalDateTime.class),
        @Result(column="notified_at",property="notified_at",javaType=LocalDateTime.class),
        @Result(column="draw_at",property="draw_at",javaType=LocalDateTime.class),
        @Result(column="opened_at",property="opened_at",javaType=LocalDateTime.class),
        @Result(column="drawn_at",property="drawn_at",javaType=LocalDateTime.class),
        @Result(column="last_attempt_at",property="last_attempt_at",javaType=LocalDateTime.class),
        @Result(column="issued_at",property="issued_at",javaType=LocalDateTime.class),
        @Result(column="paid_at",property="paid_at",javaType=LocalDateTime.class),
        @Result(column="locked_at",property="locked_at",javaType=LocalDateTime.class)
    })
    List<Map<String,Object>> list(@Param("sql") String sql,@Param("args") Object... args);

    @UpdateProvider(type=StatementProvider.class,method="statement")
    @Lang(PreparedSqlDriver.class)
    int write(@Param("sql") String sql,@Param("args") Object... args);
}
