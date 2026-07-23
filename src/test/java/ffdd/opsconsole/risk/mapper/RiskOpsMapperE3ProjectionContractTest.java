package ffdd.opsconsole.risk.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class RiskOpsMapperE3ProjectionContractTest {

    @Test
    void projectsFrequentTradeinsWithCommissionOrGiftEvidenceIntoK2() throws Exception {
        Method method = RiskOpsMapper.class.getMethod("upsertE3TradeinArbitrageRows");
        String sql = String.join(" ", method.getAnnotation(Insert.class).value()).toLowerCase();

        assertThat(sql)
                .contains("nx_tradein_application")
                .contains("nx_commission_event")
                .contains("nx_wallet_ledger")
                .contains("upper(a.status) = 'completed'")
                .contains("a.completed_at >= date_sub(now(), interval 30 day)")
                .contains("c.amount_usdt > 0")
                .contains("upper(l.direction) = 'in'")
                .contains("l.amount > 0")
                .contains("count(distinct a.id) >= 3")
                .contains("view_key")
                .contains("'tradein'")
                .contains("on duplicate key update")
                .contains("actions_csv")
                .contains("'flag'");
    }

    @Test
    void retiresThePreviousDynamicProjectionBeforeRefreshingCurrentFacts() throws Exception {
        Method method = RiskOpsMapper.class.getMethod("retireE3TradeinArbitrageRows");
        String sql = String.join(" ", method.getAnnotation(Update.class).value()).toLowerCase();

        assertThat(sql)
                .contains("row_id like 'k2-e3-u%'")
                .contains("is_deleted = 1");
    }
}
