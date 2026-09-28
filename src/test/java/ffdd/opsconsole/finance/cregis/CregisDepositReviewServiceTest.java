package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CregisDepositReviewServiceTest {
    @Test
    void changedLiveEvidenceCannotEnterReviewTransaction() {
        CregisProperties props = new CregisProperties();
        props.setMode(CregisProperties.Mode.PROVIDER);
        props.setProjectId(88);
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisDepositService deposits = mock(CregisDepositService.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(db.reviewCaseSnapshot(9)).thenReturn(Map.of("projectId", 88L, "status", "MAKER_DONE",
                "version", 0L, "makerId", 7L, "eventId", 17L, "evidenceHash", "0".repeat(64)));
        when(deposits.preflightReviewedHold(17)).thenReturn(new CregisDepositService.ReviewProof(
                17, 77, 42, "tx", "address", BigDecimal.TEN, 101, "block", 0, 15,
                100, "allocation", Instant.now()));
        CregisDepositReviewService service = new CregisDepositReviewService(props, db, deposits,
                mock(AuditLogService.class), tx);
        assertThatThrownBy(() -> service.check(8, 9, 0, "APPROVE", "Reviewed chain evidence"))
                .hasMessage("CREGIS_REVIEW_EVIDENCE_CHANGED");
        verify(db, never()).lockReviewCase(9);
    }

    @Test
    void sameAuthenticatedAdministratorCannotCheckOwnCreditProposal() {
        CregisProperties props = new CregisProperties();
        props.setMode(CregisProperties.Mode.PROVIDER);
        props.setProjectId(88);
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        CregisDepositService deposits = mock(CregisDepositService.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(db.lockReviewCase(9)).thenReturn(Map.of("projectId", 88L, "status", "MAKER_DONE",
                "version", 0L, "makerId", 7L, "eventId", 17L));
        when(db.reviewCaseSnapshot(9)).thenReturn(Map.of("projectId", 88L, "status", "MAKER_DONE",
                "version", 0L, "makerId", 7L, "eventId", 17L));
        CregisDepositReviewService service = new CregisDepositReviewService(props, db, deposits,
                mock(AuditLogService.class), tx);
        assertThatThrownBy(() -> service.check(7, 9, 0, "APPROVE", "Reviewed chain evidence"))
                .hasMessage("CREGIS_REVIEW_DIFFERENT_CHECKER_REQUIRED");
        verify(db, never()).checkReviewCase(9, 7, "RESOLVED", 0);
        verify(deposits, never()).preflightReviewedHold(17);
    }
}
