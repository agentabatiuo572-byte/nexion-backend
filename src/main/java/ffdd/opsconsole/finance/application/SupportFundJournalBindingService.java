package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.domain.SupportFundAllocation;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Event;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Binds raw revisions in the writer transaction. Source/birth/audit validators must still gate READY. */
@ApplicationService
public class SupportFundJournalBindingService implements SupportFundJournalFacade {
    public static final String PROTOCOL = "SUPPORT_FUND_BINDING_V1";
    private static final String JOURNAL_PROTOCOL_SHA = sha("SUPPORT_WALLET_JOURNAL_V1");
    private final SupportFundJournalMapper mapper;
    private final DataSource dataSource;
    private final ObjectMapper json;

    public SupportFundJournalBindingService(SupportFundJournalMapper mapper, DataSource dataSource, ObjectMapper json) {
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public Before begin(Collection<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty() || customerIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("Explicit positive customer scope required");
        Physical tx = transaction();
        List<Long> scope = List.copyOf(new TreeSet<>(customerIds));
        List<Wallet> wallets = scope.stream().map(this::lockWallet).toList();
        Set<String> incarnations = new HashSet<>();
        for (Wallet wallet : wallets) if (!incarnations.add(wallet.incarnation())) throw failure("AMBIGUOUS_WALLET_INCARNATION");
        return token(tx, scope, wallets, false);
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public Before beginBirth(long customerId) {
        if (customerId <= 0) throw new IllegalArgumentException("Explicit positive customer scope required");
        Physical tx = transaction();
        Wallet wallet = lockWallet(customerId);
        Tuple tuple = wallet.tuple();
        if (wallet.revision() != 0 || tuple.version() != 0 || tuple.usdt().signum() != 0 || tuple.nex().signum() != 0
            || tuple.pending().signum() != 0 || tuple.risk().signum() != 0) throw failure("INVALID_ZERO_WALLET_CANDIDATE");
        List<Map<String,Object>> candidates = mapper.birthCandidates(tuple.id(), customerId);
        if (candidates == null || candidates.size() != 1 || !wallet.incarnation().equals(string(candidates.get(0), "incarnation"))
            || number(candidates.get(0), "revision") != 0) throw failure("AMBIGUOUS_WALLET_BIRTH_CANDIDATE");
        List<Map<String,Object>> rows = mapper.journal(wallet.incarnation(), -1, 0);
        if (rows == null || rows.size() != 1) throw failure("FUND_JOURNAL_GAP");
        birthRow(rows.get(0), wallet);
        return token(tx, List.of(customerId), List.of(wallet), true);
    }

    private Token token(Physical tx, List<Long> scope, List<Wallet> wallets, boolean birth) {
        Token token = new Token(this, tx, scope, wallets, birth);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { token.active.set(false); }
        });
        return token;
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public BoundReceipt bind(Before before, ReceiptInput input) {
        Physical tx = transaction();
        if (!(before instanceof Token token) || token.owner != this || token.thread != Thread.currentThread()
            || token.tx.holder() != tx.holder() || token.tx.connection() != tx.connection()
            || !token.active.get() || !token.used.compareAndSet(false, true)) throw failure("INVALID_FUND_TRANSACTION_TOKEN");
        Map<String,Object> receipt = receipt(input, token.scope);
        List<Leg> legs = new ArrayList<>();
        for (Wallet old : token.wallets) {
            Wallet current = lockWallet(old.tuple().userId());
            if (!old.incarnation().equals(current.incarnation()) || !sameIdentity(old.tuple(), current.tuple())
                || current.revision() < old.revision()) throw failure("WALLET_IDENTITY_CHANGED");
            long startRevision = token.birth ? -1 : old.revision();
            List<Map<String,Object>> rows = mapper.journal(old.incarnation(), startRevision, current.revision());
            if (rows == null || (long) rows.size() != current.revision() - startRevision) throw failure("FUND_JOURNAL_GAP");
            Tuple preceding = old.tuple();
            long revision = startRevision;
            Set<Long> journalIds = new HashSet<>();
            for (Map<String,Object> row : rows) {
                if (row == null || !journalIds.add(positive(row, "id")) || !old.incarnation().equals(string(row, "incarnation"))
                    || number(row, "revision") != ++revision
                    || !JOURNAL_PROTOCOL_SHA.equals(string(row, "protocolSha256"))) throw failure("FUND_JOURNAL_GAP");
                time(row, "recordedDbUtc");
                if (token.birth && revision == 0) {
                    birthRow(row, old);
                    legs.add(new Leg(old.incarnation(), revision));
                    continue;
                }
                if (!"CHANGE".equals(string(row, "observation"))) throw failure("FUND_JOURNAL_GAP");
                Tuple oldTuple = tuple(row, "old");
                Tuple newTuple = tuple(row, "new");
                if (!preceding.equals(oldTuple) || !sameIdentity(old.tuple(), newTuple) || oldTuple.equals(newTuple))
                    throw failure("FUND_JOURNAL_TUPLE_MISMATCH");
                preceding = newTuple;
                legs.add(new Leg(old.incarnation(), revision));
            }
            if (!preceding.equals(current.tuple())) throw failure("FUND_JOURNAL_TAIL_MISMATCH");
        }
        legs.sort(java.util.Comparator.comparing(Leg::incarnation).thenComparingLong(Leg::revision));
        ObjectNode digest = json.valueToTree(receipt);
        digest.set("customerIds", json.valueToTree(token.scope));
        digest.set("legs", json.valueToTree(legs));
        receipt.put("receiptSha256", sha(canonical(digest).toString()));
        Map<String,Object> existing = null;
        // Insert first: a missing-key locking read under RR would hold a gap lock before insertion.
        try {
            if (mapper.insertOperation(receipt) != 1) throw failure("FUND_OPERATION_INSERT_FAILED");
        } catch (DuplicateKeyException duplicate) {
            existing = mapper.operation(input.operationKey());
            if (existing == null) throw duplicate;
        }
        if (existing == null) {
            existing = mapper.operation(input.operationKey());
            long id = exactReceipt(existing, receipt);
            for (long customerId : token.scope) if (mapper.insertParty(id, customerId) != 1)
                throw failure("FUND_OPERATION_PARTY_INSERT_FAILED");
            for (Leg leg : legs) if (mapper.insertLeg(id, leg.incarnation(), leg.revision()) != 1)
                throw failure("FUND_OPERATION_LEG_INSERT_FAILED");
        }
        long id = exactReceipt(existing, receipt);
        if (exactReceipt(mapper.operation(input.operationKey()), receipt) != id) throw failure("FUND_OPERATION_IDENTITY_CHANGED");
        List<Map<String,Object>> parties = mapper.parties(id);
        if (parties == null || parties.size() != token.scope.size()) throw failure("FUND_OPERATION_PARTY_MISMATCH");
        for (int i = 0; i < token.scope.size(); i++) {
            Map<String,Object> row = parties.get(i);
            if (number(row, "operationId") != id || number(row, "customerId") != token.scope.get(i))
                throw failure("FUND_OPERATION_PARTY_MISMATCH");
        }
        List<Map<String,Object>> actual = mapper.legs(id);
        if (actual == null || actual.size() != legs.size()) throw failure("FUND_OPERATION_LEG_MISMATCH");
        for (int i = 0; i < legs.size(); i++) {
            Map<String,Object> row = actual.get(i);
            Leg expected = legs.get(i);
            if (number(row, "operationId") != id || !expected.incarnation().equals(string(row, "incarnation"))
                || expected.revision() != number(row, "revision")) throw failure("FUND_OPERATION_LEG_MISMATCH");
        }
        return new BoundReceipt(id, input.operationKey(), (String) receipt.get("receiptSha256"), legs.size());
    }

    private Map<String,Object> receipt(ReceiptInput input, List<Long> scope) {
        if (input == null) throw new IllegalArgumentException("Missing formal receipt");
        key(input.operationKey(), 192, false);
        key(input.businessType(), 64, true);
        key(input.phase(), 32, true);
        key(input.sourceTable(), 64, true);
        key(input.sourceKey(), 192, false);
        if (!"PRODUCTION".equals(input.sourceEnvironment())) throw new IllegalArgumentException("Explicit production source required");
        JsonNode events = read(input.orderedEventsJson());
        if (!events.isArray()) throw new IllegalArgumentException("Ordered event array required");
        Set<String> ids = new HashSet<>();
        for (JsonNode node : events) {
            try {
                if (!node.isObject()) throw new IllegalArgumentException("Event record required");
                JsonNode action = node.path("action");
                if (!action.isTextual()) throw new IllegalArgumentException("Named event action required");
                SupportFundAllocation.Action.valueOf(action.textValue());
                Event event = decodeEvent(node);
                SupportFundAllocation.validate(event);
                if (!ids.add(event.id()) || event.from() != null && !scope.contains(event.from().customerId())
                    || event.to() != null && !scope.contains(event.to().customerId())) throw new IllegalArgumentException("Event outside scope or duplicate ID");
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalArgumentException("Invalid formal event record", ex);
            }
        }
        JsonNode snapshot = read(input.sourceSnapshotJson());
        JsonNode proof = read(input.sourceProofJson());
        if (!snapshot.isObject() || snapshot.isEmpty() || !proof.isObject() || proof.isEmpty())
            throw new IllegalArgumentException("Nonempty source snapshot and proof objects required");
        Map<String,Object> receipt = new LinkedHashMap<>();
        receipt.put("operationKey", input.operationKey()); receipt.put("businessType", input.businessType());
        receipt.put("phase", input.phase()); receipt.put("sourceTable", input.sourceTable());
        receipt.put("sourceKey", input.sourceKey()); receipt.put("sourceEnvironment", input.sourceEnvironment());
        receipt.put("orderedEventsJson", canonical(events).toString());
        receipt.put("sourceSnapshotJson", canonical(snapshot).toString());
        receipt.put("sourceProofJson", canonical(proof).toString()); receipt.put("protocolVersion", PROTOCOL);
        return receipt;
    }

    private Event decodeEvent(JsonNode node) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode decoded = node.deepCopy();
        // MySQL JSON can store canonical integer IDs as doubles; keep signed receipt bytes unchanged.
        for (String endpoint : List.of("from", "to")) {
            if (decoded.get(endpoint) instanceof ObjectNode account && account.path("customerId").isNumber()) {
                try { account.put("customerId", account.get("customerId").decimalValue().longValueExact()); }
                catch (ArithmeticException ex) { throw new IllegalArgumentException("Exact event customer ID required", ex); }
            }
        }
        return json.treeToValue(decoded, Event.class);
    }

