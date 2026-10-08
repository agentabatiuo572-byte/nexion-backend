package ffdd.opsconsole.promotion.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

public final class PromotionValues {
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private PromotionValues() {}
    public static Map<String,Object> map(Object value) {
        if (!(value instanceof Map<?,?> m)) throw new BizException(422,"PROMOTION_OBJECT_REQUIRED");
        Map<String,Object> result = new LinkedHashMap<>();
        m.forEach((k,v) -> result.put(String.valueOf(k),v)); return result;
    }
    public static List<Map<String,Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) throw new BizException(422,"PROMOTION_ARRAY_REQUIRED");
        return list.stream().map(PromotionValues::map).toList();
    }
    public static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    public static String required(Object value, String field) {
        String result=text(value).trim();
        if (result.isEmpty()) throw new BizException(422,"PROMOTION_REQUIRED:"+field);
        return result;
    }
    public static long number(Object value) {
        try { return new BigDecimal(text(value)).longValueExact(); }
        catch (RuntimeException e) { throw new BizException(422,"PROMOTION_INTEGER_REQUIRED"); }
    }
    public static BigDecimal amount(Object value) {
        if (!(value instanceof String s) || !s.matches("(0|[1-9][0-9]{0,11})(\\.[0-9]{1,6})?"))
            throw new BizException(422,"PROMOTION_DECIMAL_STRING_REQUIRED");
        return new BigDecimal(s).setScale(6,RoundingMode.UNNECESSARY);
    }
    public static BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO.setScale(6);
        return new BigDecimal(text(value)).setScale(6,RoundingMode.UNNECESSARY);
    }
    public static String money(BigDecimal value) { return value.setScale(6,RoundingMode.UNNECESSARY).toPlainString(); }
    public static Instant instant(Object value) {
        if(value instanceof Timestamp t) return t.toInstant();
        if(value instanceof LocalDateTime t) return t.atZone(DATABASE_ZONE).toInstant();
        if(value instanceof Instant t) return t;
        return Instant.parse(text(value));
    }
    public static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
    public static String json(Object value) {
        try {return JSON.writeValueAsString(value);} catch(Exception e) {throw new IllegalArgumentException("PROMOTION_JSON_INVALID",e);}
    }
    public static Map<String,Object> parse(Object value) {
        try {return JSON.readValue(value instanceof byte[] bytes ? new String(bytes,StandardCharsets.UTF_8):text(value),new TypeReference<>(){});}
        catch(Exception e) {throw new IllegalStateException("PROMOTION_STORED_JSON_INVALID",e);}
    }
    public static Map<String,Object> copy(Map<String,Object> value) {return parse(json(value));}
    public static String hash(Object value) {return sha256(json(canonical(value)));}
    private static Object canonical(Object value) {
        if(value instanceof Map<?,?> m) {Map<String,Object> sorted=new TreeMap<>();m.forEach((k,v)->sorted.put(text(k),canonical(v)));return sorted;}
        if(value instanceof List<?> l) return l.stream().map(PromotionValues::canonical).toList();
        return value;
    }
    public static String sha256(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e) {throw new IllegalStateException(e);}
    }
    public static String id(String prefix) {return prefix+UUID.randomUUID().toString().replace("-","");}
    public static void require(boolean condition,String code) {if(!condition)throw new BizException(409,code);}
    public static void changed(int rows) {require(rows==1,"PROMOTION_CONCURRENT_CHANGE");}
    public static Map<String,Object> values(Object... kv) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<kv.length;i+=2)result.put(text(kv[i]),kv[i+1]);return result;
    }
}
