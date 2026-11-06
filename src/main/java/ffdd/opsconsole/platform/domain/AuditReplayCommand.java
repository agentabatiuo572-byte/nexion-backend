package ffdd.opsconsole.platform.domain;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.Map;

public record AuditReplayCommand(
        String domain,
        String op,
        @JsonDeserialize(contentUsing = ExactBundlePercentage.class) Map<String, Object> params) {
    public static final class ExactBundlePercentage extends JsonDeserializer<Object> {
        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT
                    && switch (parser.currentName()) {
                        case "twoItemsPct", "threeItemsPct", "fourPlusItemsPct" -> true;
                        default -> false;
                    }) {
                return parser.getDecimalValue();
            }
            return context.readValue(parser, Object.class);
        }
    }
}
