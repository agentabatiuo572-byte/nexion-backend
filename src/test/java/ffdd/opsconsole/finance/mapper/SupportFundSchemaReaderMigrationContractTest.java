package ffdd.opsconsole.finance.mapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source contracts only: these tests never execute MySQL or prove native privileges. */
class SupportFundSchemaReaderMigrationContractTest {
    private static final Path MIGRATION=Path.of("scripts/migrations/20261011_support_fund_schema_reader.sql");
    private static final String FUNCTION="nx_support_fund_schema_ready_v1";
    private static String migration() throws IOException {return Files.readString(MIGRATION);}
    private static String body(String sql) {
        int start=sql.indexOf("-- FUNCTION_BODY_BEGIN\n");
        int end=sql.indexOf(";\n-- FUNCTION_BODY_END",start);
        assertTrue(start>=0 &&end>start,"Actual CREATE FUNCTION body markers required");
        return sql.substring(start+"-- FUNCTION_BODY_BEGIN\n".length(),end);
    }
    private static String expectedBody() {return "RETURN (\n"+SupportFundReadSchemaSql.SQL.strip()+"\n)";}
    private static int count(String text,String token) {return (text.length()-text.replace(token,"").length())/token.length();}
    private record Literal(String value,int end) {}
    private static Literal literal(String sql,int opening) {
        assertEquals('\'',sql.charAt(opening));var decoded=new StringBuilder();
        for(int index=opening+1;index<sql.length();index++) {
            char c=sql.charAt(index);
            if(c!='\''){decoded.append(c);continue;}
            if(index+1<sql.length() &&sql.charAt(index+1)=='\''){decoded.append('\'');index++;continue;}
            return new Literal(decoded.toString(),index+1);
        }
        throw new AssertionError("Unclosed SQL literal");
    }
    private static String metadataBody(String raw) {
        assertFalse(raw.contains("\\"),"Fixed source must not use backslash escapes");
        var result=new StringBuilder();
        for(int index=0;index<raw.length();index++) {
            if(raw.charAt(index)=='\'') {
                Literal value=literal(raw,index);
                result.append('\'').append(value.value()).append('\'');index=value.end()-1;
            }
            else result.append(raw.charAt(index));
        }
        return result.toString();
    }
    private static int expectedOpening(String sql) {
        String prefix="SET @fund_schema_reader_expected_body='";int index=sql.indexOf(prefix);
        assertTrue(index>=0);return index+prefix.length()-1;
    }
    private static String outsideLiterals(String sql) {
        var result=new StringBuilder();
        for(int index=0;index<sql.length();index++) {
            if(sql.charAt(index)=='-' &&index+2<sql.length() &&sql.charAt(index+1)=='-' &&Character.isWhitespace(sql.charAt(index+2))) {
                while(index<sql.length() &&sql.charAt(index)!='\n')index++;
                result.append('\n');
            }
            else if(sql.charAt(index)=='\''){index=literal(sql,index).end()-1;result.append("''");}
            else result.append(sql.charAt(index));
        }
        return result.toString();
    }
    private static String installationShape(String sql) {
        int opening=expectedOpening(sql);int end=literal(sql,opening).end();
        String withoutExpected=sql.substring(0,opening)+"''"+sql.substring(end);
        return withoutExpected.replace(body(withoutExpected),"/* original body verified separately */");
    }

    @Test void actualBoundSqlCallsOnlyTheFixedNoArgumentFunctionAndBypassesCaches() {
        var configuration=new Configuration();configuration.addMapper(SupportFundReadMapper.class);
        var statement=configuration.getMappedStatement(SupportFundReadMapper.class.getName()+".schemaReady");
        var bound=statement.getBoundSql(Map.of());
        assertEquals("SELECT "+FUNCTION+"()",bound.getSql().strip());
        assertEquals(SqlCommandType.SELECT,statement.getSqlCommandType());
        assertFalse(statement.isUseCache());assertTrue(statement.isFlushCacheRequired());
        assertTrue(bound.getParameterMappings().isEmpty());
    }

    @Test void installedBodyPreservesTheEntireOriginalGuardIncludingQuotedTriggerBytes() throws IOException {
        assertEquals(expectedBody(),body(migration()));
        assertEquals(17,count(SupportFundReadSchemaSql.SQL,"information_schema.TRIGGERS"));
        for(String table:java.util.List.of("TABLES","COLUMNS","KEY_COLUMN_USAGE","STATISTICS","TABLE_CONSTRAINTS","CHECK_CONSTRAINTS","REFERENTIAL_CONSTRAINTS","TRIGGERS"))
            assertTrue(SupportFundReadSchemaSql.SQL.contains("information_schema."+table),table);
        assertTrue(SupportFundReadSchemaSql.SQL.contains("BINARY ACTION_STATEMENT=BINARY 'BEGIN"));
        assertTrue(SupportFundReadSchemaSql.SQL.contains("ACTION_ORIENTATION='ROW'"));
        assertTrue(SupportFundReadSchemaSql.SQL.contains("ACTION_ORDER"));
    }

