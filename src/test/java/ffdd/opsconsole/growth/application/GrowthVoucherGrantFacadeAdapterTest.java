package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.facade.VoucherGrantFacade.VoucherGrantCommand;
import ffdd.opsconsole.growth.mapper.GrowthVoucherGrantMapper;
import ffdd.opsconsole.growth.mapper.GrowthVoucherMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GrowthVoucherGrantFacadeAdapterTest {

    @Mock GrowthVoucherMapper voucherMapper;
    @Mock GrowthVoucherGrantMapper mapper;
    @Mock AuditLogService audit;
    @Mock EventOutboxService outbox;

    private GrowthVoucherGrantFacadeAdapter service;

    @BeforeEach
    void setUp() {
        service = new GrowthVoucherGrantFacadeAdapter(voucherMapper, mapper, audit, outbox);
    }

    @Test
    void grantsActiveVoucherOnceWithRequiredAuditAndOutbox() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockGrantableVoucher(eq("VC-100"), anyLong()))
                .thenReturn(Map.of("voucherId", "VC-100", "status", "active"));
        when(mapper.insertGrant(any(), eq("vrank:42:v5:VC-100"), eq("VC-100"), eq(42L),
                eq("VRANK_REWARD"), eq("42:V5:voucher"), eq("ENGINE"), eq("F1 V-Rank voucher reward")))
                .thenReturn(1);

        var result = service.grant(command());

        assertThat(result.grantId()).startsWith("VGR-");
        assertThat(result.replayed()).isFalse();
        verify(audit).recordRequired(any());
        verify(outbox).publish(eq("VOUCHER_GRANT"), eq(result.grantId()), eq("H7_VOUCHER_GRANTED"), any());
    }

    @Test
    void exactGrantKeyReplayReturnsExistingGrantWithoutDuplicateSideEffects() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockGrantableVoucher(eq("VC-100"), anyLong()))
                .thenReturn(Map.of("voucherId", "VC-100", "status", "active"));
        when(mapper.insertGrant(any(), any(), any(), anyLong(), any(), any(), any(), any())).thenReturn(0);
        when(mapper.findByGrantKey("vrank:42:v5:VC-100")).thenReturn(Map.of(
                "grantId", "VGR-existing", "grantKey", "vrank:42:v5:VC-100", "voucherId", "VC-100",
                "userId", 42L, "sourceType", "VRANK_REWARD", "sourceId", "42:V5:voucher", "status", "AVAILABLE"));

        var result = service.grant(command());

        assertThat(result).isEqualTo(new ffdd.opsconsole.growth.facade.VoucherGrantFacade.VoucherGrantResult(
                "VGR-existing", true));
        verify(audit, never()).recordRequired(any());
        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    @Test
    void rejectsUnknownInactiveOrExpiredVoucherBeforeWritingGrant() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockGrantableVoucher(eq("VC-100"), anyLong())).thenReturn(null);

        assertThatThrownBy(() -> service.grant(command()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H7_VOUCHER_NOT_GRANTABLE");

        verify(mapper, never()).insertGrant(any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void requiredAuditFailurePropagatesAndPreventsOutboxPublication() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockGrantableVoucher(eq("VC-100"), anyLong()))
                .thenReturn(Map.of("voucherId", "VC-100", "status", "active"));
        when(mapper.insertGrant(any(), any(), any(), anyLong(), any(), any(), any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordRequired(any());

        assertThatThrownBy(() -> service.grant(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit unavailable");

        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    private VoucherGrantCommand command() {
        return new VoucherGrantCommand(42L, "VC-100", "vrank:42:v5:VC-100",
                "VRANK_REWARD", "42:V5:voucher", "ENGINE", "F1 V-Rank voucher reward");
    }
}
