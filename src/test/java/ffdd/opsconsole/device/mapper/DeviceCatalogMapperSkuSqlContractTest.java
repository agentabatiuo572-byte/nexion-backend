package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class DeviceCatalogMapperSkuSqlContractTest {

    @Test
    void skuCountOnlyReferencesDeclaredFilterParameters() throws Exception {
        Method method = DeviceCatalogMapper.class.getMethod("countSkus", String.class, String.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql).contains("#{status}", "#{keyword}");
        assertThat(sql).doesNotContain("#{taskClass}");
    }
}
