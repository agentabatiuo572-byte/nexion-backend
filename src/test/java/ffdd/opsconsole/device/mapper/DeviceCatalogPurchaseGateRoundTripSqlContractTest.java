package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class DeviceCatalogPurchaseGateRoundTripSqlContractTest {
    @Test
    void e1ReadAndOrdinaryEditPreserveTheServerEnforcedPurchaseGate() throws Exception {
        Method read = DeviceCatalogMapper.class.getMethod("findSku", String.class);
        String readSql = String.join("\n", read.getAnnotation(Select.class).value());
        Method write = DeviceCatalogMapper.class.getMethod(
                "upsertSkuMetadata", DeviceCatalogMapper.SkuWrite.class);
        String writeSql = String.join("\n", write.getAnnotation(Insert.class).value());

        assertThat(readSql)
                .contains("s.purchase_gate_json AS purchaseGateJson")
                .doesNotContain("NULL AS purchaseGateJson");
        assertThat(writeSql)
                .contains("#{sku.purchaseGateJson}")
                .contains("purchase_gate_json=VALUES(purchase_gate_json)");
    }
}
