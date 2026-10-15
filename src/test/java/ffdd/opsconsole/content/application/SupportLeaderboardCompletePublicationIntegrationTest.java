package ffdd.opsconsole.content.application;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariDataSource;
import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.content.domain.SupportLeaderboard.*;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionRow;
import ffdd.opsconsole.content.mapper.SupportLeaderboardAuthorizationMapper;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper.*;
import ffdd.opsconsole.content.mapper.SupportLeaderboardPublicationMapper;
import ffdd.opsconsole.content.web.SupportLeaderboardController;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.*;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Synthetic source facts/time; real projection, controller, MyBatis publication and commit/readback. */
@EnabledIfEnvironmentVariable(named = "SUPPORT_RANK_COMPLETE_MYSQL", matches = "true")
class SupportLeaderboardCompletePublicationIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-09T02:00:00");
    private static final LocalDateTime START = LocalDateTime.parse("2026-09-01T00:00:00");
    private static final String API = "/api/admin/content/support-workbench/leaderboard";
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final String schema = "cs_rank_complete_" + UUID.randomUUID().toString().replace("-", "");
    private final SupportLeaderboardMapper inputs = mock(SupportLeaderboardMapper.class);
    private final FinanceSupportPaymentFactsFacade finance = mock(FinanceSupportPaymentFactsFacade.class);
    private final SupportFundsReadFacade funds = mock(SupportFundsReadFacade.class);
    private final SupportOwnershipService ownership = mock(SupportOwnershipService.class);
    private final SupportLeaderboardAuthorizationMapper authorization = mock(SupportLeaderboardAuthorizationMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private HikariDataSource admin, database, independent;
    private JdbcTemplate outside;
    private DataSourceTransactionManager transactions;
    private SupportLeaderboardPublicationMapper publications;
    private boolean created;

    @BeforeEach void isolatedTwoTableDatabaseAndCompleteInputs() throws Exception {
        String url = required("SUPPORT_RANK_MYSQL_ADMIN_URL");
        assertThat(url).isEqualTo("jdbc:mysql://127.0.0.1:33338/mysql?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC");
        admin = pool(url);
        var maintenance = new JdbcTemplate(admin);
        assertThat(maintenance.queryForObject("SELECT @@server_uuid", String.class)).isEqualTo(required("SUPPORT_RANK_SERVER_UUID"));
        assertThat(maintenance.queryForObject("SELECT @@port", Integer.class)).isEqualTo(33338);
        assertThat(schema).matches("cs_rank_complete_[a-f0-9]{32}");
        maintenance.execute("CREATE DATABASE `" + schema + "`");
        created = true;
        String target = url.replace("/mysql?", "/" + schema + "?");
        database = pool(target);
        independent = pool(target);
        outside = new JdbcTemplate(independent);
        var jdbc = new JdbcTemplate(database);
        for (String statement : Files.readString(Path.of("scripts/migrations/20261009_support_leaderboard_publication.sql")).split(";")) {
            if (!statement.isBlank()) jdbc.execute(statement);
        }
        assertThat(outside.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?", Integer.class, schema)).isEqualTo(2);
        assertThat(outside.queryForObject("SELECT CONNECTION_ID()", Long.class)).isNotEqualTo(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
        transactions = new DataSourceTransactionManager(database);
        var mybatis = new Configuration(new Environment("complete-rank-test", new SpringManagedTransactionFactory(), database));
        mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.setDefaultStatementTimeout(10);
        mybatis.addMapper(SupportLeaderboardPublicationMapper.class);
        publications = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(mybatis)).getMapper(SupportLeaderboardPublicationMapper.class);
        completeInputs();
        login();
        evidence.put("mode", "SYNTHETIC_SOURCES_FIXED_TIME_REAL_MYSQL_PUBLICATION_MOCKMVC");
        evidence.put("evaluatedAt", NOW.toInstant(ZoneOffset.UTC).toString());
        evidence.put("schema", schema);
        evidence.put("sourceRecords", Map.of("agents", 3, "customers", 3, "payments", 6, "reductions", 4));
    }

    @Test @Timeout(90)
    void completeFirstPaymentAndNetContributionPublishAndSurviveServiceRecreation() throws Exception {
        MockMvc mvc = controller();
        var results = new ArrayList<Map<String, Object>>();
        for (Board board : List.of(Board.firstPayment, Board.netContribution)) for (String currency : List.of("USDT", "NEX")) {
            boolean reverse = board == Board.netContribution && currency.equals("USDT");
            List<String> ids = reverse ? List.of("8", "7", "9") : List.of("7", "8", "9");
            JsonNode first = page(mvc, board, currency, null, 1);
            String version = first.path("viewVersion").asText();
            assertThat(SupportLeaderboard.validVersion(version)).isTrue();
            assertThat(first.path("state").asText()).isEqualTo("COMPLETE");
            assertThat(first.path("candidateCoverage").asText()).isEqualTo("COMPLETE");
            assertThat(first.path("total").asInt()).isEqualTo(3);
            assertThat(first.path("ranked").asInt()).isEqualTo(3);
            assertThat(first.path("unranked").asInt()).isZero();
            assertThat(first.path("asOf").asText()).isEqualTo(NOW.toInstant(ZoneOffset.UTC).toString());
            JsonNode second = page(mvc, board, currency, version, 2);
            assertThat(second.path("viewVersion").asText()).isEqualTo(version);
            var rows = new ArrayList<JsonNode>();
            first.path("rows").forEach(rows::add);
            second.path("rows").forEach(rows::add);
            assertThat(rows).extracting(r -> r.path("agentId").asText()).containsExactlyElementsOf(ids);
            assertThat(rows).extracting(r -> r.path("rank").asInt()).containsExactly(1, 2, 3);
            long firstCustomers = 0;
            for (JsonNode row : rows) {
                String id = row.path("agentId").asText();
                long count = id.equals("7") ? 2 : id.equals("8") ? 1 : 0;
                assertThat(row.path("firstPayment").path("value").isIntegralNumber()).isTrue();
                assertThat(row.path("customers").path("value").isIntegralNumber()).isTrue();
                firstCustomers += row.path("firstPayment").path("value").asLong();
                assertThat(row.path("firstPayment").path("value").asLong()).isEqualTo(count);
                assertThat(row.path("customers").path("value").asLong()).isEqualTo(count);
                String amount = id.equals("9") ? "0" : currency.equals("USDT") ? (id.equals("7") ? "110" : "150") : (id.equals("7") ? "11" : "5");
                assertThat(new BigDecimal(row.path("amount").path("value").asText())).isEqualByComparingTo(amount);
                assertThat(row.path("amount").path("currency").asText()).isEqualTo(currency);
                assertThat(row.path("amount").path("kind").asText()).isEqualTo("NET_CONTRIBUTION");
                for (String metric : List.of("amount", "firstPayment", "customers")) assertThat(row.path(metric).path("coverage").asText()).isEqualTo("COMPLETE");
            }
            assertThat(firstCustomers).isEqualTo(3); // Six payments still represent three lifetime first-paying customers.
            assertThat(first.path("self").path("row").path("rank").asInt()).isEqualTo(reverse ? 2 : 1);
            assertThat(first.path("podium").path("total").asInt()).isEqualTo(3);
            JsonNode detail = request(mvc, API + "/8", board, currency, version, 1);
            assertThat(detail.path("row")).isEqualTo(rows.stream().filter(r -> r.path("agentId").asText().equals("8")).findFirst().orElseThrow());

            Map<String, Object> stored = outside.queryForMap("SELECT p.* FROM nx_support_leaderboard_publication p JOIN nx_support_leaderboard_latest l ON l.publication_id=p.id WHERE p.board=? AND p.currency=?", board.name(), currency);
            assertThat(stored).containsEntry("view_version", version).containsEntry("state", "COMPLETE");
            String payload = (String) stored.get("payload");
            assertThat(stored.get("payload_hash")).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))));
            SupportLeaderboard.Snapshot snapshot = json.readValue(payload, SupportLeaderboard.Snapshot.class);
            assertThat(snapshot.rows()).extracting(Row::agentId).containsExactlyElementsOf(ids.stream().map(Long::parseLong).toList());
            assertThat(snapshot.rows()).extracting(Row::rank).containsExactly(1, 2, 3);
            assertThat(snapshot.viewVersion()).isEqualTo(version);
            assertThat(snapshot.context().evaluatedAt()).isEqualTo(NOW.toInstant(ZoneOffset.UTC));
            assertThat(stored.get("definition_version")).isEqualTo(SupportLeaderboardPolicy.DEFINITION);
            JsonNode recreated = page(controller(), board, currency, version, 1);
            assertThat(recreated).isEqualTo(first);
            results.add(Map.of("board", board.name(), "currency", currency, "viewVersion", version, "publicationId", stored.get("id"), "rows", rows, "sameVersionAfterRecreation", true));
        }
        assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_leaderboard_publication", Integer.class)).isEqualTo(4);
        assertThat(outside.queryForObject("SELECT COUNT(*) FROM nx_support_leaderboard_latest", Integer.class)).isEqualTo(4);
        evidence.put("streams", results);
        evidence.put("publicationRows", 4);
        evidence.put("databaseBytes", outside.queryForObject("SELECT SUM(data_length+index_length) FROM information_schema.tables WHERE table_schema=?", Long.class, schema));
        evidence.put("passed", true);
    }

    private void completeInputs() throws Exception {
        when(inputs.nowUtc()).thenReturn(NOW);
        when(inputs.qualificationCutoverUtc()).thenReturn(START);
        when(inputs.accounts()).thenReturn(List.of(7L, 8L, 9L).stream().map(id -> new Account(id, "测试客服" + id, 1, 0, 1L, 1, 0, 1L, "SUPPORT", null, null, null, null, 1, 1)).toList());
        when(inputs.qualifications()).thenReturn(List.of(7L, 8L, 9L).stream().map(id -> new QualificationInterval(id, id, "ENABLED", 1L, START, null)).toList());
        when(inputs.memberships()).thenReturn(List.of(7L, 8L, 9L).stream().map(id -> new MemberInterval(id, id, 100L, 1L, START, null)).toList());
        when(inputs.groups()).thenReturn(List.of(new Group(100L, "测试组", "ENABLED", 10L, 1L, START, START)));
        when(inputs.productionCustomers()).thenReturn(List.of(1L, 2L, 3L));
        when(inputs.currentBindings()).thenReturn(List.of(1L, 2L, 3L).stream().map(id -> new Binding(id, id, id == 3 ? 8L : 7L, 1L, START, null)).toList());
        var facts = new ArrayList<Fact>();
        var attributions = new ArrayList<AttributionRow>();
        var proofs = new ArrayList<AttributionProof>();
        var origins = new ArrayList<ContributionOrigin>();
        var changes = new ArrayList<ContributionChange>();
        for (String currency : List.of("USDT", "NEX")) for (long customer : List.of(1L, 2L, 3L)) {
            long ledger = customer + (currency.equals("USDT") ? 100 : 200);
            LocalDateTime at = LocalDateTime.parse(currency.equals("USDT") ? "2026-10-01T10:00:00" : "2026-10-02T10:00:00");
            String amount = currency.equals("USDT") ? (customer == 1 ? "100" : customer == 2 ? "30" : "200") : (customer == 1 ? "10" : customer == 2 ? "3" : "10");
            Fact fact = new Fact("DEPOSIT:" + ledger, Kind.DEPOSIT, Source.CARD_TOPUP, List.of("test-source-" + ledger), customer, ledger, "test-payment-" + ledger, null, null, null, currency, new BigDecimal(amount), at, "nx_wallet_ledger.created_at", 0, null, at, null, "source-v1", Status.UNKNOWN);
            facts.add(fact);
            long agent = customer == 3 ? 8 : 7;
            var attribution = new AttributionRow(fact.factId(), customer, fact.kind().name(), fact.source().name(), ledger, fact.sourceBusinessId(), null, null, null, currency, fact.amount(), at, "Asia/Shanghai", fact.successTimeField(), 0, "NEW_SUCCESS", "support-payment-attribution-v1", agent, 100L, 10L, "KNOWN", "KNOWN", "KNOWN");
            attributions.add(attribution);
            proofs.add(proof(fact, attribution));
            String origin = "contribution:" + fact.factId();
            origins.add(new ContributionOrigin(origin, customer, fact.factId(), at, "Asia/Shanghai", 0, fact.factId()));
            changes.add(new ContributionChange(origin, customer, currency, fact.amount(), at, "Asia/Shanghai", 0, fact.factId()));
            if (customer != 2) {
                String reduction = currency.equals("USDT") ? (customer == 1 ? "-20" : "-50") : (customer == 1 ? "-2" : "-5");
                changes.add(new ContributionChange(origin, customer, currency, new BigDecimal(reduction), at.plusDays(1), "Asia/Shanghai", 0, "test-reduction-" + ledger));
            }
        }
        when(inputs.attributions()).thenReturn(attributions);
        when(inputs.attributionProofs()).thenReturn(proofs);
        var coverage = Arrays.stream(Source.values()).filter(s -> s != Source.FREE_TRIAL && s != Source.UNMATCHED_LEDGER).map(s -> new SupportPaymentFacts.Coverage(s, Status.READY, Status.UNKNOWN, Status.UNKNOWN, Status.UNKNOWN, null, List.of("HISTORY_UNVERIFIED", "FINAL_DEPOSIT_REFUND_SOURCE_UNAVAILABLE"), 0, "adapter-v1")).toList();
        var histories = List.of(1L, 2L, 3L).stream().map(id -> new SupportPaymentFacts.FirstHistory(id, Status.READY, List.of())).toList();
        when(finance.readHistory(List.of(1L, 2L, 3L))).thenReturn(new SupportPaymentFacts.Snapshot(facts, List.of(), coverage, "Asia/Shanghai", NOW.toInstant(ZoneOffset.UTC), histories));
        when(funds.readNetContributions(List.of(1L, 2L, 3L))).thenReturn(new NetSnapshot(List.of(
            new NetCustomer(1, EvidenceState.READY, List.of(), new BigDecimal("80"), new BigDecimal("8")),
            new NetCustomer(2, EvidenceState.READY, List.of(), new BigDecimal("30"), new BigDecimal("3")),
            new NetCustomer(3, EvidenceState.READY, List.of(), new BigDecimal("150"), new BigDecimal("5"))), origins, changes));
        when(ownership.actorId()).thenReturn(7L);
        when(ownership.canReadAgent(7L, 7L)).thenReturn(true);
        when(authorization.account(7)).thenReturn(new SupportLeaderboardAuthorizationMapper.Account(7L, 1L));
        when(authorization.roles(7)).thenReturn(List.of(new SupportLeaderboardAuthorizationMapper.Role(1L, 1L, "SUPPORT")));
        when(authorization.grants(7)).thenReturn(List.of(new SupportLeaderboardAuthorizationMapper.Grant(1L, 1L, 1L, 1L, "service_m1_read")));
        when(authorization.qualifications(7)).thenReturn(List.of(new SupportLeaderboardAuthorizationMapper.Qualification(1L, "SERVICE", 1L, 1L)));
        when(authorization.ownGroups(7)).thenReturn(List.of(new SupportLeaderboardAuthorizationMapper.Member(1L, 100L, 1L, "测试组", 1L)));
        when(config.activeValue(SupportLeaderboardService.REFRESH_KEY)).thenReturn(Optional.of("5"));
    }

    private AttributionProof proof(Fact fact, AttributionRow row) throws Exception {
        var source = new LinkedHashMap<String, Object>();
        source.put("factId", fact.factId()); source.put("customerId", fact.customerId()); source.put("ledgerId", fact.ledgerId());
        source.put("kind", fact.kind().name()); source.put("source", fact.source().name()); source.put("currency", fact.currency()); source.put("amount", fact.amount());
        source.put("succeededAt", fact.succeededAt().toString()); source.put("businessZone", "Asia/Shanghai"); source.put("fractionalSecondDigits", 0);
        var captured = new LinkedHashMap<String, Object>();
        captured.put("schemaVersion", row.captureSchemaVersion()); captured.put("captureMode", row.captureMode());
        captured.put("captureZone", "UTC"); captured.put("captureDbUtc", NOW.toString()); captured.put("sourcePartition", null);
        captured.put("agent", Map.of("status", "KNOWN", "id", row.agentAdminId()));
        captured.put("group", Map.of("status", "KNOWN", "id", 100L)); captured.put("owner", Map.of("status", "KNOWN", "id", 10L));
        return new AttributionProof(fact.factId(), null, NOW, json.writeValueAsString(source), json.writeValueAsString(captured));
    }

    private MockMvc controller() {
        var source = transactional(new SupportLeaderboardSourceService(inputs, finance, funds));
        var publisher = transactional(new SupportLeaderboardPublicationService(publications));
        var service = transactional(new SupportLeaderboardService(ownership, authorization, inputs, source, publisher, publications, config, mock(SupportAdminAvatarService.class), transactions));
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        var guard = new ProductionSupportPathGuard(environment, mock(ffdd.opsconsole.content.mapper.SupportAcceptanceSandboxMapper.class));
        return MockMvcBuilders.standaloneSetup(new SupportLeaderboardController(service, guard)).build();
    }

    @SuppressWarnings("unchecked") private <T> T transactional(T target) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    private JsonNode page(MockMvc mvc, Board board, String currency, String version, int page) throws Exception { return request(mvc, API, board, currency, version, page); }
    private JsonNode request(MockMvc mvc, String path, Board board, String currency, String version, int page) throws Exception {
        var request = get(path).param("board", board.name()).param("currency", currency).param("scope", "all").param("pageSize", "2").param("pageNum", Integer.toString(page));
        if (version != null) request.param("expectedVersion", version);
        var body = json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asInt()).isZero();
        return body.path("data");
    }

    private void login() {
        var token = new UsernamePasswordAuthenticationToken("7", "TEST_ONLY", List.of(new SimpleGrantedAuthority("service_m1_read")));
        token.setDetails(Map.of("subjectType", "ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private HikariDataSource pool(String url) {
        var pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(required("SUPPORT_RANK_MYSQL_USERNAME"));
        pool.setPassword(required("SUPPORT_RANK_MYSQL_PASSWORD"));
        pool.setMaximumPoolSize(3); pool.setMinimumIdle(0); pool.setConnectionTimeout(5000);
        return pool;
    }
    private static String required(String name) { return Objects.requireNonNull(System.getenv(name), "Missing " + name); }

    @AfterEach void removeOnlyThisTestSchema() throws Exception {
        SecurityContextHolder.clearContext();
        try {
            if (database != null) database.close();
            if (independent != null) independent.close();
            if (created) {
                assertThat(schema).matches("cs_rank_complete_[a-f0-9]{32}");
                var maintenance = new JdbcTemplate(admin);
                maintenance.execute("DROP DATABASE `" + schema + "`");
                assertThat(maintenance.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema)).isZero();
                evidence.put("schemaRemoved", true);
            }
        } finally {
            if (admin != null) admin.close();
            String output = System.getenv("SUPPORT_RANK_EVIDENCE");
            if (output != null) json.writerWithDefaultPrettyPrinter().writeValue(Path.of(output).toFile(), evidence);
        }
    }
}
