package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class SupportAgentMapperTicketAssigneeCandidateSqlContractTest {

    @Test
    void candidateProjectionIsAReadOnlyTwoFieldJoinWithoutSchemaOrSeedStatements() {
        Method method = Arrays.stream(SupportAgentMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("listTicketAssigneeCandidates"))
                .findFirst()
                .orElseThrow();
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();

        assertThat(sql)
                .startsWith("select distinct a.id as adminid")
                .contains(" as name from nx_admin a")
                .contains("join nx_admin_role_relation rr")
                .contains("join nx_admin_role r")
                .contains("join nx_support_agent_profile p")
                .contains("a.status = 1")
                .contains("r.role_code = 'support'")
                .contains("p.enabled = 1")
                .contains("p.transferable = 1")
                .contains("find_in_set('support'")
                .doesNotContain(" create ", " alter ", " insert ", " update ", " delete ", " for update");
        assertThat(sql.substring(0, sql.indexOf(" from ")))
                .doesNotContain("email", "username as", "role", "seat", "service", "tags", "busy", "capacity");
    }
}
