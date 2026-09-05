package ffdd.opsconsole.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.user.dto.UserQueryRequest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class UserOpsPhoneSearchSqlTest {
    @Test
    void supportCountAndRowsIncludeBoundPhoneSuffixAndInternationalNumber() {
        for (String method : List.of("countUsersByQuery", "pageUsers")) {
            BoundSql sql = render(method, "3775", "3775");
            assertThat(sql.getSql())
                    .contains("RIGHT(REGEXP_REPLACE(u.phone", "REGEXP_REPLACE(CONCAT(u.country_code, u.phone)")
                    .doesNotContain("3775")
                    .contains("u.is_deleted = 0");
            assertThat(sql.getParameterMappings()).filteredOn(p -> p.getProperty().equals("phoneKeyword"))
                    .hasSize(3);
        }
    }

    @Test
    void c1AndEmptySearchNeverEnablePhoneMatching() {
        for (String method : List.of("countUsersByQuery", "pageUsers")) {
            assertThat(render(method, "3775", null).getSql()).doesNotContain("RIGHT(REGEXP_REPLACE(u.phone");
            assertThat(render(method, null, null).getSql()).doesNotContain("RIGHT(REGEXP_REPLACE(u.phone");
        }
    }

    static BoundSql render(String method, String keyword, String phoneKeyword) {
        String script = Arrays.stream(UserOpsMapper.class.getMethods())
                .filter(m -> m.getName().equals(method)).findFirst()
                .map(m -> String.join("\n", m.getAnnotation(Select.class).value())).orElseThrow();
        Map<String, Object> params = new HashMap<>();
        params.put("query", UserQueryRequest.basic(keyword, null, null, 1, 8, null));
        params.put("statuses", List.of());
        params.put("phoneKeyword", phoneKeyword);
        params.put("offset", 0);
        params.put("pageSize", 8);
        return new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class).getBoundSql(params);
    }
}
