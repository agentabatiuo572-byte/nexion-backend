package ffdd.opsconsole.content.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupportLeaderboardAuthorizationMapperTest {
    @Test void everyAuthQueryIsCurrentParameterizedForShareWithoutStaleMapperCache(){Configuration configuration=new Configuration();
        new MapperAnnotationBuilder(configuration,SupportLeaderboardAuthorizationMapper.class).parse();
        for(var method:SupportLeaderboardAuthorizationMapper.class.getDeclaredMethods()){
            if(!method.isAnnotationPresent(Select.class))continue;
            Options options=method.getAnnotation(Options.class);assertFalse(options.useCache());assertEquals(Options.FlushCachePolicy.TRUE,options.flushCache());
            String sql=configuration.getMappedStatement(SupportLeaderboardAuthorizationMapper.class.getName()+"."+method.getName()).getBoundSql(Map.of("actor",7,"admin",9,"superAdmin",false)).getSql();
            assertTrue(sql.contains("FOR SHARE"),method.getName());assertFalse(sql.contains("${"));assertTrue(sql.contains("?"));
        }
    }
    @Test void grantsAreActiveApiOnlyAndQualificationMembershipOwnerAreUniqueCurrent() throws Exception {
        String grants=String.join("",SupportLeaderboardAuthorizationMapper.class.getMethod("grants",long.class).getAnnotation(Select.class).value());
        assertTrue(grants.contains("resource_type='API'"));assertTrue(grants.contains("service_m1_read") && grants.contains("service_m3_read"));assertFalse(grants.contains("platform_a1_read"));
        assertTrue(SupportGroupMapper.UNIQUE_MEMBER.contains("NOT EXISTS"));assertTrue(SupportGroupMapper.UNIQUE_QUALIFICATION.contains("NOT EXISTS"));assertTrue(SupportGroupMapper.CURRENT_GROUP_OWNER.contains("NOT EXISTS"));
    }
}
