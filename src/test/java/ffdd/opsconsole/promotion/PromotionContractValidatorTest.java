package ffdd.opsconsole.promotion;

import com.fasterxml.jackson.databind.*;
import ffdd.opsconsole.promotion.application.PromotionContractValidator;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

class PromotionContractValidatorTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final PromotionContractValidator validator=new PromotionContractValidator(json);
    @Test void runtimeSchemaExactlyMatchesFrozenContract() throws Exception{
        JsonNode source=json.readTree(Path.of("docs/specs/growth-promotions/openapi.json").toFile()).path("components").path("schemas");
        JsonNode runtime=json.readTree(getClass().getResourceAsStream("/promotion/contract-schemas.json")).path("components").path("schemas");
        assertEquals(source,runtime);
    }
    @Test void moneyMustBeDecimalStringAndSixPlaces(){
        validator.validate("Amount","1.250000");
        for(Object value:List.of(1.25,"1.0000001","-1","1e2","NaN"))assertThrows(BizException.class,()->validator.validate("Amount",value));
    }
    @Test void quoteRequiresCapabilitiesAndRejectsUnknownFields(){
        var request=values("items",List.of(values("productNo","P1","quantity",2)),"clientCapabilities",List.of("PROMOTION_QUOTE_V1","ORDER_ITEM_QUANTITIES_V1","PROMOTION_REWARDS_V1"));
        validator.validate("QuoteInput",request);
        request.put("beneficiaryId",99);assertThrows(BizException.class,()->validator.validate("QuoteInput",request));
        request.remove("beneficiaryId");request.remove("clientCapabilities");assertThrows(BizException.class,()->validator.validate("QuoteInput",request));
    }
    @Test void incompleteDraftIsNotPublishable(){
        var draft=values("category","PROMOTION","template","SKU_GIFT");
        validator.validate("Draft",draft);
        assertThrows(BizException.class,()->validator.validate("PromotionContract",draft));
    }
}
