package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.user.facade.UserWeeklyRegionFacade.RegisteredCallingCode;
import ffdd.opsconsole.user.infrastructure.UserWeeklyRegionMapper;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class UserWeeklyRegionFacadeTest {
    @Test void accountProjectionRequiresTrustedScopeAndRejectsUnavailableAccounts() {
        var mapper = mock(UserWeeklyRegionMapper.class);
        var facade = new UserWeeklyRegionFacadeAdapter(mapper);
        when(mapper.lockActive(1L,false)).thenReturn(new RegisteredCallingCode(1L,"86",false));
        when(mapper.lockActive(2L,true)).thenReturn(new RegisteredCallingCode(2L,"+86",true));
        assertThat(facade.lockActiveRegisteredCallingCode(1L,false).countryCode()).isEqualTo("86");
        assertThat(facade.lockActiveRegisteredCallingCode(2L,true).sandbox()).isTrue();
        assertThat(facade.lockActiveRegisteredCallingCode(2L,false)).isNull();
        assertThat(facade.lockActiveRegisteredCallingCode(3L,false)).isNull();
        assertThatThrownBy(() -> facade.lockActiveRegisteredCallingCode(null,false)).hasMessage("WEEKLY_ACCOUNT_UNAVAILABLE");
        verify(mapper,never()).lockActive(null,false);
    }

    @Test void actualMyBatisStatementBindsAccountScopeAndFiltersDisabledDeletedUsersUnderSameTransaction() throws Exception {
        var config = new Configuration();
        config.addMapper(UserWeeklyRegionMapper.class);
        var sql = config.getMappedStatement(UserWeeklyRegionMapper.class.getName()+".lockActive")
                .getBoundSql(Map.of("userId",4L,"sandbox",false));
        assertThat(sql.getSql()).contains("status='ACTIVE'","is_deleted=0","COALESCE(sandbox,0)=?","FOR UPDATE")
                .doesNotContain("phone,","timezone","language");
        assertThat(sql.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactly("userId","sandbox");
        assertThat(UserWeeklyRegionFacadeAdapter.class.getMethod("lockActiveRegisteredCallingCode",Long.class,boolean.class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
