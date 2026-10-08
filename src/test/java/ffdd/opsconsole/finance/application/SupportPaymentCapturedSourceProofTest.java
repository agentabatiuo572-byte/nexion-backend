package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure tests: no Spring, DataSource, ledger insertion or receipt minting. */
class SupportPaymentCapturedSourceProofTest {
    private static final DateTimeFormatter ISO6=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");
    private final ObjectMapper json=new ObjectMapper();
    private final Fact wallet=new Fact("PURCHASE:order-1",Kind.DEVICE_PURCHASE,Source.WALLET_ORDER,
        List.of("nx_order:31"),7,201,"order-1","order-1","SINGLE",null,"USDT",
        new BigDecimal("80.123456"),LocalDateTime.of(2026,10,8,12,0,1,800000000),
        "nx_order.paid_at",6,null,LocalDateTime.of(2026,10,8,12,0,0),
        LocalDateTime.of(2026,10,8,12,0,2),null,Status.UNKNOWN);

    @Test void exactCrossSecondProofStillMatchesAndStrictDecoderPreservesAllFinancialFields() throws Exception {
        var proof=proof();
        assertThat(matches(proof)).isTrue();
        Fact decoded=SupportPaymentCapturedSourceProof.decodeSourceFact(proof.get("sourceFactJson").toString(),json);
        assertThat(decoded).isEqualTo(wallet);
        assertThat(decoded.amount()).isEqualByComparingTo("80.123456");
        assertThat(decoded.ledgerRecordedAt()).isNotEqualTo(decoded.succeededAt());
        assertThat(decoded.sourceConfirmationAt()).isNotEqualTo(decoded.succeededAt());
    }

    @Test void extractionPreservesLegacyComparatorWithoutPretendingToDecodeMissingNewHistoryFields() throws Exception {
        var proof=proof();var saved=saved(proof);saved.remove("sourceVersion");storeSaved(proof,saved);
        assertThat(matches(proof)).isTrue(); // The original write replay comparator does not compare sourceVersion.
        assertInvalid(proof);
    }

    @Test void numericAmountScaleIsComparedExactlyWithoutFloatingPointRounding() throws Exception {
        var proof=proof();var saved=saved(proof);saved.put("amount",new BigDecimal("80.12345600"));storeSaved(proof,saved);
        assertThat(matches(proof)).isTrue();assertThat(decode(proof).amount()).isEqualByComparingTo(wallet.amount());
        saved.put("amount",new BigDecimal("80.123457"));storeSaved(proof,saved);assertThat(matches(proof)).isFalse();
        saved.put("amount","80.123456");storeSaved(proof,saved);assertThat(matches(proof)).isFalse();assertInvalid(proof);
    }

    @ParameterizedTest @ValueSource(strings={"captureMode","evidenceCaptureMode","schemaVersion","evidenceSchemaVersion"})
    void metadataMustMatchBeforeParsing(String field) throws Exception {
        var proof=proof();proof.put(field,"OLD_OR_OTHER");proof.put("sourceFactJson","broken");
        assertThat(matches(proof)).isFalse();
    }

    @Test void badJsonInEligiblePersistedProofPropagatesRatherThanBecomingFalse() throws Exception {
        var proof=proof();proof.put("sourceFactJson","{");
        assertThatThrownBy(()->matches(proof)).isInstanceOf(IllegalStateException.class)
            .hasMessage("INVALID_PERSISTED_SOURCE_PROOF");
        assertInvalid(proof);
    }

    @ParameterizedTest @ValueSource(strings={"customerId","ledgerId","fractionalSecondDigits"})
    void integralFieldsRejectTextFloatAndOutOfRangeNumbers(String field) throws Exception {
        var proof=proof();var saved=saved(proof);
        saved.put(field,"7");storeSaved(proof,saved);assertThat(matches(proof)).isFalse();assertInvalid(proof);
        saved.put(field,new BigDecimal("7.0"));storeSaved(proof,saved);assertThat(matches(proof)).isFalse();assertInvalid(proof);
        saved.put(field,new java.math.BigInteger("9223372036854775808"));storeSaved(proof,saved);
        assertThat(matches(proof)).isFalse();assertInvalid(proof);
    }

