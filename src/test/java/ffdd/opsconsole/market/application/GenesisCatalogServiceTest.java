package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.market.mapper.GenesisCatalogMapper;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.CatalogState;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.TierRow;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.InviteRow;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class GenesisCatalogServiceTest {
    private final GenesisCatalogMapper mapper = mock(GenesisCatalogMapper.class);
    private final GenesisCatalogService service = new GenesisCatalogService(mapper,
            mock(AdminIdempotencyService.class), mock(AuditLogService.class),
            Clock.fixed(Instant.parse("2026-08-07T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void updateTierRejectsBoundaryThatWouldInvertTheNextRangeBeforeAnyWrite() {
        when(mapper.lockState()).thenReturn(new CatalogState(1L, 7L, "closed", 2L,
                "default", "seed", 3L));
        when(mapper.activeTiers()).thenReturn(List.of(
                new TierRow("t1", 0, 100, new BigDecimal("10")),
                new TierRow("t2", 100, 200, new BigDecimal("20"))));
        when(mapper.soldCount()).thenReturn(0L);

        assertThatThrownBy(() -> service.updateTierOnce("t1",
                new GenesisCatalogService.TierRequest(250, new BigDecimal("11"), 7L,
                        "reject inverted neighbor range", "superadmin")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(422);
                    assertThat(ex.getMessage()).isEqualTo("GENESIS_TIER_CROSSES_NEXT_RANGE");
                });

        verify(mapper, never()).updateTier(anyString(), anyInt(), any(BigDecimal.class));
        verify(mapper, never()).updateTierFrom(anyString(), anyInt());
        verify(mapper, never()).advanceTierVersion(anyLong(), anyLong());
    }

    @Test
    void issuedInviteUsesAtLeastSixtyFourBitsOfRandomPayload() {
        when(mapper.insertInvite(anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.inviteCodes()).thenReturn(List.of());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);

        assertThat(service.issueInvitesOnce(new GenesisCatalogService.InviteIssueRequest(
                1, "security entropy check", "superadmin")).getCode()).isZero();

        verify(mapper).insertInvite(code.capture(), anyString(), anyString());
        assertThat(code.getValue()).matches("NEXGRID-OG-[A-F0-9]{16}");
    }

    @Test
    void concurrentSecondCodeForSameAccountReturnsStableConflictInsteadOfDatabase500() {
        String code = "NEXGRID-OG-0123456789ABCDEF";
        when(mapper.lockInvite(code)).thenReturn(new InviteRow(code, "unused", "superadmin",
                null, "", null, null, null, null, null));
        when(mapper.redeemedCount(42L)).thenReturn(0);
        when(mapper.redeemInvite(code, 42L))
                .thenThrow(new DuplicateKeyException("uk_genesis_invite_redeemed_account"));

        assertThatThrownBy(() -> service.redeem(42L, code))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(409);
                    assertThat(ex.getMessage()).isEqualTo("GENESIS_INVITE_ACCOUNT_ALREADY_REDEEMED");
                });
    }
}
