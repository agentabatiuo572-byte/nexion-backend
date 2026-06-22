package ffdd.opsconsole.shared.api;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class ApiResultHttpStatusAdvice implements ResponseBodyAdvice<ApiResult<?>> {
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return ApiResult.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public ApiResult<?> beforeBodyWrite(
            ApiResult<?> body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body != null && body.getCode() != 0) {
            response.setStatusCode(httpStatus(body.getCode()));
        }
        return body;
    }

    private HttpStatusCode httpStatus(int code) {
        try {
            return HttpStatusCode.valueOf(code);
        } catch (IllegalArgumentException ex) {
            return HttpStatusCode.valueOf(500);
        }
    }
}
