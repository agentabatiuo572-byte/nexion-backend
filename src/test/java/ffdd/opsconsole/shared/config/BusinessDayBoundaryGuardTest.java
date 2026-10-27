package ffdd.opsconsole.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** R-1: business days are cut by the business calendar, never by the database session's date. */
class BusinessDayBoundaryGuardTest {
    private static final Pattern SESSION_DATE = Pattern.compile("\\bCURRENT_DATE\\b|\\bCURDATE\\s*\\(");

    @Test void mainSourcesUseBusinessPeriodSqlInsteadOfSessionDate() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            offenders = files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".xml"))
                    .filter(path -> {
                        try { return SESSION_DATE.matcher(Files.readString(path)).find(); }
                        catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                    })
                    .map(Path::toString).toList();
        }
        assertThat(offenders).as("use BusinessPeriodSql.Legacy / GrowthTeam (TODAY, DAY_START, ...) instead of CURRENT_DATE").isEmpty();
    }
}
