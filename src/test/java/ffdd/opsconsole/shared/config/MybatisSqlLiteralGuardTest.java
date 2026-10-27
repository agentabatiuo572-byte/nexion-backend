package ffdd.opsconsole.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** SQL that MySQL 8.0 / 8.4 rejects outright must not reach a mapper annotation. */
class MybatisSqlLiteralGuardTest {
    /** Single-line annotations are raw SQL unless they start with &lt;script&gt;; XML escapes stay literal there. */
    private static final Pattern PLAIN_ANNOTATION = Pattern.compile("@(?:Select|Insert|Update|Delete)\\(\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** Unquoted aliases that are reserved words (PRECISION in 8.0; MANUAL, PARALLEL, QUALIFY, TABLESAMPLE in 8.4). */
    private static final Pattern RESERVED_ALIAS = Pattern.compile(
            "(?i)(?:\\bAS\\s+|[\\w)]\\s+)(manual|precision|parallel|qualify|tablesample)\\b(?=\\s*(?:,|FROM\\b|\\)|$))", Pattern.MULTILINE);

    /** Text blocks and ordinary string literals in mapper sources, where the SQL lives. */
    private static final Pattern SQL_LITERAL = Pattern.compile("\"\"\"([\\s\\S]*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    @Test void mapperSqlHasNoLiteralXmlEscapesOrReservedAliases() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source;
                try { source = Files.readString(path); } catch (IOException e) { throw new UncheckedIOException(e); }
                var plain = PLAIN_ANNOTATION.matcher(source);
                while (plain.find()) {
                    String sql = plain.group(1);
                    if (!sql.trim().startsWith("<script>") && sql.matches("(?s).*&(?:lt|gt|amp);.*")) offenders.add(path + " literal XML escape");
                }
                if (!path.toString().contains("/mapper/")) continue;
                var literal = SQL_LITERAL.matcher(source);
                while (literal.find()) {
                    var alias = RESERVED_ALIAS.matcher(literal.group(1) != null ? literal.group(1) : literal.group(2));
                    while (alias.find()) offenders.add(path + " reserved alias " + alias.group(1));
                }
            }
        }
        assertThat(offenders).as("write < in plain SQL and quote reserved aliases with backticks").isEmpty();
    }
}
