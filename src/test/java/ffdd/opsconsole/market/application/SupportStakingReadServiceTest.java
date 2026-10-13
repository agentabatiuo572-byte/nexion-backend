package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.market.mapper.SupportStakingSourceMapper;
import java.util.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SupportStakingReadServiceTest {
    @Test void fixedBatchPreservesFailedDeletedForeignAndNullTerminalOriginals(){
        var mapper=mock(SupportStakingSourceMapper.class);var service=new SupportStakingReadService(mapper);
        var p=Map.<String,Object>of("positionNo","STK-source","customerId",7L,"status","FAILED","deleted",true,"userSandbox",true,"productCode","UNSUPPORTED");
        var ledger=Map.<String,Object>of("positionNo","STK-source","customerId",8L,"status","FAILED","deleted",true);
        when(mapper.byCustomers(new TreeSet<>(List.of(7L,8L)))).thenReturn(List.of(p));when(mapper.ledgers(List.of("STK-source"))).thenReturn(List.of(ledger));
        var source=service.readByCustomers(List.of(8L,7L,7L)).get(0);assertThat(source.position()).containsEntry("claimedAt",null).containsEntry("earlyWithdrawnAt",null).containsEntry("status","FAILED").containsEntry("deleted",true).doesNotContainKey("userDeleted");assertThat(source.ledgers()).containsExactly(ledger);
        verify(mapper).byCustomers(new TreeSet<>(List.of(7L,8L)));verify(mapper).ledgers(List.of("STK-source"));verifyNoMoreInteractions(mapper);
    }
    @Test void emptyOrInvalidScopeNeverBecomesUnscopedRead(){var mapper=mock(SupportStakingSourceMapper.class);var service=new SupportStakingReadService(mapper);assertThat(service.readByCustomers(List.of())).isEmpty();assertThat(service.readByPositionNos(List.of())).isEmpty();assertThatThrownBy(()->service.readByCustomers(List.of(0L))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.readByPositionNos(List.of("bad:scope"))).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(mapper);}
    @Test void manyPositionsStillUseTwoQueriesAndDuplicateOriginalsRemainVisible(){var mapper=mock(SupportStakingSourceMapper.class);var service=new SupportStakingReadService(mapper);var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<1000;i++)rows.add(Map.of("positionNo","STK-"+i,"customerId",7L));rows.add(rows.get(0));when(mapper.byCustomers(anyCollection())).thenReturn(rows);when(mapper.ledgers(anyCollection())).thenReturn(List.of());assertThat(service.readByCustomers(List.of(7L))).hasSize(1001);verify(mapper).byCustomers(anyCollection());verify(mapper).ledgers(anyCollection());verifyNoMoreInteractions(mapper);}
    @ParameterizedTest @ValueSource(strings={"byCustomers","byPositionNos","ledgers"})
    void actualBoundSqlHasOnlyDeclaredScopedPlainParameters(String method){var config=new Configuration();config.addMapper(SupportStakingSourceMapper.class);var ids=method.equals("byCustomers")?List.of(7L,8L):List.of("STK-'OR1=1--","STK-b");var bound=config.getMappedStatement(SupportStakingSourceMapper.class.getName()+"."+method).getBoundSql(Map.of("ids",ids));String sql=bound.getSql().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT);assertThat(sql).contains(method.equals("byCustomers")?"WHERE P.USER_ID IN": "WHERE P.POSITION_NO IN").doesNotContain("FOR UPDATE","FOR SHARE","LOCK IN","STK-'OR1=1--","IS_DELETED=0","STATUS='ACTIVE'","${");assertThat(bound.getParameterMappings()).hasSize(2);assertThat(sql.chars().filter(c->c=='?').count()).isEqualTo(2);}
    @Test void actualPrecisionIsScopedAndTypedWithoutFlagsAsNumbers(){var mapper=mock(SupportStakingSourceMapper.class);var service=new SupportStakingReadService(mapper);when(mapper.timePrecisions()).thenReturn(List.of(Map.of("tableName","nx_staking_position","columnName","claimed_at","precision",0)));assertThat(service.timePrecisions()).containsEntry("nx_staking_position.claimed_at",0);for(Object invalid:List.of(true,"0",7,new java.math.BigDecimal("0.5"))){when(mapper.timePrecisions()).thenReturn(List.of(Map.of("tableName","nx_staking_position","columnName","claimed_at","precision",invalid)));assertThatThrownBy(service::timePrecisions).isInstanceOf(RuntimeException.class);}var config=new Configuration();config.addMapper(SupportStakingSourceMapper.class);assertThat(config.getMappedStatement(SupportStakingSourceMapper.class.getName()+".timePrecisions").getBoundSql(null).getSql()).contains("table_schema=DATABASE()","table_name='nx_staking_position'","'created_at','claimed_at','early_withdrawn_at'");}
}
