package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

class FinancialCommandIsolationTest {
    @Test
    void financialCommandsKeepReadCommittedAtomicityAndRollbackCheckedFailures() {
        var attributes = new AnnotationTransactionAttributeSource();
        for (var type : new Class<?>[] {AppExchangeService.class, AppStakingService.class,
                AppRepurchaseService.class, ffdd.opsconsole.finance.application.AppWithdrawalService.class,
                ffdd.opsconsole.device.application.AppTradeinService.class}) {
            var commands = type == AppExchangeService.class ? Set.of("swap", "cancel")
                    : type == ffdd.opsconsole.finance.application.AppWithdrawalService.class ? Set.of("submit", "abandonAttempt")
                    : type == ffdd.opsconsole.device.application.AppTradeinService.class ? Set.of("capacityKeep")
                    : Set.of("open", "claim", "earlyWithdraw");
            var found = new java.util.HashSet<String>();
            for (var method : type.getDeclaredMethods()) {
                if (!commands.contains(method.getName())) continue;
                found.add(method.getName());
                var attribute = attributes.getTransactionAttribute(method, type);
                assertThat(attribute).as(type.getSimpleName() + "." + method.getName()).isNotNull();
                assertThat(attribute.getIsolationLevel()).as(method.getName() + " outer isolation")
                        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
                assertThat(attribute.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
                assertThat(attribute.rollbackOn(new Exception("checked business failure")))
                        .as(method.getName() + " checked failure rollback").isTrue();
            }
            assertThat(found).containsExactlyInAnyOrderElementsOf(commands);
        }
    }
}
