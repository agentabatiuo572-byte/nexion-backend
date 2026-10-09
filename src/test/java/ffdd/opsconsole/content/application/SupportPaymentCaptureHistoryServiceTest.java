package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.facade.SupportPaymentCaptureHistoryFacade;
import ffdd.opsconsole.content.mapper.SupportPaymentCaptureHistoryMapper;
import ffdd.opsconsole.content.mapper.SupportPaymentHistoryBirthMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SupportPaymentCaptureHistoryServiceTest {
    private static final String VERSION = "support-payment-attribution-v1";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 7, 12, 0, 0, 800_000_000);
    private final SupportPaymentCaptureHistoryMapper mapper = mock(SupportPaymentCaptureHistoryMapper.class);
    private final SupportPaymentHistoryBirthMapper births = mock(SupportPaymentHistoryBirthMapper.class);
    private final SupportPaymentCaptureHistoryService service = new SupportPaymentCaptureHistoryService(mapper,births);

    @Test void birthReadUsesExactSortedScopeAndTransportsUnchangedRegistrationEvidence() {
        var row=new SupportPaymentHistoryBirthMapper.Birth(7L,VERSION,"AUTH_NEW_ACCOUNT_REGISTRATION",AT,0,"PRODUCTION");
        when(births.readBirths(any())).thenReturn(List.of(row));
        var result=service.readBirths(List.of(8L,7L,8L));
        verify(births).readBirths(List.of(7L,8L));
        assertThat(result).containsExactly(new SupportPaymentCaptureHistoryFacade.BirthEvidence(7L,VERSION,"AUTH_NEW_ACCOUNT_REGISTRATION",AT,0,"PRODUCTION"));
        assertThatThrownBy(()->result.clear()).isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(mapper);
    }
    @Test void birthMissingAndLegacyFacadeConstructionCannotAttestHistory() {
        when(births.readBirths(any())).thenReturn(List.of());
        assertThat(service.readBirths(List.of(7L))).isEmpty();
        assertThat(new SupportPaymentCaptureHistoryService(mapper).readBirths(List.of(7L))).isEmpty();
        SupportPaymentCaptureHistoryFacade oldFacade=ids->List.of();
        assertThat(oldFacade.readBirths(List.of(7L))).isEmpty();
    }
    @Test void birthScopeRejectsInvalidInputsForeignRowsAndDuplicateIdentity() {
        for(var scope:Arrays.asList(null,List.<Long>of(),List.of(0L),List.of(-1L),Arrays.asList(7L,null)))
            assertThatThrownBy(()->service.readBirths(scope)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(births);
        var row=new SupportPaymentHistoryBirthMapper.Birth(7L,VERSION,"AUTH_NEW_ACCOUNT_REGISTRATION",AT,0,"PRODUCTION");
        when(births.readBirths(any())).thenReturn(List.of(new SupportPaymentHistoryBirthMapper.Birth(8L,VERSION,row.birthOrigin(),AT,0,"PRODUCTION")));
        assertThatThrownBy(()->service.readBirths(List.of(7L))).isInstanceOf(IllegalStateException.class);
        when(births.readBirths(any())).thenReturn(List.of(row,row));
        assertThatThrownBy(()->service.readBirths(List.of(7L))).isInstanceOf(IllegalStateException.class);
    }
    @Test void birthQueryFailureDoesNotBecomeAnEmptyBirth() {
        var failure=new DataAccessResourceFailureException("private source");
        when(births.readBirths(any())).thenThrow(failure);
        assertThatThrownBy(()->service.readBirths(List.of(7L))).isSameAs(failure);
    }
    @Test void birthBatchIsParameterizedOrdinarySelectWithoutOwnerOrStateFilters() {
        var configuration=new Configuration();configuration.addMapper(SupportPaymentHistoryBirthMapper.class);
        var statement=configuration.getMappedStatement(SupportPaymentHistoryBirthMapper.class.getName()+".readBirths");
        assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
        var bound=statement.getBoundSql(Map.of("customerIds",List.of(7L,8L)));
        assertThat(bound.getParameterMappings()).hasSize(2);
        assertThat(bound.getSql()).contains("nx_support_payment_history_birth","customer_id IN","capture_protocol captureProtocol","birth_db_utc birthDbUtc");
        assertThat(bound.getSql()).doesNotContain("FOR SHARE","FOR UPDATE","${","agent_admin_id","group_id"," JOIN ","environment_status=","sandbox_at_birth=");
    }

    @Test void explicitPositiveScopeIsRequiredBeforeQuerying() {
        for (var scope : Arrays.asList(null, List.<Long>of(), List.of(0L), List.of(-1L), Arrays.asList(7L, null)))
            assertThatThrownBy(() -> service.readNewFinancialProofs(scope))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_CAPTURE_HISTORY_SCOPE");
        verifyNoInteractions(mapper);
    }

    @Test void sortedUniqueScopeMapsOnlyFinancialFieldsWithoutChangingRawMoneyOrTimes() {
        var row = row(7L, 101L, 6, "NEW_SUCCESS", VERSION, "NEW_SUCCESS", VERSION);
        when(mapper.readNewFinancialProofs(any())).thenReturn(List.of(row));
        var result = service.readNewFinancialProofs(List.of(8L, 7L, 8L));
        verify(mapper).readNewFinancialProofs(List.of(7L, 8L));
        assertThat(result).hasSize(1);
        var proof = result.get(0);
        assertThat(proof.identity()).isEqualTo(new SupportPaymentCaptureHistoryFacade.Identity(
            "PURCHASE:order-1", 7L, "DEVICE_PURCHASE", "WALLET_ORDER", 101L, "order-1",
            "order-1", "SINGLE", null, "USDT", new BigDecimal("10.123456"), AT,
            "Asia/Shanghai", "nx_order.paid_at", 6));
        assertThat(proof.captureDbUtc()).isEqualTo(AT.minusHours(8).plusSeconds(1));
        assertThat(proof.sourcePartition()).isNull();
        assertThat(proof.sourceFactJson()).isEqualTo(row.sourceFactJson());
        assertThat(proof.beforeSourceJson()).isEqualTo(row.beforeSourceJson());
        assertThat(proof.captureMode()).isEqualTo(row.captureMode());
        assertThat(proof.captureSchemaVersion()).isEqualTo(row.captureSchemaVersion());
        assertThat(proof.evidenceCaptureMode()).isEqualTo(row.evidenceCaptureMode());
        assertThat(proof.evidenceSchemaVersion()).isEqualTo(row.evidenceSchemaVersion());
        assertThatThrownBy(() -> result.add(proof)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void noProofRemainsEmptyRatherThanCreatingCoverageOrFacts() {
        when(mapper.readNewFinancialProofs(any())).thenReturn(List.of());
        assertThat(service.readNewFinancialProofs(List.of(7L))).isEmpty();
    }

    @Test void returnedCustomerOutsideRequestedScopeFailsClosed() {
        when(mapper.readNewFinancialProofs(any())).thenReturn(List.of(row(8L, 101L, 6, "NEW_SUCCESS", VERSION, "NEW_SUCCESS", VERSION)));
        assertThatThrownBy(() -> service.readNewFinancialProofs(List.of(7L)))
            .isInstanceOf(IllegalStateException.class).hasMessage("INVALID_CAPTURE_HISTORY_SCOPE");
    }

    @Test void malformedLocalProofCannotHideForeignCustomerInEitherReturnedOrder() {
        var invalid = row(7L, 101L, 6, "NEW_SUCCESS", "future-schema", "NEW_SUCCESS", VERSION);
        var foreign = row(8L, 102L, 6, "NEW_SUCCESS", VERSION, "NEW_SUCCESS", VERSION);
        for (var rows : List.of(List.of(invalid, foreign), List.of(foreign, invalid))) {
            when(mapper.readNewFinancialProofs(any())).thenReturn(rows);
            assertThatThrownBy(() -> service.readNewFinancialProofs(List.of(7L)))
                .isInstanceOf(IllegalStateException.class).hasMessage("INVALID_CAPTURE_HISTORY_SCOPE");
        }
    }

    @Test void nullOrInvalidMappedValuesFailClosed() {
        for (var row : List.of(row(null, 101L, 6, "NEW_SUCCESS", VERSION, "NEW_SUCCESS", VERSION),
                row(7L, null, 6, "NEW_SUCCESS", VERSION, "NEW_SUCCESS", VERSION),
                row(7L, 0L, 6, "NEW_SUCCESS", VERSION, "NEW_SUCCESS", VERSION),
                row(7L, 101L, null, "NEW_SUCCESS", VERSION, "NEW_SUCCESS", VERSION),
                row(7L, 101L, 7, "NEW_SUCCESS", VERSION, "NEW_SUCCESS", VERSION))) {
            when(mapper.readNewFinancialProofs(any())).thenReturn(List.of(row));
            assertThatThrownBy(() -> service.readNewFinancialProofs(List.of(7L)))
                .isInstanceOf(IllegalStateException.class).hasMessage("INVALID_CAPTURE_HISTORY_ROW");
        }
        when(mapper.readNewFinancialProofs(any())).thenReturn(Arrays.asList((SupportPaymentCaptureHistoryMapper.Row) null));
        assertThatThrownBy(() -> service.readNewFinancialProofs(List.of(7L))).hasMessage("INVALID_CAPTURE_HISTORY_ROW");
        when(mapper.readNewFinancialProofs(any())).thenReturn(null);
        assertThatThrownBy(() -> service.readNewFinancialProofs(List.of(7L))).hasMessage("INVALID_CAPTURE_HISTORY_ROW");
    }

    @Test void oldOrWrongSchemaIsExplicitlyRejectedRatherThanSilentlyRemoved() {
        for (var row : List.of(row(7L, 101L, 6, "OLD_SOURCE", VERSION, "NEW_SUCCESS", VERSION),
                row(7L, 101L, 6, "NEW_SUCCESS", "future-schema", "NEW_SUCCESS", VERSION),
                row(7L, 101L, 6, "NEW_SUCCESS", VERSION, "OLD_SOURCE", VERSION),
                row(7L, 101L, 6, "NEW_SUCCESS", VERSION, "NEW_SUCCESS", "future-schema"),
                row(7L, 101L, 6, "NEW_SUCCESS", null, "NEW_SUCCESS", VERSION),
                row(7L, 101L, 6, "NEW_SUCCESS", VERSION, null, VERSION))) {
            when(mapper.readNewFinancialProofs(any())).thenReturn(List.of(row));
            assertThatThrownBy(() -> service.readNewFinancialProofs(List.of(7L)))
                .isInstanceOf(IllegalStateException.class).hasMessage("INVALID_CAPTURE_HISTORY_SCHEMA");
        }
    }

    @Test void queryFailurePropagatesWithoutInventingAnEmptyHistory() {
        var failure = new DataAccessResourceFailureException("source unavailable");
        when(mapper.readNewFinancialProofs(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.readNewFinancialProofs(List.of(7L))).isSameAs(failure);
    }

    @Test void missingFinancialJsonFailsWithoutReturningPartialProofs() {
        for (var missing : List.of(row(7L, 101L, 6, "NEW_SUCCESS", VERSION, "NEW_SUCCESS", VERSION, null, "{}"),
                row(7L, 101L, 6, "NEW_SUCCESS", VERSION, "NEW_SUCCESS", VERSION, "{}", " "))) {
            when(mapper.readNewFinancialProofs(any())).thenReturn(List.of(
                row(7L, 101L, 6, "NEW_SUCCESS", VERSION, "NEW_SUCCESS", VERSION), missing));
            assertThatThrownBy(() -> service.readNewFinancialProofs(List.of(7L)))
                .isInstanceOf(IllegalStateException.class).hasMessage("INVALID_CAPTURE_HISTORY_ROW");
        }
    }

    @Test void mapperHasOneParameterizedOrdinarySelectAndNoOwnershipOrSchemaFilters() {
        var configuration = new Configuration();
        configuration.addMapper(SupportPaymentCaptureHistoryMapper.class);
        var statement = configuration.getMappedStatement(SupportPaymentCaptureHistoryMapper.class.getName() + ".readNewFinancialProofs");
        assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
        var bound = statement.getBoundSql(Map.of("customerIds", List.of(7L, 8L)));
        assertThat(bound.getParameterMappings()).hasSize(2);
        String sql = bound.getSql();
        assertThat(sql).contains("customer_id IN", "capture_mode='NEW_SUCCESS'", "capture_schema_version captureSchemaVersion",
            "JSON_EXTRACT(attribution_evidence_json,'$.beforeSource') beforeSourceJson",
            "JSON_UNQUOTE(JSON_EXTRACT(attribution_evidence_json,'$.captureMode')) evidenceCaptureMode",
            "JSON_UNQUOTE(JSON_EXTRACT(attribution_evidence_json,'$.schemaVersion')) evidenceSchemaVersion");
        assertThat(sql).doesNotContain("FOR SHARE", "FOR UPDATE", "${", "agent_admin_id", "group_id", "owner_admin_id",
            "agent_status", "group_status", "owner_status", "evidenceJson", "nx_user", " JOIN ", "$.witness", "$.requiredAudit");
        String where = sql.substring(sql.indexOf(" WHERE "), sql.indexOf(" ORDER BY "));
        assertThat(where).doesNotContain("schema", "capture_db_utc", "succeeded_at", "group");
    }

    @Test void facadeExposesOnlyFixedFinancialIdentityAndEnvelope() {
        assertThat(Arrays.stream(SupportPaymentCaptureHistoryFacade.Identity.class.getRecordComponents()).map(c -> c.getName()))
            .containsExactly("factId", "customerId", "kind", "source", "ledgerId", "sourceBusinessId", "orderNo", "orderType",
                "originalFactId", "currency", "amount", "succeededAt", "sourceBusinessZone", "successTimeField", "fractionalSecondDigits");
        assertThat(Arrays.stream(SupportPaymentCaptureHistoryFacade.Envelope.class.getRecordComponents()).map(c -> c.getName()))
            .containsExactly("identity", "sourcePartition", "captureDbUtc", "captureMode", "captureSchemaVersion",
                "sourceFactJson", "beforeSourceJson", "evidenceCaptureMode", "evidenceSchemaVersion");
    }

    private SupportPaymentCaptureHistoryMapper.Row row(Long customer, Long ledger, Integer precision,
            String mode, String schema, String evidenceMode, String evidenceSchema) {
        return row(customer, ledger, precision, mode, schema, evidenceMode, evidenceSchema,
            "{\"factId\":\"PURCHASE:order-1\",\"amount\":10.123456}", "{\"oldSource\":false}");
    }

    private SupportPaymentCaptureHistoryMapper.Row row(Long customer, Long ledger, Integer precision,
            String mode, String schema, String evidenceMode, String evidenceSchema, String sourceJson, String beforeJson) {
        return new SupportPaymentCaptureHistoryMapper.Row("PURCHASE:order-1", customer, "DEVICE_PURCHASE", "WALLET_ORDER", ledger,
            "order-1", "order-1", "SINGLE", null, "USDT", new BigDecimal("10.123456"), AT, "Asia/Shanghai",
            "nx_order.paid_at", precision, null, AT.minusHours(8).plusSeconds(1), mode, schema,
            sourceJson, beforeJson, evidenceMode, evidenceSchema);
    }
}
