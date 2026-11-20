package ffdd.opsconsole.growth.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

public record GrowthQuestEventRequest(
        String id,
        String name,
        String kind,
        String state,
        String reward,
        Boolean featured,
        Boolean trackable,
        String condition,
        String geo,
        @JsonDeserialize(using = StrictTarget.class) Integer targetValue,
        String href,
        java.time.LocalDateTime startsAt,
        java.time.LocalDateTime endsAt,
        String reason,
        String operator) {
    public static final class StrictTarget extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            return parser.getIntValue();
        }
    }
}
