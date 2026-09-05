package bpmplus.multitenancy

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.regex.Pattern

import javax.sql.DataSource

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import org.grails.datastore.gorm.jdbc.schema.SchemaHandler

/**
 * SchemaHandler para MySQL, requerido por el modo de multi-tenancy SCHEMA de GORM.
 *
 * El DefaultSchemaHandler que trae GORM (grails-datamapping-core) emite "SET SCHEMA x",
 * resuelve los schemas con DatabaseMetaData.getSchemas() y asume "PUBLIC" como schema por
 * defecto: los tres supuestos son de H2/PostgreSQL. En MySQL el cambio de base es "USE x"
 * y las bases se exponen como catalogs, no como schemas (getSchemas() devuelve vacio con
 * el databaseTerm=CATALOG por defecto de Connector/J).
 *
 * Se configura en application.yml con dataSource.schemaHandler.
 */
@CompileStatic
@Slf4j
class MySqlSchemaHandler implements SchemaHandler {

    /** Schemas internos de MySQL: nunca son tenants. */
    static final Set<String> SYSTEM_SCHEMAS = ['information_schema', 'mysql', 'performance_schema', 'sys'].toSet()

    /**
     * El nombre del schema llega desde el TenantResolver, que en un request HTTP lo toma de
     * una cabecera. Como "USE" no admite parametros preparados, el identificador se valida
     * contra esta expresion antes de interpolarse. Es la defensa contra inyeccion a nivel
     * de handler, independiente de la validacion contra el registro de tenants.
     */
    private static final Pattern VALID_SCHEMA_NAME = ~/^[A-Za-z][A-Za-z0-9_]{0,62}$/

    /**
     * Schema de la conexion base (donde viven las tablas no multi-tenant, incluido el
     * registro de tenants). GORM instancia este handler por reflexion con el constructor
     * sin argumentos, asi que no se puede inyectar: se captura de la primera conexion que
     * pasa por aca, que GORM entrega recien sacada del pool y todavia sin cambiar de schema.
     * Se puede fijar explicitamente con setDefaultSchema() si se prefiere no depender de eso.
     */
    private static volatile String defaultSchema

    static void setDefaultSchema(String schema) {
        defaultSchema = schema
    }

    static String getDefaultSchema() {
        defaultSchema
    }

    @Override
    void useSchema(Connection connection, String name) {
        rememberDefaultSchema(connection)
        pointAt(connection, name)
    }

    @Override
    void useDefaultSchema(Connection connection) {
        String schema = defaultSchema
        if (!schema) {
            // Todavia no se vio ninguna conexion sin cambiar de schema; dejarla como esta es
            // preferible a mandarla a un schema adivinado.
            log.debug('No hay schema por defecto registrado; la conexion queda en su schema actual')
            return
        }
        pointAt(connection, schema)
    }

    /**
     * Apunta la conexion a un schema.
     *
     * Con setCatalog y no con "USE", que es lo que se hacia antes. La diferencia no es de
     * sintaxis sino de quien se entera:
     *
     * - El POOL. HikariCP restaura el catalog al devolver la conexion, pero solo si el cambio
     *   paso por setCatalog; un "USE" crudo no marca nada y la conexion vuelve al pool apuntando
     *   al schema del ultimo tenant que la uso. La siguiente consulta a una tabla NO multi-tenant
     *   —la identidad, sin ir mas lejos— aterriza ahi. Se veia como
     *   "Table 'acme.user' doesn't exist" al iniciar sesion, y de forma intermitente, porque
     *   depende de que conexion del pool toque.
     *   Requiere que el pool tenga configurado su catalog; ver dataSource.properties.catalog.
     * - El DRIVER. Connection.getCatalog() no sigue un "USE", asi que la lectura de metadatos
     *   (con nullCatalogMeansCurrent=true) se resuelve contra la base equivocada.
     */
    private static void pointAt(Connection connection, String name) {
        // Primero el USE. No es redundante: obliga a que el cambio ocurra AHORA y falle si el
        // schema no existe. El DataSource que entrega GORM es un LazyConnectionDataSourceProxy,
        // donde setCatalog solo se anota y se aplica cuando alguien ejecuta algo; apuntar con
        // setCatalog a un schema inexistente no lanza nada.
        //
        // Y eso importa mucho mas de lo que parece: GORM decide si tiene que CREAR el schema de
        // un tenant probando a usarlo, y creandolo si useSchema lanza
        // (HibernateDatastore.addTenantForSchemaInternal). Sin excepcion no crea nada, y el
        // fallo aparece mucho despues, al construir el SessionFactory del tenant, como
        // "Unknown database".
        execute(connection, "USE ${quoteIdentifier(name)}")

        // Y despues setCatalog, que es lo que ven el driver y el pool. Cuesta un viaje mas, pero
        // sin esto la conexion vuelve al pool apuntando al tenant (el pool no se entera de un USE)
        // y la lectura de metadatos se resuelve contra la base equivocada (getCatalog() tampoco
        // sigue un USE).
        connection.catalog = name
    }

    @Override
    void createSchema(Connection connection, String name) {
        execute(connection, "CREATE SCHEMA IF NOT EXISTS ${quoteIdentifier(name)} " +
                'DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
    }

    @Override
    Collection<String> resolveSchemaNames(DataSource dataSource) {
        Collection<String> schemaNames = []
        Connection connection = null
        try {
            connection = dataSource.connection
            rememberDefaultSchema(connection)
            ResultSet catalogs = connection.metaData.catalogs
            while (catalogs.next()) {
                String name = catalogs.getString('TABLE_CAT')
                if (name && !SYSTEM_SCHEMAS.contains(name.toLowerCase())) {
                    schemaNames << name
                }
            }
        }
        finally {
            try {
                connection?.close()
            }
            catch (Throwable e) {
                log.debug("Error cerrando la conexion JDBC: ${e.message}", e)
            }
        }
        schemaNames
    }

    private static void rememberDefaultSchema(Connection connection) {
        if (defaultSchema != null) {
            return
        }
        try {
            String catalog = connection.catalog
            if (catalog) {
                defaultSchema = catalog
                log.info('Schema por defecto detectado: {}', catalog)
            }
        }
        catch (SQLException e) {
            log.debug("No se pudo leer el catalog de la conexion: ${e.message}", e)
        }
    }

    private static void execute(Connection connection, String sql) {
        log.debug('Ejecutando sentencia de schema: {}', sql)
        Statement statement = connection.createStatement()
        try {
            statement.execute(sql)
        }
        finally {
            statement.close()
        }
    }

    /**
     * Valida el identificador y lo entrecomilla. Es publico porque tambien lo usa
     * bpmplus.flowable.TenantSchemaDataSource, que emite su propio USE sin pasar por
     * useSchema(): ese metodo, de paso, recuerda el schema por defecto a partir de la
     * conexion, y una conexion del pool que ya venia apuntando a un tenant lo dejaria mal.
     */
    static String quoteIdentifier(String name) {
        if (!name || !VALID_SCHEMA_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Nombre de schema invalido: [${name}]. Debe empezar con letra y contener " +
                    'solo letras, digitos o guion bajo (maximo 63 caracteres).')
        }
        "`${name}`"
    }
}
