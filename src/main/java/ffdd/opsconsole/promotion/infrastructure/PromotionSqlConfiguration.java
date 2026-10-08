package ffdd.opsconsole.promotion.infrastructure;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class PromotionSqlConfiguration {
    @Bean @Primary
    public PromotionMapper promotionRawMapper(DataSource dataSource) {
        var configuration=new MybatisConfiguration(new Environment("promotion",new SpringManagedTransactionFactory(),dataSource));
        // Existing raw rows retain even all-null columns; locking reads never reuse a session cache.
        configuration.setCallSettersOnNulls(true);
        configuration.setReturnInstanceForEmptyRow(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.addMapper(PromotionMapper.class);
        return new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration)).getMapper(PromotionMapper.class);
    }
}
