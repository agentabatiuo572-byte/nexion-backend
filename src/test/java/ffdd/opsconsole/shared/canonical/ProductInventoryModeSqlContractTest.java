package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.team.mapper.TeamFulfillmentQueueMapper;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.shared.canonical.mapper.AppBundleOrderMapper;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class ProductInventoryModeSqlContractTest {

    @Test
    void catalogShowsSoldOutFiniteRowsWhileEligibilityAndOrderRequirePurchasableStock() throws Exception {
        String catalog = select(AppTradeinMapper.class, "listPurchasableCatalogTargets");
        String lock = select(CanonicalStateMapper.class, "lockProduct", Long.class, String.class);
        String find = select(CanonicalStateMapper.class, "findPurchasableProduct", Long.class, String.class);
        String batch = select(CanonicalStateMapper.class, "findPurchasableProducts", List.class);
        String decrement = update(CanonicalStateMapper.class, "decrementProductStock", Long.class, Integer.class);

        assertThat(catalog).contains("p.inventory_mode AS inventoryMode")
                .contains("p.inventory_mode='UNLIMITED' OR p.stock>=0");
        assertThat(lock).contains("p.inventory_mode AS inventoryMode")
                .contains("p.inventory_mode='UNLIMITED' OR p.stock >= 1");
        assertThat(find).contains("p.inventory_mode AS inventoryMode")
                .contains("p.inventory_mode='UNLIMITED' OR p.stock>=1");
        assertThat(batch).contains("p.inventory_mode AS inventoryMode")
                .contains("p.inventory_mode='UNLIMITED' OR p.stock&gt;=1");
        assertThat(decrement)
                .contains("CASE WHEN inventory_mode='FINITE' THEN stock - #{quantity} ELSE stock END")
                .contains("inventory_mode='UNLIMITED' OR stock >= #{quantity}");
    }

    @Test
    void singleOrderProductProjectionMatchesProductStockRecordConstructorOrder() throws Exception {
        String lock = select(CanonicalStateMapper.class, "lockProduct", Long.class, String.class);
        String find = select(CanonicalStateMapper.class, "findPurchasableProduct", Long.class, String.class);
        String batch = select(CanonicalStateMapper.class, "findPurchasableProducts", List.class);

        assertProductStockProjectionOrder(lock);
        assertProductStockProjectionOrder(find);
        assertProductStockProjectionOrder(batch);
    }

    @Test
    void bundleOrderUsesTheSameUnlimitedInventoryRule() throws Exception {
        String lock = select(AppBundleOrderMapper.class, "lockProducts", java.util.List.class);
        String decrement = update(AppBundleOrderMapper.class, "decrementStock", Long.class);

        assertThat(lock).contains("p.inventory_mode AS inventoryMode");
        assertThat(decrement)
                .contains("CASE WHEN inventory_mode='FINITE' THEN stock-1 ELSE stock END")
                .contains("inventory_mode='UNLIMITED' OR stock>=1");
    }

    @Test
    void tradeinRecordProjectionsKeepInventoryModeInConstructorOrder() throws Exception {
        String catalog = select(AppTradeinMapper.class, "listPurchasableCatalogTargets");
        String find = select(AppTradeinMapper.class, "findTargetProduct", Long.class, String.class);
        String lock = select(AppTradeinMapper.class, "lockTargetProduct", Long.class, String.class);

        assertThat(catalog.indexOf("p.inventory_mode AS inventoryMode"))
                .isGreaterThan(catalog.indexOf("NULL AS purchaseGateJson"));
        assertThat(find.indexOf("inventory_mode AS inventoryMode"))
                .isGreaterThan(find.indexOf("daily_nex AS dailyNex"));
        assertThat(lock.indexOf("inventory_mode AS inventoryMode"))
                .isGreaterThan(lock.indexOf("daily_nex AS dailyNex"));
    }

    @Test
    void rewardTrialAndAcceptanceRailsCannotBypassUnlimitedShareInvariant() throws Exception {
        String reward = update(TeamFulfillmentQueueMapper.class, "reserveSkuStock", String.class);
        String trial = update(AppTrialLifecycleMapper.class, "decrementProductStock", Long.class);
        String sandboxCatalog = select(CommerceAcceptanceSandboxMapper.class, "listEligibleCatalogSeeds");
        String sandboxReserve = update(CommerceAcceptanceSandboxMapper.class, "reserveSandboxCatalogStock",
                String.class, Long.class, Long.class, Integer.class);
        String sandboxReturn = update(CommerceAcceptanceSandboxMapper.class, "returnSandboxCatalogStock",
                String.class, Long.class, Long.class, Integer.class);

        assertThat(reward).contains("inventory_mode='FINITE' OR UPPER(product_type)='SHARE'");
        assertThat(trial).contains("inventory_mode='FINITE' OR UPPER(product_type)='SHARE'")
                .contains("sold_count=sold_count+1");
        assertThat(sandboxCatalog).contains("p.inventory_mode inventoryMode")
                .contains("p.inventory_mode='UNLIMITED' OR p.stock>=1");
        assertThat(sandboxReserve)
                .contains("CASE WHEN inventory_mode='FINITE' THEN stock-#{quantity} ELSE stock END")
                .contains("sold_count=sold_count+#{quantity}");
        assertThat(sandboxReturn)
                .contains("CASE WHEN inventory_mode='FINITE' THEN stock+#{quantity} ELSE stock END")
                .contains("sold_count=GREATEST(0,sold_count-#{quantity})");
    }

    private static String select(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getMethod(name, parameters);
        return String.join("\n", method.getAnnotation(Select.class).value());
    }

    private static String update(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getMethod(name, parameters);
        return String.join("\n", method.getAnnotation(Update.class).value());
    }

    private static void assertProductStockProjectionOrder(String sql) {
        List<String> recordComponents = Arrays.stream(CanonicalStateMapper.ProductStock.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(recordComponents).containsExactly(
                "id", "productNo", "priceUsdt", "stock", "unlockPhase",
                "purchaseGateJson", "productType", "inventoryMode",
                "gpuModel", "vramTotalGb", "power", "datacenter");

        int id = sql.indexOf("SELECT p.id");
        int productNo = sql.indexOf("product_no AS productNo");
        int priceUsdt = sql.indexOf("price_usdt AS priceUsdt");
        int stock = sql.indexOf("p.stock");
        int unlockPhase = sql.indexOf("unlock_phase AS unlockPhase");
        int purchaseGate = sql.indexOf("AS purchaseGateJson");
        int productType = sql.indexOf("product_type AS productType");
        int inventoryMode = sql.indexOf("inventory_mode AS inventoryMode");
        int gpuModel = sql.indexOf("gpu_model AS gpuModel");
        int vramTotalGb = sql.indexOf("vram_total_gb AS vramTotalGb");
        int power = sql.indexOf("AS power");
        int datacenter = sql.indexOf("AS datacenter");

        assertThat(id).isNotNegative();
        assertThat(productNo).isGreaterThan(id);
        assertThat(priceUsdt).isGreaterThan(productNo);
        assertThat(stock).isGreaterThan(priceUsdt);
        assertThat(unlockPhase).isGreaterThan(stock);
        assertThat(purchaseGate).isGreaterThan(unlockPhase);
        assertThat(productType).isGreaterThan(purchaseGate);
        assertThat(inventoryMode).isGreaterThan(productType);
        assertThat(gpuModel).isGreaterThan(inventoryMode);
        assertThat(vramTotalGb).isGreaterThan(gpuModel);
        assertThat(power).isGreaterThan(vramTotalGb);
        assertThat(datacenter).isGreaterThan(power);
    }
}
