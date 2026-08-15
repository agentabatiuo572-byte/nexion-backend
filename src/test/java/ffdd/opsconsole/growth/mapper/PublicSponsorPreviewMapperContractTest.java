package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class PublicSponsorPreviewMapperContractTest {
    @Test
    void publicProjectionDoesNotSelectGeographicOrAccountIdentifiers() throws Exception {
        Method method = PublicSponsorPreviewMapper.class.getMethod("findActiveByCanonicalCode", String.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value()).toLowerCase();
        assertThat(sql)
                .doesNotContain("region")
                .doesNotContain("phone")
                .doesNotContain("user_id")
                .doesNotContain("${")
                .contains("status = 'active'")
                .contains("is_deleted = 0");
    }
}
