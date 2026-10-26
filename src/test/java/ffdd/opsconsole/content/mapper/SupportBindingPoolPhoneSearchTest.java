package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import java.util.HashMap;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SupportBindingPoolPhoneSearchTest {
    @Test void countAndPageUseTheSameScopedLiteralPhoneFilterAndBoundKeyword() throws Exception {
        for (String keyword : new String[]{"18800002601", "0000", "2601", "%", "_", "' OR 1=1 --"}) {
            var params = new HashMap<String,Object>();
            params.put("scope", new ReadScope(7L, ReadMode.MANAGED, 8L, null));
            params.put("reason", "LEGACY"); params.put("keyword", keyword);
            params.put("offset", 8L); params.put("limit", 8);
            for (var method : SupportBindingMapper.class.getMethods()) {
                if (!method.getName().equals("scopedPool") && !method.getName().equals("scopedPoolCount")) continue;
                String script = String.join(" ", method.getAnnotation(Select.class).value());
                var bound = new XMLLanguageDriver().createSqlSource(new Configuration(), script, HashMap.class).getBoundSql(params);
                String sql = bound.getSql().replaceAll("\\s+", " ");
                assertThat(sql).contains("OR CAST(scope_customer.id AS CHAR)=? OR LOCATE(?,scope_customer.phone)>0)",
                        "scope_customer.is_deleted=0", "scope_route", "AND p.reason=?", "active_pool_binding");
                assertThat(sql).doesNotContain("' OR 1=1 --", "scope_customer.phone LIKE", "scope_customer.*");
                assertThat(bound.getParameterMappings().stream().filter(p -> p.getProperty().equals("keyword"))).hasSize(3);
            }
        }
    }
}