    @Test void metadataProjectionPreservesEmptyLiteralsQuotedTriggerBytesAndOutsideText() {
        String raw="RETURN (\n '', BINARY ACTION_STATEMENT=BINARY 'BEGIN SET x=''a''''b''; END', 1\n)";
        String expected="RETURN (\n '', BINARY ACTION_STATEMENT=BINARY 'BEGIN SET x='a''b'; END', 1\n)";
        assertEquals(expected,metadataBody(raw));
    }

    @Test void metadataProjectionRejectsUnsupportedBackslashesAndUnclosedLiterals() {
        assertThrows(AssertionError.class,()->metadataBody("RETURN ('unterminated"));
        assertThrows(AssertionError.class,()->metadataBody("RETURN ('back\\slash')"));
    }

    @Test void shapeComparisonUsesTheFullMetadataProjectionRatherThanAProtocolHashOrReadyFlag() throws IOException {
        String sql=migration();assertEquals(metadataBody(expectedBody()),literal(sql,expectedOpening(sql)).value());
        assertEquals(2,count(sql,"BINARY ROUTINE_DEFINITION=BINARY @fund_schema_reader_expected_body"));
        assertFalse(sql.contains("SHA2(ROUTINE_DEFINITION"));
    }

    @Test void unknownExistingRoutineIsRejectedBeforeAnyCreateAndCheckedAgainAfterCreate() throws IOException {
        String sql=migration();int pre=sql.indexOf("FAIL SUPPORT_FUND_SCHEMA_READER_EXISTING_SHAPE");
        int create=sql.indexOf("CREATE DEFINER=CURRENT_USER FUNCTION IF NOT EXISTS "+FUNCTION+"()");
        int post=sql.indexOf("FAIL SUPPORT_FUND_SCHEMA_READER_INSTALLED_SHAPE");
        assertTrue(pre>=0 &&create>pre &&post>create);
        String executable=outsideLiterals(sql).toUpperCase(Locale.ROOT);
        for(String forbidden:java.util.List.of("DROP FUNCTION","ALTER FUNCTION","CREATE OR REPLACE","GRANT ","SET GLOBAL"))assertFalse(executable.contains(forbidden),forbidden);
    }

    @Test void existingAndInstalledShapeRequireNoArgumentsDefinerReadonlyAndExactReturn() throws IOException {
        String sql=installationShape(migration());
        for(String guard:java.util.List.of("ROUTINE_SCHEMA=DATABASE()","ROUTINE_TYPE='FUNCTION'","DATA_TYPE='tinyint'","DTD_IDENTIFIER='tinyint unsigned'",
                "IS_DETERMINISTIC='NO'","SQL_DATA_ACCESS='READS SQL DATA'","SECURITY_TYPE='DEFINER'","DEFINER=CURRENT_USER()",
                "ORDINAL_POSITION>0","SPECIFIC_SCHEMA=DATABASE()"))assertEquals(2,count(sql,guard),guard);
        assertTrue(sql.contains("RETURNS TINYINT UNSIGNED\nNOT DETERMINISTIC\nREADS SQL DATA\nSQL SECURITY DEFINER"));
    }

    @Test void functionContainsOnlyMetadataSelectsAndDoesNotCreateHistoricalAttestation() throws IOException {
        String executable=outsideLiterals(body(migration())).toUpperCase(Locale.ROOT);
        for(String forbidden:java.util.List.of("INSERT ","UPDATE ","DELETE ","PREPARE ","EXECUTE ","CALL ","SET ","COMMIT","ROLLBACK","FOR UPDATE","FOR SHARE","@"))assertFalse(executable.contains(forbidden),forbidden);
        assertTrue(migration().contains("not historical trigger-coverage attestation"));
        assertTrue(migration().contains("FAIL SUPPORT_FUND_SCHEMA_READER_NOT_READY"));
    }

    @Test void fourthMigrationIsRegisteredImmediatelyAfterTheOriginalFundThree() throws IOException {
        String startup=Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String party="scripts\\migrations\\20261011_support_fund_operation_party.sql";
        String reader="scripts\\migrations\\20261011_support_fund_schema_reader.sql";
        assertEquals(1,count(startup,reader));
        int index=startup.indexOf(party);assertTrue(index>=0);
        String next=startup.substring(startup.indexOf('\n',index)+1).lines().findFirst().orElseThrow();
        assertTrue(next.contains(reader),next);
    }

    @Test void missingOrUnexpectedFunctionErrorsRemainMappedByTheExistingReader() throws IOException {
        String reader=Files.readString(Path.of("src/main/java/ffdd/opsconsole/finance/application/SupportFundReadService.java"));
        assertTrue(reader.contains("if(!Boolean.TRUE.equals(mapper.schemaReady()))return unknown(roots,List.of(Gap.RAW_HISTORY))"));
        assertTrue(reader.contains("catch(org.springframework.dao.DataAccessException"));
        assertFalse(reader.contains("SupportFundReadSchemaSql.SQL"));
    }
}
