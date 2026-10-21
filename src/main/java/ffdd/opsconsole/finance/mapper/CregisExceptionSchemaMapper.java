package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.infrastructure.CregisDepositEventEntity;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CregisExceptionSchemaMapper extends BaseMapper<CregisDepositEventEntity> {
    @Select("""
        SELECT TABLE_NAME tableName,COLUMN_NAME columnName,DATA_TYPE dataType,
               NUMERIC_PRECISION numericPrecision,NUMERIC_SCALE numericScale
          FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
           AND TABLE_NAME IN ('nx_cregis_deposit_event','nx_cregis_exception_source','nx_cregis_ownership_challenge',
             'nx_cregis_exception_claim','nx_cregis_exception_refund_policy',
             'nx_cregis_exception_refund_policy_case','nx_cregis_exception_refund',
             'nx_cregis_exception_refund_callback','nx_cregis_exception_refund_ledger',
             'nx_cregis_reorg_resolution','nx_cregis_reorg_proposal','nx_cregis_reorg_operation')
        """)
    List<Map<String,Object>> columns();

    @Select("""
        SELECT TABLE_NAME tableName,INDEX_NAME indexName,
               GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') indexColumns
          FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND NON_UNIQUE=0
           AND TABLE_NAME IN ('nx_cregis_exception_source','nx_cregis_ownership_challenge',
             'nx_cregis_exception_claim','nx_cregis_exception_refund_policy',
             'nx_cregis_exception_refund_policy_case','nx_cregis_exception_refund',
             'nx_cregis_exception_refund_callback','nx_cregis_exception_refund_ledger',
             'nx_cregis_reorg_resolution','nx_cregis_reorg_proposal','nx_cregis_reorg_operation')
         GROUP BY TABLE_NAME,INDEX_NAME
        """)
    List<Map<String,Object>> indexes();
}
