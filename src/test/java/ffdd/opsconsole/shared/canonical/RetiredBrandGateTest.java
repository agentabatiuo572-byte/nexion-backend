package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * zentao #73:退役品牌检测必须真的抓到「Nexion 作前缀」的复合名。
 *
 * <p>本门的价值在于钉住 {@link RetiredBrandGate} javadoc 承诺的行为,而不是钉住那个
 * 正则字符串本身 —— 首版正则 {@code (^|[^[:alnum:]])nexion([^[:alnum:]]|$)}
 * 要求 'nexion' 后面紧跟非字母,于是 {@code NexionBox} / {@code NexionRack}
 * (品牌后面直接接 {@code B}/{@code R})**既不被门拦住、也不被存量迁移清掉**,
 * 而「NexionBox Pro v2」正是用户实际看到的旧品牌商品名。判据与注释各说一套,
 * 只有拿真实串跑一遍才看得出来。</p>
 */
class RetiredBrandGateTest {

    @Test
    void catchesTheBrandWhenItPrefixesACompoundProductName() {
        // 用户实际看到的旧商品名:品牌是**前缀**,后面紧跟字母。
        assertThat(RetiredBrandGate.carriesRetiredBrand("NexionBox Pro v2")).isTrue();
        assertThat(RetiredBrandGate.carriesRetiredBrand("NexionRack")).isTrue();
        assertThat(RetiredBrandGate.carriesRetiredBrand("NEXIONBOX PRO V2")).isTrue();
        assertThat(RetiredBrandGate.carriesRetiredBrand("NexionBox")).isTrue();
    }

    @Test
    void catchesTheBrandAsAStandaloneWordInEveryLocale() {
        assertThat(RetiredBrandGate.carriesRetiredBrand("欢迎来到 Nexion")).isTrue();
        assertThat(RetiredBrandGate.carriesRetiredBrand("Chào mừng đến Nexion")).isTrue();
        assertThat(RetiredBrandGate.carriesRetiredBrand("Welcome to Nexion")).isTrue();
        assertThat(RetiredBrandGate.carriesRetiredBrand("Nexion")).isTrue();
        assertThat(RetiredBrandGate.carriesRetiredBrand("nexion")).isTrue();
    }

    @Test
    void doesNotDamageWordsThatMerelyContainTheLetterSequence() {
        // 前边界是必须的:这些词里的 'nexion' 前面是字母,不是品牌。
        assertThat(RetiredBrandGate.carriesRetiredBrand("annexion report")).isFalse();
        assertThat(RetiredBrandGate.carriesRetiredBrand("connexion log")).isFalse();
        assertThat(RetiredBrandGate.carriesRetiredBrand("Annexion")).isFalse();
    }

    @Test
    void acceptsTheCurrentBrand() {
        assertThat(RetiredBrandGate.carriesRetiredBrand("NexGridBox Pro v2")).isFalse();
        assertThat(RetiredBrandGate.carriesRetiredBrand("NexGrid")).isFalse();
        assertThat(RetiredBrandGate.carriesRetiredBrand("")).isFalse();
        assertThat(RetiredBrandGate.carriesRetiredBrand(null)).isFalse();
    }

    @Test
    void anyCarriesRetiredBrandChecksEverySuppliedField() {
        assertThat(RetiredBrandGate.anyCarriesRetiredBrand("NexGrid", "NexGridBox", "NexionRack")).isTrue();
        assertThat(RetiredBrandGate.anyCarriesRetiredBrand("NexGrid", "NexGridBox")).isFalse();
        assertThat(RetiredBrandGate.anyCarriesRetiredBrand((String[]) null)).isFalse();
    }

    /**
     * SQL 侧与 Java 侧必须同一口径:存量迁移用 SQL 谓词清理,新写入用 Java 判据拦截,
     * 两者一旦分叉就会出现「清不掉 / 拦不住」的单边漏洞。
     */
    @Test
    void sqlPredicateUsesTheSameCaseInsensitiveWordStartPattern() {
        String sql = RetiredBrandGate.matchesSql("name");

        assertThat(sql).isEqualTo("REGEXP_LIKE(COALESCE(name,''), '(^|[^[:alnum:]])nexion', 'i')");
        // 后边界不能回来 —— 它正是漏掉 NexionBox 的那一半。
        assertThat(RetiredBrandGate.RETIRED_BRAND_REGEX).doesNotContain("|$)");
    }
}
