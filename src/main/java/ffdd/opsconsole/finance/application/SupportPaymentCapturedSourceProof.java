package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import static ffdd.opsconsole.finance.application.SupportPaymentFactService.text;

/** Pure persisted financial comparisons only; this helper never creates write authorization. */
final class SupportPaymentCapturedSourceProof {
    private static final DateTimeFormatter ISO6=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");
    private static final DateTimeFormatter STRICT_ISO6=ISO6.withResolverStyle(ResolverStyle.STRICT);
    private SupportPaymentCapturedSourceProof() {}

    // Exact existing replay comparator. New history decoding is intentionally a separate entry point.
    static boolean matchesExpected(Fact original,String partition,Map<String,Object> stored,ObjectMapper json) {
        if(stored==null || !"NEW_SUCCESS".equals(text(stored,"captureMode"))
            || !"NEW_SUCCESS".equals(text(stored,"evidenceCaptureMode"))
            || !"support-payment-attribution-v1".equals(text(stored,"schemaVersion"))
            || !"support-payment-attribution-v1".equals(text(stored,"evidenceSchemaVersion"))
            || !Objects.equals(text(stored,"sourcePartition"),partition)) return false;
        if(text(stored,"sourceFactJson")==null || text(stored,"beforeSourceJson")==null) return false;
        JsonNode saved,before;
        try {
            var reader=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            saved=reader.readTree(text(stored,"sourceFactJson"));
            before=reader.readTree(text(stored,"beforeSourceJson"));
        } catch(JsonProcessingException ex) { throw new IllegalStateException("INVALID_PERSISTED_SOURCE_PROOF",ex); }
        if(saved==null || before==null || !saved.isObject() || !before.isObject()) return false;
        String zone=DateTimeFormatConfig.BUSINESS_ZONE.getId();
        return equalText(saved,"factId",original.factId()) && equalText(saved,"kind",original.kind().name())
            && equalText(saved,"source",original.source().name()) && equalLong(saved,"customerId",original.customerId())
            && equalLong(saved,"ledgerId",original.ledgerId()) && equalText(saved,"sourceBusinessId",original.sourceBusinessId())
            && equalText(saved,"orderNo",original.orderNo()) && equalText(saved,"orderType",original.orderType())
            && equalText(saved,"originalFactId",original.originalFactId()) && equalText(saved,"currency",original.currency())
            && saved.path("amount").isNumber() && saved.path("amount").decimalValue().compareTo(original.amount())==0
            && equalTime(saved,"succeededAt",original.succeededAt()) && equalTime(saved,"ledgerRecordedAt",original.ledgerRecordedAt())
            && equalTime(saved,"sourceConfirmationAt",original.sourceConfirmationAt()) && equalTime(saved,"providerPaidAt",original.providerPaidAt())
            && equalText(saved,"successTimeField",original.successTimeField())
            && equalText(saved,"historicalEnvironmentStatus",original.historicalEnvironmentStatus().name())
            && equalLong(saved,"fractionalSecondDigits",original.fractionalSecondDigits()) && equalText(saved,"businessZone",zone)
            && equalText(saved,"succeededAtInstant",ISO6.format(original.succeededAt().atZone(DateTimeFormatConfig.BUSINESS_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime())+"Z")
            && Objects.equals(saved.get("sourceIds"),json.valueToTree(original.sourceIds()))
            && before.path("oldSource").isBoolean() && !before.path("oldSource").booleanValue()
            && equalLong(before,"customerId",original.customerId()) && equalText(before,"source",original.source().name())
            && equalText(before,"stableBusinessKey",original.sourceBusinessId()) && equalText(before,"sourcePartition",partition)
            && equalText(before,"existingLedgerId",null) && equalText(before,"existingFactId",null) && equalText(before,"existingSuccessAt",null)
            && equalText(before,"businessZone",zone) && equalText(before,"successTimeField",original.successTimeField())
            && equalLong(before,"fractionalSecondDigits",original.fractionalSecondDigits());
    }
    private static boolean equalText(JsonNode node,String key,String expected) {
        JsonNode value=node.get(key);
        return value!=null && (expected==null?value.isNull():value.isTextual() && expected.equals(value.textValue()));
    }
    private static boolean equalLong(JsonNode node,String key,long expected) {
        JsonNode value=node.get(key);
        return value!=null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue()==expected;
    }
    private static boolean equalTime(JsonNode node,String key,LocalDateTime expected) {
        return equalText(node,key,expected==null?null:ISO6.format(expected));
    }
    private static final Set<String> SOURCE_FACT_KEYS=Set.of("factId","kind","source","sourceIds",
        "customerId","ledgerId","sourceBusinessId","orderNo","orderType","originalFactId","currency",
        "amount","succeededAt","succeededAtInstant","successTimeField","fractionalSecondDigits",
        "businessZone","providerPaidAt","ledgerRecordedAt","sourceConfirmationAt","sourceVersion",
        "historicalEnvironmentStatus");

    /** Strict typed decode for the later history reader, never an authorization or a Receipt. */
    static Fact decodeSourceFact(String sourceFactJson,ObjectMapper json) {
        JsonNode saved;
        try {
            saved=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(sourceFactJson);
        } catch(JsonProcessingException | IllegalArgumentException ex) {
            throw invalidProof(ex);
        }
        if(saved==null || !saved.isObject())throw invalidProof(null);
        var keys=new HashSet<String>();saved.fieldNames().forEachRemaining(keys::add);
        if(!keys.equals(SOURCE_FACT_KEYS))throw invalidProof(null);
        try {
            String zone=requiredText(saved,"businessZone");
            var sourceIds=new ArrayList<String>();
            JsonNode ids=saved.get("sourceIds");
            if(!ids.isArray() || ids.isEmpty())throw invalidProof(null);
            for(JsonNode id:ids) {
                if(!id.isTextual() || id.textValue().isBlank())throw invalidProof(null);
                sourceIds.add(id.textValue());
            }
            JsonNode amount=saved.get("amount");
            if(!amount.isNumber())throw invalidProof(null);
            long precision=requiredLong(saved,"fractionalSecondDigits");
            if(precision<0 || precision>6)throw invalidProof(null);
            Fact decoded=new Fact(requiredText(saved,"factId"),Kind.valueOf(requiredText(saved,"kind")),
                Source.valueOf(requiredText(saved,"source")),List.copyOf(sourceIds),
                requiredLong(saved,"customerId"),requiredLong(saved,"ledgerId"),requiredText(saved,"sourceBusinessId"),
                nullableText(saved,"orderNo"),nullableText(saved,"orderType"),nullableText(saved,"originalFactId"),
                requiredText(saved,"currency"),amount.decimalValue(),requiredTime(saved,"succeededAt"),
                requiredText(saved,"successTimeField"),(int)precision,nullableTime(saved,"providerPaidAt"),
                requiredTime(saved,"ledgerRecordedAt"),nullableTime(saved,"sourceConfirmationAt"),
                nullableText(saved,"sourceVersion"),Status.valueOf(requiredText(saved,"historicalEnvironmentStatus")));
            if(validateCanonical(decoded,zone)!=null || !zone.equals(DateTimeFormatConfig.BUSINESS_ZONE.getId())
                || decoded.kind()==Kind.DEPOSIT && (decoded.orderNo()!=null || decoded.orderType()!=null)
                || decoded.source()!=Source.CARD_TOPUP && decoded.source()!=Source.VIETQR && decoded.providerPaidAt()!=null
                || decoded.source()!=Source.WALLET_ORDER && decoded.source()!=Source.TRIAL_CONVERT && decoded.sourceConfirmationAt()!=null
                || !equalText(saved,"succeededAtInstant",ISO6.format(decoded.succeededAt().atZone(ZoneId.of(zone))
                    .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime())+"Z"))throw invalidProof(null);
            return decoded;
        } catch(IllegalArgumentException | DateTimeException ex) {
            throw invalidProof(ex);
        }
    }

    private static String requiredText(JsonNode saved,String key) {
        String value=nullableText(saved,key);
        if(!present(value))throw invalidProof(null);
        return value;
    }
    private static String nullableText(JsonNode saved,String key) {
        JsonNode value=saved.get(key);
        if(value==null || (!value.isNull() && !value.isTextual()))throw invalidProof(null);
        return value.isNull()?null:value.textValue();
    }
    private static long requiredLong(JsonNode saved,String key) {
        JsonNode value=saved.get(key);
        if(value==null || !value.isIntegralNumber() || !value.canConvertToLong())throw invalidProof(null);
        return value.longValue();
    }
    private static LocalDateTime requiredTime(JsonNode saved,String key) {
        LocalDateTime value=nullableTime(saved,key);
        if(value==null)throw invalidProof(null);
        return value;
    }
    private static LocalDateTime nullableTime(JsonNode saved,String key) {
        String value=nullableText(saved,key);
        if(value==null)return null;
        if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}"))throw invalidProof(null);
        return LocalDateTime.parse(value,STRICT_ISO6);
    }
    private static boolean present(String value) {return value!=null && !value.isBlank();}
    private static IllegalStateException invalidProof(Exception cause) {
        return cause==null?new IllegalStateException("INVALID_PERSISTED_SOURCE_PROOF")
            :new IllegalStateException("INVALID_PERSISTED_SOURCE_PROOF",cause);
    }
}
