package ffdd.opsconsole.team.mapper;

import ffdd.opsconsole.team.application.SupportInvitationReadService;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

class SupportInvitationReadMapperSqlTest {
    @Test void rootsAreExplicitExactIdsAndChildrenAreOneOrdinaryUnprunedFrontierRead() {
        var configuration = new Configuration();
        configuration.addMapper(SupportInvitationReadMapper.class);
        var prefix = SupportInvitationReadMapper.class.getName()+".";
        for (var name : List.of("readUsers","children")) {
            var statement = configuration.getMappedStatement(prefix+name);
            var parameters = name.equals("readUsers")?Map.of("customerIds",List.of(3L,7L)):
                Map.of("sponsorCustomerIds",List.of(3L,7L));
            var bound = statement.getBoundSql(parameters);
            String sql = bound.getSql().replaceAll("\\s+"," ").trim();
            assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
            assertThat(bound.getParameterMappings()).hasSize(2);
            assertThat(sql).contains("FROM nx_user", "sandbox", "is_deleted deleted", "status");
            assertThat(sql.replace(" ","")).contains("?,?");
            assertThat(sql).doesNotContain("FOR UPDATE","FOR SHARE","RECURSIVE","LIMIT","level","ACTIVE",
                "is_deleted=0","sandbox=","nx_team_member","nx_support","nickname","phone");
            assertThat(sql).contains(name.equals("readUsers")?"WHERE id IN":"WHERE sponsor_user_id IN");
        }
    }

    @Test void serviceOwnsAnRrReadSnapshotAndTheValueBoundaryHasNoHttpOrFinancialIdentityFields() throws Exception {
        var transaction = SupportInvitationReadService.class.getMethod("readInvitations",java.util.Collection.class)
            .getAnnotation(Transactional.class);
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(ffdd.opsconsole.common.boundary.DomainFacade.class)
            .isAssignableFrom(ffdd.opsconsole.team.facade.SupportInvitationReadFacade.class);
        assertThat(ffdd.opsconsole.team.facade.SupportInvitationReadFacade.Invitation.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .containsExactly("rootCustomerId","rootSandbox","directCustomerIds","descendantCustomerIds","completeness","reasons");
    }
}
