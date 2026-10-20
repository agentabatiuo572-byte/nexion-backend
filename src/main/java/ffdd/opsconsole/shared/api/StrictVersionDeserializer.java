package ffdd.opsconsole.shared.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Version preconditions are JSON integers, never silently truncated decimal numbers. */
public class StrictVersionDeserializer extends JsonDeserializer<Long> {
    @Override public Long deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT)
            return (Long)context.handleUnexpectedToken(Long.class,parser);
        var value=parser.getBigIntegerValue();
        if(value.signum()<0 || value.compareTo(java.math.BigInteger.valueOf(9_007_199_254_740_991L))>0)
            return (Long)context.handleWeirdNumberValue(Long.class,value,"version outside supported range");
        return value.longValueExact();
    }
}
