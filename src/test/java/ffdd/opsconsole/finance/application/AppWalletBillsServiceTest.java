package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.AppWalletBillsMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppWalletBillsServiceTest {
    @Test
    void productionUserReceivesOnlyTheirCanonicalLedger() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        when(mapper.rows(7L, 200)).thenReturn(List.of(new AppWalletBillsMapper.LedgerRow(
                11L, "WD-1", "WITHDRAWAL", "USDT", "OUT", new BigDecimal("12.5"),
                new BigDecimal("87.5"), "SUCCESS", "withdrawal", LocalDateTime.of(2026, 8, 14, 9, 0))));
        var service = new AppWalletBillsService(mapper, environment("prod"));

        var result = service.list(7L).getData();

        assertThat(result).containsEntry("source", "server").containsEntry("sourceEnvironment", "PRODUCTION");
        assertThat((List<?>) result.get("bills")).hasSize(1);
        verify(mapper).rows(7L, 200);
    }

    @Test
    void developmentAccountReceivesCanonicalBusinessLedgerWithProductionShapedContract() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        when(mapper.count(7L)).thenReturn(1L);
        when(mapper.rows(7L, 50, 0)).thenReturn(List.of(new AppWalletBillsMapper.LedgerRow(
                12L, "QUEST:H3:7", "QUEST_REWARD", "NEX", "IN", new BigDecimal("50"),
                new BigDecimal("50"), "POSTED", "quest reward", LocalDateTime.of(2026, 8, 23, 21, 0))));
        var service = new AppWalletBillsService(mapper, environment("dev"));

        var result = service.list(7L, 1, 50).getData();

        assertThat(result).containsEntry("source", "server").containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("page", 1).containsEntry("pageSize", 50).containsEntry("total", 1L);
        assertThat((List<?>) result.get("bills")).singleElement()
                .isInstanceOfSatisfying(Map.class, bill -> assertThat(bill).containsEntry("status", "SUCCESS"));
        verify(mapper).rows(7L, 50, 0);
    }

    @Test
    void testProfileRemainsIsolatedFromCanonicalLedger() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        var service = new AppWalletBillsService(mapper, environment("test"));

        assertThatThrownBy(() -> service.list(7L)).isInstanceOf(BizException.class)
                .hasMessageContaining("WALLET_PRODUCTION_BILLS_FORBIDDEN");
        verify(mapper, never()).rows(7L, 200);
    }

    @Test
    void canonicalLedgerStatusesMapToTheThreeStateAppContract() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        when(mapper.rows(7L, 200)).thenReturn(List.of(
                ledgerRow(13L, "SUCCESS"),
                ledgerRow(14L, "POSTED"),
                ledgerRow(15L, "COMPLETED"),
                ledgerRow(16L, "CONFIRMED"),
                ledgerRow(17L, "PENDING"),
                ledgerRow(18L, "FAILED"),
                ledgerRow(19L, "REJECTED"),
                ledgerRow(20L, "CANCELLED")));
        var service = new AppWalletBillsService(mapper, environment("prod"));

        var result = service.list(7L).getData();

        List<String> statuses = ((List<?>) result.get("bills")).stream()
                .map(row -> String.valueOf(((Map<?, ?>) row).get("status")))
                .toList();
        assertThat(statuses).containsExactly(
                "SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS",
                "PENDING", "FAILED", "FAILED", "FAILED");
    }

    @Test
    void unknownLedgerStatusFailsClosedBeforeSendingAnInvalidApiContract() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        when(mapper.rows(7L, 200)).thenReturn(List.of(ledgerRow(21L, "SETTLED")));
        var service = new AppWalletBillsService(mapper, environment("prod"));

        assertThatThrownBy(() -> service.list(7L)).isInstanceOf(BizException.class)
                .hasMessageContaining("WALLET_LEDGER_STATUS_INVALID");
    }

    @Test
    void businessLocalLedgerTimeIsPublishedAsAnUnambiguousInstant() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        when(mapper.rows(7L, 200)).thenReturn(List.of(new AppWalletBillsMapper.LedgerRow(
                22L, "G4-SBX-1", "GENESIS_PURCHASE", "USDT", "OUT", new BigDecimal("7999"),
                new BigDecimal("2711.97"), "SUCCESS", "Genesis purchase",
                LocalDateTime.of(2026, 8, 28, 11, 10, 12))));
        var service = new AppWalletBillsService(mapper, environment("dev"));

        @SuppressWarnings("unchecked")
        var bills = (List<Map<String, Object>>) service.list(7L).getData().get("bills");

        assertThat(bills).singleElement()
                .satisfies(bill -> assertThat(bill).containsEntry("createdAt", "2026-08-28T03:10:12Z"));
    }

    @Test
    void cursorPagingUsesAnOpaqueCreatedAtAndIdBoundaryWithoutChangingTheLegacyPageContract() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        LocalDateTime first = LocalDateTime.of(2026, 8, 31, 12, 0, 0, 123_456_000);
        when(mapper.rowsAfter(7L, 3, null, null, null, null, null))
                .thenReturn(List.of(ledgerRow(31L, "SUCCESS", "QUEST_REWARD", "NEX", "IN", first),
                        ledgerRow(30L, "SUCCESS", "ORDER_PURCHASE", "USDT", "OUT", first),
                        ledgerRow(29L, "SUCCESS", "EARN", "NEX", "IN", first.minusSeconds(1))));
        var service = new AppWalletBillsService(mapper, environment("prod"));

        Map<String, Object> firstPage = service.list(7L, 1, 2, null, null, null, "start").getData();

        assertThat(firstPage).containsEntry("nextPage", 2);
        assertThat(firstPage.get("nextCursor")).isInstanceOf(String.class)
                .asString().doesNotContain("2026-08-31", "30");
        assertThat((List<?>) firstPage.get("bills")).hasSize(2);
        verify(mapper).rowsAfter(7L, 3, null, null, null, null, null);
    }

    @Test
    void cursorPreservesMicrosecondBoundaryAndRejectsValuesOutsideMySqlDatetimeRange() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        LocalDateTime boundary = LocalDateTime.of(2026, 8, 31, 12, 0, 0, 123_456_000);
        when(mapper.rowsAfter(7L, 2, null, null, null, null, null)).thenReturn(List.of(
                ledgerRow(52L, "SUCCESS", "EARN", "NEX", "IN", boundary.plusNanos(1)),
                ledgerRow(51L, "SUCCESS", "EARN", "NEX", "IN", boundary)));
        when(mapper.count(7L)).thenReturn(2L);
        var service = new AppWalletBillsService(mapper, environment("prod"));

        String cursor = String.valueOf(service.list(7L, 1, 1, null, null, null, "start").getData().get("nextCursor"));
        service.list(7L, 2, 1, null, null, null, cursor);

        verify(mapper).rowsAfter(7L, 2, null, null, null, boundary.plusNanos(1), 52L);
        String outOfRange = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v1|0999-12-31T23:59:59|1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.list(7L, 1, 1, null, null, null, outOfRange))
                .isInstanceOf(BizException.class).hasMessageContaining("WALLET_BILLS_CURSOR_INVALID");
    }

    @Test
    void summaryUsesServerAggregatesAndKeepsRewardAndTrialBonusRowsInMyRewards() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        when(mapper.summary(7L,
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 2, 0, 0),
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 10, 1, 0, 0)))
                .thenReturn(new AppWalletBillsMapper.SummaryRow(new BigDecimal("1.2"), new BigDecimal("3.4"),
                        LocalDateTime.of(2026, 8, 30, 23, 0), new BigDecimal("-2"), new BigDecimal("5"), 8L));
        when(mapper.recentNexRows(7L, 10)).thenReturn(List.of(
                ledgerRow(42L, "SUCCESS", "PURCHASE_REWARD", "NEX", "IN", LocalDateTime.of(2026, 8, 31, 8, 0)),
                ledgerRow(41L, "PENDING", "QUEST_REWARD", "NEX", "IN", LocalDateTime.of(2026, 8, 31, 7, 0)),
                ledgerRow(40L, "SUCCESS", "TRIAL_BONUS", "NEX", "IN", LocalDateTime.of(2026, 8, 31, 6, 0))));
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T16:30:00Z"), ZoneOffset.UTC);
        var service = new AppWalletBillsService(mapper, environment("prod"), clock);

        Map<String, Object> result = service.summary(7L).getData();

        assertThat(result).containsEntry("source", "server").containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("timeZone", "Asia/Shanghai").containsEntry("asOf", "2026-08-31T16:30:00Z")
                .containsEntry("rewardsUsdt", new BigDecimal("1.2")).containsEntry("rewardsNex", new BigDecimal("3.4"))
                .containsEntry("todayNexEarn", new BigDecimal("-2")).containsEntry("pendingNex", new BigDecimal("5"))
                .containsEntry("monthBillCount", 8L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("recentNexBills");
        assertThat(rows).extracting(row -> row.get("category")).containsExactly("bonus", "achievement", "bonus");
    }

    @Test
    void malformedFiltersAndCursorsFailClosedBeforeAnyLedgerRead() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        var service = new AppWalletBillsService(mapper, environment("prod"));

        assertThatThrownBy(() -> service.list(7L, 1, 50, "BTC", null, null, null))
                .isInstanceOf(BizException.class).hasMessageContaining("WALLET_BILLS_FILTER_INVALID");
        assertThatThrownBy(() -> service.list(7L, 1, 50, null, "IN", "REWARD", "not-a-cursor"))
                .isInstanceOf(BizException.class).hasMessageContaining("WALLET_BILLS_CURSOR_INVALID");
        verify(mapper, never()).rows(7L, 50, 0);
    }

    @Test
    void overflowingLegacyOffsetIsRejectedBeforeAnyLedgerQuery() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        var service = new AppWalletBillsService(mapper, environment("prod"));

        assertThatThrownBy(() -> service.list(7L, Integer.MAX_VALUE, 2))
                .isInstanceOf(BizException.class).hasMessageContaining("WALLET_BILLS_PAGE_INVALID");
        verify(mapper, never()).count(7L);
    }

    @Test
    void explicitPageAndPageSizeOutsideThePublicRangeAreRejectedRatherThanClamped() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        var service = new AppWalletBillsService(mapper, environment("prod"));

        assertThatThrownBy(() -> service.list(7L, 0, 50))
                .isInstanceOf(BizException.class).hasMessageContaining("WALLET_BILLS_PAGE_INVALID");
        assertThatThrownBy(() -> service.list(7L, 1, 0))
                .isInstanceOf(BizException.class).hasMessageContaining("WALLET_BILLS_PAGE_INVALID");
        assertThatThrownBy(() -> service.list(7L, 1, 101))
                .isInstanceOf(BizException.class).hasMessageContaining("WALLET_BILLS_PAGE_INVALID");
        verify(mapper, never()).count(7L);
        verify(mapper, never()).rows(7L, 50, 0);
    }

    @Test
    void largestOffsetThatStillFitsAnIntDoesNotOverflowTheNextPageCalculation() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        when(mapper.count(7L)).thenReturn(0L);
        when(mapper.rows(7L, 100, 2_147_483_600)).thenReturn(List.of());
        var service = new AppWalletBillsService(mapper, environment("prod"));

        Map<String, Object> result = service.list(7L, 21_474_837, 100).getData();

        assertThat(result).containsEntry("nextPage", null);
    }

    @Test
    void aValidFinalIntOffsetCannotWrapTheNextPageNumber() {
        AppWalletBillsMapper mapper = mock(AppWalletBillsMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppWalletBillsMapper.UserScope(0));
        when(mapper.count(7L)).thenReturn(Long.MAX_VALUE);
        when(mapper.rows(7L, 1, Integer.MAX_VALUE - 1)).thenReturn(List.of());
        var service = new AppWalletBillsService(mapper, environment("prod"));

        assertThatThrownBy(() -> service.list(7L, Integer.MAX_VALUE, 1))
                .isInstanceOf(BizException.class).hasMessageContaining("WALLET_BILLS_PAGE_INVALID");
    }

    private AppWalletBillsMapper.LedgerRow ledgerRow(long id, String status) {
        return ledgerRow(id, status, "TEST", "USDT", "IN", LocalDateTime.of(2026, 8, 27, 9, 0));
    }

    private AppWalletBillsMapper.LedgerRow ledgerRow(long id, String status, String bizType, String asset,
                                                       String direction, LocalDateTime createdAt) {
        return new AppWalletBillsMapper.LedgerRow(
                id, "BIZ-" + id, bizType, asset, direction, BigDecimal.ONE,
                new BigDecimal("10"), status, "test", createdAt);
    }

    private MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
