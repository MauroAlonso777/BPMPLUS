package bpmplus.migration

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

import javax.sql.DataSource

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.Database
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor

import bpmplus.multitenancy.MySqlSchemaHandler

/**
 * Aplica, etiqueta y revierte las migraciones de Liquibase sobre el schema maestro y sobre el
 * de cada tenant.
 *
 * No depende de Spring ni de Grails a proposito: la usan tanto el arranque de la aplicacion
 * (SchemaMigrationService) como las tareas de deploy (MigrationRunner), que corren con la
 * aplicacion apagada.
 *
 * Hay dos changelogs porque hay dos esquemas: el maestro (identidad y registro de tenants) y
 * el que se replica dentro del schema de cada cliente. El de tenant se corre una vez por
 * tenant, con su propio DATABASECHANGELOG, para que cada uno lleve su historial por separado.
 */
@CompileStatic
@Slf4j
class SchemaMigrator {

    static final String MASTER_CHANGELOG = 'db/changelog/master/changelog.xml'
    static final String TENANT_CHANGELOG = 'db/changelog/tenant/changelog.xml'

    private final DataSource dataSource
    private final Contexts contexts
    private final MySqlSchemaHandler schemaHandler = new MySqlSchemaHandler()

    /**
     * @param context contexto de Liquibase, normalmente el nombre del entorno.
     *
     * Es obligatorio y no admite vacio: cuando no se pasa contexto, Liquibase corre TODOS los
     * changesets, incluidos los marcados como `context="development"`. Un default silencioso
     * significaria cargar datos de desarrollo en produccion.
     */
    SchemaMigrator(DataSource dataSource, String context) {
        if (!context?.trim()) {
            throw new IllegalArgumentException('Hay que indicar el contexto de Liquibase ' +
                    '(normalmente el entorno). Sin contexto, Liquibase aplica tambien los ' +
                    'changesets marcados para un entorno concreto.')
        }
        this.dataSource = dataSource
        this.contexts = new Contexts(context.trim())
    }

    String getContextNames() {
        contexts.toString()
    }

    // --- aplicar ---

    /**
     * Migra el schema maestro y despues el de TODOS los tenants del registro.
     *
     * @return los codigos de tenant migrados, en orden
     */
    List<String> migrateAll() {
        migrateMaster()
        List<String> codes = registeredTenantCodes()
        log.info('Tenants en el registro: {}', codes)
        codes.each { String code -> migrateTenant(code) }
        codes
    }

    /** Migra el schema maestro (el de la url del dataSource). */
    void migrateMaster() {
        onSchema(MASTER_CHANGELOG, null) { Liquibase liquibase ->
            liquibase.update(contexts, new LabelExpression())
        }
        log.info('Migraciones aplicadas sobre el schema maestro')
    }

    /** Crea el schema del tenant si falta y le aplica el changelog de tenant. */
    void migrateTenant(String schema) {
        createSchemaIfMissing(schema)
        onSchema(TENANT_CHANGELOG, schema) { Liquibase liquibase ->
            liquibase.update(contexts, new LabelExpression())
        }
        log.info('Migraciones aplicadas sobre el schema [{}]', schema)
    }

    // --- etiquetar ---

    /**
     * Etiqueta el estado actual del maestro y de cada tenant, para poder volver despues.
     * Se etiqueta antes de aplicar una tanda de cambios en un deploy.
     */
    List<String> tagAll(String tag) {
        requireTag(tag)
        onSchema(MASTER_CHANGELOG, null) { Liquibase liquibase -> liquibase.tag(tag) }
        List<String> codes = registeredTenantCodes()
        codes.each { String code ->
            onSchema(TENANT_CHANGELOG, code) { Liquibase liquibase -> liquibase.tag(tag) }
        }
        log.info('Etiqueta [{}] aplicada al maestro y a {} tenant(s)', tag, codes.size())
        codes
    }

    // --- revertir ---

    /**
     * Revierte el maestro y cada tenant hasta la etiqueta indicada.
     *
     * Los tenants se revierten primero y el maestro al final: revertir el maestro puede borrar
     * la tabla `tenant`, y sin ella ya no se sabe que schemas hay que recorrer. Por eso tambien
     * la lista se lee antes de tocar nada.
     */
    List<String> rollbackAll(String tag) {
        requireTag(tag)
        List<String> codes = registeredTenantCodes()
        log.info('Revirtiendo a la etiqueta [{}]: {} tenant(s) y despues el maestro', tag, codes.size())
        codes.each { String code ->
            onSchema(TENANT_CHANGELOG, code) { Liquibase liquibase ->
                liquibase.rollback(tag, contexts, new LabelExpression())
            }
        }
        onSchema(MASTER_CHANGELOG, null) { Liquibase liquibase ->
            liquibase.rollback(tag, contexts, new LabelExpression())
        }
        codes
    }

    // --- registro de tenants ---

    /**
     * Todos los tenants del registro, activos o no.
     *
     * 'active' decide si la aplicacion atiende al cliente, no si se mantiene su esquema: un
     * tenant desactivado cuyo schema se quedara atras seria imposible de reactivar sin una
     * migracion manual.
     */
    List<String> registeredTenantCodes() {
        List<String> codes = []
        Connection connection = dataSource.connection
        try {
            Statement statement = connection.createStatement()
            try {
                ResultSet rs = statement.executeQuery('SELECT code FROM tenant ORDER BY code')
                while (rs.next()) {
                    codes << rs.getString(1)
                }
            }
            finally {
                statement.close()
            }
        }
        finally {
            connection.close()
        }
        codes
    }

    // --- interno ---

    private static void requireTag(String tag) {
        if (!tag?.trim()) {
            throw new IllegalArgumentException('Hay que indicar una etiqueta.')
        }
    }

    private void createSchemaIfMissing(String schema) {
        Connection connection = dataSource.connection
        try {
            // CREATE SCHEMA IF NOT EXISTS, con el identificador validado por el handler.
            schemaHandler.createSchema(connection, schema)
        }
        finally {
            connection.close()
        }
    }

    private void onSchema(String changeLogPath, String schema, Closure<Void> action) {
        Connection connection = dataSource.connection
        Database database = null
        try {
            database = DatabaseFactory.instance.findCorrectDatabaseImplementation(new JdbcConnection(connection))
            if (schema) {
                // Las tablas y el propio DATABASECHANGELOG van dentro del schema del tenant.
                database.defaultSchemaName = schema
                database.liquibaseSchemaName = schema
            }
            action.call(new Liquibase(changeLogPath, new ClassLoaderResourceAccessor(), database))
        }
        finally {
            // database.close() cierra tambien la conexion subyacente.
            if (database != null) {
                database.close()
            }
            else {
                connection.close()
            }
        }
    }
}
