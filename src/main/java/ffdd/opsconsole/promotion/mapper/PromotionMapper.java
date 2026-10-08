package ffdd.opsconsole.promotion.mapper;

import ffdd.opsconsole.shared.exception.BizException;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.*;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

public interface PromotionMapper extends BaseMapper<Object> {
    /** SQL is supplied only by domain code; all data values remain bound parameters. */
    @SelectProvider(type=StatementProvider.class,method="statement")
    @Lang(PreparedSqlDriver.class) @Options(useCache=false)
    List<Map<String,Object>> list(@Param("sql") String sql,@Param("args") Object... args);
    default Map<String,Object> one(String sql,Object... args) {
        List<Map<String,Object>> rows=list(sql,args);
        if(rows.size()>1)throw new IllegalStateException("PROMOTION_EXPECTED_SINGLE_ROW");
        return rows.isEmpty()?null:rows.get(0);
    }
    default Map<String,Object> requiredRow(String sql,Object... args) {
        Map<String,Object> row=one(sql,args);
        if(row==null)throw new BizException(404,"PROMOTION_RESOURCE_NOT_FOUND");
        return row;
    }
    @UpdateProvider(type=StatementProvider.class,method="statement")
    @Lang(PreparedSqlDriver.class)
    int write(@Param("sql") String sql,@Param("args") Object... args);
    @SelectProvider(type=StatementProvider.class,method="statement")
    @Lang(PreparedSqlDriver.class) @Options(useCache=false)
    long count(@Param("sql") String sql,@Param("args") Object... args);
    default Map<String,Object> user(long id,boolean lock) {
        return requiredRow("SELECT id,status,CAST(sandbox AS UNSIGNED) sandbox,sponsor_user_id,v_rank,region,created_at FROM nx_user WHERE id=? AND is_deleted=0"+(lock?" FOR UPDATE":""),id);
    }
    default Map<String,Object> order(String no,boolean lock) {
        return requiredRow("SELECT * FROM nx_order WHERE order_no=? AND is_deleted=0"+(lock?" FOR UPDATE":""),no);
    }
    default Map<String,Object> activity(String id,boolean lock) {
        return requiredRow("SELECT * FROM nx_promotion WHERE activity_id=?"+(lock?" FOR UPDATE":""),id);
    }
    default Map<String,Object> version(String id,long version,boolean lock) {
        return requiredRow("SELECT * FROM nx_promotion_version WHERE activity_id=? AND version=?"+(lock?" FOR UPDATE":""),id,version);
    }
    default Map<String,Object> product(String no,boolean lock) {
        return requiredRow("SELECT * FROM nx_product WHERE product_no=? AND is_deleted=0"+(lock?" FOR UPDATE":""),no);
    }
    default List<Map<String,Object>> reservations(String order,boolean lock) {
        return list("SELECT * FROM nx_promotion_reservation WHERE order_no=? ORDER BY activity_id,beneficiary_id,beneficiary_role,rule_id,unit_seq"+(lock?" FOR UPDATE":""),order);
    }
    default boolean hasHold(String order) {
        return !list("SELECT refund_request_id FROM nx_promotion_refund_hold WHERE order_no=? AND status IN ('HELD','OUTCOME_UNKNOWN','EXECUTED') FOR UPDATE",order).isEmpty();
    }
    /** Caller holds the account lock. Current reads must not reuse an idempotency RR snapshot. */
    default long occupiedDeviceSlots(long account) {
        long used=list("""
            SELECT id FROM nx_user_device WHERE user_id=? AND is_deleted=0 AND source_environment='PRODUCTION' AND run_id=''
              AND UPPER(ownership_status)='OWNED' AND UPPER(status) IN ('ACTIVE','ONLINE','BUSY','RUNNING','OFFLINE')
              AND UPPER(COALESCE(NULLIF(device_type,''),'DEVICE'))<>'SHARE' AND activated_at IS NOT NULL
              AND deactivated_at IS NULL AND pending_deactivate=0 FOR UPDATE
            """,account).size();
        for(Map<String,Object> order:list("""
            SELECT order_no,product_id,quantity FROM nx_order WHERE user_id=? AND is_deleted=0
              AND UPPER(order_status) IN ('PENDING_PAYMENT','PAID','PROCESSING','PROVISIONING')
              AND UPPER(COALESCE(activation_status,'WAITING_PAYMENT')) NOT IN ('ACTIVATED','REFUNDED','CANCELLED','PROVISIONING_FAILED')
              ORDER BY id FOR UPDATE
            """,account)) {
            List<Map<String,Object>> lines=list("SELECT product_id,quantity FROM nx_order_item WHERE order_no=? AND is_deleted=0 ORDER BY id FOR UPDATE",order.get("order_no"));
            if(lines.isEmpty())lines=List.of(order);
            for(Map<String,Object> line:lines){
                Map<String,Object> p=one("SELECT product_type FROM nx_product WHERE id=? AND is_deleted=0",line.get("product_id"));
                if(p==null||!"SHARE".equalsIgnoreCase(text(p.get("product_type"))))used+=number(line.get("quantity"));
            }
        }
        // This is the same reservation/receipt fact used by CanonicalStateMapper.PROMOTION_RESERVED_SLOTS.
        for(Map<String,Object> r:list("SELECT reservation_id,amount FROM nx_promotion_reservation WHERE beneficiary_id=? AND asset='DEVICE' AND status IN ('RESERVED','COMMITTED') ORDER BY reservation_id FOR UPDATE",account)){
            Map<String,Object> reward=one("SELECT obligation_id,status FROM nx_promotion_reward WHERE reservation_id=? FOR UPDATE",r.get("reservation_id"));
            if(reward!=null&&(Set.of("CANCELLED","REVERSED").contains(text(reward.get("status")))||!list("SELECT device_id FROM nx_promotion_device_receipt WHERE obligation_id=? FOR UPDATE",reward.get("obligation_id")).isEmpty()))continue;
            used+=number(r.get("amount"));
        }
        return used;
    }
    default long deviceSlotCap(){
        Map<String,Object> config=one("SELECT config_value FROM nx_config_item WHERE config_key='device.max_active_slots' AND status=1 AND is_deleted=0 LIMIT 1");
        return config==null?3:Long.parseLong(text(config.get("config_value")));
    }
    class StatementProvider {
        public static String statement(Map<String,Object> parameters) {return (String)parameters.get("sql");}
    }
    class PreparedSqlDriver extends XMLLanguageDriver {
        @Override
        public SqlSource createSqlSource(Configuration configuration,String sql,Class<?> parameterType) {
            // Keep existing SQL and quoted question marks intact; bind only the separate argument array.
            return parameters -> {
                Object[] args=(Object[])((Map<?,?>)parameters).get("args");
                List<ParameterMapping> mappings=new ArrayList<>(args.length);
                for(int i=0;i<args.length;i++)mappings.add(new ParameterMapping.Builder(configuration,"args["+i+"]",Object.class).build());
                return new BoundSql(configuration,sql,mappings,parameters);
            };
        }
    }
}