    private long exactReceipt(Map<String,Object> actual, Map<String,Object> expected) {
        if (actual == null) throw failure("FUND_OPERATION_RECEIPT_MISSING");
        long id = positive(actual, "id");
        time(actual, "recordedDbUtc");
        for (Map.Entry<String,Object> entry : expected.entrySet()) {
            String value = string(actual, entry.getKey());
            if (entry.getKey().endsWith("Json")) value = canonical(read(value)).toString();
            if (!entry.getValue().equals(value)) throw failure("CONFLICTING_FUND_OPERATION_RECEIPT");
        }
        return id;
    }

    private static void birthRow(Map<String,Object> row, Wallet wallet) {
        if (row == null || positive(row, "id") <= 0 || !wallet.incarnation().equals(string(row, "incarnation"))
            || number(row, "revision") != 0 || !"NEW_ZERO".equals(string(row, "observation"))
            || !JOURNAL_PROTOCOL_SHA.equals(string(row, "protocolSha256")) || !tuple(row, "new").equals(wallet.tuple()))
            throw failure("INVALID_WALLET_BIRTH_CANDIDATE");
        time(row, "recordedDbUtc");
        for (String field : List.of("Id", "UserId", "UsdtAvailable", "NexAvailable", "PendingWithdraw", "CregisRiskHeld",
                "Version", "Sandbox", "IsDeleted", "CreatedAt"))
            if (row.get("old" + field) != null) throw failure("INVALID_WALLET_BIRTH_CANDIDATE");
    }

