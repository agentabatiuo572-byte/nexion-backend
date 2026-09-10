package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AppTaskAssignmentReceiptCursorMySqlUrlGuardTest {
    @Test
    void permitsOnlyTheExplicitIsolatedPortAndRejectsBusinessAndLeadingZeroVariants() {
        assertThatCode(() -> AppTaskAssignmentReceiptCursorReadOnlyMySqlTest
                .requireIsolatedEndpoint("127.0.0.1:13306")).doesNotThrowAnyException();
        assertThatThrownBy(() -> AppTaskAssignmentReceiptCursorReadOnlyMySqlTest
                .requireIsolatedEndpoint("127.0.0.1:3306")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AppTaskAssignmentReceiptCursorReadOnlyMySqlTest
                .requireIsolatedEndpoint("127.0.0.1:013306")).isInstanceOf(IllegalStateException.class);
    }
}
