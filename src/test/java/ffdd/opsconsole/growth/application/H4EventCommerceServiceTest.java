package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.promotion.application.PromotionPublicService;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class H4EventCommerceServiceTest {
    private final PromotionMapper db = mock(PromotionMapper.class);
    private final PromotionPublicService promotions = mock(PromotionPublicService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.ofHours(8));
    private final GenesisRaffleService raffle = mock(GenesisRaffleService.class);
    private final ffdd.opsconsole.platform.facade.PlatformConfigFacade config=mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class);
    private final H4EventCommerceService service = new H4EventCommerceService(db, promotions, clock, raffle, new EventDeviceGiftService(db),config,null);
    private static final long USER = 7L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final Map<String, Object> TICKET_RULE = Map.of("action", "REINVEST_TICKETS", "thresholdUsdt", 100, "ticketsPerThreshold", 2);
    private static final Map<String, Object> GIFT_RULE = Map.of("targetInvites", 5, "windowDays", 7, "giftProductNo", "SERVER-A", "giftQuantity", 1);
    private Map<String, Object> ticket;

    @BeforeEach
    void setUp() {
        when(config.activeValue("G.staking.app_visible")).thenReturn(java.util.Optional.of("true"));
        when(db.user(USER, true)).thenReturn(Map.of("status", "ACTIVE", "sandbox", 0));
        when(db.requiredRow(contains("nx_staking_product"))).thenReturn(Map.of("id", 1));
        when(db.requiredRow(contains("nx_config_item"))).thenReturn(Map.of("config_value", "1000"));
        Map<String, Object> position = Map.of("position_no", "RP-1", "amount_usdt", new BigDecimal("100.00"), "locked_at", Timestamp.valueOf(START.plusDays(1)));
        when(db.one(contains("nx_staking_position"), eq(USER), eq("RP-1"), eq(START.plusHours(8)), eq(END.plusHours(8)))).thenReturn(position);
        ticket = new HashMap<>(Map.of("id", 11L, "ticket_no", "GT-1", "quantity", 1, "status", "AVAILABLE", "issued_at", Timestamp.valueOf(START.plusDays(1))));
        when(db.one(contains("nx_g7_repurchase_ticket"), eq(USER), eq("RP-1"))).thenAnswer(invocation -> ticket);
        when(raffle.grant(anyLong(), anyString(), anyString(), anyLong(), any())).thenReturn(Map.of("receiptId", "GRT-1"));
        when(raffle.grant(anyLong(), anyString(), anyString(), anyLong(), any(), any())).thenReturn(Map.of("receiptId", "GRT-1"));
        when(db.write(anyString(), any(Object[].class))).thenReturn(1);
        when(db.count(contains("qualifying_windows"), any(Object[].class))).thenReturn(5L);
        when(db.deviceSlotCap()).thenReturn(3L);
        Map<String, Object> product = new HashMap<>(Map.of("id", 2L, "product_no", "SERVER-A", "product_type", "SERVER", "status", "ACTIVE", "vram_total_gb", 16));
        product.putAll(Map.of("tier", "T1", "name", "Server A", "generation", 1, "gpu_model", "A", "hashrate", BigDecimal.TEN, "estimated_daily_usdt", BigDecimal.ONE, "daily_nex", BigDecimal.ONE));
        when(db.product("SERVER-A", true)).thenReturn(product);
        when(db.requiredRow(contains("nx_admin_device_sku"), eq("SERVER-A"))).thenReturn(Map.of("power_text", "200 W", "datacenter", "DC1"));
    }

    @Test void hiddenOrMissingStakingSwitchBlocksOpeningWithoutInvalidatingEarnedTickets(){
        for(var value:List.of(java.util.Optional.of("false"),java.util.Optional.<String>empty())){
            when(config.activeValue("G.staking.app_visible")).thenReturn(value);
            assertThat(service.readiness("evt-reinvest-bonus",TICKET_RULE,START,END)).containsExactly("stakingAppVisible");
            assertThat(service.awardReinvestTickets(USER,"evt-reinvest-bonus",TICKET_RULE,START,END,"RP-1"))
                    .containsEntry("rewardType","GENESIS_TICKET").containsEntry("quantity",1L);
        }
        when(config.activeValue("G.staking.app_visible")).thenReturn(java.util.Optional.of(" true "));
        assertThat(service.readiness("evt-reinvest-bonus",TICKET_RULE,START,END)).isEmpty();
        verify(db,never()).write(anyString(),any(Object[].class));
    }

    @Test
    void reinvestAddsOnlyTheDifferenceToTwoTicketsAndDoesNotCreateWheelTickets() {
        Map<String, Object> receipt = service.award(USER, "reinvest", TICKET_RULE, START, END, "RP-1");
        assertThat(receipt).containsEntry("rewardType", "GENESIS_TICKET").containsEntry("quantity", 1L).containsEntry("receiptId", "GRT-1");
        verify(raffle).grant(USER, "H4_REINVEST", "reinvest:RP-1", 1L, "RP-1");
        verify(db, never()).write(contains("nx_g7_repurchase_ticket"), any(Object[].class));
        verify(db, never()).write(contains("nx_growth_spin_ticket"), any(Object[].class));
    }
    @Test void referralCannotOpenWhenCombinedGiftQuantityExceedsAvailableStock(){
        var product=new HashMap<String,Object>(Map.of("product_type","SERVER","status","ACTIVE","vram_total_gb",16,"inventory_mode","FINITE","stock",0));
        when(db.product("SERVER-A",false)).thenReturn(product);
        assertThat(service.readiness("evt-refer-5-get-pro",GIFT_RULE,START,END)).containsExactly("giftProductNo");
        product.put("stock",1);
        assertThat(service.readiness("evt-refer-5-get-pro",GIFT_RULE,START,END)).isEmpty();
        var two=new HashMap<>(GIFT_RULE);two.put("giftQuantity",2);
        assertThat(service.readiness("evt-refer-5-get-pro",two,START,END)).containsExactly("giftProductNo");
        verify(db,never()).write(anyString(),any(Object[].class));
    }
    @Test void referralReadinessAcceptsExplicitRatedPowerButRejectsAmbiguousHardwareText(){
        var product=new HashMap<String,Object>(Map.of("product_type","SERVER","status","ON_SALE","vram_total_gb",12,"inventory_mode","FINITE","stock",293));
        when(db.product("SERVER-A",false)).thenReturn(product);
        for(var power:List.of("200W（单卡额定）","575W（单卡额定）","1800W（显卡额定合计）","800W（显卡额定合计）","3600W（显卡额定合计）")){
            when(db.requiredRow(contains("nx_admin_device_sku"),eq("SERVER-A"))).thenReturn(Map.of("power_text",power,"datacenter","DC1"));
            assertThat(service.readiness("evt-refer-5-get-pro",GIFT_RULE,START,END)).isEmpty();
        }
        for(var power:List.of("200-250W","200W/300W","200W（单卡额定2）","0W（单卡额定）")){
            when(db.requiredRow(contains("nx_admin_device_sku"),eq("SERVER-A"))).thenReturn(Map.of("power_text",power,"datacenter","DC1"));
            assertThat(service.readiness("evt-refer-5-get-pro",GIFT_RULE,START,END)).containsExactly("giftProductNo");
        }
        verify(db,never()).write(anyString(),any(Object[].class));
    }

    @Test
    void alreadySupplementedAndForfeitedTicketsCannotBeAwardedAgain() {
        ticket.put("quantity", 2);
        assertThatThrownBy(() -> service.award(USER, "reinvest", TICKET_RULE, START, END, "RP-1"))
                .isInstanceOf(BizException.class).hasMessageContaining("H4_EVENT_NOT_ELIGIBLE");
        ticket.put("quantity", 1); ticket.put("status", "FORFEITED");
        assertThatThrownBy(() -> service.award(USER, "reinvest", TICKET_RULE, START, END, "RP-1"))
                .isInstanceOf(BizException.class).hasMessageContaining("H4_EVENT_NOT_ELIGIBLE");
        verify(db, never()).write(anyString(), any(Object[].class));
    }

    @Test
    void missingOrForeignPositionAndFullGlobalCapacityFailBeforeWriting() {
        when(db.one(contains("nx_staking_position"), eq(USER), eq("RP-1"), eq(START.plusHours(8)), eq(END.plusHours(8)))).thenReturn(null);
        assertThatThrownBy(() -> service.award(USER, "reinvest", TICKET_RULE, START, END, "RP-1"))
                .hasMessageContaining("H4_EVENT_NOT_ELIGIBLE");
        verify(db, never()).write(anyString(), any(Object[].class));
    }

    @Test
    void anotherEventCannotSupplementTheSamePositionTwice() {
        when(db.count(contains("nx_genesis_raffle_ticket"), eq(USER), eq("RP-1"))).thenReturn(1L);
        assertThatThrownBy(() -> service.award(USER, "reinvest", TICKET_RULE, START, END, "RP-1"))
                .hasMessageContaining("H4_EVENT_NOT_ELIGIBLE");
        verify(db, never()).write(anyString(), any(Object[].class));
    }

    @Test
    void referralQualificationRetainsAnyCompletedWindowInsideThisActivity() {
        assertThat(service.progress(USER, "invite", GIFT_RULE, START, END))
                .containsEntry("current", 5L).containsEntry("target", 5L).containsEntry("claimKeys", List.of("ONCE"));
        verify(db).count(contains("MAX(window_count)"), eq(7L), eq(LocalDateTime.of(2026, 10, 25, 20, 0)),
                eq(USER), eq(USER), eq(USER), eq(START.plusHours(8)), eq(LocalDateTime.of(2026, 10, 25, 20, 0)));
        assertThat(service.award(USER, "invite", GIFT_RULE, START, END, "ONCE"))
                .containsEntry("rewardType", "DEVICE").containsEntry("quantity", 1).containsEntry("productNo", "SERVER-A");
        verify(db).write(contains("UPDATE nx_product SET stock=stock-?"), eq(1), eq(2L), eq(1));
        verify(db).write(contains("INSERT INTO nx_user_device"), any(Object[].class));
    }

    @Test
    void capacityOrStockFailureLeavesNoDeviceReceiptAndCanBeRetried() {
        when(db.occupiedDeviceSlots(USER)).thenReturn(3L);
        assertThatThrownBy(() -> service.award(USER, "invite", GIFT_RULE, START, END, "ONCE"))
                .hasMessageContaining("H4_EVENT_DEVICE_CAPACITY_UNAVAILABLE");
        verify(db, never()).write(anyString(), any(Object[].class));
        when(db.occupiedDeviceSlots(USER)).thenReturn(0L);
        when(db.write(contains("UPDATE nx_product"), any(Object[].class))).thenReturn(0);
        assertThatThrownBy(() -> service.award(USER, "invite", GIFT_RULE, START, END, "ONCE"))
                .hasMessageContaining("H4_EVENT_GIFT_STOCK_UNAVAILABLE");
        verify(db, never()).write(contains("INSERT INTO nx_user_device"), any(Object[].class));
    }

    @Test
    void endedEventAndFrozenAccountCannotIssueAssets() {
        assertThatThrownBy(() -> service.award(USER, "invite", GIFT_RULE, START, START.plusDays(1), "ONCE"))
                .hasMessageContaining("H4_EVENT_NOT_ACTIVE");
        when(db.user(USER, true)).thenReturn(Map.of("status", "FROZEN", "sandbox", 0));
        assertThatThrownBy(() -> service.award(USER, "invite", GIFT_RULE, START, END, "ONCE"))
                .hasMessageContaining("H4_EVENT_ACCOUNT_UNAVAILABLE");
        verify(db, never()).write(anyString(), any(Object[].class));
    }

    @Test
    void enablementRequiresAPublishedPromotionCoveringTheUtcActivityWindow() {
        Map<String, Object> rules = Map.of("promotionActivityId", "PROMO-A");
        when(db.one(contains("JOIN nx_promotion_version"), eq("PROMO-A"), eq(START.plusHours(8)), eq(END.plusHours(8)))).thenReturn(null);
        assertThat(service.readiness("discount", rules, START, END)).containsExactly("promotionActivityId");
        when(db.one(contains("JOIN nx_promotion_version"), eq("PROMO-A"), eq(START.plusHours(8)), eq(END.plusHours(8))))
                .thenReturn(Map.of("activity_id", "PROMO-A"));
        assertThat(service.readiness("discount", rules, START, END)).isEmpty();
    }

    @Test
    void onlyMatchedPaidPurchaseGrantsPromotionalRaffleTicketsAndCanSettleAfterTheWindow() {
        Map<String, Object> rules = Map.of("promotionActivityId", "PROMO-A", "extraGenesisTickets", 1);
        assertThat(service.awardPurchaseTickets(USER, "discount", rules, START, END, "ORDER-1")).isNull();
        verifyNoInteractions(raffle);
        when(db.one(contains("SELECT o.order_no FROM nx_order"), eq("ORDER-1"), eq(USER), eq(START.plusHours(8)), eq(START.plusDays(2).plusHours(8)), eq("PROMO-A")))
                .thenReturn(Map.of("order_no", "ORDER-1"));
        assertThat(service.awardPurchaseTickets(USER, "discount", rules, START, START.plusDays(2), "ORDER-1"))
                .containsEntry("quantity", 1L).containsEntry("orderNo", "ORDER-1");
        verify(raffle).grant(USER, "H4_PROMOTION", "discount:ORDER-1", 1L, null, "ORDER-1");
    }

    @Test
    void linkedPurchaseUsesExistingPromotionEligibilityWithoutAwardingOnClick() {
        when(promotions.get(USER, "PROMO-A")).thenReturn(Map.of("activityId", "PROMO-A", "state", "ACTIVE", "eligibility", "ESTIMATE_ELIGIBLE"));
        Map<String, Object> rules = Map.of("promotionActivityId", "PROMO-A");
        assertThat(service.progress(USER, "discount", rules, START, END)).containsEntry("eligible", true).containsEntry("claimKeys", List.of());
        assertThatThrownBy(() -> service.award(USER, "discount", rules, START, END, "ONCE"))
                .hasMessageContaining("H4_EVENT_PURCHASE_REQUIRED");
        verify(db, never()).write(anyString(), any(Object[].class));
    }
}
