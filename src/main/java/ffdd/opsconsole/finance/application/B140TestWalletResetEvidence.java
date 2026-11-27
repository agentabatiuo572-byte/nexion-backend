package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Account;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Action;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Event;
import ffdd.opsconsole.finance.domain.SupportFundReplay.Cash;
import ffdd.opsconsole.finance.domain.SupportFundReplay.Point;
import ffdd.opsconsole.finance.domain.SupportFundReplay.Stamp;
import ffdd.opsconsole.finance.mapper.B140TestWalletResetMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class B140TestWalletResetEvidence {
    static final String BUSINESS_TYPE = "SUPPORT_B140_TEST_WALLET_RESET";
    static final String SOURCE_TABLE = "nx_b140_test_wallet_reset";
    static final String PHASE = "RESET";
    static final BigDecimal AMOUNT = new BigDecimal("60.000000");
    private static final ObjectMapper JSON = new ObjectMapper();

    private B140TestWalletResetEvidence() { }

    static String key(String entryNo) {
        return B140TestWalletResetMapper.OPERATION_PREFIX + entryNo;
    }

    static Event event(String entryNo, long customerId) {
        return new Event(key(entryNo), Action.RECOVER, new Account(customerId, "UVT", "available"),
                null, SupportFundEarningsAdapter.eventId(entryNo), AMOUNT, null);
    }

    static void verifyOriginal(SupportFundJournalBindingService codec, Map<String, Object> credit,
            List<Event> events, Map<String, Object> entry, String entryNo, long customerId, long walletId) {
        require(credit != null && entry != null && ("EARNINGS:CREDIT:" + entryNo).equals(credit.get("operationKey"))
                && "SUPPORT_EARNINGS".equals(credit.get("businessType")) && "CREDIT".equals(credit.get("phase"))
                && "nx_earnings_release_entry".equals(credit.get("sourceTable")) && entryNo.equals(credit.get("sourceKey"))
                && "PRODUCTION".equals(credit.get("sourceEnvironment")), "original credit receipt");
        var current = new LinkedHashMap<>(entry);
        for (String nullable : List.of("clusterId", "releasedAt", "releaseSource")) current.putIfAbsent(nullable, null);
        var original = codec.read((String) credit.get("sourceSnapshotJson"));
        require(codec.canonical(original).equals(tree(codec, Map.of("entry", current))), "original entry changed");
        String sourceRef = (String) entry.get("sourceRef");
        require(entryNo.equals(entry.get("entryNo")) && number(entry, "userId") == customerId
                && number(entry, "sourceWalletId") == walletId && number(entry, "isDeleted") == 0
                && "QUEST_REWARD".equals(entry.get("sourceType")) && "UVT".equals(entry.get("asset"))
                && "PRODUCTION".equals(entry.get("sourceEnvironment")) && "ACTIVE".equals(entry.get("status"))
                && "withdrawable".equals(entry.get("bucket")) && entry.get("releasedAt") == null
                && entry.get("releaseSource") == null && amount(entry, "amount").compareTo(AMOUNT) == 0
                && amount(entry, "recoveredAmount").signum() == 0 && amount(entry, "debitBaseline").signum() == 0
                && sourceRef.matches("QUEST:DAY_ONE:" + customerId + ":DAY_ONE:[0-9]{8}T[0-9]{6}")
                && ("GROWTH:" + sourceRef + ":NEX").equals(entry.get("idempotencyKey")), "original test reward shape");
        var expected = new Event(SupportFundEarningsAdapter.eventId(entryNo), Action.EARNINGS, null,
                new Account(customerId, "UVT", "available"), null, AMOUNT, null);
        require(events.equals(List.of(expected)), "original credit event");
    }

    static Stamp verify(SupportFundJournalBindingService codec, Map<String, Object> operation, JsonNode source,
            JsonNode proof, List<Event> events, List<Point> points, Map<String, Object> actual,
            Map<String, Object> credit, List<Event> creditEvents, Map<String, Object> entry,
            List<Long> parties, List<Long> creditParties, List<Map<String, Object>> creditLegs) {
        require(actual != null, "reset source missing");
        String entryNo = (String) actual.get("entryNo");
        long customerId = number(actual, "customerId"), walletId = number(actual, "walletId");
        verifyOriginal(codec, credit, creditEvents, entry, entryNo, customerId, walletId);
        require(codec.canonical(source).equals(tree(codec, actual)) && walletId > 0
                && ((String) actual.get("serverUuid")).matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                && amount(actual, "amount").compareTo(AMOUNT) == 0
                && BUSINESS_TYPE.equals(operation.get("businessType")) && PHASE.equals(operation.get("phase"))
                && SOURCE_TABLE.equals(operation.get("sourceTable")) && "PRODUCTION".equals(operation.get("sourceEnvironment"))
                && entryNo.equals(operation.get("sourceKey")) && key(entryNo).equals(operation.get("operationKey"))
                && credit.get("receiptSha256").equals(actual.get("originalReceiptSha256"))
                && number(actual, "originalOperationId") == number(credit, "id")
                && number(credit, "id") < number(operation, "id")
                && parties.equals(List.of(customerId)) && creditParties.equals(parties)
                && events.equals(List.of(event(entryNo, customerId))) && points.size() == 1 && creditLegs.size() == 1,
                "reset source identity");
        var point = points.get(0);
        var trace = proof.path("cashTrace");
        require(proof.isObject() && proof.size() == 3 && trace.isArray() && trace.size() == 1
                && (SOURCE_TABLE + ":" + entryNo).equals(trace.get(0).path("sourceId").asText())
                && point.customerId() == customerId && point.revision() == 2
                && point.incarnation().equals(creditLegs.get(0).get("incarnation"))
                && number(creditLegs.get(0), "revision") == 1
                && point.before().equals(new Cash(BigDecimal.ZERO, AMOUNT, BigDecimal.ZERO, BigDecimal.ZERO))
                && point.after().equals(new Cash(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
                && point.eventIds().equals(List.of(key(entryNo)))
                && proof.path("cashBefore").path("id").longValue() == walletId
                && proof.path("cashBefore").path("version").longValue() == 1
                && proof.path("cashAfter").path("version").longValue() == 2, "reset cash movement");
        var at = time(actual.get("recordedDbUtc"));
        require(!at.isBefore(time(credit.get("recordedDbUtc"))) && !at.isAfter(time(operation.get("recordedDbUtc"))),
                "reset source time");
        return new Stamp(SOURCE_TABLE + ":" + entryNo, at, "UTC", 6);
    }

    static JsonNode tree(SupportFundJournalBindingService codec, Object value) {
        return codec.canonical(JSON.valueToTree(SupportFundEarningsAdapter.normalize(value)));
    }

    static long number(Map<String, Object> row, String field) {
        return amount(row, field).longValueExact();
    }

    private static BigDecimal amount(Map<String, Object> row, String field) {
        Object value = row.get(field);
        require(value instanceof Number, field);
        return new BigDecimal(value.toString());
    }

    private static LocalDateTime time(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        require(value instanceof LocalDateTime, "recordedDbUtc");
        return (LocalDateTime) value;
    }

    static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException("B140 wallet reset unproven: " + reason);
    }
}
