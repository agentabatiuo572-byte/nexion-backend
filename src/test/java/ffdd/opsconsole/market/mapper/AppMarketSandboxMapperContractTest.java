package ffdd.opsconsole.market.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class AppMarketSandboxMapperContractTest {
    @Test
    void sandboxMarketStateRemainsRunScopedInDedicatedTables() {
        String sql=Arrays.stream(AppMarketSandboxMapper.class.getDeclaredMethods()).map(this::sql).reduce("",(a,b)->a+" "+b).toLowerCase();
        assertThat(sql).contains("nx_exchange_sandbox_wallet","nx_exchange_sandbox_order","nx_exchange_sandbox_ledger","nx_genesis_sandbox_wallet","nx_genesis_sandbox_order","nx_genesis_sandbox_holding","nx_genesis_sandbox_ledger");
        assertThat(sql).contains("run_id").contains("user_id");
        assertThat(sql).doesNotContain("nx_exchange_order").doesNotContain("nx_genesis_order").doesNotContain("nx_genesis_holding");
    }

    @Test
    void genesisSandboxMoneyUsesTheAppVisibleWalletAndBillsWithASandboxIdentityGuard() {
        String sql=Arrays.stream(AppMarketSandboxMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().contains("CanonicalGenesis"))
                .map(this::sql)
                .reduce("",(a,b)->a+" "+b)
                .toLowerCase()
                .replaceAll("\\s+", " ");

        assertThat(sql).contains("nx_user_wallet", "nx_wallet_ledger", "coalesce(u.sandbox,0)=1", "coalesce(w.sandbox,0)=1");
        assertThat(sql).contains("usdt_available>=#{amount}", "'success'");
    }

    @Test
    void genesisLedgerProjectionMatchesLedgerViewConstructorOrder() {
        String sql=Arrays.stream(AppMarketSandboxMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("genesisLedger"))
                .map(this::sql)
                .findFirst()
                .orElseThrow()
                .toLowerCase()
                .replaceAll("\\s+", " ");

        assertThat(sql).contains("select biz_no as bizno,'usdt' as asset,direction,amount,balance_after as balanceafter,remark,created_at as createdat");
    }

    @Test
    void genesisSoldSupplyExcludesRevokedOrCancelledHoldings() throws Exception {
        String sql=sql(AppMarketSandboxMapper.class.getDeclaredMethod("holdingCount", String.class))
                .toLowerCase()
                .replaceAll("\\s+", " ");
        assertThat(sql).contains("upper(status) in ('active','listed')");
    }

    @Test
    void genesisAccountProjectionExcludesRevokedOrCancelledHoldings() throws Exception {
        String sql=sql(AppMarketSandboxMapper.class.getDeclaredMethod("holdings", String.class, Long.class))
                .toLowerCase()
                .replaceAll("\\s+", " ");
        assertThat(sql).contains("upper(status) in ('active','listed')");
    }

    @Test
    void genesisCanonicalMoneyCannotReuseAUserAcrossSandboxRuns() throws Exception {
        String sql=sql(AppMarketSandboxMapper.class.getDeclaredMethod("genesisArtifactsInOtherRuns", String.class, Long.class))
                .toLowerCase()
                .replaceAll("\\s+", " ");
        assertThat(sql).contains("nx_genesis_sandbox_order", "nx_genesis_sandbox_holding",
                "nx_genesis_sandbox_ledger", "nx_genesis_sandbox_wallet", "run_id<>#{runid}");
        assertThat(sql).contains("version>0");
    }

    @Test
    void genesisSecondarySnapshotDoesNotLockTheHoldingBeforeWalletMutexes() throws Exception {
        String sql=sql(AppMarketSandboxMapper.class.getDeclaredMethod("holdingSnapshot", String.class, String.class))
                .toLowerCase()
                .replaceAll("\\s+", " ");
        assertThat(sql).contains("nx_genesis_sandbox_holding", "run_id=#{runid}", "holding_no=#{holdingno}")
                .doesNotContain("for update");
    }

    private String sql(Method method) {
        Select s=method.getAnnotation(Select.class);
        Insert i=method.getAnnotation(Insert.class);
        Update u=method.getAnnotation(Update.class);
        if(s!=null)return String.join(" ",s.value());
        if(i!=null)return String.join(" ",i.value());
        return u==null?"":String.join(" ",u.value());
    }
}
