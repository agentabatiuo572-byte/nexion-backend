package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class ComputeV2ReadMapperTest {
    private final Configuration configuration=new Configuration();

    ComputeV2ReadMapperTest() { configuration.addMapper(ComputeV2ReadMapper.class); }

    @Test void everyProjectionBindsOwnerScopeAndItsOnlySupportedSelectors() {
        var parameters=new HashMap<String,Object>();
        parameters.put("userId",10L); parameters.put("scope","TEST");
        parameters.put("eventIds",List.of("event-1","event-2"));
        parameters.put("taskNos",List.of("task-1")); parameters.put("batchNo","batch-1");
        parameters.put("beforeId",null); parameters.put("beforePostedAt",null); parameters.put("limit",21);
        for (String method : List.of("pendingAllocations","postedTasks","batches","batch","allocations")) {
            var sql=sql(method,parameters);
            assertThat(sql.getSql()).contains("e.user_id=a.user_id","e.deployment_scope=?");
            assertThat(sql.getSql()).contains(method.equals("batch") || method.equals("batches") ? "s.user_id=?" : "a.user_id=?");
            assertThat(values(sql,parameters)).contains(10L,"TEST");
        }
        assertThat(sql("allocations",parameters).getSql()).contains("LIMIT 201");
    }

    @Test void eventListsAndSeekCursorUseBoundParametersInsteadOfSqlFragments() {
        String opaque="event' OR 1=1 --";
        var parameters=new HashMap<String,Object>();
        parameters.put("userId",10L); parameters.put("scope","TEST"); parameters.put("eventIds",List.of(opaque,"event-2"));
        var allocations=sql("pendingAllocations",parameters);
        assertThat(allocations.getSql().replaceAll("\\s+"," ")).doesNotContain(opaque).contains("IN ( ? , ? )");
        assertThat(values(allocations,parameters)).containsExactly(10L,"TEST",opaque,"event-2");
        var timestamp=LocalDateTime.of(2026,11,1,1,0);
        parameters.put("beforeId","batch-1"); parameters.put("beforePostedAt",timestamp); parameters.put("limit",21);
        var page=sql("batches",parameters);
        assertThat(page.getSql()).contains("s.posted_at_utc < ?","s.settlement_id < ?","LIMIT ?");
        assertThat(values(page,parameters)).containsExactly("TEST",10L,timestamp,timestamp,"batch-1",21);
    }

    private BoundSql sql(String method,Map<String,Object> parameters) {
        return configuration.getMappedStatement(ComputeV2ReadMapper.class.getName()+"."+method).getBoundSql(parameters);
    }

    private List<Object> values(BoundSql sql,Map<String,Object> parameters) {
        return sql.getParameterMappings().stream().map(mapping -> {
            String property=mapping.getProperty();
            return sql.hasAdditionalParameter(property) ? sql.getAdditionalParameter(property) : parameters.get(property);
        }).toList();
    }
}
