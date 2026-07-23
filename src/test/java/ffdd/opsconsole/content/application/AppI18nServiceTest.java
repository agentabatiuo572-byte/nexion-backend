package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.I18nLearningRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppI18nServiceTest {
    private final I18nLearningRepository repository = mock(I18nLearningRepository.class);
    private final AppI18nService service = new AppI18nService(repository);

    @Test
    void returnsOnlyPublishedServerCanonicalNamespaceForSupportedLocale() {
        when(repository.listPublishedMessages("home", "vi-VN")).thenReturn(new LinkedHashMap<>(Map.of(
                "home.hero.title", "Tieu de",
                "home.hero.body", "Noi dung")));

        var result = service.namespace("home", "vi");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().namespace()).isEqualTo("home");
        assertThat(result.getData().locale()).isEqualTo("vi-VN");
        assertThat(result.getData().messages()).containsEntry("home.hero.title", "Tieu de");
        assertThat(result.getData().serverCanonical()).isTrue();
    }

    @Test
    void rejectsUnknownLocaleAndMalformedNamespace() {
        assertThat(service.namespace("home", "ja").getMessage()).isEqualTo("I18N_LOCALE_UNSUPPORTED");
        assertThat(service.namespace("../home", "zh").getMessage()).isEqualTo("I18N_NAMESPACE_INVALID");
    }

    @Test
    void returnsAllPublishedMessagesForRuntimeBootstrap() {
        when(repository.listPublishedMessages(null, "zh-CN"))
                .thenReturn(Map.of("home.hero.title", "首页标题", "learn.course.title", "课程"));

        var result = service.all("zh-CN");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().namespace()).isEqualTo("*");
        assertThat(result.getData().messages()).hasSize(2);
    }
}
