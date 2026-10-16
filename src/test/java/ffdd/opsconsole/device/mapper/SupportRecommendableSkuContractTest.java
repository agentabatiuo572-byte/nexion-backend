package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.dto.DeviceSkuQueryRequest;
import ffdd.opsconsole.device.infrastructure.MybatisDeviceCatalogRepository;
import ffdd.opsconsole.shared.canonical.StorefrontProductPublishGate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SupportRecommendableSkuContractTest {
    @Test
    void countAndPageApplyTheSendGateBeforePaginationWithoutHidingE1Rows() throws Exception {
        String count = sql("countRecommendableSkus", String.class);
        String page = sql("pageRecommendableSkus", String.class, long.class, long.class);
        for (String query : List.of(count, page)) {
            assertThat(query).contains(DeviceCatalogMapper.RECOMMENDABLE_SKU_WHERE)
                    .contains(StorefrontProductPublishGate.PUBLISHABLE_SQL)
                    .contains("p.is_deleted=0", "p.store_visible=1", "p.price_usdt>0",
                            "REGEXP_LIKE(p.name, #{nonblankNamePattern})", "CHAR_LENGTH(p.product_no) BETWEEN 1 AND 64",
                            "NOT REGEXP_LIKE(p.product_no, '[^A-Za-z0-9_-]', 'c')", "='on'");
        }
        assertThat(page.indexOf(StorefrontProductPublishGate.PUBLISHABLE_SQL)).isLessThan(page.indexOf("LIMIT"));
        assertThat(sql("countSkus", String.class, String.class))
                .doesNotContain(StorefrontProductPublishGate.PUBLISHABLE_SQL);
    }

    @Test
    void mybatisBuildsCountAndPageForEmptyAndPresentKeywords() throws Exception {
        for (String keyword : new String[] {null, "Lumen"}) {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("keyword", keyword);
            parameters.put("limit", 8L);
            parameters.put("offset", 8L);
            for (String query : List.of(sql("countRecommendableSkus", String.class),
                    sql("pageRecommendableSkus", String.class, long.class, long.class))) {
                var bound = new XMLLanguageDriver().createSqlSource(new Configuration(), query, Map.class)
                        .getBoundSql(parameters);
                assertThat(bound.getSql()).doesNotContain("<script>", "<if", "#{");
                assertThat(bound.getSql().contains("LIKE CONCAT")).isEqualTo(keyword != null);
                assertThat(bound.getParameterMappings()).hasSize(1 + (keyword == null ? 0 : 4) + (query.contains("LIMIT") ? 2 : 0));
                assertThat(bound.getAdditionalParameter("nonblankNamePattern")).isEqualTo(DeviceCatalogMapper.SKU_NONBLANK_NAME_REGEX);
            }
        }
    }

    @Test
    void namePatternMatchesJavaBlankChecksIncludingControlAndUnicodeWhitespace() {
        var pattern = java.util.regex.Pattern.compile(DeviceCatalogMapper.SKU_NONBLANK_NAME_REGEX);
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            boolean nonblank = pattern.matcher(new String(Character.toChars(codePoint))).find();
            if (nonblank == Character.isWhitespace(codePoint)) {
                throw new AssertionError("Whitespace mismatch at U+" + Integer.toHexString(codePoint));
            }
        }
    }

    @Test
    void repositoryKeepsFilteredTotalsAndOffsetsAndCannotWidenViaStatus() {
        var mapper = mock(DeviceCatalogMapper.class);
        var repository = new MybatisDeviceCatalogRepository(mapper, new ObjectMapper());
        when(mapper.countRecommendableSkus("Lumen")).thenReturn(21L);
        when(mapper.pageRecommendableSkus("Lumen", 10L, 10L)).thenReturn(List.of());
        var result = repository.pageRecommendableSkus(new DeviceSkuQueryRequest("off", " Lumen ", 2L, 10L));
        assertThat(result.getTotal()).isEqualTo(21);
        assertThat(result.getPageNum()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(10);
        verify(mapper).countRecommendableSkus("Lumen");
        verify(mapper).pageRecommendableSkus("Lumen", 10L, 10L);
        verifyNoMoreInteractions(mapper);
    }

    private static String sql(String name, Class<?>... parameters) throws Exception {
        return String.join("\n", DeviceCatalogMapper.class.getMethod(name, parameters).getAnnotation(Select.class).value());
    }
}
