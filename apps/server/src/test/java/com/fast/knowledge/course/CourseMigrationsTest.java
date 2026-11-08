package com.fast.knowledge.course;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class CourseMigrationsTest {
    DriverManagerDataSource database() {return new DriverManagerDataSource("jdbc:h2:mem:course_rag_test_"+UUID.randomUUID()
            +";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1","sa","");}
    Flyway flyway(DriverManagerDataSource data) {return Flyway.configure().dataSource(data).locations(CourseMigrations.LOCATION)
            .baselineOnMigrate(false).cleanDisabled(true).load();}
    void legacy(DriverManagerDataSource data) throws Exception {
        try(var connection=data.getConnection()) {ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/course-migration/h2/V1__course_baseline.sql"));}
    }
    @Test void versionTwoUpgradePreservesSingleTurnsAndEnforcesParentOwnership() {
        var data=database();Flyway.configure().dataSource(data).locations(CourseMigrations.LOCATION).target("2").load().migrate();
        var jdbc=new JdbcTemplate(data);records(jdbc);CourseMigrations.migrate(flyway(data));
        assertNull(jdbc.queryForObject("SELECT parent_qa_id FROM kb_qa_record WHERE id=1",Long.class));
        jdbc.update("INSERT INTO kb_qa_record(id,user_id,question,status,parent_qa_id) VALUES (2,1,'Followup','NO_EVIDENCE',1)");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO kb_qa_record(id,user_id,question,status,parent_qa_id) VALUES (3,2,'Forged','NO_EVIDENCE',1)"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE kb_qa_record SET parent_qa_id=2 WHERE id=1"));
        assertEquals("Original excerpt",jdbc.queryForObject("SELECT excerpt FROM kb_qa_source WHERE qa_id=1",String.class));
        CourseMigrations.migrate(flyway(data));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM kb_qa_record",Integer.class));
    }
    @Test void emptyDatabaseMigratesOnceAndNeverSeedsUsers() {
        var data=database();var flyway=flyway(data);CourseMigrations.migrate(flyway);var jdbc=new JdbcTemplate(data);
        assertEquals(3,flyway.info().applied().length);assertEquals("3",flyway.info().current().getVersion().getVersion());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM kb_user",Integer.class));
        assertTrue(flyway.validateWithResult().validationSuccessful);CourseMigrations.migrate(flyway);
        assertEquals(3,flyway.info().applied().length);assertThrows(FlywayException.class,flyway::clean);
    }
    void records(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO kb_user(id,username,password) VALUES (1,'fixture_one','fixture_hash'),(2,'fixture_two','fixture_hash')");
        jdbc.update("INSERT INTO kb_category(id,name) VALUES (1,'Synthetic category')");
        jdbc.update("INSERT INTO kb_document(id,kb_id,title,file_name,file_type,file_path,created_by,category_id) VALUES (1,0,'Synthetic original','original.txt','txt','fixture/original',1,1),(2,0,'Other','other.txt','txt','fixture/other',1,1)");
        jdbc.update("INSERT INTO kb_document_version(id,document_id,version_number,status,object_key,original_filename,file_type,file_size) VALUES (1,1,1,'READY','fixture/original','original.txt','TXT',8),(2,2,1,'READY','fixture/other','other.txt','TXT',8)");
        jdbc.update("UPDATE kb_document SET current_version_id=id WHERE id IN (1,2)");
        jdbc.update("INSERT INTO kb_document_chunk(id,kb_id,document_id,version_id,chunk_index,content) VALUES (1,0,1,1,1,'Original excerpt')");
        jdbc.update("INSERT INTO kb_qa_record(id,user_id,question,status,answer) VALUES (1,1,'Synthetic question','SUCCESS','Supported [S1]')");
        jdbc.update("INSERT INTO kb_qa_source(qa_id,evidence_label,document_id,version_id,chunk_id,document_title,version_number,chunk_index,excerpt) VALUES (1,'S1',1,1,1,'Synthetic original',1,1,'Original excerpt')");
        jdbc.update("INSERT INTO kb_favorite(user_id,qa_id) VALUES (1,1)");
        jdbc.update("INSERT INTO kb_feedback(user_id,qa_id,rating,reason) VALUES (1,1,'HELPFUL','Synthetic reason')");
    }
    @Test void exactLegacySchemaIsAdoptedWithoutLosingOriginalRecordsOrSnapshots() throws Exception {
        var data=database();legacy(data);var jdbc=new JdbcTemplate(data);records(jdbc);var flyway=flyway(data);
        CourseMigrations.migrate(flyway);
        assertEquals("3",flyway.info().current().getVersion().getVersion());assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM kb_user",Integer.class));
        assertEquals("fixture_hash",jdbc.queryForObject("SELECT password FROM kb_user WHERE id=1",String.class));
        assertEquals("Original excerpt",jdbc.queryForObject("SELECT excerpt FROM kb_qa_source",String.class));
        assertEquals("fixture/original",jdbc.queryForObject("SELECT object_key FROM kb_document_version WHERE id=1",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kb_feedback",Integer.class));
        assertNull(jdbc.queryForObject("SELECT sha256 FROM kb_document_version WHERE id=1",String.class));
    }
    @Test void versionOneUpgradesIncrementallyAndDatabaseEnforcesOwnershipAndProvenance() {
        var data=database();Flyway.configure().dataSource(data).locations(CourseMigrations.LOCATION).target("1").load().migrate();
        var jdbc=new JdbcTemplate(data);records(jdbc);var flyway=flyway(data);CourseMigrations.migrate(flyway);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE kb_document SET current_version_id=2 WHERE id=1"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE kb_document_chunk SET document_id=2 WHERE id=1"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE kb_qa_source SET version_id=2"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO kb_favorite(user_id,qa_id) VALUES (2,1)"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO kb_feedback(user_id,qa_id,rating) VALUES (2,1,'HELPFUL')"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE kb_document_version SET sha256='invalid' WHERE id=1"));
        assertEquals(1,jdbc.queryForObject("SELECT current_version_id FROM kb_document WHERE id=1",Integer.class));
    }
    @Test void unknownNonemptyDatabaseIsNeverBaselinedOrChanged() throws Exception {
        var data=database();var jdbc=new JdbcTemplate(data);jdbc.execute("CREATE TABLE important_original(id INT PRIMARY KEY,content VARCHAR(20))");
        jdbc.update("INSERT INTO important_original VALUES(1,'preserve me')");var before=signature(data);
        assertThrows(IllegalStateException.class,()->CourseMigrations.migrate(flyway(data)));
        assertEquals(before,signature(data));assertEquals("preserve me",jdbc.queryForObject("SELECT content FROM important_original",String.class));
    }
    @Test void partialOrModifiedLookalikeSchemaIsNotAdopted() throws Exception {
        var data=database();legacy(data);var jdbc=new JdbcTemplate(data);jdbc.execute("ALTER TABLE kb_feedback ADD COLUMN unexpected_column INT");
        var before=signature(data);assertThrows(IllegalStateException.class,()->CourseMigrations.migrate(flyway(data)));assertEquals(before,signature(data));
        try(var connection=data.getConnection()) {assertFalse(CourseMigrations.tables(connection).contains("flyway_schema_history"));}
    }
    @Test void invalidLegacyRelationshipsFailBeforeAnySchemaChange() throws Exception {
        var data=database();legacy(data);var jdbc=new JdbcTemplate(data);records(jdbc);
        jdbc.update("UPDATE kb_document SET current_version_id=2 WHERE id=1");var before=signature(data);
        assertThrows(IllegalStateException.class,()->CourseMigrations.migrate(flyway(data)));assertEquals(before,signature(data));
        assertEquals(2,jdbc.queryForObject("SELECT current_version_id FROM kb_document WHERE id=1",Integer.class));
    }
    @Test void changedAppliedChecksumIsRejectedWithoutAutomaticRepair() {
        var data=database();var flyway=flyway(data);CourseMigrations.migrate(flyway);var jdbc=new JdbcTemplate(data);
        jdbc.update("UPDATE \"flyway_schema_history\" SET \"checksum\"=0 WHERE \"version\"='2'");
        assertThrows(FlywayException.class,()->CourseMigrations.migrate(flyway));
        assertEquals(0,jdbc.queryForObject("SELECT \"checksum\" FROM \"flyway_schema_history\" WHERE \"version\"='2'",Integer.class));
    }
    @Test void foreignOrFutureHistoryDoesNotBypassUnknownDatabaseProtection() throws Exception {
        var data=database();var jdbc=new JdbcTemplate(data);jdbc.execute("CREATE TABLE important_original(id INT)");
        Flyway.configure().dataSource(data).baselineVersion("99").load().baseline();var before=signature(data);
        assertThrows(IllegalStateException.class,()->CourseMigrations.migrate(flyway(data)));assertEquals(before,signature(data));
        assertEquals("99",flyway(data).info().current().getVersion().getVersion());
    }
    @Test void unexpectedTablesInVersionedDatabaseFailWithoutChangingData() throws Exception {
        var data=database();var flyway=flyway(data);CourseMigrations.migrate(flyway);var jdbc=new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE unrelated_business(id INT,content VARCHAR(30))");jdbc.update("INSERT INTO unrelated_business VALUES(1,'preserve original')");
        var before=signature(data);assertThrows(IllegalStateException.class,()->CourseMigrations.migrate(flyway));assertEquals(before,signature(data));
        assertEquals("preserve original",jdbc.queryForObject("SELECT content FROM unrelated_business",String.class));
    }
    @Test void configurationCannotEnableCleanOrUnknownAutomaticBaseline() {
        var configuration=Flyway.configure().cleanDisabled(false).baselineOnMigrate(true);
        new CourseMigrations().courseMigrationSafety().customize(configuration);
        assertTrue(configuration.isCleanDisabled());assertFalse(configuration.isBaselineOnMigrate());
    }
    java.util.Set<String> signature(DriverManagerDataSource data) throws Exception {
        try(var connection=data.getConnection()) {return CourseMigrations.signature(connection);}
    }
}
