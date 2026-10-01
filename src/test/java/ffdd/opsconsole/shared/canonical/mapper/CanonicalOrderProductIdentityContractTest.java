package ffdd.opsconsole.shared.canonical.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.MySQLGroupConcat;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** Parses the actual MyBatis statement without opening any database connection. */
class CanonicalOrderProductIdentityContractTest {

    @Test
    void onlySingleOrdersReadAnUnformattedSkuWithHistoricalCatalogFallback() throws Exception {
        CaseExpression identity = identityProjection();
        assertThat(identity.getWhenClauses()).hasSize(1);
        EqualsTo condition = (EqualsTo) identity.getWhenClauses().get(0).getWhenExpression();
        Function orderType = (Function) condition.getLeftExpression();
        assertThat(orderType.getName()).isEqualToIgnoringCase("UPPER");
        assertThat(((Column) orderType.getParameters().get(0)).getFullyQualifiedName())
                .isEqualTo("o.order_type");
        assertThat(((StringValue) condition.getRightExpression()).getValue()).isEqualTo("SINGLE");

        Function fallback = (Function) identity.getWhenClauses().get(0).getThenExpression();
        assertThat(fallback.getName()).isEqualToIgnoringCase("COALESCE");
        assertThat(fallback.getParameters()).hasSize(2);
        PlainSelect snapshot = ((ParenthesedSelect) fallback.getParameters().get(0)).getPlainSelect();
        // A present line returns its stored SKU directly, regardless of quantity or catalog removal.
        assertThat(snapshot.getSelectItems()).hasSize(1);
        assertThat(snapshot.getSelectItems().get(0).getExpression()).isInstanceOf(Column.class);
        assertThat(((Column) snapshot.getSelectItems().get(0).getExpression()).getFullyQualifiedName())
                .isEqualTo("oi.product_no");
        assertThat(snapshot.getWhere().toString()).contains("oi.order_no = o.order_no", "oi.is_deleted = 0");
        assertThat(snapshot.getOrderByElements().stream().map(item -> item.getExpression().toString()))
                .containsExactly("oi.sort_order", "oi.id");
        assertThat(((LongValue) snapshot.getLimit().getRowCount()).getValue()).isEqualTo(1);
        // Historical orders with no surviving item keep the catalog SKU, without display decoration.
        assertThat(((Column) fallback.getParameters().get(1)).getFullyQualifiedName()).isEqualTo("p.product_no");
    }

    @Test
    void bundleAndOtherOrderTypesKeepTheirExistingOrderedSummary() throws Exception {
        Function fallback = (Function) identityProjection().getElseExpression();
        assertThat(fallback.getName()).isEqualToIgnoringCase("COALESCE");
        PlainSelect lines = ((ParenthesedSelect) fallback.getParameters().get(0)).getPlainSelect();
        MySQLGroupConcat summary = (MySQLGroupConcat) lines.getSelectItems().get(0).getExpression();
        Function label = (Function) summary.getExpressionList().get(0);
        assertThat(label.getName()).isEqualToIgnoringCase("CONCAT");
        assertThat(((Column) label.getParameters().get(0)).getFullyQualifiedName()).isEqualTo("oi.product_no");
        assertThat(((StringValue) label.getParameters().get(1)).getValue()).isEqualTo("×");
        assertThat(((Column) label.getParameters().get(2)).getFullyQualifiedName()).isEqualTo("oi.quantity");
        assertThat(summary.getOrderByElements().stream().map(item -> item.getExpression().toString()))
                .containsExactly("oi.sort_order", "oi.id");
        assertThat(summary.toString()).contains("SEPARATOR ' + '");
        assertThat(lines.getWhere().toString()).contains("oi.order_no = o.order_no", "oi.is_deleted = 0");
        assertThat(((Column) fallback.getParameters().get(1)).getFullyQualifiedName()).isEqualTo("p.product_no");
    }

    @Test
    void actualMyBatisExpansionRetainsCustomerCursorAndLimitBindings() throws Exception {
        BoundSql firstPage = boundSql(null);
        assertThat(firstPage.getParameterMappings().stream().map(ParameterMapping::getProperty))
                .containsExactly("userId", "limit");
        BoundSql nextPage = boundSql("ORD-CURSOR");
        assertThat(nextPage.getParameterMappings().stream().map(ParameterMapping::getProperty))
                .containsExactly("userId", "beforeOrderNo", "userId", "limit");
        PlainSelect page = ((Select) CCJSqlParserUtil.parse(nextPage.getSql())).getPlainSelect();
        assertThat(page.getWhere().toString()).contains("o.user_id = ?", "o.is_deleted = 0",
                "cursor_order.user_id = ?", "cursor_order.is_deleted = 0");
        assertThat(page.getOrderByElements().stream().map(item -> item.getExpression().toString()))
                .containsExactly("o.created_at", "o.id");
        assertThat(page.getLimit().getRowCount().toString()).isEqualTo("?");
    }

    private CaseExpression identityProjection() throws Exception {
        PlainSelect page = ((Select) CCJSqlParserUtil.parse(boundSql(null).getSql())).getPlainSelect();
        var expression = page.getSelectItems().stream()
                .filter(item -> item.getAlias() != null && "productNo".equals(item.getAlias().getName()))
                .findFirst().orElseThrow().getExpression();
        assertThat(expression).as("Only SINGLE product identity may bypass quantity display aggregation")
                .isInstanceOf(CaseExpression.class);
        return (CaseExpression) expression;
    }

    private BoundSql boundSql(String cursor) throws Exception {
        String script = String.join("\n", CanonicalStateMapper.class
                .getMethod("userOrdersPage", Long.class, String.class, Integer.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        Map<String, Object> params = new HashMap<>();
        params.put("userId", 42L);
        params.put("beforeOrderNo", cursor);
        params.put("limit", 51);
        return new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class).getBoundSql(params);
    }
}
