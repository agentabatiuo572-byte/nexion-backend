package ffdd.opsconsole.market.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppMarketSandboxMapperContractTest {
    @Test
    void everySandboxMutationIsRunScopedAndUsesDedicatedTables() {
        String sql=Arrays.stream(AppMarketSandboxMapper.class.getDeclaredMethods()).map(this::sql).reduce("",(a,b)->a+" "+b).toLowerCase();
        assertThat(sql).contains("nx_exchange_sandbox_wallet","nx_exchange_sandbox_order","nx_exchange_sandbox_ledger","nx_genesis_sandbox_wallet","nx_genesis_sandbox_order","nx_genesis_sandbox_holding","nx_genesis_sandbox_ledger");
        assertThat(sql).contains("run_id").contains("user_id");
        assertThat(sql).doesNotContain("nx_user_wallet").doesNotContain("nx_exchange_order").doesNotContain("nx_genesis_order").doesNotContain("nx_genesis_holding").doesNotContain("nx_wallet_ledger");
    }
    private String sql(Method method) { Select s=method.getAnnotation(Select.class); Insert i=method.getAnnotation(Insert.class); return s==null?(i==null?"":String.join(" ",i.value())):String.join(" ",s.value()); }
}
