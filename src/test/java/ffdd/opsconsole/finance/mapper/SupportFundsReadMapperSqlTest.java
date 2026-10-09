package ffdd.opsconsole.finance.mapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SupportFundsReadMapperSqlTest {
    @Test void bothQueriesAreParameterizedOrdinaryBatchSelectsWithMinimalColumnsAndNoMutationOrPii() {
        var configuration=new Configuration();configuration.addMapper(SupportFundsReadMapper.class);
        for(String name:List.of("wallets","withdrawals")) {
            var statement=configuration.getMappedStatement(SupportFundsReadMapper.class.getName()+"."+name);var bound=statement.getBoundSql(Map.of("customerIds",List.of(7L,8L)));
            String sql=bound.getSql().replaceAll("\\s+"," ");assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
            assertThat(statement.isUseCache()).isFalse();assertThat(statement.isFlushCacheRequired()).isTrue();assertThat(bound.getParameterMappings()).hasSize(2);
            assertThat(sql).containsPattern("user_id IN\\s*\\(\\s*\\?\\s*,\\s*\\?\\s*\\)");
            assertThat(sql).contains("is_deleted=0","NOW(6) evaluatedDbAt","ORDER BY user_id,id")
                .doesNotContain("FOR UPDATE","FOR SHARE","${","JOIN","SUM(","COALESCE","SELECT *","INSERT ","UPDATE ","DELETE ","CREATE ",
                    "target_address","chain_tx_hash","chain,","withdrawal_no","nx_support_","source_environment","run_id","cumulative_deposit","pending_withdraw","lifetime_earned");
        }
        String wallet=sql(configuration,"wallets",List.of(7L));assertThat(wallet).contains("id walletId,user_id customerId,version,usdt_available usdtAvailable","nex_available nexAvailable","FROM nx_user_wallet");
        String withdrawals=sql(configuration,"withdrawals",List.of(7L));assertThat(withdrawals).contains("id withdrawalId,user_id customerId,asset currency,amount principal","d2_actual_fee actualFee,d2_net_receive net,status,completed_at completedAt","FROM nx_withdrawal_order");
    }
    @Test void emptyOrNullScopeNeverExpandsToAllCustomersEvenIfMapperIsCalledDirectly() {
        var configuration=new Configuration();configuration.addMapper(SupportFundsReadMapper.class);
        for(String name:List.of("wallets","withdrawals"))for(List<Long> ids:java.util.Arrays.<List<Long>>asList(List.of(),null)) {
            Map<String,Object> scope=new HashMap<>();scope.put("customerIds",ids);
            var bound=configuration.getMappedStatement(SupportFundsReadMapper.class.getName()+"."+name).getBoundSql(scope);
            assertThat(bound.getSql()).contains("1=0");assertThat(bound.getParameterMappings()).isEmpty();
        }
    }
    private static String sql(Configuration configuration,String name,List<Long> ids) {
        return configuration.getMappedStatement(SupportFundsReadMapper.class.getName()+"."+name).getBoundSql(Map.of("customerIds",ids)).getSql().replaceAll("\\s+"," ");
    }
}
