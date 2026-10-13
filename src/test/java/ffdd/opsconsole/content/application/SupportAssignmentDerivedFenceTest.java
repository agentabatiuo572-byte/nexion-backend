package ffdd.opsconsole.content.application;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Complete assignment row images only; no database, fixture writes or production commands. */
class SupportAssignmentDerivedFenceTest {
    private static LinkedHashMap<String,Object> active() {
        var row=new LinkedHashMap<String,Object>();row.put("id",31L);row.put("user_id",17L);row.put("agent_admin_id",7L);
        row.put("status","ACTIVE");row.put("is_deleted",0);row.put("active_user_id",17L);row.put("version",3L);
        row.put("starts_at",Timestamp.valueOf("2026-10-10 01:00:00"));row.put("ends_at",null);
        row.put("updated_at",Timestamp.valueOf("2026-10-10 01:00:00"));row.put("operation_id","owned-transfer");return row;
    }
    private static LinkedHashMap<String,Object> closed(Map<String,Object> before) {
        var row=new LinkedHashMap<>(before);row.put("status","INACTIVE");row.put("active_user_id",null);
        row.put("ends_at",Timestamp.valueOf("2026-10-10 02:00:00"));row.put("updated_at",Timestamp.valueOf("2026-10-10 02:00:00"));
        row.put("version",4L);return row;
    }
    private static void validate(Map<String,Object> before,Map<String,Object> after) {
        SupportGroupRuntimeFixtures.ExactFacts.validateUpdate("nx_support_agent_user_assignment",before,after,"CUSTOMER_TRANSFER_HTTP");
    }
    @Test void actualInactiveCloseReadsDerivedNullWithoutWritingOrReassigningOwnership() {
        var before=active();var after=closed(before);assertThatCode(()->validate(before,after)).doesNotThrowAnyException();
        assertThat(SupportGroupRuntimeFixtures.ExactFacts.fence(after)).contains("`active_user_id` <=> ?");
        assertThat(after.get("user_id")).isEqualTo(before.get("user_id"));assertThat(after.get("agent_admin_id")).isEqualTo(before.get("agent_admin_id"));
    }
    @Test void incorrectBeforeDerivedValueCannotHideBehindUnchangedNull() {
        var before=active();before.put("active_user_id",null);var after=closed(before);
        assertThatThrownBy(()->validate(before,after)).isInstanceOf(AssertionError.class);
    }
    @Test void incorrectAfterDerivedValueCannotHideBehindUnchangedOriginalUser() {
        var before=active();var after=closed(before);after.put("active_user_id",before.get("user_id"));
        assertThatThrownBy(()->validate(before,after)).isInstanceOf(AssertionError.class);
    }
    @Test void generatedColumnCannotBeClearedByHandWhileStatusRemainsActive() {
        var before=active();var after=closed(before);after.put("status","ACTIVE");
        assertThatThrownBy(()->validate(before,after)).isInstanceOf(AssertionError.class);
    }
    @Test void formulaUsesStatusAndDeletionRatherThanEndTime() {
        var before=active();var after=closed(before);after.put("status","ACTIVE");after.put("active_user_id",before.get("user_id"));
        assertThatCode(()->validate(before,after)).doesNotThrowAnyException();
        var deleted=active();deleted.put("is_deleted",1);deleted.put("active_user_id",null);
        assertThatCode(()->validate(deleted,closed(deleted))).doesNotThrowAnyException();
        deleted.put("active_user_id",deleted.get("user_id"));var wrong=closed(deleted);wrong.put("active_user_id",deleted.get("user_id"));
        assertThatThrownBy(()->validate(deleted,wrong)).isInstanceOf(AssertionError.class);
    }
    @Test void customerAgentDeletionPrimaryAndCreatorCannotChange() {
        for(String column:List.of("id","user_id","agent_admin_id","is_deleted","starts_at","operation_id")) {
            var before=active();var after=closed(before);after.put(column,column.equals("operation_id")?"foreign-operation":column.equals("starts_at")?Timestamp.valueOf("2026-10-10 00:00:00"):99);
            assertThatThrownBy(()->validate(before,after)).as(column).isInstanceOf(AssertionError.class);
        }
    }
    @Test void versionAndHalfOpenEndConstraintsAreNotRelaxed() {
        var before=active();var after=closed(before);after.put("version",before.get("version"));
        assertThatThrownBy(()->validate(before,after)).isInstanceOf(AssertionError.class);
        after.put("version",2L);assertThatThrownBy(()->validate(before,after)).isInstanceOf(AssertionError.class);
        after.put("version",4L);after.put("ends_at",null);assertThatThrownBy(()->validate(before,after)).isInstanceOf(AssertionError.class);
        before.put("ends_at",Timestamp.valueOf("2026-10-10 01:30:00"));after.put("ends_at",Timestamp.valueOf("2026-10-10 02:00:00"));
        assertThatThrownBy(()->validate(before,after)).isInstanceOf(AssertionError.class);
    }
    @Test void missingDerivedOrFormulaInputsAreNotAcceptedAsCompleteReadback() {
        for(String column:List.of("active_user_id","status","is_deleted","user_id")) {
            var before=active();var after=closed(before);before.remove(column);after.remove(column);
            assertThatThrownBy(()->validate(before,after)).as(column).isInstanceOf(AssertionError.class);
        }
    }
}