    @ParameterizedTest @ValueSource(strings={"orderType","providerPaidAt","originalFactId","sourceVersion","sourceConfirmationAt"})
    void nullableFieldsMustBePresentAndStrictlyTyped(String field) throws Exception {
        var proof=proof();var saved=saved(proof);saved.remove(field);storeSaved(proof,saved);assertInvalid(proof);
        saved.put(field,9);storeSaved(proof,saved);assertInvalid(proof);
    }

    @Test void beforeOldOrMissingNullAndWrongPartitionCannotClaimFreshSource() throws Exception {
        var proof=proof();var before=before(proof);
        before.put("oldSource",true);storeBefore(proof,before);assertThat(matches(proof)).isFalse();
        before.put("oldSource",0);storeBefore(proof,before);assertThat(matches(proof)).isFalse();
        before.put("oldSource",false);before.remove("existingLedgerId");storeBefore(proof,before);assertThat(matches(proof)).isFalse();
        before.putNull("existingLedgerId");before.put("sourcePartition","foreign");storeBefore(proof,before);
        assertThat(matches(proof)).isFalse();
    }

    @ParameterizedTest @ValueSource(strings={"factId","kind","source","sourceBusinessId","orderNo","orderType","currency",
        "successTimeField","historicalEnvironmentStatus","businessZone","succeededAtInstant"})
    void originalComparatorStillRejectsEachChangedFinancialIdentity(String field) throws Exception {
        var proof=proof();var saved=saved(proof);saved.put(field,"foreign");storeSaved(proof,saved);
        assertThat(matches(proof)).isFalse();
    }

    @Test void sourceIdsRemainExactAndDecoderRejectsNonStringEntries() throws Exception {
        var proof=proof();var saved=saved(proof);saved.putArray("sourceIds").add("other:31");storeSaved(proof,saved);
        assertThat(matches(proof)).isFalse();
        saved.putArray("sourceIds").add(31);storeSaved(proof,saved);assertInvalid(proof);
        saved.putArray("sourceIds");storeSaved(proof,saved);assertInvalid(proof);
    }

    @ParameterizedTest @ValueSource(strings={"succeededAt","ledgerRecordedAt","sourceConfirmationAt"})
    void localTimesRequireValidRawIso6RatherThanCoercionOrGuessedPrecision(String field) throws Exception {
        var proof=proof();var saved=saved(proof);
        for(String invalid:List.of("2026-10-08T12:00:01Z","2026-10-08T12:00:01.8","2026-02-30T12:00:01.800000")) {
            saved.put(field,invalid);storeSaved(proof,saved);assertThat(matches(proof)).isFalse();assertInvalid(proof);
        }
    }

    @Test void decoderRejectsUnknownMissingDuplicateAndTrailingKeysAndInvalidZoneInstant() throws Exception {
        var proof=proof();var saved=saved(proof);saved.put("futureField",1);storeSaved(proof,saved);assertInvalid(proof);
        saved.remove("futureField");saved.remove("currency");storeSaved(proof,saved);assertInvalid(proof);
        String original=proof().get("sourceFactJson").toString();
        proof.put("sourceFactJson",original.substring(0,original.length()-1)+",\"currency\":\"USDT\"}");assertInvalid(proof);
        proof.put("sourceFactJson",original+" {}");assertInvalid(proof);
        saved=saved(proof());saved.put("businessZone","not-a-zone");storeSaved(proof,saved);assertInvalid(proof);
        saved=saved(proof());saved.put("succeededAtInstant","2026-10-08T00:00:00.000000Z");storeSaved(proof,saved);assertInvalid(proof);
    }

