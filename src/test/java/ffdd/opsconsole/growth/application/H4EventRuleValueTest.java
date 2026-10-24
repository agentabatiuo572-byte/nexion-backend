package ffdd.opsconsole.growth.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class H4EventRuleValueTest {
    @Test void preservesMysqlBooleanFlagsInsteadOfTreatingTrueAsZero() {
        assertThat(H4EventRuleService.number(Boolean.TRUE)).isEqualByComparingTo("1");
        assertThat(H4EventRuleService.number(Boolean.FALSE)).isEqualByComparingTo("0");
        assertThat(H4EventRuleService.number(1)).isEqualByComparingTo("1");
        assertThat(H4EventRuleService.number("2.5")).isEqualByComparingTo("2.5");
        assertThat(H4EventRuleService.number(null)).isEqualByComparingTo("0");
    }
}
