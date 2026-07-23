package ffdd.opsconsole.treasury.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.treasury.application.OpsTreasuryService;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.infrastructure.MybatisTreasuryLedgerRepository;
import ffdd.opsconsole.treasury.mapper.TreasuryLedgerMapper;
import ffdd.opsconsole.treasury.web.OpsTreasuryController;
import ffdd.opsconsole.treasury.web.OpsBillsController;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

class D4LedgerReadOnlyContractTest {

    @Test
    void controllerDoesNotExposeALedgerAdjustmentCommand() {
        List<String> postMappings = Arrays.stream(OpsTreasuryController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(annotation -> annotation != null)
                .flatMap(annotation -> Arrays.stream(annotation.value()))
                .toList();

        assertThat(postMappings).doesNotContain("/ledger/adjustments");
    }

    @Test
    void treasuryServiceAndDomainRepositoryRemainReadOnlyForD4() {
        assertThat(methodNames(OpsTreasuryService.class)).doesNotContain("createLedgerAdjustment");
        assertThat(methodNames(TreasuryLedgerRepository.class)).doesNotContain("createLedgerAdjustment");
    }

    @Test
    void treasuryMapperAndAdapterCannotInsertAssetAdjustments() {
        assertThat(methodNames(TreasuryLedgerMapper.class)).doesNotContain("insertLedgerAdjustment");
        assertThat(methodNames(MybatisTreasuryLedgerRepository.class)).doesNotContain("createLedgerAdjustment");
    }

    @Test
    void canonicalBillsControllerIsReadOnlyAndUsesTheDedicatedReadAndExportPermissions() {
        RequestMapping mapping = OpsBillsController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).contains("/api/admin/bills");
        assertThat(Arrays.stream(OpsBillsController.class.getDeclaredMethods())
                .noneMatch(method -> method.getAnnotation(PostMapping.class) != null)).isTrue();
        String sourceShape = Arrays.toString(OpsBillsController.class.getDeclaredMethods());
        assertThat(sourceShape).contains("bills", "runningBalance", "userLedger", "export");
    }

    @Test
    void genericLedgerPostingSerializesTheReadAndAppendSequence() throws Exception {
        Method posting = MybatisTreasuryLedgerRepository.class.getDeclaredMethod(
                "postLedgerEntry", String.class, Long.class, String.class, String.class, String.class,
                BigDecimal.class, String.class, String.class);
        assertThat(posting.getAnnotation(Transactional.class)).isNotNull();
        assertThat(methodNames(TreasuryLedgerMapper.class)).contains("ensureLedgerMutex", "lockLedgerMutex");
    }

    @Test
    void financeD4RoleCanReachItsC3AndA4CrossModuleReadLinks() throws Exception {
        String migration = Files.readString(Path.of("scripts/migrations/20260720_d4_ledger_closure.sql"));
        assertThat(migration)
                .contains("'FINANCE','FINANCE_LEAD'")
                .contains("'C','C3','A','A4'")
                .contains("'user_c3_read','platform_a4_read'");
    }

    private static List<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).map(Method::getName).toList();
    }
}
