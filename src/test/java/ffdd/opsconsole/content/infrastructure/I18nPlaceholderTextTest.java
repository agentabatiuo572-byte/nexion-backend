package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import ffdd.opsconsole.content.domain.I18nCopyQuality;

import org.junit.jupiter.api.Test;

/**
 * I1 完整性扫描的**内容**判据。
 *
 * <p>背景(zentao #67):已发布 v3 的中文正文是「ccccc」,三语占位符 token 集完全一致
 * (都为空),所以原有的 token 校验放它过门、扫描结论是「完整」。真正的问题是内容本身
 * 还是占位文本,或正文仍带退役品牌 —— 这两类必须能被扫描发现。
 *
 * <p>判据的两条边界同等重要:占位文本必须命中;正常业务文案(含数字、百分号、
 * 中英混排)绝不能被误判成占位,否则扫描会淹没在假阳性里。
 */
class I18nPlaceholderTextTest {

    @Test
    void detectsUnreplacedPlaceholderText() {
        assertThat(I18nCopyQuality.isPlaceholderText("ccccc")).isTrue();
        assertThat(I18nCopyQuality.isPlaceholderText("CCCCCC")).isTrue();
        assertThat(I18nCopyQuality.isPlaceholderText("xxxxx")).isTrue();
        // 纯标点重复同样是未替换的占位,不能因为「剥掉标点就空了」而漏判。
        assertThat(I18nCopyQuality.isPlaceholderText("-----")).isTrue();
        assertThat(I18nCopyQuality.isPlaceholderText("测试")).isTrue();
        assertThat(I18nCopyQuality.isPlaceholderText("placeholder")).isTrue();
        assertThat(I18nCopyQuality.isPlaceholderText("to do")).isTrue();
        assertThat(I18nCopyQuality.isPlaceholderText("place-holder")).isTrue();
    }

    @Test
    void leavesRealCopyAlone() {
        assertThat(I18nCopyQuality.isPlaceholderText("今日收益")).isFalse();
        assertThat(I18nCopyQuality.isPlaceholderText("幸运轮盘")).isFalse();
        assertThat(I18nCopyQuality.isPlaceholderText("2.9%")).isFalse();
        assertThat(I18nCopyQuality.isPlaceholderText("今日 ccccc 收益")).isFalse();
        assertThat(I18nCopyQuality.isPlaceholderText("a")).isFalse();
        assertThat(I18nCopyQuality.isPlaceholderText("cc")).isFalse();
        assertThat(I18nCopyQuality.isPlaceholderText("AAA")).isFalse();
        assertThat(I18nCopyQuality.isPlaceholderText("NEX")).isFalse();
        assertThat(I18nCopyQuality.isPlaceholderText("")).isFalse();
        assertThat(I18nCopyQuality.isPlaceholderText("   ")).isFalse();
    }

    @Test
    void publicationStillRejectsMissingLocales() {
        assertThat(I18nCopyQuality.publishError("中文", "English", " ")).isEqualTo("I18N_COPY_REQUIRED");
    }
}
