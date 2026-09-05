package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.market.mapper.AppGenesisMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.CanonicalEventSchemaMySqlFixture;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.stubbing.Answer;
import org.springframework.core.env.Environment;

@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class MarketEventSchemaMySqlIntegrationTest {
    private static final String EMISSION_EVENT = "genesis.emission_paid";
    private static final String RESTORE_EVENT = "admin.staking_pool_restored";
    private static final String HOLDING_NO = "fixture-holding-001";
    private static final String BATCH_NO = "20260831";
    private static final String TIER_KEY = "usdt30d";
    private static final int REVISION = 316;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void absentSchemaRollsBackBothRealPublisherTransactionsBeforeAnyOutboxInsert() throws Exception {
        try (CanonicalEventSchemaMySqlFixture fixture = fixture()) {
            seedEmissionBusinessState(fixture);
            seedStakingRestoreState(fixture);
            G4AdminCommandService g4 = fixture.transactional(g4Publisher(fixture));
            G1AdminCommandService g1 = fixture.transactional(g1Publisher(fixture));

            assertThatThrownBy(() -> g4.rerunEmission("fixture-g4-missing-schema", BATCH_NO, emissionRequest()))
                    .hasMessage("A4_SCHEMA_NOT_REGISTERED");
            assertThatThrownBy(() -> g1.restore("fixture-g1-missing-schema", TIER_KEY, restoreRequest()))
                    .hasMessage("A4_SCHEMA_NOT_REGISTERED");

            assertThat(fixture.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM nx_genesis_emission_batch WHERE batch_no=?", Integer.class, BATCH_NO))
                    .isZero();
            assertThat(fixture.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM nx_genesis_emission_item WHERE batch_no=?", Integer.class, BATCH_NO))
                    .isZero();
            assertThat(fixture.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no LIKE 'G4E-%'", Integer.class))
                    .isZero();
            assertThat(configValue(fixture, stakingKillConfigKey())).isEqualTo("true");
            assertThat(walletBalance(fixture)).isEqualByComparingTo("100.000000");
            assertThat(outboxCount(fixture, EMISSION_EVENT)).isZero();
            assertThat(outboxCount(fixture, RESTORE_EVENT)).isZero();
        }
    }

    @Test
    void migrationRevision316LetsTheRealG4PublisherPersistItsExactEmissionPayload() throws Exception {
        try (CanonicalEventSchemaMySqlFixture fixture = fixture()) {
            fixture.migrate();
            seedEmissionBusinessState(fixture);

            fixture.transactional(g4Publisher(fixture))
                    .rerunEmission("fixture-g4-schema-316", BATCH_NO, emissionRequest());

            JsonNode payload = outboxPayload(fixture, EMISSION_EVENT);
            assertThat(schemaRevision(fixture, EMISSION_EVENT)).isEqualTo(REVISION);
            assertThat(fieldNames(payload)).containsExactlyInAnyOrderElementsOf(Set.of(
                    "holdingNo", "amountUsdt", "rateApplied", "paidAt",
                    "holding_no", "amount_usdt", "rate_applied", "paid_at",
                    "event_id", "event_name", "ts", "user_id", "anon_id", "session_id", "phase",
                    "account_age_months", "cohort", "ref", "source", "platform", "app_version", "locale",
                    "is_server_authoritative", "schema_revision"));
            assertThat(payload.path("holding_no").asText()).isEqualTo(HOLDING_NO);
            assertThat(payload.path("amount_usdt").decimalValue()).isEqualByComparingTo("2.500000");
            assertThat(payload.path("rate_applied").decimalValue()).isEqualByComparingTo("2.5");
            assertThat(payload.path("paid_at").asText()).isEqualTo("2026-08-31 12:00:00");
            assertThat(payload.path("user_id").asLong()).isEqualTo(1L);
            assertThat(payload.path("event_name").asText()).isEqualTo(EMISSION_EVENT);
            assertThat(payload.path("schema_revision").asInt()).isEqualTo(REVISION);
            assertNoPii(payload);
            assertThat(fixture.jdbc().queryForObject(
                    "SELECT status FROM nx_genesis_emission_item WHERE batch_no=?", String.class, BATCH_NO))
                    .isEqualTo("PAID");
            assertThat(walletBalance(fixture)).isEqualByComparingTo("102.500000");
            assertThat(fixture.jdbc().queryForObject("SELECT amount FROM nx_wallet_ledger WHERE biz_no LIKE 'G4E-%'",
                    java.math.BigDecimal.class)).isEqualByComparingTo("2.500000");
        }
    }

    @Test
    void migrationRevision316LetsTheRealG1PublisherPersistItsExactRestorationPayload() throws Exception {
        try (CanonicalEventSchemaMySqlFixture fixture = fixture()) {
            fixture.migrate();
            seedStakingRestoreState(fixture);

            fixture.transactional(g1Publisher(fixture))
                    .restore("fixture-g1-schema-316", TIER_KEY, restoreRequest());

            JsonNode payload = outboxPayload(fixture, RESTORE_EVENT);
            assertThat(schemaRevision(fixture, RESTORE_EVENT)).isEqualTo(REVISION);
            assertThat(fieldNames(payload)).containsExactlyInAnyOrderElementsOf(Set.of(
                    "tierKey", "triggerBasis", "reviewConclusion", "reason", "operator", "restorationDomain",
                    "tier_key", "trigger_basis", "review_conclusion", "restoration_domain",
                    "event_id", "event_name", "ts", "user_id", "anon_id", "session_id", "phase",
                    "account_age_months", "cohort", "ref", "source", "platform", "app_version", "locale",
                    "is_server_authoritative", "schema_revision"));
            assertThat(payload.path("tier_key").asText()).isEqualTo(TIER_KEY);
            assertThat(payload.path("trigger_basis").asText()).isEqualTo("MANUAL_RISK_REVIEW");
            assertThat(payload.path("review_conclusion").asText())
                    .isEqualTo("documented review confirms controlled restoration");
            assertThat(payload.path("reason").asText()).isEqualTo("coverage and incident controls recovered");
            assertThat(payload.path("operator").asText()).isEqualTo("fixture-admin");
            assertThat(payload.path("restoration_domain").asText()).isEqualTo("J1");
            assertThat(payload.path("event_name").asText()).isEqualTo(RESTORE_EVENT);
            assertThat(payload.path("schema_revision").asInt()).isEqualTo(REVISION);
            assertNoPii(payload);
            assertThat(configValue(fixture, stakingKillConfigKey())).isEqualTo("false");
        }
    }

    @Test
    void pendingPublishLifecycleStillBlocksBothPublishersAndRollsBackTheirBusinessWrites() throws Exception {
        try (CanonicalEventSchemaMySqlFixture fixture = fixture()) {
            fixture.migrate();
            seedEmissionBusinessState(fixture);
            seedStakingRestoreState(fixture);
            fixture.jdbc().update("UPDATE nx_admin_event_lifecycle SET lifecycle_state='pending_publish' WHERE event_name IN (?, ?)",
                    EMISSION_EVENT, RESTORE_EVENT);

            G4AdminCommandService g4 = fixture.transactional(g4Publisher(fixture));
            G1AdminCommandService g1 = fixture.transactional(g1Publisher(fixture));

            assertThatThrownBy(() -> g4.rerunEmission("fixture-g4-lifecycle", BATCH_NO, emissionRequest()))
                    .hasMessage("A4_EVENT_LIFECYCLE_BLOCKED_PENDING_PUBLISH");
            assertThatThrownBy(() -> g1.restore("fixture-g1-lifecycle", TIER_KEY, restoreRequest()))
                    .hasMessage("A4_EVENT_LIFECYCLE_BLOCKED_PENDING_PUBLISH");

            assertThat(fixture.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM nx_genesis_emission_batch WHERE batch_no=?", Integer.class, BATCH_NO))
                    .isZero();
            assertThat(fixture.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM nx_wallet_ledger WHERE biz_no LIKE 'G4E-%'", Integer.class))
                    .isZero();
            assertThat(configValue(fixture, stakingKillConfigKey())).isEqualTo("true");
            assertThat(walletBalance(fixture)).isEqualByComparingTo("100.000000");
            assertThat(outboxCount(fixture, EMISSION_EVENT)).isZero();
            assertThat(outboxCount(fixture, RESTORE_EVENT)).isZero();
        }
    }

    private CanonicalEventSchemaMySqlFixture fixture() throws Exception {
        return new CanonicalEventSchemaMySqlFixture(
                "nx_user", "nx_user_wallet", "nx_wallet_ledger", "nx_config_item",
                "nx_genesis_series", "nx_genesis_holding", "nx_genesis_emission_batch", "nx_genesis_emission_item");
    }

    private G4AdminCommandService g4Publisher(CanonicalEventSchemaMySqlFixture fixture) {
        OpsNexMarketService market = mock(OpsNexMarketService.class);
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        AdminIdempotencyService idempotency = immediateIdempotency();
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(config.activeValue("growth.phase.genesis_emissions_open")).thenReturn(java.util.Optional.of("true"));
        when(config.activeValue("killswitch.genesis")).thenReturn(java.util.Optional.of("true"));
        when(market.genesisOverview()).thenReturn(ApiResult.ok(new LinkedHashMap<>(Map.of("domain", "G4"))));

        EventOutboxService outbox = spy(fixture.outbox());
        // The batch summary is not part of this target regression; the per-holding event stays real.
        doReturn("fixture-summary-noop").when(outbox).publish(eq("GENESIS_EMISSION_BATCH"), anyString(),
                eq("admin.genesis_emission_batch_rerun"), any());
        // Isolate the earnings-release policy but exercise a real transactional wallet write.
        // This is not a full EarningsReleaseService reserve/coverage acceptance test.
        EarningsReleaseService earnings = mock(EarningsReleaseService.class);
        doAnswer(call -> {
            java.math.BigDecimal amount = call.getArgument(4);
            Long userId = call.getArgument(0);
            assertThat(fixture.jdbc().update("UPDATE nx_user_wallet SET usdt_available=usdt_available+? WHERE user_id=?",
                    amount, userId)).isEqualTo(1);
            return "fixture-reward-credit";
        }).when(earnings).creditReward(any(), anyString(), anyString(), eq("USDT"), any(), anyString());
        return new G4AdminCommandService(market, fixture.mapper(AppGenesisMapper.class), config, idempotency, outbox,
                mock(AuditLogService.class), Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC),
                earnings, environment);
    }

    private G1AdminCommandService g1Publisher(CanonicalEventSchemaMySqlFixture fixture) {
        OpsNexMarketService market = mock(OpsNexMarketService.class);
        when(market.restoreStakingPool(anyString(), eq(TIER_KEY), any())).thenAnswer((Answer<ApiResult<Map<String, Object>>>) invocation -> {
            fixture.jdbc().update("UPDATE nx_config_item SET config_value='false' WHERE config_key=?", stakingKillConfigKey());
            return ApiResult.ok(Map.of("domain", "G1"));
        });
        return new G1AdminCommandService(market, immediateIdempotency(), fixture.outbox(),
                mock(ffdd.opsconsole.market.mapper.StakingMapper.class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private AdminIdempotencyService immediateIdempotency() {
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
        return idempotency;
    }

    private void seedEmissionBusinessState(CanonicalEventSchemaMySqlFixture fixture) {
        fixture.jdbc().update("""
                INSERT INTO nx_user (id,country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox,is_deleted,created_at)
                VALUES (1,'ZZ','fixture-user','0.0.0.0','fixture-only','fixture-user','fixture-ref','ACTIVE',0,0,'2026-01-01 00:00:00')
                """);
        fixture.jdbc().update("""
                INSERT INTO nx_user_wallet (user_id,usdt_available,nex_available,pending_withdraw,lifetime_earned,cumulative_deposit_usdt,version,is_deleted)
                VALUES (1,100,0,0,0,0,0,0)
                """);
        fixture.jdbc().update("""
                INSERT INTO nx_genesis_series (series_code,name,total_supply,sold_supply,price_usdt,status,royalty_bps,daily_dividend_rate_pct,is_deleted)
                VALUES ('fixture-genesis','Fixture Genesis',10,1,100,'ACTIVE',0,2.5,0)
                """);
        fixture.jdbc().update("""
                INSERT INTO nx_genesis_holding (holding_no,user_id,order_no,series_code,acquired_price_usdt,status,acquired_at,is_deleted)
                VALUES (?,1,'fixture-order-001','fixture-genesis',100,'ACTIVE','2026-01-01 00:00:00',0)
                """, HOLDING_NO);
    }

    private void seedStakingRestoreState(CanonicalEventSchemaMySqlFixture fixture) {
        fixture.jdbc().update("""
                INSERT INTO nx_config_item (config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
                VALUES (?,'true','BOOLEAN','market','ADMIN','fixture-only restore state',1,0)
                """, stakingKillConfigKey());
    }

    private NexMarketValueUpdateRequest emissionRequest() {
        return new NexMarketValueUpdateRequest("2.5", "rerun daily emission", "fixture-admin",
                "FIXTURE-DECISION-316", null, null);
    }

    private NexMarketValueUpdateRequest restoreRequest() {
        return new NexMarketValueUpdateRequest("false", "coverage and incident controls recovered", "fixture-admin",
                null, "documented review confirms controlled restoration", "MANUAL_RISK_REVIEW");
    }

    private String stakingKillConfigKey() {
        return "G.staking." + TIER_KEY + ".killed";
    }

    private int schemaRevision(CanonicalEventSchemaMySqlFixture fixture, String eventName) {
        return fixture.jdbc().queryForObject(
                "SELECT current_revision FROM nx_event_schema_registry WHERE event_name=?", Integer.class, eventName);
    }

    private int outboxCount(CanonicalEventSchemaMySqlFixture fixture, String eventName) {
        return fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM nx_event_outbox WHERE event_name=?", Integer.class, eventName);
    }

    private JsonNode outboxPayload(CanonicalEventSchemaMySqlFixture fixture, String eventName) throws Exception {
        String payload = fixture.jdbc().queryForObject(
                "SELECT payload FROM nx_event_outbox WHERE event_name=? ORDER BY id DESC LIMIT 1", String.class, eventName);
        return JSON.readTree(payload);
    }

    private String configValue(CanonicalEventSchemaMySqlFixture fixture, String key) {
        return fixture.jdbc().queryForObject(
                "SELECT config_value FROM nx_config_item WHERE config_key=?", String.class, key);
    }

    private java.math.BigDecimal walletBalance(CanonicalEventSchemaMySqlFixture fixture) {
        return fixture.jdbc().queryForObject("SELECT usdt_available FROM nx_user_wallet WHERE user_id=1", java.math.BigDecimal.class);
    }

    private void assertNoPii(JsonNode payload) {
        assertThat(fieldNames(payload))
                .noneMatch(field -> field.contains("phone") || field.contains("email") || field.contains("address")
                        || field.contains("password") || field.contains("token"));
    }

    private Set<String> fieldNames(JsonNode payload) {
        Set<String> names = new LinkedHashSet<>();
        payload.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
