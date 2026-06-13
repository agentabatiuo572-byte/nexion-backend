package ffdd.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.api.ApiResult;
import ffdd.system.dto.ConfigItemResponse;
import ffdd.system.service.SystemContentService;
import ffdd.system.service.SystemHelpService;
import ffdd.system.service.SystemConfigService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicConfigControllerTest {
    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);
    private final SystemContentService systemContentService = mock(SystemContentService.class);
    private final SystemHelpService systemHelpService = mock(SystemHelpService.class);
    private final PublicConfigController controller =
            new PublicConfigController(systemConfigService, systemContentService, systemHelpService, new ObjectMapper());

    @Test
    void exposesDayOnePublicConfigFromStructuredItems() {
        when(systemConfigService.listPublicByGroup("onboarding")).thenReturn(List.of(
                new ConfigItemResponse(261L, "onboarding.day0.first_receipt_target_seconds", "90", "NUMBER", "onboarding", "PUBLIC", "First receipt target.", 1, null, null),
                new ConfigItemResponse(262L, "onboarding.day0.first_receipt_usdt", "0.000300", "NUMBER", "onboarding", "PUBLIC", "First receipt amount.", 1, null, null),
                new ConfigItemResponse(263L, "onboarding.day0.welcome_bonus_asset", "USDT", "STRING", "onboarding", "PUBLIC", "Welcome asset.", 1, null, null),
                new ConfigItemResponse(264L, "onboarding.day0.welcome_bonus_amount", "5.000000", "NUMBER", "onboarding", "PUBLIC", "Welcome amount.", 1, null, null),
                new ConfigItemResponse(265L, "onboarding.day0.active_device_count", "28432", "NUMBER", "onboarding", "PUBLIC", "Active devices.", 1, null, null),
                new ConfigItemResponse(266L, "onboarding.day0.paid_today_usdt", "1247893", "NUMBER", "onboarding", "PUBLIC", "Paid today.", 1, null, null),
                new ConfigItemResponse(267L, "onboarding.day0.intro_refresh_seconds", "60", "NUMBER", "onboarding", "PUBLIC", "Intro refresh.", 1, null, null)));

        ApiResult<Map<String, Object>> response = controller.dayOne();

        assertThat(response.getCode()).isZero();
        assertThat(response.getData())
                .containsEntry("firstReceiptTargetSeconds", new java.math.BigDecimal("90"))
                .containsEntry("firstReceiptUsdt", new java.math.BigDecimal("0.000300"))
                .containsEntry("welcomeBonusAsset", "USDT")
                .containsEntry("welcomeBonusAmount", new java.math.BigDecimal("5.000000"))
                .containsEntry("activeDeviceCount", new java.math.BigDecimal("28432"))
                .containsEntry("paidTodayUsdt", new java.math.BigDecimal("1247893"))
                .containsEntry("introRefreshSeconds", new java.math.BigDecimal("60"));
    }

    @Test
    void keepsLegacyDayOneJsonFallbackForExistingDatabases() {
        when(systemConfigService.listPublicByGroup("onboarding")).thenReturn(List.of(new ConfigItemResponse(
                16L,
                "onboarding.day0",
                "{\"firstReceiptTargetSeconds\":90,\"welcomeBonusAsset\":\"USDT\"}",
                "JSON",
                "onboarding",
                "PUBLIC",
                "Legacy day one config.",
                1,
                null,
                null)));

        ApiResult<Map<String, Object>> response = controller.dayOne();

        assertThat(response.getCode()).isZero();
        assertThat(response.getData())
                .containsEntry("firstReceiptTargetSeconds", 90)
                .containsEntry("welcomeBonusAsset", "USDT");
    }

    @Test
    void exposesDeviceFleetMaxActiveSlotsFromPublicComputeConfig() {
        when(systemConfigService.listPublicByGroup("compute")).thenReturn(List.of(new ConfigItemResponse(
                21L,
                "compute.active_device_slots.default",
                "6",
                "NUMBER",
                "compute",
                "PUBLIC",
                "Default max active compute devices per user.",
                1,
                null,
                null)));

        ApiResult<Map<String, Object>> response = controller.deviceFleet();

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).containsEntry("maxActiveSlots", 6);
    }

    @Test
    void exposesCompliancePublicConfigItems() {
        when(systemConfigService.listPublicByGroup("compliance")).thenReturn(List.of(
                new ConfigItemResponse(268L, "compliance.kyc.review_sla_seconds", "90", "NUMBER", "compliance", "PUBLIC", "KYC Express review SLA seconds shown in uniapp.", 1, null, null),
                new ConfigItemResponse(673L, "compliance.trust.mrr_usd", "4870000", "NUMBER", "compliance", "PUBLIC", "Trust Center quarterly report MRR in USD.", 1, null, null),
                new ConfigItemResponse(785L, "compliance.trust.mrr_growth_pct", "22", "NUMBER", "compliance", "PUBLIC", "Trust Center quarterly report MRR growth percentage.", 1, null, null),
                new ConfigItemResponse(674L, "compliance.trust.active_accounts", "184206", "NUMBER", "compliance", "PUBLIC", "Trust Center quarterly report active account count.", 1, null, null),
                new ConfigItemResponse(786L, "compliance.trust.active_accounts_growth_pct", "38", "NUMBER", "compliance", "PUBLIC", "Trust Center quarterly report active account growth percentage.", 1, null, null),
                new ConfigItemResponse(675L, "compliance.trust.devices_online", "28432", "NUMBER", "compliance", "PUBLIC", "Trust Center quarterly report online device count.", 1, null, null),
                new ConfigItemResponse(787L, "compliance.trust.devices_online_growth_pct", "12", "NUMBER", "compliance", "PUBLIC", "Trust Center quarterly report online device growth percentage.", 1, null, null),
                new ConfigItemResponse(676L, "compliance.trust.payouts_processed_usd", "31200000", "NUMBER", "compliance", "PUBLIC", "Trust Center quarterly report processed payout amount in USD.", 1, null, null),
                new ConfigItemResponse(788L, "compliance.trust.payouts_growth_pct", "27", "NUMBER", "compliance", "PUBLIC", "Trust Center quarterly report processed payout growth percentage.", 1, null, null)));

        ApiResult<Map<String, Object>> response = controller.compliance();

        assertThat(response.getCode()).isZero();
        assertThat(response.getData())
                .containsEntry("kyc.review_sla_seconds", new java.math.BigDecimal("90"))
                .containsEntry("trust.mrr_usd", new java.math.BigDecimal("4870000"))
                .containsEntry("trust.mrr_growth_pct", new java.math.BigDecimal("22"))
                .containsEntry("trust.active_accounts", new java.math.BigDecimal("184206"))
                .containsEntry("trust.active_accounts_growth_pct", new java.math.BigDecimal("38"))
                .containsEntry("trust.devices_online", new java.math.BigDecimal("28432"))
                .containsEntry("trust.devices_online_growth_pct", new java.math.BigDecimal("12"))
                .containsEntry("trust.payouts_processed_usd", new java.math.BigDecimal("31200000"))
                .containsEntry("trust.payouts_growth_pct", new java.math.BigDecimal("27"));
    }

    @Test
    void exposesProfileOptionsFromPublicProfileConfigItems() {
        when(systemConfigService.listPublicByGroup("profile")).thenReturn(List.of(
                new ConfigItemResponse(1801L, "profile.region.singapore.label", "Singapore", "STRING", "profile", "PUBLIC", "Profile region.", 1, null, null),
                new ConfigItemResponse(1802L, "profile.timezone.asia_singapore.label", "Asia/Singapore (UTC+8)", "STRING", "profile", "PUBLIC", "Profile timezone.", 1, null, null),
                new ConfigItemResponse(1803L, "profile.avatar.mech_lime.accent", "#c6ff3a", "STRING", "profile", "PUBLIC", "Profile avatar accent.", 1, null, null),
                new ConfigItemResponse(1804L, "profile.avatar.mech_lime.glow", "rgba(198, 255, 58, 0.22)", "STRING", "profile", "PUBLIC", "Profile avatar glow.", 1, null, null)));

        ApiResult<Map<String, Object>> response = controller.profile();

        assertThat(response.getCode()).isZero();
        assertThat(response.getData())
                .containsEntry("region.singapore.label", "Singapore")
                .containsEntry("timezone.asia_singapore.label", "Asia/Singapore (UTC+8)")
                .containsEntry("avatar.mech_lime.accent", "#c6ff3a")
                .containsEntry("avatar.mech_lime.glow", "rgba(198, 255, 58, 0.22)");
    }

    @Test
    void exposesWalletPublicEconomicsFromConfigItems() {
        when(systemConfigService.listPublicByGroup("wallet")).thenReturn(List.of(
                new ConfigItemResponse(22L, "wallet.withdrawal.min_usdt", "20", "NUMBER", "wallet", "PUBLIC", "Minimum withdrawal.", 1, null, null),
                new ConfigItemResponse(23L, "wallet.withdrawal.fee_rate", "0.02", "NUMBER", "wallet", "PUBLIC", "Withdrawal fee rate.", 1, null, null),
                new ConfigItemResponse(24L, "wallet.exchange.nex_usdt_price", "0.171", "NUMBER", "wallet", "PUBLIC", "NEX price.", 1, null, null),
                new ConfigItemResponse(25L, "wallet.deposit.trc20.enabled", "true", "BOOLEAN", "wallet", "PUBLIC", "TRC20 enabled.", 1, null, null),
                new ConfigItemResponse(26L, "wallet.deposit.trc20.address", "TNexionDepositDemo111111111111111111111", "STRING", "wallet", "PUBLIC", "TRC20 address.", 1, null, null),
                new ConfigItemResponse(27L, "wallet.deposit.trc20.min_confirmations", "20", "NUMBER", "wallet", "PUBLIC", "TRC20 confirmations.", 1, null, null),
                new ConfigItemResponse(28L, "wallet.nex.boost_low_threshold", "1000", "NUMBER", "wallet", "PUBLIC", "Low boost threshold.", 1, null, null),
                new ConfigItemResponse(29L, "wallet.nex.boost_high_pct", "10", "NUMBER", "wallet", "PUBLIC", "High boost pct.", 1, null, null),
                new ConfigItemResponse(30L, "wallet.nex.fee_discount_target_nex", "5000", "NUMBER", "wallet", "PUBLIC", "Fee discount target.", 1, null, null),
                new ConfigItemResponse(31L, "wallet.nex_market.pump_curve", "STEP", "STRING", "wallet", "PUBLIC", "Pump curve.", 1, null, null),
                new ConfigItemResponse(32L, "wallet.nex_market.oracle_sources", "3", "NUMBER", "wallet", "PUBLIC", "Oracle sources.", 1, null, null),
                new ConfigItemResponse(33L, "wallet.nex_market.circulating_supply", "24000000", "NUMBER", "wallet", "PUBLIC", "Circulating supply.", 1, null, null),
                new ConfigItemResponse(34L, "wallet.nex_market.total_supply", "100000000", "NUMBER", "wallet", "PUBLIC", "Total supply.", 1, null, null),
                new ConfigItemResponse(35L, "wallet.nex_market.volume_24h_usdt", "980000", "NUMBER", "wallet", "PUBLIC", "24h volume.", 1, null, null),
                new ConfigItemResponse(91L, "wallet.nex_market.volatility_pct_per_hour", "3", "NUMBER", "wallet", "PUBLIC", "Volatility guardrail.", 1, null, null),
                new ConfigItemResponse(41L, "wallet.nex_market.paused", "true", "BOOLEAN", "wallet", "PUBLIC", "NEX market pause.", 1, null, null),
                new ConfigItemResponse(42L, "wallet.exchange.kyc_trigger_usdt", "100", "NUMBER", "wallet", "PUBLIC", "Exchange KYC trigger.", 1, null, null),
                new ConfigItemResponse(43L, "wallet.exchange.fee_label", "Free", "STRING", "wallet", "PUBLIC", "Exchange fee label.", 1, null, null),
                new ConfigItemResponse(44L, "wallet.genesis.daily_dividend_rate_pct", "0.1", "NUMBER", "wallet", "PUBLIC", "Genesis dividend rate.", 1, null, null),
                new ConfigItemResponse(45L, "wallet.genesis.daily_volume_base_usdt", "24000000", "NUMBER", "wallet", "PUBLIC", "Genesis daily volume base.", 1, null, null),
                new ConfigItemResponse(46L, "wallet.genesis.secondary_floor_usdt", "25000", "NUMBER", "wallet", "PUBLIC", "Genesis secondary floor.", 1, null, null),
                new ConfigItemResponse(47L, "wallet.genesis.secondary_market_paused", "false", "BOOLEAN", "wallet", "PUBLIC", "Genesis secondary pause.", 1, null, null),
                new ConfigItemResponse(36L, "wallet.nex_lock.apy_bps", "25000", "NUMBER", "wallet", "PUBLIC", "NEX lock APY.", 1, null, null),
                new ConfigItemResponse(37L, "wallet.nex_lock.default_term_months", "24", "NUMBER", "wallet", "PUBLIC", "NEX lock term.", 1, null, null),
                new ConfigItemResponse(38L, "wallet.nex_lock.min_amount_nex", "1000", "NUMBER", "wallet", "PUBLIC", "NEX lock minimum.", 1, null, null),
                new ConfigItemResponse(39L, "wallet.premium.black_threshold", "80", "NUMBER", "wallet", "PUBLIC", "Premium Black threshold.", 1, null, null),
                new ConfigItemResponse(40L, "wallet.repurchase.boost_multiplier", "1.5", "NUMBER", "wallet", "PUBLIC", "Repurchase boost.", 1, null, null)));

        ApiResult<Map<String, Object>> response = controller.wallet();

        assertThat(response.getCode()).isZero();
        assertThat(response.getData())
                .containsEntry("withdrawal.min_usdt", new java.math.BigDecimal("20"))
                .containsEntry("withdrawal.fee_rate", new java.math.BigDecimal("0.02"))
                .containsEntry("exchange.nex_usdt_price", new java.math.BigDecimal("0.171"))
                .containsEntry("deposit.trc20.enabled", true)
                .containsEntry("deposit.trc20.address", "TNexionDepositDemo111111111111111111111")
                .containsEntry("deposit.trc20.min_confirmations", new java.math.BigDecimal("20"))
                .containsEntry("nex.boost_low_threshold", new java.math.BigDecimal("1000"))
                .containsEntry("nex.boost_high_pct", new java.math.BigDecimal("10"))
                .containsEntry("nex.fee_discount_target_nex", new java.math.BigDecimal("5000"))
                .containsEntry("nex_market.pump_curve", "STEP")
                .containsEntry("nex_market.oracle_sources", new java.math.BigDecimal("3"))
                .containsEntry("nex_market.circulating_supply", new java.math.BigDecimal("24000000"))
                .containsEntry("nex_market.total_supply", new java.math.BigDecimal("100000000"))
                .containsEntry("nex_market.volume_24h_usdt", new java.math.BigDecimal("980000"))
                .containsEntry("nex_market.volatility_pct_per_hour", new java.math.BigDecimal("3"))
                .containsEntry("nex_market.paused", true)
                .containsEntry("exchange.kyc_trigger_usdt", new java.math.BigDecimal("100"))
                .containsEntry("exchange.fee_label", "Free")
                .containsEntry("genesis.daily_dividend_rate_pct", new java.math.BigDecimal("0.1"))
                .containsEntry("genesis.daily_volume_base_usdt", new java.math.BigDecimal("24000000"))
                .containsEntry("genesis.secondary_floor_usdt", new java.math.BigDecimal("25000"))
                .containsEntry("genesis.secondary_market_paused", false)
                .containsEntry("nex_lock.apy_bps", new java.math.BigDecimal("25000"))
                .containsEntry("nex_lock.default_term_months", new java.math.BigDecimal("24"))
                .containsEntry("nex_lock.min_amount_nex", new java.math.BigDecimal("1000"))
                .containsEntry("premium.black_threshold", new java.math.BigDecimal("80"))
                .containsEntry("repurchase.boost_multiplier", new java.math.BigDecimal("1.5"));
    }

    @Test
    void exposesTeamPublicGrowthConfigItems() {
        when(systemConfigService.listPublicByGroup("team")).thenReturn(List.of(
                new ConfigItemResponse(94L, "team.invite.reward_usdt", "400", "NUMBER", "team", "PUBLIC", "Invite reward USDT.", 1, null, null),
                new ConfigItemResponse(95L, "team.invite.previous_reward_usdt", "200", "NUMBER", "team", "PUBLIC", "Previous invite reward USDT.", 1, null, null),
                new ConfigItemResponse(96L, "team.invite.reward_nex", "400", "NUMBER", "team", "PUBLIC", "Invite reward NEX.", 1, null, null),
                new ConfigItemResponse(97L, "team.invite.cooldown_days", "30", "NUMBER", "team", "PUBLIC", "Invite cooldown.", 1, null, null),
                new ConfigItemResponse(99L, "team.leaderboard.weekly_pool_usdt", "50000", "NUMBER", "team", "PUBLIC", "Weekly leaderboard pool.", 1, null, null),
                new ConfigItemResponse(101L, "team.unilevel.l1_usdt_pct", "10", "NUMBER", "team", "PUBLIC", "L1 rate.", 1, null, null),
                new ConfigItemResponse(102L, "team.binary.track_min_usd", "1000", "NUMBER", "team", "PUBLIC", "Binary lane minimum.", 1, null, null),
                new ConfigItemResponse(103L, "team.binary.match_rate_pct", "10", "NUMBER", "team", "PUBLIC", "Binary match rate.", 1, null, null),
                new ConfigItemResponse(104L, "team.binary.daily_cap_usdt", "5000", "NUMBER", "team", "PUBLIC", "Binary daily cap.", 1, null, null),
                new ConfigItemResponse(105L, "team.binary.next_daily_cap_usdt", "2000", "NUMBER", "team", "PUBLIC", "Next binary daily cap.", 1, null, null),
                new ConfigItemResponse(106L, "team.binary.next_cap_day", "7", "NUMBER", "team", "PUBLIC", "Binary cap switch day.", 1, null, null),
                new ConfigItemResponse(107L, "team.binary.gv_reset_day", "1", "NUMBER", "team", "PUBLIC", "Binary GV reset day.", 1, null, null),
                new ConfigItemResponse(108L, "team.binary.auto_placement_enabled", "true", "BOOLEAN", "team", "PUBLIC", "Binary auto-placement flag.", 1, null, null),
                new ConfigItemResponse(109L, "team.rate_tier.verified_min_usd", "5000", "NUMBER", "team", "PUBLIC", "Verified tier minimum.", 1, null, null),
                new ConfigItemResponse(110L, "team.rate_tier.diamond_direct_pct", "15", "NUMBER", "team", "PUBLIC", "Diamond direct rate.", 1, null, null),
                new ConfigItemResponse(111L, "team.influence_score.max", "5", "NUMBER", "team", "PUBLIC", "Influence max.", 1, null, null),
                new ConfigItemResponse(112L, "team.promo.weekly_multiplier", "1", "NUMBER", "team", "PUBLIC", "Promo multiplier.", 1, null, null),
                new ConfigItemResponse(244L, "team.hardware.pro_name", "NexionBox Pro", "STRING", "team", "PUBLIC", "Pro display name.", 1, null, null),
                new ConfigItemResponse(245L, "team.hardware.pro_price_usdt", "3999", "NUMBER", "team", "PUBLIC", "Pro price.", 1, null, null),
                new ConfigItemResponse(246L, "team.hardware.pro_note", "For creators scaling regional compute demand.", "STRING", "team", "PUBLIC", "Pro note.", 1, null, null),
                new ConfigItemResponse(131L, "team.hardware.monthly_stock_limit", "96", "NUMBER", "team", "PUBLIC", "Monthly stock.", 1, null, null),
                new ConfigItemResponse(247L, "team.hardware.rack_name", "NexionRack", "STRING", "team", "PUBLIC", "Rack display name.", 1, null, null),
                new ConfigItemResponse(248L, "team.hardware.rack_price_usdt", "19999", "NUMBER", "team", "PUBLIC", "Rack price.", 1, null, null),
                new ConfigItemResponse(249L, "team.hardware.rack_note", "For regional operators with dedicated deployment capacity.", "STRING", "team", "PUBLIC", "Rack note.", 1, null, null),
                new ConfigItemResponse(180L, "team.hardware.pro_stock_pct", "72", "NUMBER", "team", "PUBLIC", "Pro stock split.", 1, null, null),
                new ConfigItemResponse(181L, "team.hardware.rack_stock_pct", "28", "NUMBER", "team", "PUBLIC", "Rack stock split.", 1, null, null),
                new ConfigItemResponse(250L, "team.agent.event_title", "Offline event venue", "STRING", "team", "PUBLIC", "Event title.", 1, null, null),
                new ConfigItemResponse(251L, "team.agent.event_value", "$1,000-$10,000", "STRING", "team", "PUBLIC", "Event value.", 1, null, null),
                new ConfigItemResponse(252L, "team.agent.event_note", "Attendance >= 100 verified by sign-in QR.", "STRING", "team", "PUBLIC", "Event note.", 1, null, null),
                new ConfigItemResponse(253L, "team.agent.kol_title", "KOL campaign match", "STRING", "team", "PUBLIC", "KOL title.", 1, null, null),
                new ConfigItemResponse(254L, "team.agent.kol_note", "Invoice and traffic logs required.", "STRING", "team", "PUBLIC", "KOL note.", 1, null, null),
                new ConfigItemResponse(255L, "team.agent.materials_title", "Print materials", "STRING", "team", "PUBLIC", "Materials title.", 1, null, null),
                new ConfigItemResponse(256L, "team.agent.materials_value", "Region quota", "STRING", "team", "PUBLIC", "Materials value.", 1, null, null),
                new ConfigItemResponse(257L, "team.agent.materials_note", "Banner, brochure and local launch kits.", "STRING", "team", "PUBLIC", "Materials note.", 1, null, null),
                new ConfigItemResponse(258L, "team.agent.sdk_title", "SDK / dev support", "STRING", "team", "PUBLIC", "SDK title.", 1, null, null),
                new ConfigItemResponse(259L, "team.agent.sdk_value", "Hourly", "STRING", "team", "PUBLIC", "SDK value.", 1, null, null),
                new ConfigItemResponse(260L, "team.agent.sdk_note", "Custom integrations for top customers.", "STRING", "team", "PUBLIC", "SDK note.", 1, null, null)));

        ApiResult<Map<String, Object>> response = controller.team();

        assertThat(response.getCode()).isZero();
        assertThat(response.getData())
                .containsEntry("invite.reward_usdt", new java.math.BigDecimal("400"))
                .containsEntry("invite.previous_reward_usdt", new java.math.BigDecimal("200"))
                .containsEntry("invite.reward_nex", new java.math.BigDecimal("400"))
                .containsEntry("invite.cooldown_days", new java.math.BigDecimal("30"))
                .containsEntry("leaderboard.weekly_pool_usdt", new java.math.BigDecimal("50000"))
                .containsEntry("unilevel.l1_usdt_pct", new java.math.BigDecimal("10"))
                .containsEntry("binary.track_min_usd", new java.math.BigDecimal("1000"))
                .containsEntry("binary.match_rate_pct", new java.math.BigDecimal("10"))
                .containsEntry("binary.daily_cap_usdt", new java.math.BigDecimal("5000"))
                .containsEntry("binary.next_daily_cap_usdt", new java.math.BigDecimal("2000"))
                .containsEntry("binary.next_cap_day", new java.math.BigDecimal("7"))
                .containsEntry("binary.gv_reset_day", new java.math.BigDecimal("1"))
                .containsEntry("binary.auto_placement_enabled", true)
                .containsEntry("rate_tier.verified_min_usd", new java.math.BigDecimal("5000"))
                .containsEntry("rate_tier.diamond_direct_pct", new java.math.BigDecimal("15"))
                .containsEntry("influence_score.max", new java.math.BigDecimal("5"))
                .containsEntry("promo.weekly_multiplier", new java.math.BigDecimal("1"))
                .containsEntry("hardware.pro_name", "NexionBox Pro")
                .containsEntry("hardware.pro_price_usdt", new java.math.BigDecimal("3999"))
                .containsEntry("hardware.pro_note", "For creators scaling regional compute demand.")
                .containsEntry("hardware.monthly_stock_limit", new java.math.BigDecimal("96"))
                .containsEntry("hardware.rack_name", "NexionRack")
                .containsEntry("hardware.rack_price_usdt", new java.math.BigDecimal("19999"))
                .containsEntry("hardware.rack_note", "For regional operators with dedicated deployment capacity.")
                .containsEntry("hardware.pro_stock_pct", new java.math.BigDecimal("72"))
                .containsEntry("hardware.rack_stock_pct", new java.math.BigDecimal("28"))
                .containsEntry("agent.event_title", "Offline event venue")
                .containsEntry("agent.event_value", "$1,000-$10,000")
                .containsEntry("agent.event_note", "Attendance >= 100 verified by sign-in QR.")
                .containsEntry("agent.kol_title", "KOL campaign match")
                .containsEntry("agent.kol_note", "Invoice and traffic logs required.")
                .containsEntry("agent.materials_title", "Print materials")
                .containsEntry("agent.materials_value", "Region quota")
                .containsEntry("agent.materials_note", "Banner, brochure and local launch kits.")
                .containsEntry("agent.sdk_title", "SDK / dev support")
                .containsEntry("agent.sdk_value", "Hourly")
                .containsEntry("agent.sdk_note", "Custom integrations for top customers.");
    }

    @Test
    void exposesCommercePaymentConfigItems() {
        when(systemConfigService.listPublicByGroup("commerce")).thenReturn(List.of(
                new ConfigItemResponse(147L, "commerce.payment.default_provider", "MOCK", "STRING", "commerce", "PUBLIC", "Default checkout provider.", 1, null, null),
                new ConfigItemResponse(148L, "commerce.payment.checkout.enabled", "true", "BOOLEAN", "commerce", "PUBLIC", "Checkout enabled.", 1, null, null),
                new ConfigItemResponse(149L, "commerce.payment.checkout.label", "USDT checkout", "STRING", "commerce", "PUBLIC", "Checkout label.", 1, null, null),
                new ConfigItemResponse(150L, "commerce.payment.checkout.min_usdt", "1", "NUMBER", "commerce", "PUBLIC", "Minimum order amount.", 1, null, null),
                new ConfigItemResponse(205L, "commerce.payment.checkout.fee_mode", "INCLUDED", "STRING", "commerce", "PUBLIC", "Checkout network fee mode.", 1, null, null),
                new ConfigItemResponse(206L, "commerce.payment.checkout.fee_label", "Included", "STRING", "commerce", "PUBLIC", "Checkout network fee label.", 1, null, null),
                new ConfigItemResponse(207L, "commerce.payment.checkout.fee_amount_usdt", "0", "NUMBER", "commerce", "PUBLIC", "Checkout network fee amount.", 1, null, null),
                new ConfigItemResponse(208L, "commerce.payment.checkout.fee_rate_pct", "0", "NUMBER", "commerce", "PUBLIC", "Checkout network fee rate.", 1, null, null),
                new ConfigItemResponse(209L, "commerce.orders.page_size", "20", "NUMBER", "commerce", "PUBLIC", "Orders page size.", 1, null, null),
                new ConfigItemResponse(184L, "commerce.bundle.discount_tier_1_min_items", "2", "NUMBER", "commerce", "PUBLIC", "Bundle tier 1 item count.", 1, null, null),
                new ConfigItemResponse(185L, "commerce.bundle.discount_tier_1_pct", "5", "NUMBER", "commerce", "PUBLIC", "Bundle tier 1 pct.", 1, null, null),
                new ConfigItemResponse(186L, "commerce.bundle.discount_tier_2_min_items", "3", "NUMBER", "commerce", "PUBLIC", "Bundle tier 2 item count.", 1, null, null),
                new ConfigItemResponse(187L, "commerce.bundle.discount_tier_2_pct", "8", "NUMBER", "commerce", "PUBLIC", "Bundle tier 2 pct.", 1, null, null),
                new ConfigItemResponse(188L, "commerce.bundle.discount_tier_3_min_items", "4", "NUMBER", "commerce", "PUBLIC", "Bundle tier 3 item count.", 1, null, null),
                new ConfigItemResponse(189L, "commerce.bundle.discount_tier_3_pct", "12", "NUMBER", "commerce", "PUBLIC", "Bundle tier 3 pct.", 1, null, null),
                new ConfigItemResponse(210L, "commerce.bundle.default_selected_count", "2", "NUMBER", "commerce", "PUBLIC", "Bundle default selected count.", 1, null, null),
                new ConfigItemResponse(211L, "commerce.bundle.suggestion_limit", "4", "NUMBER", "commerce", "PUBLIC", "Bundle suggestion limit.", 1, null, null),
                new ConfigItemResponse(190L, "commerce.yield.month_days", "30", "NUMBER", "commerce", "PUBLIC", "Yield month days.", 1, null, null),
                new ConfigItemResponse(191L, "commerce.yield.year_days", "365", "NUMBER", "commerce", "PUBLIC", "Yield year days.", 1, null, null),
                new ConfigItemResponse(192L, "commerce.payback.month_threshold_days", "60", "NUMBER", "commerce", "PUBLIC", "Payback month threshold.", 1, null, null),
                new ConfigItemResponse(193L, "commerce.checkout.max_quantity", "6", "NUMBER", "commerce", "PUBLIC", "Checkout max quantity.", 1, null, null),
                new ConfigItemResponse(194L, "commerce.inventory.low_stock_threshold", "50", "NUMBER", "commerce", "PUBLIC", "Low stock threshold.", 1, null, null),
                new ConfigItemResponse(195L, "commerce.share.min_stake_usdt", "199", "NUMBER", "commerce", "PUBLIC", "Share min stake.", 1, null, null),
                new ConfigItemResponse(196L, "commerce.share.redemption_days", "30", "NUMBER", "commerce", "PUBLIC", "Share redemption days.", 1, null, null),
                new ConfigItemResponse(197L, "commerce.store.datacenter_label", "Singapore DC", "STRING", "commerce", "PUBLIC", "Store datacenter label.", 1, null, null),
                new ConfigItemResponse(198L, "commerce.store.sla_uptime_pct", "99.9", "NUMBER", "commerce", "PUBLIC", "Store SLA uptime.", 1, null, null),
                new ConfigItemResponse(199L, "commerce.store.shipping_label", "zero shipping", "STRING", "commerce", "PUBLIC", "Store shipping label.", 1, null, null),
                new ConfigItemResponse(200L, "commerce.store.phone_daily_usdt_baseline", "0.08", "NUMBER", "commerce", "PUBLIC", "Phone daily USDT baseline.", 1, null, null),
                new ConfigItemResponse(212L, "commerce.store.ladder_product_limit", "4", "NUMBER", "commerce", "PUBLIC", "Store income ladder product limit.", 1, null, null),
                new ConfigItemResponse(201L, "commerce.review.media_max_count", "6", "NUMBER", "commerce", "PUBLIC", "Review media max count.", 1, null, null),
                new ConfigItemResponse(202L, "commerce.review.video_max_duration_sec", "30", "NUMBER", "commerce", "PUBLIC", "Review video max duration.", 1, null, null),
                new ConfigItemResponse(203L, "commerce.review.title_max_length", "64", "NUMBER", "commerce", "PUBLIC", "Review title max length.", 1, null, null),
                new ConfigItemResponse(204L, "commerce.review.content_max_length", "800", "NUMBER", "commerce", "PUBLIC", "Review content max length.", 1, null, null),
                new ConfigItemResponse(215L, "commerce.review.content_min_length", "10", "NUMBER", "commerce", "PUBLIC", "Review content min length.", 1, null, null)));

        ApiResult<Map<String, Object>> response = controller.commerce();

        assertThat(response.getCode()).isZero();
        assertThat(response.getData())
                .containsEntry("payment.default_provider", "MOCK")
                .containsEntry("payment.checkout.enabled", true)
                .containsEntry("payment.checkout.label", "USDT checkout")
                .containsEntry("payment.checkout.min_usdt", new java.math.BigDecimal("1"))
                .containsEntry("payment.checkout.fee_mode", "INCLUDED")
                .containsEntry("payment.checkout.fee_label", "Included")
                .containsEntry("payment.checkout.fee_amount_usdt", new java.math.BigDecimal("0"))
                .containsEntry("payment.checkout.fee_rate_pct", new java.math.BigDecimal("0"))
                .containsEntry("orders.page_size", new java.math.BigDecimal("20"))
                .containsEntry("bundle.discount_tier_1_min_items", new java.math.BigDecimal("2"))
                .containsEntry("bundle.discount_tier_1_pct", new java.math.BigDecimal("5"))
                .containsEntry("bundle.discount_tier_2_min_items", new java.math.BigDecimal("3"))
                .containsEntry("bundle.discount_tier_2_pct", new java.math.BigDecimal("8"))
                .containsEntry("bundle.discount_tier_3_min_items", new java.math.BigDecimal("4"))
                .containsEntry("bundle.discount_tier_3_pct", new java.math.BigDecimal("12"))
                .containsEntry("bundle.default_selected_count", new java.math.BigDecimal("2"))
                .containsEntry("bundle.suggestion_limit", new java.math.BigDecimal("4"))
                .containsEntry("yield.month_days", new java.math.BigDecimal("30"))
                .containsEntry("yield.year_days", new java.math.BigDecimal("365"))
                .containsEntry("payback.month_threshold_days", new java.math.BigDecimal("60"))
                .containsEntry("checkout.max_quantity", new java.math.BigDecimal("6"))
                .containsEntry("inventory.low_stock_threshold", new java.math.BigDecimal("50"))
                .containsEntry("share.min_stake_usdt", new java.math.BigDecimal("199"))
                .containsEntry("share.redemption_days", new java.math.BigDecimal("30"))
                .containsEntry("store.datacenter_label", "Singapore DC")
                .containsEntry("store.sla_uptime_pct", new java.math.BigDecimal("99.9"))
                .containsEntry("store.shipping_label", "zero shipping")
                .containsEntry("store.phone_daily_usdt_baseline", new java.math.BigDecimal("0.08"))
                .containsEntry("store.ladder_product_limit", new java.math.BigDecimal("4"))
                .containsEntry("review.media_max_count", new java.math.BigDecimal("6"))
                .containsEntry("review.video_max_duration_sec", new java.math.BigDecimal("30"))
                .containsEntry("review.title_max_length", new java.math.BigDecimal("64"))
                .containsEntry("review.content_max_length", new java.math.BigDecimal("800"))
                .containsEntry("review.content_min_length", new java.math.BigDecimal("10"));
    }

    @Test
    void exposesGrowthTrialConfigItems() {
        when(systemConfigService.listPublicByGroup("growth")).thenReturn(List.of(
                new ConfigItemResponse(160L, "growth.trial.enabled", "true", "BOOLEAN", "growth", "PUBLIC", "Trial enabled.", 1, null, null),
                new ConfigItemResponse(161L, "growth.trial.device_name", "NexionBox S1", "STRING", "growth", "PUBLIC", "Trial device.", 1, null, null),
                new ConfigItemResponse(162L, "growth.trial.duration_days", "3", "NUMBER", "growth", "PUBLIC", "Trial days.", 1, null, null),
                new ConfigItemResponse(163L, "growth.trial.daily_usdt", "38.50", "NUMBER", "growth", "PUBLIC", "Daily shadow USD.", 1, null, null),
                new ConfigItemResponse(164L, "growth.trial.daily_nex", "65", "NUMBER", "growth", "PUBLIC", "Daily NEX.", 1, null, null),
                new ConfigItemResponse(165L, "growth.trial.seats_left_today", "47", "NUMBER", "growth", "PUBLIC", "Seats left.", 1, null, null),
                new ConfigItemResponse(166L, "growth.trial.offset_cap_usdt", "50", "NUMBER", "growth", "PUBLIC", "Offset cap.", 1, null, null),
                new ConfigItemResponse(167L, "growth.trial.price_usdt", "1299", "NUMBER", "growth", "PUBLIC", "Trial reference price.", 1, null, null),
                new ConfigItemResponse(169L, "growth.earn.device_rank_limit", "5", "NUMBER", "growth", "PUBLIC", "Earn device rank limit.", 1, null, null),
                new ConfigItemResponse(170L, "growth.earn.task_teaser_limit", "3", "NUMBER", "growth", "PUBLIC", "Earn task teaser limit.", 1, null, null),
                new ConfigItemResponse(171L, "growth.earn.locked_task_limit", "3", "NUMBER", "growth", "PUBLIC", "Earn locked task limit.", 1, null, null),
                new ConfigItemResponse(172L, "growth.earn.jobs_live_global", "8432", "NUMBER", "growth", "PUBLIC", "Earn live jobs.", 1, null, null),
                new ConfigItemResponse(244L, "growth.earn.production_share_pct", "76", "NUMBER", "growth", "PUBLIC", "Earn production share.", 1, null, null),
                new ConfigItemResponse(245L, "growth.earn.ai_premium_share_pct", "24", "NUMBER", "growth", "PUBLIC", "Earn AI Premium share.", 1, null, null),
                new ConfigItemResponse(173L, "growth.earn.phone_battery_pct", "78", "NUMBER", "growth", "PUBLIC", "Earn phone battery.", 1, null, null),
                new ConfigItemResponse(444L, "growth.earn.phone_daily_usdt", "0.06", "NUMBER", "growth", "PUBLIC", "Earn phone daily baseline.", 1, null, null),
                new ConfigItemResponse(445L, "growth.earn.phone_npu_tops", "28", "NUMBER", "growth", "PUBLIC", "Earn phone NPU.", 1, null, null),
                new ConfigItemResponse(446L, "growth.earn.phone_network_ping_ms", "62", "NUMBER", "growth", "PUBLIC", "Earn phone ping.", 1, null, null),
                new ConfigItemResponse(447L, "growth.earn.gateway_sg_ping_ms", "38", "NUMBER", "growth", "PUBLIC", "SG gateway ping.", 1, null, null),
                new ConfigItemResponse(448L, "growth.earn.gateway_tokyo_ping_ms", "42", "NUMBER", "growth", "PUBLIC", "Tokyo gateway ping.", 1, null, null),
                new ConfigItemResponse(449L, "growth.earn.gateway_us_west_ping_ms", "156", "NUMBER", "growth", "PUBLIC", "US west gateway ping.", 1, null, null),
                new ConfigItemResponse(450L, "growth.earn.estimator_detection_delay_ms", "700", "NUMBER", "growth", "PUBLIC", "Estimator detection delay.", 1, null, null),
                new ConfigItemResponse(451L, "growth.earn.calibration_duration_ms", "12000", "NUMBER", "growth", "PUBLIC", "Calibration duration.", 1, null, null),
                new ConfigItemResponse(452L, "growth.earn.calibration_base_score", "58", "NUMBER", "growth", "PUBLIC", "Calibration base.", 1, null, null),
                new ConfigItemResponse(453L, "growth.earn.calibration_active_device_score", "12", "NUMBER", "growth", "PUBLIC", "Calibration active device score.", 1, null, null),
                new ConfigItemResponse(454L, "growth.earn.calibration_wallet_paired_score", "16", "NUMBER", "growth", "PUBLIC", "Calibration wallet paired score.", 1, null, null),
                new ConfigItemResponse(455L, "growth.earn.calibration_wallet_balance_score", "8", "NUMBER", "growth", "PUBLIC", "Calibration wallet balance score.", 1, null, null),
                new ConfigItemResponse(456L, "growth.earn.calibration_tier2_threshold", "75", "NUMBER", "growth", "PUBLIC", "Calibration tier 2.", 1, null, null),
                new ConfigItemResponse(457L, "growth.earn.calibration_tier3_threshold", "90", "NUMBER", "growth", "PUBLIC", "Calibration tier 3.", 1, null, null),
                new ConfigItemResponse(174L, "growth.earn.max_device_slots", "6", "NUMBER", "growth", "PUBLIC", "Earn max slots.", 1, null, null),
                new ConfigItemResponse(175L, "growth.earn.network_avg_daily_usdt", "143", "NUMBER", "growth", "PUBLIC", "Earn network average.", 1, null, null),
                new ConfigItemResponse(176L, "growth.earn.completed_rows_limit", "3", "NUMBER", "growth", "PUBLIC", "Earn completed rows limit.", 1, null, null),
                new ConfigItemResponse(498L, "growth.daily.hero_copy", "Keep today active, recover missed streaks, and unlock configured milestone rewards.", "STRING", "growth", "PUBLIC", "Daily hero copy.", 1, null, null),
                new ConfigItemResponse(499L, "growth.daily.loading_copy", "Loading daily rewards...", "STRING", "growth", "PUBLIC", "Daily loading copy.", 1, null, null),
                new ConfigItemResponse(500L, "growth.daily.next_milestone_done_label", "All caught up", "STRING", "growth", "PUBLIC", "Daily next milestone complete label.", 1, null, null),
                new ConfigItemResponse(501L, "growth.daily.saver_title", "Streak saver available", "STRING", "growth", "PUBLIC", "Daily saver title.", 1, null, null),
                new ConfigItemResponse(502L, "growth.daily.saver_copy", "Recover yesterday's missed activity and keep the current reward chain alive.", "STRING", "growth", "PUBLIC", "Daily saver copy.", 1, null, null),
                new ConfigItemResponse(503L, "growth.daily.baseline_points", "10", "NUMBER", "growth", "PUBLIC", "Daily baseline points.", 1, null, null),
                new ConfigItemResponse(504L, "growth.daily.streak_bonus_7d_points", "20", "NUMBER", "growth", "PUBLIC", "Daily 7 day bonus.", 1, null, null),
                new ConfigItemResponse(505L, "growth.daily.lucky_multiplier_max", "3", "NUMBER", "growth", "PUBLIC", "Daily lucky multiplier cap.", 1, null, null),
                new ConfigItemResponse(506L, "growth.daily.lucky_probability_pct", "18", "NUMBER", "growth", "PUBLIC", "Daily lucky probability.", 1, null, null),
                new ConfigItemResponse(507L, "growth.daily.points_redeem_rate", "100 pts = $1", "STRING", "growth", "PUBLIC", "Daily points redeem rate.", 1, null, null),
                new ConfigItemResponse(508L, "growth.daily.leaderboard_limit", "5", "NUMBER", "growth", "PUBLIC", "Daily leaderboard limit.", 1, null, null),
                new ConfigItemResponse(509L, "growth.daily.milestones_empty_copy", "No milestone configuration yet.", "STRING", "growth", "PUBLIC", "Daily milestones empty copy.", 1, null, null),
                new ConfigItemResponse(510L, "growth.daily.powerups_empty_copy", "No power-up configuration yet.", "STRING", "growth", "PUBLIC", "Daily powerups empty copy.", 1, null, null),
                new ConfigItemResponse(511L, "growth.daily.leaderboard_empty_copy", "No leaderboard entries yet.", "STRING", "growth", "PUBLIC", "Daily leaderboard empty copy.", 1, null, null),
                new ConfigItemResponse(512L, "growth.daily.metric_longest_label", "Longest streak", "STRING", "growth", "PUBLIC", "Daily longest streak label.", 1, null, null),
                new ConfigItemResponse(513L, "growth.daily.metric_next_label", "Next milestone", "STRING", "growth", "PUBLIC", "Daily next milestone label.", 1, null, null),
                new ConfigItemResponse(514L, "growth.daily.metric_savers_label", "Streak savers", "STRING", "growth", "PUBLIC", "Daily savers label.", 1, null, null),
                new ConfigItemResponse(515L, "growth.daily.metric_recoverable_label", "Recoverable", "STRING", "growth", "PUBLIC", "Daily recoverable label.", 1, null, null),
                new ConfigItemResponse(516L, "growth.daily.checkin_label", "Check in", "STRING", "growth", "PUBLIC", "Daily checkin label.", 1, null, null),
                new ConfigItemResponse(517L, "growth.daily.checked_in_label", "Checked in", "STRING", "growth", "PUBLIC", "Daily checked label.", 1, null, null),
                new ConfigItemResponse(518L, "growth.daily.streak_saver_action_label", "Use", "STRING", "growth", "PUBLIC", "Daily saver action label.", 1, null, null),
                new ConfigItemResponse(519L, "growth.daily.checked_in_toast", "Checked in", "STRING", "growth", "PUBLIC", "Daily checked toast.", 1, null, null),
                new ConfigItemResponse(520L, "growth.daily.streak_restored_toast", "Streak restored", "STRING", "growth", "PUBLIC", "Daily restored toast.", 1, null, null),
                new ConfigItemResponse(521L, "growth.daily.reward_claimed_toast", "Reward claimed", "STRING", "growth", "PUBLIC", "Daily claimed toast.", 1, null, null),
                new ConfigItemResponse(522L, "growth.daily.powerup_active_toast", "Power-up active", "STRING", "growth", "PUBLIC", "Daily powerup toast.", 1, null, null),
                new ConfigItemResponse(625L, "growth.daily.points_balance_label", "Points balance", "STRING", "growth", "PUBLIC", "Daily points label.", 1, null, null),
                new ConfigItemResponse(626L, "growth.daily.next_reset_label", "Next check-in", "STRING", "growth", "PUBLIC", "Daily next reset label.", 1, null, null),
                new ConfigItemResponse(627L, "growth.daily.next_reward_label", "Next reward", "STRING", "growth", "PUBLIC", "Daily next reward label.", 1, null, null),
                new ConfigItemResponse(628L, "growth.daily.ready_now_label", "Ready now", "STRING", "growth", "PUBLIC", "Daily ready now label.", 1, null, null),
                new ConfigItemResponse(629L, "growth.daily.week_calendar_title", "This week", "STRING", "growth", "PUBLIC", "Daily week calendar title.", 1, null, null),
                new ConfigItemResponse(630L, "growth.daily.roadmap_title", "Streak roadmap", "STRING", "growth", "PUBLIC", "Daily roadmap title.", 1, null, null),
                new ConfigItemResponse(631L, "growth.daily.points_history_title", "Recent points", "STRING", "growth", "PUBLIC", "Daily points history title.", 1, null, null),
                new ConfigItemResponse(632L, "growth.daily.points_history_empty_copy", "No points ledger rows yet.", "STRING", "growth", "PUBLIC", "Daily points history empty copy.", 1, null, null)));

        ApiResult<Map<String, Object>> response = controller.growth();

        assertThat(response.getCode()).isZero();
        assertThat(response.getData())
                .containsEntry("trial.enabled", true)
                .containsEntry("trial.device_name", "NexionBox S1")
                .containsEntry("trial.duration_days", new java.math.BigDecimal("3"))
                .containsEntry("trial.daily_usdt", new java.math.BigDecimal("38.50"))
                .containsEntry("trial.daily_nex", new java.math.BigDecimal("65"))
                .containsEntry("trial.seats_left_today", new java.math.BigDecimal("47"))
                .containsEntry("trial.offset_cap_usdt", new java.math.BigDecimal("50"))
                .containsEntry("trial.price_usdt", new java.math.BigDecimal("1299"))
                .containsEntry("earn.device_rank_limit", new java.math.BigDecimal("5"))
                .containsEntry("earn.task_teaser_limit", new java.math.BigDecimal("3"))
                .containsEntry("earn.locked_task_limit", new java.math.BigDecimal("3"))
                .containsEntry("earn.jobs_live_global", new java.math.BigDecimal("8432"))
                .containsEntry("earn.production_share_pct", new java.math.BigDecimal("76"))
                .containsEntry("earn.ai_premium_share_pct", new java.math.BigDecimal("24"))
                .containsEntry("earn.phone_battery_pct", new java.math.BigDecimal("78"))
                .containsEntry("earn.phone_daily_usdt", new java.math.BigDecimal("0.06"))
                .containsEntry("earn.phone_npu_tops", new java.math.BigDecimal("28"))
                .containsEntry("earn.phone_network_ping_ms", new java.math.BigDecimal("62"))
                .containsEntry("earn.gateway_sg_ping_ms", new java.math.BigDecimal("38"))
                .containsEntry("earn.gateway_tokyo_ping_ms", new java.math.BigDecimal("42"))
                .containsEntry("earn.gateway_us_west_ping_ms", new java.math.BigDecimal("156"))
                .containsEntry("earn.estimator_detection_delay_ms", new java.math.BigDecimal("700"))
                .containsEntry("earn.calibration_duration_ms", new java.math.BigDecimal("12000"))
                .containsEntry("earn.calibration_base_score", new java.math.BigDecimal("58"))
                .containsEntry("earn.calibration_active_device_score", new java.math.BigDecimal("12"))
                .containsEntry("earn.calibration_wallet_paired_score", new java.math.BigDecimal("16"))
                .containsEntry("earn.calibration_wallet_balance_score", new java.math.BigDecimal("8"))
                .containsEntry("earn.calibration_tier2_threshold", new java.math.BigDecimal("75"))
                .containsEntry("earn.calibration_tier3_threshold", new java.math.BigDecimal("90"))
                .containsEntry("earn.max_device_slots", new java.math.BigDecimal("6"))
                .containsEntry("earn.network_avg_daily_usdt", new java.math.BigDecimal("143"))
                .containsEntry("earn.completed_rows_limit", new java.math.BigDecimal("3"))
                .containsEntry("daily.hero_copy", "Keep today active, recover missed streaks, and unlock configured milestone rewards.")
                .containsEntry("daily.loading_copy", "Loading daily rewards...")
                .containsEntry("daily.next_milestone_done_label", "All caught up")
                .containsEntry("daily.saver_title", "Streak saver available")
                .containsEntry("daily.saver_copy", "Recover yesterday's missed activity and keep the current reward chain alive.")
                .containsEntry("daily.baseline_points", new java.math.BigDecimal("10"))
                .containsEntry("daily.streak_bonus_7d_points", new java.math.BigDecimal("20"))
                .containsEntry("daily.lucky_multiplier_max", new java.math.BigDecimal("3"))
                .containsEntry("daily.lucky_probability_pct", new java.math.BigDecimal("18"))
                .containsEntry("daily.points_redeem_rate", "100 pts = $1")
                .containsEntry("daily.leaderboard_limit", new java.math.BigDecimal("5"))
                .containsEntry("daily.milestones_empty_copy", "No milestone configuration yet.")
                .containsEntry("daily.powerups_empty_copy", "No power-up configuration yet.")
                .containsEntry("daily.leaderboard_empty_copy", "No leaderboard entries yet.")
                .containsEntry("daily.metric_longest_label", "Longest streak")
                .containsEntry("daily.metric_next_label", "Next milestone")
                .containsEntry("daily.metric_savers_label", "Streak savers")
                .containsEntry("daily.metric_recoverable_label", "Recoverable")
                .containsEntry("daily.checkin_label", "Check in")
                .containsEntry("daily.checked_in_label", "Checked in")
                .containsEntry("daily.streak_saver_action_label", "Use")
                .containsEntry("daily.checked_in_toast", "Checked in")
                .containsEntry("daily.streak_restored_toast", "Streak restored")
                .containsEntry("daily.reward_claimed_toast", "Reward claimed")
                .containsEntry("daily.powerup_active_toast", "Power-up active")
                .containsEntry("daily.points_balance_label", "Points balance")
                .containsEntry("daily.next_reset_label", "Next check-in")
                .containsEntry("daily.next_reward_label", "Next reward")
                .containsEntry("daily.ready_now_label", "Ready now")
                .containsEntry("daily.week_calendar_title", "This week")
                .containsEntry("daily.roadmap_title", "Streak roadmap")
                .containsEntry("daily.points_history_title", "Recent points")
                .containsEntry("daily.points_history_empty_copy", "No points ledger rows yet.");
    }

    @Test
    void exposesPlatformPhaseConfigFromPublicPlatformConfig() {
        when(systemConfigService.listPublicByGroup("platform")).thenReturn(List.of(new ConfigItemResponse(
                22L,
                "platform.phase.config",
                "{\"currentPhase\":\"P1\",\"phases\":[{\"id\":\"P1\",\"name\":\"P1 极速拉新\"},{\"id\":\"P3\",\"name\":\"P3 升级窗口\"}]}",
                "JSON",
                "platform",
                "PUBLIC",
                "Platform phase config.",
                1,
                null,
                null)));

        ApiResult<Map<String, Object>> response = controller.generationGates();

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).containsEntry("currentPhase", "P1");
        assertThat(response.getData()).containsKey("phases");
    }
}
