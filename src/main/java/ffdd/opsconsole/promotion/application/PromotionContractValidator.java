package ffdd.opsconsole.promotion.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.application.PlatformExperienceConfigService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.i18n.AuthoringWhitespace;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import static ffdd.opsconsole.promotion.domain.PromotionValues.map;

@Component
public class PromotionContractValidator {
    private final ObjectMapper json;
    private final JsonNode schemas;
    @Autowired @Lazy
    private PlatformExperienceConfigService experience;
    public PromotionContractValidator(ObjectMapper json) {
        this.json=json;
        try(var stream=getClass().getResourceAsStream("/promotion/contract-schemas.json")) {
            if(stream==null)throw new IllegalStateException("PROMOTION_SCHEMA_RESOURCE_MISSING");
            schemas=json.readTree(stream).path("components").path("schemas");
        } catch(java.io.IOException e){throw new IllegalStateException("PROMOTION_SCHEMA_UNREADABLE",e);}
    }
    public void validate(String schema,Object value) {
        if(!schemas.has(schema))throw new IllegalArgumentException("Unknown promotion schema");
        check(json.valueToTree(value),schemas.get(schema),"$");
    }
    /** Read compatibility only; write commands continue to call the strict current schema above. */
    public void validateRecorded(String schema,Object original) {
        validate(schema,ffdd.opsconsole.promotion.domain.PromotionLegacyAssetView.validationView(original));
    }

    /** Complete a language group before freezing an authored contract. */
    Map<String,Object> completeAuthoredCopy(Map<String,Object> content, String... fields) {
        var result = new LinkedHashMap<>(content);
        Map<String,Map<String,Object>> texts = new LinkedHashMap<>();
        for (String field : fields) {
            if (content.get(field) == null) continue;
            var localized = map(content.get(field));
            for (String locale : List.of("en","zh","vi")) {
                if (localized.get(locale) instanceof String text && AuthoringWhitespace.isBlank(text)) localized.remove(locale);
            }
            texts.put(field, localized);
            result.put(field, localized);
        }
        if (texts.isEmpty()) return result;
        var required = requiredAuthoredLanguages();
        for (String locale : List.of("en","zh","vi")) {
            boolean useTranslation = required.contains(locale) || texts.values().stream().anyMatch(localized -> localized.containsKey(locale));
            for (var field : texts.entrySet()) {
                var localized = field.getValue();
                if (useTranslation) {
                    if (!localized.containsKey(locale)) fail("$." + field.getKey() + "." + locale);
                } else {
                    localized.put(locale, localized.get("en"));
                }
            }
        }
        return result;
    }

    /** Check a schema-validated frozen contract without changing the recorded copy. */
    void validateRecordedAuthoredCopy(Map<String,Object> content, String... fields) {
        var required = requiredAuthoredLanguages();
        for (String field : fields) {
            var localized = map(content.get(field));
            for (String locale : required) {
                if (AuthoringWhitespace.isBlank((String) localized.get(locale))) fail("$." + field + "." + locale);
            }
        }
    }

    private List<String> requiredAuthoredLanguages() {
        return experience == null ? List.of("en") : experience.operatingLanguages();
    }

    private void fail(String field) {throw new BizException(422,"PROMOTION_FIELD_INVALID:"+field);}
    private boolean matches(JsonNode value,JsonNode schema,String path) {
        try{check(value,schema,path);return true;}catch(BizException rejected){return false;}
    }
    private void check(JsonNode value,JsonNode schema,String path) {
        if(schema.has("$ref")) {String ref=schema.get("$ref").asText();String prefix="#/components/schemas/";
            if(!ref.startsWith(prefix)||!schemas.has(ref.substring(prefix.length())))throw new IllegalStateException("PROMOTION_SCHEMA_REF_INVALID");
            check(value,schemas.get(ref.substring(prefix.length())),path);return;}
        for(String keyword:List.of("oneOf","anyOf","allOf"))if(schema.has(keyword)) {
            int count=0;for(JsonNode candidate:schema.get(keyword))if(matches(value,candidate,path))count++;
            if(("oneOf".equals(keyword)&&count!=1)||("anyOf".equals(keyword)&&count==0)||("allOf".equals(keyword)&&count!=schema.get(keyword).size()))fail(path);
        }
        if(schema.has("if")){JsonNode branch=schema.get(matches(value,schema.get("if"),path)?"then":"else");if(branch!=null)check(value,branch,path);}
        if(schema.has("not")&&matches(value,schema.get("not"),path))fail(path);
        if(schema.has("const")&&!schema.get("const").equals(value))fail(path);
        if(schema.has("enum")){boolean found=false;for(JsonNode item:schema.get("enum"))if(item.equals(value))found=true;if(!found)fail(path);}
        if(schema.has("type")){JsonNode type=schema.get("type");boolean accepted=type.isArray()?false:isType(value,type.asText());
            if(type.isArray())for(JsonNode item:type)accepted|=isType(value,item.asText());if(!accepted)fail(path);}
        if(value.isObject()){
            for(JsonNode required:schema.path("required"))if(!value.has(required.asText()))fail(path+"."+required.asText());
            if(schema.has("minProperties")&&value.size()<schema.get("minProperties").asInt())fail(path);
            JsonNode properties=schema.path("properties");
            for(var fields=value.fields();fields.hasNext();){var field=fields.next();
                if(properties.has(field.getKey()))check(field.getValue(),properties.get(field.getKey()),path+"."+field.getKey());
                else if(schema.has("additionalProperties")&&!schema.get("additionalProperties").asBoolean())fail(path+"."+field.getKey());}
        }
        if(value.isArray()){
            if(schema.has("minItems")&&value.size()<schema.get("minItems").asInt())fail(path);
            if(schema.has("maxItems")&&value.size()>schema.get("maxItems").asInt())fail(path);
            Set<JsonNode> unique=new HashSet<>();int index=0;
            for(JsonNode item:value){if(schema.path("uniqueItems").asBoolean()&&!unique.add(item))fail(path);if(schema.has("items"))check(item,schema.get("items"),path+"["+(index++)+"]");}
        }
        if(value.isTextual()){
            String s=value.asText();int length=s.codePointCount(0,s.length());
            if(schema.has("minLength")&&length<schema.get("minLength").asInt())fail(path);
            if(schema.has("maxLength")&&length>schema.get("maxLength").asInt())fail(path);
            if(schema.has("pattern")&&!java.util.regex.Pattern.compile(schema.get("pattern").asText()).matcher(s).find())fail(path);
            if("date-time".equals(schema.path("format").asText()))try{Instant.parse(s);}catch(RuntimeException e){fail(path);}
        }
        if(value.isNumber()){
            BigDecimal n=value.decimalValue();
            if(schema.has("minimum")&&n.compareTo(schema.get("minimum").decimalValue())<0)fail(path);
            if(schema.has("maximum")&&n.compareTo(schema.get("maximum").decimalValue())>0)fail(path);
        }
    }
    private boolean isType(JsonNode value,String type) {
        return switch(type){case "null"->value.isNull();case "object"->value.isObject();case "array"->value.isArray();
            case "string"->value.isTextual();case "integer"->value.isIntegralNumber();case "number"->value.isNumber();case "boolean"->value.isBoolean();default->false;};
    }
}
