package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LearningCourseConcurrencyMySqlUrlGuardTest {
    @Test
    void allowsOnlyTheExplicitDisposableServerAndGeneratedSchema() {
        String schema = "nexion_learning_course_it_" + "a".repeat(32);
        assertThat(LearningCourseConcurrencyMySqlTest.jdbcUrl("127.0.0.1:13306", schema))
                .startsWith("jdbc:mysql://127.0.0.1:13306/" + schema + "?");
        assertThat(LearningCourseConcurrencyMySqlTest.jdbcUrl("127.0.0.1:13306", ""))
                .startsWith("jdbc:mysql://127.0.0.1:13306/?");
        for (String endpoint : new String[] {null, "", "127.0.0.1:3306", "127.0.0.1:03306",
                "127.0.0.1:013306", "localhost:13306", "127.0.0.1:13306/other"}) {
            assertThatThrownBy(() -> LearningCourseConcurrencyMySqlTest.jdbcUrl(endpoint, schema))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String unsafe : new String[] {null, "nexion", "mysql", schema + ";DROP DATABASE nexion", schema + "/other"}) {
            assertThatThrownBy(() -> LearningCourseConcurrencyMySqlTest.jdbcUrl("127.0.0.1:13306", unsafe))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
