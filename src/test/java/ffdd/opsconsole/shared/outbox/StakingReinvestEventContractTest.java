package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper.SchemaPropertyGateRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class StakingReinvestEventContractTest {
    record Contract(String event, int originalRevision, int revision, List<SchemaPropertyGateRow> original,
                    Map<String,Object> payload) {
        List<SchemaPropertyGateRow> h1() {
            boolean required = event.equals("wallet.reinvest");
            return List.of(new SchemaPropertyGateRow("h1_reinvest_multiplier", "number", required),
                    new SchemaPropertyGateRow("effective_nurture_multiplier", "number", required));
        }
        List<SchemaPropertyGateRow> complete() {
            var fields = new ArrayList<>(original); fields.addAll(h1()); return fields;
        }
    }

    static List<Contract> contracts() {
        var staking = new LinkedHashMap<String,Object>();
        staking.put("positionNo", "STK-FIXTURE"); staking.put("tierKey", "usdt30d");
        staking.put("productCode", "USDT_30D"); staking.put("amountUsdt", new BigDecimal("20.000000"));
        staking.put("apyPct", new BigDecimal("12")); staking.put("termDays", 30);
        staking.put("unlockAt", LocalDateTime.of(2026,11,15,5,18,39));
        staking.put("walletBalanceUsdt", new BigDecimal("4714.160665"));
        var reinvest = new LinkedHashMap<String,Object>();
        reinvest.put("positionNo", "REP-FIXTURE"); reinvest.put("product", "repurchase");
        reinvest.put("amountUsdt", new BigDecimal("100.000000")); reinvest.put("apyPct", new BigDecimal("35"));
        reinvest.put("termDays", 90); reinvest.put("unlockAt", LocalDateTime.of(2027,1,14,5,18,39));
        reinvest.put("ticketCount", 1); reinvest.put("nurtureMultiplier", new BigDecimal("1.5"));
        reinvest.put("h1ReinvestMultiplier", new BigDecimal("1.2"));
        reinvest.put("effectiveNurtureMultiplier", new BigDecimal("1.8"));
        reinvest.put("walletBalanceUsdt", new BigDecimal("4634.160665"));
        return List.of(new Contract("staking.opened",62,99,List.of(
                required("position_no","id"),required("tier_key","string"),required("product_code","id"),
                required("amount_usdt","number"),required("apy_pct","number"),required("term_days","number"),
                required("unlock_at","timestamp"),required("wallet_balance_usdt","number")),staking),
                new Contract("wallet.reinvest",72,98,List.of(
                required("position_no","id"),required("product","enum"),required("amount_usdt","number"),
                required("apy_pct","number"),required("term_days","number"),required("unlock_at","timestamp"),
                required("ticket_count","number"),required("nurture_multiplier","number"),
                required("wallet_balance_usdt","number")),reinvest));
    }

    static SchemaPropertyGateRow required(String name,String type) { return new SchemaPropertyGateRow(name,type,true); }
    private final EventOutboxMapper mapper = mock(EventOutboxMapper.class);
    private final EventOutboxService outbox;
    StakingReinvestEventContractTest() {
        var builder = new Jackson2ObjectMapperBuilder();
        new DateTimeFormatConfig().nexionDateTimeJacksonCustomizer().customize(builder);
        ObjectMapper json = builder.build();
        outbox = new EventOutboxService(mapper,json,new OutboxProperties(),mock(A4RuntimePolicyService.class));
    }
    private void schema(Contract c,List<SchemaPropertyGateRow> fields) {
        when(mapper.findActiveSchema(c.event())).thenReturn(new EventOutboxMapper.SchemaGateRow("market",c.revision(),true));
        when(mapper.findLifecycleState(c.event())).thenReturn("full");
        when(mapper.listActiveProperties(c.event())).thenReturn(fields);
    }
    private String publish(Contract c,Map<String,Object> payload) {
        return outbox.publishUserEvent("STAKING_POSITION","FIXTURE",c.event(),42L,"P1",1,"2026-W42",payload);
    }
    private void noWrite() {
        verify(mapper,never()).insertEvent(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),
                anyString(),anyInt(),anyString(),anyBoolean(),any(),anyBoolean(),anyBoolean(),anyString());
    }

    @ParameterizedTest @MethodSource("contracts")
    void observedRevisionSplitRejectsRealProducerShapeBeforeOutboxWrite(Contract c) {
        schema(c,c.h1());
        assertThatThrownBy(()->publish(c,c.payload())).isInstanceOf(BizException.class)
                .hasMessage("A4_SCHEMA_PROPERTY_NOT_REGISTERED")
                .satisfies(ex->assertThat(((BizException)ex).getCode()).isEqualTo(422));
        noWrite();
    }

    @ParameterizedTest @MethodSource("contracts")
    void completeCurrentContractAcceptsCamelCaseProducerAndDateTime(Contract c) {
        schema(c,c.complete());
        assertThat(publish(c,c.payload())).isNotBlank();
        verify(mapper).insertEvent(anyString(),eq("STAKING_POSITION"),eq("FIXTURE"),eq(c.event()),eq(c.event()),
                eq("market"),eq("P1"),eq(1),eq("2026-W42"),eq(true),eq(c.revision()),eq(true),eq(true),anyString());
    }

    @ParameterizedTest @MethodSource("contracts")
    void completeContractStillRejectsUnknownMissingAndWrongType(Contract c) {
        schema(c,c.complete());
        var payload = new LinkedHashMap<>(c.payload()); payload.put("unknownProperty",1);
        assertThatThrownBy(()->publish(c,payload)).hasMessage("A4_SCHEMA_PROPERTY_NOT_REGISTERED");
        payload.remove("unknownProperty"); payload.remove("amountUsdt");
        assertThatThrownBy(()->publish(c,payload)).hasMessage("A4_SCHEMA_REQUIRED_PROPERTY_MISSING");
        payload.put("amountUsdt","20");
        assertThatThrownBy(()->publish(c,payload)).hasMessage("A4_SCHEMA_PROPERTY_TYPE_MISMATCH");
        noWrite();
    }

    @ParameterizedTest @MethodSource("contracts")
    void lifecycleRestrictionsStillApplyAfterPropertyRepair(Contract c) {
        schema(c,c.complete());
        for(String state:List.of("new","pending_publish","disabled")) {
            when(mapper.findLifecycleState(c.event())).thenReturn(state);
            assertThatThrownBy(()->publish(c,c.payload())).hasMessage("A4_EVENT_LIFECYCLE_BLOCKED_"+state.toUpperCase());
        }
        noWrite();
    }

    @Test void stakingH1FieldsStayOptionalWhileReinvestRequiresBoth() {
        var staking=contracts().get(0); schema(staking,staking.complete());
        var withH1=new LinkedHashMap<>(staking.payload());
        withH1.put("h1ReinvestMultiplier",new BigDecimal("1.2"));
        withH1.put("effectiveNurtureMultiplier",new BigDecimal("1.8"));
        assertThat(publish(staking,withH1)).isNotBlank();
        var reinvest=contracts().get(1); schema(reinvest,reinvest.complete());
        for(String field:List.of("h1ReinvestMultiplier","effectiveNurtureMultiplier")) {
            var missing=new LinkedHashMap<>(reinvest.payload()); missing.remove(field);
            assertThatThrownBy(()->publish(reinvest,missing)).hasMessage("A4_SCHEMA_REQUIRED_PROPERTY_MISSING");
        }
    }
}
