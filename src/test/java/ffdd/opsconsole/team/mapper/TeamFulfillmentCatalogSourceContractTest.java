package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.mapper.GrowthVoucherGrantMapper;
import ffdd.opsconsole.team.infrastructure.MybatisTeamCommissionRepository;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class TeamFulfillmentCatalogSourceContractTest {

    @Test
    void skuRewardReservationUsesTheCanonicalProductInventory() throws Exception {
        Method method = TeamFulfillmentQueueMapper.class.getMethod("reserveSkuStock", String.class);
        String sql = String.join("\n", method.getAnnotation(Update.class).value());

        assertThat(sql)
                .contains("UPDATE nx_product")
                .contains("stock = CASE WHEN inventory_mode='FINITE' THEN stock - 1 ELSE stock END")
                .contains("(inventory_mode='UNLIMITED' OR stock > 0)")
                .contains("sold_count = sold_count + 1")
                .doesNotContain("nx_admin_device_sku");
    }

    @Test
    void skuOptionsUseExactlyTheExistingFulfillmentEligibility() throws Exception {
        String reservation = normalized(String.join(" ", TeamFulfillmentQueueMapper.class
                .getMethod("reserveSkuStock", String.class).getAnnotation(Update.class).value()));
        String options = selectSql(TeamCommissionMapper.class, "vRankSkuOptions");
        String reservationEligibility = reservation.substring(reservation.indexOf("WHERE ") + 6)
                .replace("product_no = #{skuId} AND ", "");
        String optionEligibility = options.substring(options.indexOf("WHERE ") + 6, options.indexOf(" ORDER BY"));

        assertThat(options).contains("FROM nx_product", "product_no AS id, name").doesNotContain("nx_admin_device_sku");
        assertThat(optionEligibility).isEqualTo(reservationEligibility);
    }

    @Test
    void voucherOptionsMatchGrantTimeEligibilityAndCountAllLiveIssuances() throws Exception {
        String locked = selectSql(GrowthVoucherGrantMapper.class, "lockGrantableVoucher", String.class, long.class);
        String options = selectSql(TeamCommissionMapper.class, "vRankVoucherOptions", long.class);
        String grantEligibility = locked.substring(locked.indexOf("WHERE ") + 6, locked.indexOf(" LIMIT 1"))
                .replace("voucher_id = #{voucherId} AND ", "");
        String optionEligibility = options.substring(options.indexOf("WHERE ") + 6, options.indexOf(" AND (COALESCE"))
                .replace("v.", "");

        assertThat(optionEligibility).isEqualTo(grantEligibility);
        assertThat(options).contains("v.voucher_id AS id, v.voucher_name AS name",
                "COALESCE(v.issuance_limit, 0) <= 0",
                "SELECT COUNT(1) FROM nx_growth_voucher_grant g",
                "g.voucher_id = v.voucher_id AND g.is_deleted = 0) < v.issuance_limit")
                .doesNotContain("availableCount", "g.status", "nx_v_rank_reward_rule");
    }

    @Test
    void repositoryForwardsCatalogRowsAndServerEpochToTheMapper() {
        TeamCommissionMapper mapper = mock(TeamCommissionMapper.class);
        List<Map<String, Object>> vouchers = List.of(Map.of("id", "VC-REAL", "name", "真实券"));
        List<Map<String, Object>> skus = List.of(Map.of("id", "SKU-REAL", "name", "真实商品"));
        long now = 1791266400000L;
        when(mapper.vRankVoucherOptions(now)).thenReturn(vouchers);
        when(mapper.vRankSkuOptions()).thenReturn(skus);
        MybatisTeamCommissionRepository repository = new MybatisTeamCommissionRepository(mapper);

        assertThat(repository.vRankVoucherOptions(now)).isSameAs(vouchers);
        assertThat(repository.vRankSkuOptions()).isSameAs(skus);
        verify(mapper).vRankVoucherOptions(now);
        verify(mapper).vRankSkuOptions();
    }

    private String selectSql(Class<?> mapper, String method, Class<?>... types) throws Exception {
        return normalized(String.join(" ", mapper.getMethod(method, types).getAnnotation(Select.class).value()));
    }

    private String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
