package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import java.util.HashMap;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SupportBindingPoolTimeContractTest {
    @Test void poolReadCarriesBothUtcClocksWithMicrosecondsWithoutSessionConversion() throws Exception {
        var method=SupportBindingMapper.class.getMethod("scopedPool",ReadScope.class,String.class,String.class,long.class,int.class);
        String script=String.join(" ",method.getAnnotation(Select.class).value());
        var params=new HashMap<String,Object>();
        params.put("scope",new ReadScope(7L,ReadMode.ALL,null,null));
        params.put("reason",null);params.put("keyword",null);params.put("offset",0L);params.put("limit",8);
        String sql=new XMLLanguageDriver().createSqlSource(new Configuration(),script,HashMap.class).getBoundSql(params).getSql();
        // DATE_FORMAT(NULL,...) remains NULL; no COALESCE may fabricate a last attempt.
        assertThat(sql).contains("DATE_FORMAT(p.entered_at,'%Y-%m-%dT%H:%i:%s.%fZ') enteredAt",
                "DATE_FORMAT(p.last_attempt_at,'%Y-%m-%dT%H:%i:%s.%fZ') lastAttemptAt",
                "ORDER BY p.customer_id LIMIT ? OFFSET ? FOR SHARE")
                .doesNotContain("CONVERT_TZ", "DATE_ADD", "COALESCE(p.last_attempt_at", "p.entered_at enteredAt", "p.last_attempt_at lastAttemptAt");
        assertThat(sql).contains("CASE WHEN scope_customer.phone REGEXP '^[0-9]{7,15}$' THEN CONCAT(SUBSTRING(scope_customer.phone,1,3),'****',SUBSTRING(scope_customer.phone,LENGTH(scope_customer.phone)-3)) ELSE NULL END phoneMasked")
                .doesNotContain("scope_customer.phone phone", "scope_customer.phone AS phone", "scope_customer.*");
    }
}