    private Wallet lockWallet(long customerId) {
        List<Map<String,Object>> rows = mapper.lockWallet(customerId);
        if (rows == null || rows.size() != 1) throw failure("MISSING_OR_AMBIGUOUS_WALLET");
        Map<String,Object> row = rows.get(0);
        Tuple tuple = tuple(row, "");
        String incarnation = string(row, "incarnation");
        if (tuple.userId() != customerId || tuple.sandbox() != 0 || tuple.isDeleted() != 0
            || !incarnation.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw failure("INVALID_PRODUCTION_WALLET");
        long revision = number(row, "revision");
        if (revision < 0) throw failure("INVALID_WALLET_REVISION");
        return new Wallet(incarnation, revision, tuple);
    }

    Connection readSnapshot(DataSource expectedDataSource) {
        if (expectedDataSource != dataSource) throw new IllegalStateException("SUPPORT_FUND_SNAPSHOT_DATASOURCE_MISMATCH");
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
            throw new IllegalStateException("SUPPORT_FUND_READ_ONLY_REPEATABLE_READ_REQUIRED");
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (!(resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction())
            throw new IllegalStateException("SUPPORT_FUND_PHYSICAL_SNAPSHOT_REQUIRED");
        try {
            Connection connection = holder.getConnection();
            if (connection.isClosed() || connection.getAutoCommit() || !connection.isReadOnly()
                    || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ)
                throw new IllegalStateException("SUPPORT_FUND_PHYSICAL_SNAPSHOT_REQUIRED");
            return connection;
        } catch (SQLException ex) { throw new IllegalStateException("SUPPORT_FUND_SNAPSHOT_CONNECTION_FAILED", ex); }
    }

    private Physical transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw failure("WRITABLE_FUND_TRANSACTION_REQUIRED");
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (!(resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction())
            throw failure("PHYSICAL_FUND_TRANSACTION_REQUIRED");
        try {
            Connection connection = holder.getConnection();
            if (connection.isClosed() || connection.isReadOnly() || connection.getAutoCommit()) throw failure("PHYSICAL_WRITABLE_FUND_TRANSACTION_REQUIRED");
            return new Physical(holder, connection);
        } catch (SQLException ex) { throw new IllegalStateException("FUND_TRANSACTION_CONNECTION_FAILED", ex); }
    }

    JsonNode read(String value) {
        if (value == null) throw new IllegalArgumentException("Missing receipt JSON");
        try {
            JsonNode node = json.readTree(value);
            if (node == null || node.isNull()) throw new IllegalArgumentException("Missing receipt JSON");
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException("Invalid receipt JSON", ex); }
    }

    JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = json.createObjectNode();
            Set<String> fields = new TreeSet<>(); node.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) sorted.set(field, canonical(node.get(field)));
            return sorted;
        }
        if (node.isArray()) {
            var array = json.createArrayNode();
            node.forEach(child -> array.add(canonical(child)));
            return array;
        }
        return node.isNumber() ? DecimalNode.valueOf(node.decimalValue().stripTrailingZeros()) : node;
    }

    static Tuple tuple(Map<String,Object> row, String prefix) {
        long version = number(row, field(prefix, "version"));
        if (version < 0) throw failure("INVALID_FUND_ROW:" + field(prefix, "version"));
        return new Tuple(positive(row, field(prefix, "id")), positive(row, field(prefix, "userId")),
            decimal(row, field(prefix, "usdtAvailable")), decimal(row, field(prefix, "nexAvailable")),
            decimal(row, field(prefix, "pendingWithdraw")), decimal(row, field(prefix, "cregisRiskHeld")),
            version, flag(row, field(prefix, "sandbox")),
            flag(row, field(prefix, "isDeleted")), time(row, field(prefix, "createdAt")));
    }
    private static String field(String prefix, String field) {
        return prefix.isEmpty() ? field : prefix + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }
    private static boolean sameIdentity(Tuple a, Tuple b) {
        return a.id() == b.id() && a.userId() == b.userId() && a.sandbox() == b.sandbox()
            && a.isDeleted() == b.isDeleted() && a.createdAt().equals(b.createdAt());
    }
    private static long number(Map<String,Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (!(value instanceof Number number)) throw failure("INVALID_FUND_ROW:" + key);
        try { return new BigDecimal(number.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException ex) { throw failure("INVALID_FUND_ROW:" + key); }
    }
    private static long positive(Map<String,Object> row, String key) {
        long value = number(row, key);
        if (value <= 0) throw failure("INVALID_FUND_ROW:" + key);
        return value;
    }
    private static long flag(Map<String,Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        long flag = value instanceof Boolean bit ? bit ? 1 : 0 : number(row, key);
        if (flag != 0 && flag != 1) throw failure("INVALID_FUND_ROW:" + key);
        return flag;
    }
    private static BigDecimal decimal(Map<String,Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (!(value instanceof BigDecimal amount) || amount.signum() < 0 || amount.precision() - amount.scale() > 12)
            throw failure("INVALID_FUND_ROW:" + key);
        try { return amount.setScale(6, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw failure("INVALID_FUND_ROW:" + key); }
    }
    private static LocalDateTime time(Map<String,Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        throw failure("INVALID_FUND_ROW:" + key);
    }
    private static String string(Map<String,Object> row, String key) {
        if (row == null || !(row.get(key) instanceof String value)) throw failure("INVALID_FUND_ROW:" + key);
        return value;
    }
    private static void key(String value, int length, boolean ascii) {
        if (value == null || value.isBlank() || !value.equals(value.trim()) || value.codePointCount(0, value.length()) > length
            || value.codePoints().anyMatch(c -> Character.isISOControl(c) || ascii && c > 127))
            throw new IllegalArgumentException("Invalid formal receipt identity");
    }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static IllegalStateException failure(String message) { return new IllegalStateException(message); }
    record Tuple(long id, long userId, BigDecimal usdt, BigDecimal nex, BigDecimal pending,
                         BigDecimal risk, long version, long sandbox, long isDeleted, LocalDateTime createdAt) {}
    private record Wallet(String incarnation, long revision, Tuple tuple) {}
    private record Leg(String incarnation, long revision) {}

    /** Read-side integrity uses the exact writer protocol; this method never touches the mapper. */
    List<Event> decodeReceipt(Map<String,Object> row, List<Long> parties, List<Map<String,Object>> storedLegs) {
        if (parties.isEmpty() || !parties.equals(List.copyOf(new TreeSet<>(parties))))
            throw failure("FUND_OPERATION_PARTY_MISMATCH");
        var input = new ReceiptInput(string(row,"operationKey"),string(row,"businessType"),string(row,"phase"),
            string(row,"sourceTable"),string(row,"sourceKey"),string(row,"sourceEnvironment"),
            string(row,"orderedEventsJson"),string(row,"sourceSnapshotJson"),string(row,"sourceProofJson"));
        Map<String,Object> expected = receipt(input, parties);
        if (!PROTOCOL.equals(string(row,"protocolVersion"))) throw failure("UNSUPPORTED_FUND_PROTOCOL");
        var legs = storedLegs.stream().map(leg -> new Leg(string(leg,"incarnation"),number(leg,"revision"))).toList();
        var sorted = legs.stream().sorted(java.util.Comparator.comparing(Leg::incarnation).thenComparingLong(Leg::revision)).toList();
        if (!legs.equals(sorted) || new HashSet<>(legs).size()!=legs.size()) throw failure("FUND_OPERATION_LEG_MISMATCH");
        ObjectNode digest = json.valueToTree(expected);
        digest.set("customerIds",json.valueToTree(parties)); digest.set("legs",json.valueToTree(legs));
        expected.put("receiptSha256",sha(canonical(digest).toString()));
        exactReceipt(row,expected);
        var result = new ArrayList<Event>();
        for (JsonNode node:read(input.orderedEventsJson())) {
            try { result.add(decodeEvent(node)); }
            catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException("Invalid formal event record",ex); }
        }
        return List.copyOf(result);
    }
    private record Physical(ConnectionHolder holder, Connection connection) {}
    private sealed interface InternalToken extends Before permits Token {}
    private static final class Token implements InternalToken {
        private final SupportFundJournalBindingService owner;
        private final Physical tx;
        private final Thread thread = Thread.currentThread();
        private final List<Long> scope;
        private final List<Wallet> wallets;
        private final boolean birth;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean used = new AtomicBoolean();
        private Token(SupportFundJournalBindingService owner, Physical tx, List<Long> scope, List<Wallet> wallets, boolean birth) {
            this.owner = owner; this.tx = tx; this.scope = scope; this.wallets = wallets; this.birth = birth;
        }
    }
}
