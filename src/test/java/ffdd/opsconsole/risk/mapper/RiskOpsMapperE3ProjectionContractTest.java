package ffdd.opsconsole.risk.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class RiskOpsMapperE3ProjectionContractTest {

    @Test
    void projectsFrequentTradeinsWithCommissionOrGiftEvidenceIntoK2() throws Exception {
        Method method = RiskOpsMapper.class.getMethod("e3TradeinArbitrageProjection");
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .toLowerCase()
                .replaceAll("\\s+", " ")
                .trim();

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
                .contains("viewkey")
                .contains("'tradein'")
                .contains("actionscsv")
                .contains("'flag'")
                .contains("null as cell6, 3 as level, 'flag' as actionscsv")
                .doesNotContain("for update", "for share", "insert into");

        String upsert = String.join(" ", RiskOpsMapper.class.getMethod(
                        "upsertE3TradeinArbitrageRows", RiskOpsMapper.RiskArbitrageRowRecord.class)
                .getAnnotation(Insert.class).value()).toLowerCase();
        assertThat(upsert).contains("values (", "on duplicate key update", "is_deleted = 0")
                .doesNotContain("select", "nx_wallet_ledger", "disposition", "version");
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
