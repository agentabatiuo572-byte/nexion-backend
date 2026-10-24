package ffdd.opsconsole.growth.infrastructure;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.growth.mapper.H4EventSqlMapper;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class H4EventSqlConfiguration {
    @Bean @Primary
    public H4EventSqlMapper h4EventRawMapper(DataSource dataSource) {
        var configuration=new MybatisConfiguration(new Environment("h4-events",new SpringManagedTransactionFactory(),dataSource));
        configuration.setCallSettersOnNulls(true);
        configuration.setReturnInstanceForEmptyRow(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.addMapper(H4EventSqlMapper.class);
        return new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration)).getMapper(H4EventSqlMapper.class);
    }
}
