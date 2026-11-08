package com.fast.knowledge.course;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.*;
import java.util.*;

/** Migration owns schema changes. Only the exact previously shipped course schema can be adopted. */
@Configuration(proxyBeanMethods=false)
public class CourseMigrations {
    public static final String LOCATION="classpath:db/course-migration/h2";
    private static final String[] SCRIPTS={"db/course-migration/h2/V1__course_baseline.sql",
            "db/course-migration/h2/V2__provenance_integrity.sql","db/course-migration/h2/V3__qa_followup.sql"};
    @Bean FlywayConfigurationCustomizer courseMigrationSafety() {
        return configuration -> configuration.cleanDisabled(true).baselineOnMigrate(false)
                .validateOnMigrate(true).outOfOrder(false).ignoreMigrationPatterns(new String[0]).locations(LOCATION);
    }
    @Bean FlywayMigrationStrategy courseMigrationStrategy() {return CourseMigrations::migrate;}

    public static void migrate(Flyway flyway) {
        if(!flyway.getConfiguration().isCleanDisabled() || flyway.getConfiguration().isBaselineOnMigrate())
            throw new IllegalStateException("Unsafe database migration configuration");
        try(var connection=flyway.getConfiguration().getDataSource().getConnection()) {
            if(!"H2".equals(connection.getMetaData().getDatabaseProductName()))
                throw new IllegalStateException("Current course migrations require the configured H2 deployment");
            Set<String> tables=tables(connection);
            if(tables.contains("flyway_schema_history")) {
                var current=flyway.info().current();
                String version=current==null || current.getVersion()==null?"":current.getVersion().getVersion();
                if(!Set.of("1","2","3").contains(version))
                    throw new IllegalStateException("Unknown database migration history; database left unchanged");
                requireSchema(connection,Integer.parseInt(version));
            }
            if(!tables.isEmpty() && !tables.contains("flyway_schema_history")) {
                // No DDL, baseline or user-data mutation precedes full schema/data validation.
                requireSchema(connection,1);
                checkLegacyData(connection);
                // Explicit baseline follows exact recognition, never baselineOnMigrate for an unknown database.
                Flyway.configure().configuration(flyway.getConfiguration()).baselineVersion("1")
                        .baselineDescription("Verified B01-B06 course schema").load().baseline();
            }
        } catch(SQLException e) {throw new IllegalStateException("Course database migration preflight failed",e);}
        flyway.migrate();
        // WRITE_DELAY is process-scoped in H2. A one-time migration cannot configure later JVMs.
        // Restore synchronous commits after schema recognition, before any application runner/request.
        try(var connection=flyway.getConfiguration().getDataSource().getConnection();var statement=connection.createStatement()) {
            statement.execute("SET WRITE_DELAY 0");
        } catch(SQLException e) {throw new IllegalStateException("Course database durability configuration failed",e);}
    }
    private static void requireSchema(Connection connection,int version) throws SQLException {
        try(var expected=DriverManager.getConnection("jdbc:h2:mem:course_schema_"+UUID.randomUUID()
                +";MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","")) {
            for(int index=0;index<version;index++) ScriptUtils.executeSqlScript(expected,new ClassPathResource(SCRIPTS[index]));
            if(!signature(connection).equals(signature(expected)))
                throw new IllegalStateException("Unknown non-empty database structure; migration stopped without adopting it");
        }
    }
    private static void checkLegacyData(Connection connection) throws SQLException {
        String[] invalid={
            "SELECT COUNT(*) FROM kb_document d JOIN kb_document_version v ON d.current_version_id=v.id WHERE d.id<>v.document_id",
            "SELECT COUNT(*) FROM kb_document d WHERE d.current_version_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM kb_document_version v WHERE v.id=d.current_version_id)",
            "SELECT COUNT(*) FROM kb_document_version WHERE status NOT IN ('QUEUED','PARSING','READY','FAILED') OR version_number<1 OR file_size<0",
            "SELECT COUNT(*) FROM kb_document_chunk c JOIN kb_document_version v ON c.version_id=v.id WHERE c.document_id<>v.document_id OR c.chunk_index<1 OR c.page_no<1",
            "SELECT COUNT(*) FROM kb_index_task t WHERE t.version_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM kb_document_version v WHERE v.id=t.version_id AND v.document_id=t.document_id)",
            "SELECT COUNT(*) FROM kb_qa_source s WHERE NOT EXISTS (SELECT 1 FROM kb_document_chunk c WHERE c.id=s.chunk_id AND c.document_id=s.document_id AND c.version_id=s.version_id)",
            "SELECT COUNT(*) FROM kb_favorite f JOIN kb_qa_record q ON f.qa_id=q.id WHERE f.user_id<>q.user_id",
            "SELECT COUNT(*) FROM kb_feedback f JOIN kb_qa_record q ON f.qa_id=q.id WHERE f.user_id<>q.user_id"
        };
        for(String query:invalid) try(var statement=connection.createStatement();var rows=statement.executeQuery(query)) {
            rows.next();if(rows.getLong(1)>0)
                throw new IllegalStateException("Course data integrity preflight failed; database left unchanged");
        }
    }
    static Set<String> tables(Connection connection) throws SQLException {
        Set<String> result=new TreeSet<>();
        try(var rows=connection.getMetaData().getTables(null,"public","%",new String[]{"TABLE"})) {
            while(rows.next()) result.add(rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        }
        return result;
    }
    /** Ignore generated constraint names and identity counters; compare their actual structure instead. */
    static Set<String> signature(Connection connection) throws SQLException {
        Set<String> result=new TreeSet<>();var metadata=connection.getMetaData();
        for(String table:tables(connection)) {
            if(table.equals("flyway_schema_history")) continue;
            result.add("TABLE:"+table);
            try(var rows=metadata.getColumns(null,"public",table,"%")) {
                while(rows.next()) result.add("COLUMN:"+table+":"+rows.getString("COLUMN_NAME")+":"
                        +rows.getInt("DATA_TYPE")+":"+rows.getInt("COLUMN_SIZE")+":"+rows.getInt("DECIMAL_DIGITS")+":"
                        +rows.getInt("NULLABLE")+":"+rows.getString("COLUMN_DEF")+":"+rows.getString("IS_AUTOINCREMENT"));
            }
            Map<String,SortedMap<Short,String>> indexes=new HashMap<>();
            try(var rows=metadata.getIndexInfo(null,"public",table,false,false)) {
                while(rows.next()) if(rows.getString("COLUMN_NAME")!=null) {
                    String key=rows.getString("INDEX_NAME")+":"+rows.getBoolean("NON_UNIQUE");
                    indexes.computeIfAbsent(key,k->new TreeMap<>()).put(rows.getShort("ORDINAL_POSITION"),rows.getString("COLUMN_NAME"));
                }
            }
            for(var index:indexes.entrySet()) result.add("INDEX:"+table+":"+index.getKey().substring(index.getKey().lastIndexOf(':')+1)+":"+index.getValue().values());
            try(var rows=metadata.getPrimaryKeys(null,"public",table)) {
                while(rows.next()) result.add("PRIMARY:"+table+":"+rows.getShort("KEY_SEQ")+":"+rows.getString("COLUMN_NAME"));
            }
            Map<String,SortedMap<Short,String>> foreignKeys=new HashMap<>();
            try(var rows=metadata.getImportedKeys(null,"public",table)) {
                while(rows.next()) foreignKeys.computeIfAbsent(rows.getString("FK_NAME"),k->new TreeMap<>())
                        .put(rows.getShort("KEY_SEQ"),rows.getString("FKCOLUMN_NAME")+"->"+rows.getString("PKTABLE_NAME")+"."+rows.getString("PKCOLUMN_NAME")
                                +":"+rows.getShort("UPDATE_RULE")+":"+rows.getShort("DELETE_RULE"));
            }
            for(var key:foreignKeys.values()) result.add("FOREIGN:"+table+":"+key.values());
        }
        // Include CHECK expressions so a lookalike schema with weakened checks is not silently adopted.
        try(var statement=connection.createStatement();var rows=statement.executeQuery(
                "SELECT t.table_name,c.check_clause FROM information_schema.table_constraints t JOIN information_schema.check_constraints c "
                +"ON t.constraint_schema=c.constraint_schema AND t.constraint_name=c.constraint_name WHERE t.table_schema='public'")) {
            while(rows.next()) result.add("CHECK:"+rows.getString(1)+":"+rows.getString(2));
        }
        return result;
    }
}
