package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("ccccc")).isTrue();
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("CCCCCC")).isTrue();
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("xxxxx")).isTrue();
        // 纯标点重复同样是未替换的占位,不能因为「剥掉标点就空了」而漏判。
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("-----")).isTrue();
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("测试")).isTrue();
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("placeholder")).isTrue();
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("to do")).isTrue();
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("place-holder")).isTrue();
    }

    @Test
    void leavesRealCopyAlone() {
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("今日收益")).isFalse();
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("幸运轮盘")).isFalse();
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("2.9%")).isFalse();
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("今日 ccccc 收益")).isFalse();
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("a")).isFalse();
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("cc")).isFalse();
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("")).isFalse();
        assertThat(MybatisI18nLearningRepository.isPlaceholderText("   ")).isFalse();
    }
}
