package ffdd.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
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
    void convertsRequestParamLocalDateTimeWithSpaceSeparator() {
        LocalDateTime value = config.stringToLocalDateTimeConverter().convert("2026-05-01 00:00:00");

        assertThat(value).isEqualTo(LocalDateTime.of(2026, 5, 1, 0, 0, 0));
        assertThat(config.localDateTimeToStringConverter().convert(value)).isEqualTo("2026-05-01 00:00:00");
    }

    private record TimePayload(LocalDateTime createdAt) {
    }
}
