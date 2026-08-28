package ffdd.opsconsole.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.domain.DeviceSkuView;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class DateTimeFormatConfigTest {
    private final DateTimeFormatConfig config = new DateTimeFormatConfig();

    @Test
    void serializesAndDeserializesLocalDateTimeWithSpaceSeparator() throws Exception {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        config.nexionDateTimeJacksonCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        String json = mapper.writeValueAsString(Map.of(
                "createdAt", LocalDateTime.of(2026, 5, 1, 0, 0, 0)));

        assertThat(json).contains("\"createdAt\":\"2026-05-01 00:00:00\"");
        TimePayload payload = mapper.readValue("{\"createdAt\":\"2026-05-01 00:00:00\"}", TimePayload.class);
        assertThat(payload.createdAt()).isEqualTo(LocalDateTime.of(2026, 5, 1, 0, 0, 0));
    }

    @Test
    void skuRevisionOverridesTheGlobalSecondsFormatWithIsoMicroseconds() throws Exception {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        config.nexionDateTimeJacksonCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();
        var constructor = Arrays.stream(DeviceSkuView.class.getDeclaredConstructors())
                .filter(candidate -> {
                    Class<?>[] types = candidate.getParameterTypes();
                    return types.length >= 2
                            && types[types.length - 2] == LocalDateTime.class
                            && types[types.length - 1] == LocalDateTime.class;
                })
                .findFirst()
                .orElseThrow();
        Object[] arguments = new Object[constructor.getParameterCount()];
        arguments[arguments.length - 2] = LocalDateTime.of(2026, 8, 15, 1, 23, 34);
        arguments[arguments.length - 1] = LocalDateTime.of(2026, 8, 15, 1, 23, 35, 123_456_000);
        DeviceSkuView view = (DeviceSkuView) constructor.newInstance(arguments);

        JsonNode json = mapper.valueToTree(view);

        assertThat(json.path("createdAt").asText()).isEqualTo("2026-08-15 01:23:34");
        assertThat(json.path("updatedAt").asText()).isEqualTo("2026-08-15T01:23:35.123456");
        assertThat(LocalDateTime.parse(json.path("updatedAt").asText()))
                .isEqualTo(view.updatedAt());
    }

    @Test
    void convertsRequestParamLocalDateTimeWithSpaceSeparator() {
        LocalDateTime value = config.stringToLocalDateTimeConverter().convert("2026-05-01 00:00:00");

        assertThat(value).isEqualTo(LocalDateTime.of(2026, 5, 1, 0, 0, 0));
        assertThat(config.localDateTimeToStringConverter().convert(value)).isEqualTo("2026-05-01 00:00:00");
    }

    @Test
    void convertsHtmlDateTimeLocalRequestParamsIncludingMinuteAndFractionalSecondPrecision() {
        assertThat(config.stringToLocalDateTimeConverter().convert("2026-07-17T09:00"))
                .isEqualTo(LocalDateTime.of(2026, 7, 17, 9, 0));
        assertThat(config.stringToLocalDateTimeConverter().convert("2026-07-17T11:00:59.999"))
                .isEqualTo(LocalDateTime.of(2026, 7, 17, 11, 0, 59, 999_000_000));
    }

    @Test
    void applicationClockUsesTheSameUtcPlusEightZoneAsDatabaseSessions() {
        assertThat(config.systemClock().getZone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    private record TimePayload(LocalDateTime createdAt) {
    }
}
