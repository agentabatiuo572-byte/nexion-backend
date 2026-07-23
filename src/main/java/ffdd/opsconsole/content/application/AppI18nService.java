package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.AppI18nBundle;
import ffdd.opsconsole.content.domain.I18nLearningRepository;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class AppI18nService {
    private static final Pattern NAMESPACE = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");
    private static final Map<String, String> LOCALES = Map.of(
            "en", "en-US", "en-us", "en-US",
            "zh", "zh-CN", "zh-cn", "zh-CN",
            "vi", "vi-VN", "vi-vn", "vi-VN");

    private final I18nLearningRepository repository;

    public ApiResult<AppI18nBundle> namespace(String namespace, String locale) {
        if (!StringUtils.hasText(namespace) || !NAMESPACE.matcher(namespace.trim()).matches()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_NAMESPACE_INVALID");
        }
        return bundle(namespace.trim(), locale);
    }

    public ApiResult<AppI18nBundle> all(String locale) {
        return bundle(null, locale);
    }

    private ApiResult<AppI18nBundle> bundle(String namespace, String locale) {
        String normalizedLocale = normalizeLocale(locale);
        if (normalizedLocale == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_LOCALE_UNSUPPORTED");
        }
        Map<String, String> source = repository.listPublishedMessages(namespace, normalizedLocale);
        Map<String, String> messages = source == null ? Map.of() : new LinkedHashMap<>(source);
        return ApiResult.ok(new AppI18nBundle(namespace == null ? "*" : namespace,
                normalizedLocale, Map.copyOf(messages), true));
    }

    private String normalizeLocale(String locale) {
        if (!StringUtils.hasText(locale)) {
            return "en-US";
        }
        return LOCALES.get(locale.trim().toLowerCase(Locale.ROOT));
    }
}