    @ParameterizedTest @EnumSource(value=Source.class,names={"FREE_TRIAL","UNMATCHED_LEDGER"},mode=EnumSource.Mode.EXCLUDE)
    void sourceKindAndCanonicalIdsReuseTheProducerRule(Source source) throws Exception {
        var proof=proof();var saved=saved(proof);saved.put("source",source.name());
        Kind kind=switch(source) {
            case DEPOSIT_ORDER,CARD_TOPUP,VIETQR,HDPAY -> Kind.DEPOSIT;
            case ORDER_REFUND -> Kind.DEVICE_PURCHASE_REFUND;
            default -> Kind.DEVICE_PURCHASE;
        };
        saved.put("kind",kind.name());saved.put("factId",kind==Kind.DEPOSIT?"DEPOSIT:201"
            :kind==Kind.DEVICE_PURCHASE_REFUND?"ORDER_REFUND:201":"PURCHASE:order-1");
        if(kind==Kind.DEPOSIT) {saved.putNull("orderNo");saved.putNull("orderType");}
        if(source!=Source.WALLET_ORDER && source!=Source.TRIAL_CONVERT)saved.putNull("sourceConfirmationAt");
        if(kind==Kind.DEVICE_PURCHASE_REFUND)saved.put("originalFactId","PURCHASE:order-1");
        storeSaved(proof,saved);assertThat(SupportPaymentFacts.validateCanonical(decode(proof),zone())).isNull();
        saved.put("factId","other:201");storeSaved(proof,saved);assertInvalid(proof);
    }

    @ParameterizedTest @EnumSource(value=Source.class,names={"FREE_TRIAL","UNMATCHED_LEDGER"})
    void excludedSourcesCannotDecodeAsQualifiedNewPayments(Source source) throws Exception {
        var proof=proof();var saved=saved(proof);saved.put("source",source.name());storeSaved(proof,saved);assertInvalid(proof);
    }

    @ParameterizedTest @EnumSource(value=Source.class,names={"FREE_TRIAL","UNMATCHED_LEDGER"},mode=EnumSource.Mode.EXCLUDE)
    void nonApplicableFinancialFieldsCannotBeInventedInAnOtherwiseTypedProof(Source source) throws Exception {
        var proof=proof();var baseline=saved(proof);baseline.put("source",source.name());
        Kind kind=switch(source) {
            case DEPOSIT_ORDER,CARD_TOPUP,VIETQR,HDPAY -> Kind.DEPOSIT;
            case ORDER_REFUND -> Kind.DEVICE_PURCHASE_REFUND;
            default -> Kind.DEVICE_PURCHASE;
        };
        baseline.put("kind",kind.name());baseline.put("factId",kind==Kind.DEPOSIT?"DEPOSIT:201"
            :kind==Kind.DEVICE_PURCHASE_REFUND?"ORDER_REFUND:201":"PURCHASE:order-1");
        if(kind==Kind.DEPOSIT) {baseline.putNull("orderNo");baseline.putNull("orderType");}
        if(kind==Kind.DEVICE_PURCHASE_REFUND)baseline.put("originalFactId","PURCHASE:order-1");
        if(source!=Source.WALLET_ORDER && source!=Source.TRIAL_CONVERT)baseline.putNull("sourceConfirmationAt");
        storeSaved(proof,baseline);assertThat(decode(proof).source()).isEqualTo(source);
        if(kind==Kind.DEPOSIT)for(String field:List.of("orderNo","orderType")) {
            var changed=baseline.deepCopy();changed.put(field,"invented-order");storeSaved(proof,changed);assertInvalid(proof);
        }
        if(source!=Source.CARD_TOPUP && source!=Source.VIETQR) {
            var changed=baseline.deepCopy();changed.put("providerPaidAt",time(wallet.ledgerRecordedAt()));storeSaved(proof,changed);assertInvalid(proof);
        }
        if(source!=Source.WALLET_ORDER && source!=Source.TRIAL_CONVERT) {
            var changed=baseline.deepCopy();changed.put("sourceConfirmationAt",time(wallet.ledgerRecordedAt()));storeSaved(proof,changed);assertInvalid(proof);
        }
    }

    @Test void canonicalValidatorEnforcesPositiveDecimalAndReportedTimePrecision() throws Exception {
        for(String amount:List.of("0","-1","1000000000000","80.1234567")) {
            var proof=proof();var saved=saved(proof);saved.put("amount",new BigDecimal(amount));storeSaved(proof,saved);assertInvalid(proof);
        }
        var proof=proof();var saved=saved(proof);saved.put("fractionalSecondDigits",0);storeSaved(proof,saved);assertInvalid(proof);
        saved=saved(proof());saved.put("originalFactId","PURCHASE:foreign");storeSaved(proof,saved);assertInvalid(proof);
    }

