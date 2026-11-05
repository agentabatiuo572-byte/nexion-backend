package ffdd.opsconsole.growth.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

public record GrowthPowerUpUpdateRequest(
        @JsonDeserialize(using = StrictDay.class) Integer day,
        String note,
        @JsonDeserialize(using = StrictDay.class) Integer expectedDay,
        String expectedNote,
        String reason,
        String operator) {
    public static final class StrictDay extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            return parser.getIntValue();
        }
    }
}
