package ffdd.opsconsole.promotion.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.BusinessProductImages;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@RestControllerAdvice
@RequiredArgsConstructor
public class PromotionItemImageAdvice implements ResponseBodyAdvice<Object> {
    private final BusinessProductImages productImages;

    @Override
    public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
        return method.hasMethodAnnotation(PromotionItemImages.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter method, MediaType type,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof ApiResult<?> result) || result.getCode()!=0 || !(result.getData() instanceof Map<?,?> data)) return body;
        String field=method.getMethodAnnotation(PromotionItemImages.class).value();
        Object value=field.isEmpty()?data:data.get(field);
        if (!(value instanceof Map<?,?> receipt) || !(receipt.get("items") instanceof List<?>)
                || (receipt.get("promotionQuoteId")==null && receipt.get("quoteId")==null)
                || "SANDBOX".equals(receipt.get("sourceEnvironment"))) return body;
        var lines=maps(receipt.get("items"));
        var urls=productImages.urls(lines.stream().map(line->text(line.get("productNo"))).toList());
        for(var line:lines)line.put("imageUrl",urls.get(text(line.get("productNo"))));
        var enriched=map(receipt);
        enriched.put("items",lines);
        var projected=field.isEmpty()?enriched:map(data);
        if(!field.isEmpty())projected.put(field,enriched);
        return new ApiResult<>(result.getCode(),result.getMessage(),projected);
    }
}
