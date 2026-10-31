package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.finance.mapper.*;
import java.math.BigDecimal;
import java.util.Map;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class CregisNetworkMapperBindingTest {
    @Test void mapperBindsEthereumIdentityAndKeepsLegacyExceptionAndCollectionQueriesOnBsc() {
        var configuration=new Configuration();
        configuration.addMapper(CregisDepositMapper.class);
        configuration.addMapper(CregisDepositPolicyMapper.class);
        configuration.addMapper(CregisExceptionSourceMapper.class);
        configuration.addMapper(CregisExceptionOwnershipMapper.class);
        configuration.addMapper(CregisAutoForwardMapper.class);
        var params=Map.of("network",CregisNetwork.ERC20,"projectId",88L,"cid",77L,"txid","0x"+"a".repeat(64));
        var event=configuration.getMappedStatement(CregisDepositMapper.class.getName()+".lockEvent").getBoundSql(params);
        assertThat(event.getSql()).contains("chain_id=?");
        assertThat(event.getParameterMappings().stream().map(m->SystemMetaObject.forObject(params).getValue(m.getProperty())).toList())
            .contains("60").doesNotContain("2510");
        var snapshot=configuration.getMappedStatement(CregisDepositPolicyMapper.class.getName()+".snapshot").getBoundSql(params);
        assertThat(snapshot.getParameterMappings().stream().map(m->SystemMetaObject.forObject(params).getValue(m.getProperty())).toList())
            .contains("ETH","USDT-ERC20");
        var source=new CregisExceptionSourceService.Source(88,77,"0x"+"a".repeat(64),2,"0x"+"b".repeat(40),BigDecimal.TEN,CregisNetwork.ERC20);
        var sourceParams=Map.of("s",source);
        var locked=configuration.getMappedStatement(CregisExceptionSourceMapper.class.getName()+".lock").getBoundSql(sourceParams);
        assertThat(locked.getParameterMappings().stream().map(m->SystemMetaObject.forObject(sourceParams).getValue(m.getProperty())).toList()).contains("60");
        assertThat(configuration.getMappedStatement(CregisExceptionOwnershipMapper.class.getName()+".eventByLog").getBoundSql(Map.of()).getSql()).contains("chain_id='2510'");
        assertThat(configuration.getMappedStatement(CregisAutoForwardMapper.class.getName()+".candidates").getBoundSql(Map.of()).getSql()).contains("e.chain_id='2510'");
        assertThat(configuration.getMappedStatement(CregisDepositMapper.class.getName()+".deposits").getBoundSql(Map.of()).getSql()).contains("a.chain_id=d.chain_id");
    }
}