    private boolean matches(Map<String,Object> proof) {return SupportPaymentCapturedSourceProof.matchesExpected(wallet,null,proof,json);}
    private Fact decode(Map<String,Object> proof) {return SupportPaymentCapturedSourceProof.decodeSourceFact(proof.get("sourceFactJson").toString(),json);}
    private void assertInvalid(Map<String,Object> proof) {
        assertThatThrownBy(()->decode(proof)).isInstanceOf(IllegalStateException.class).hasMessage("INVALID_PERSISTED_SOURCE_PROOF");
    }
    private ObjectNode saved(Map<String,Object> proof) throws Exception {return (ObjectNode)json.readTree(proof.get("sourceFactJson").toString());}
    private ObjectNode before(Map<String,Object> proof) throws Exception {return (ObjectNode)json.readTree(proof.get("beforeSourceJson").toString());}
    private void storeSaved(Map<String,Object> proof,ObjectNode saved) throws Exception {proof.put("sourceFactJson",json.writeValueAsString(saved));}
    private void storeBefore(Map<String,Object> proof,ObjectNode before) throws Exception {proof.put("beforeSourceJson",json.writeValueAsString(before));}
    private static String zone() {return DateTimeFormatConfig.BUSINESS_ZONE.getId();}
    private static String time(LocalDateTime value) {return value==null?null:ISO6.format(value);}
    private Map<String,Object> proof() throws Exception {
        var saved=new LinkedHashMap<String,Object>();
        saved.put("factId",wallet.factId());saved.put("kind",wallet.kind());saved.put("source",wallet.source());saved.put("sourceIds",wallet.sourceIds());
        saved.put("customerId",wallet.customerId());saved.put("ledgerId",wallet.ledgerId());saved.put("sourceBusinessId",wallet.sourceBusinessId());
        saved.put("orderNo",wallet.orderNo());saved.put("orderType",wallet.orderType());saved.put("originalFactId",wallet.originalFactId());
        saved.put("currency",wallet.currency());saved.put("amount",wallet.amount());saved.put("succeededAt",time(wallet.succeededAt()));
        saved.put("succeededAtInstant",ISO6.format(wallet.succeededAt().atZone(DateTimeFormatConfig.BUSINESS_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime())+"Z");
        saved.put("successTimeField",wallet.successTimeField());saved.put("fractionalSecondDigits",wallet.fractionalSecondDigits());saved.put("businessZone",zone());
        saved.put("providerPaidAt",time(wallet.providerPaidAt()));saved.put("ledgerRecordedAt",time(wallet.ledgerRecordedAt()));
        saved.put("sourceConfirmationAt",time(wallet.sourceConfirmationAt()));saved.put("sourceVersion",wallet.sourceVersion());saved.put("historicalEnvironmentStatus",wallet.historicalEnvironmentStatus());
        var before=new LinkedHashMap<String,Object>();
        before.put("customerId",wallet.customerId());before.put("source",wallet.source());before.put("stableBusinessKey",wallet.sourceBusinessId());
        before.put("sourcePartition",null);before.put("oldSource",false);before.put("existingLedgerId",null);before.put("existingFactId",null);
        before.put("existingSuccessAt",null);before.put("sourceIds",List.of());before.put("sourceVersion",null);before.put("businessZone",zone());
        before.put("successTimeField",wallet.successTimeField());before.put("fractionalSecondDigits",wallet.fractionalSecondDigits());
        return new HashMap<>(Map.of("captureMode","NEW_SUCCESS","evidenceCaptureMode","NEW_SUCCESS","schemaVersion","support-payment-attribution-v1",
            "evidenceSchemaVersion","support-payment-attribution-v1","sourceFactJson",json.writeValueAsString(saved),"beforeSourceJson",json.writeValueAsString(before)));
    }
}
