package bpmplus.migration

import groovy.transform.CompileStatic

import org.springframework.jdbc.datasource.DriverManagerDataSource

/**
 * Punto de entrada para operar las migraciones como paso de deploy, con la aplicacion apagada.
 *
 * Comandos: migrate | tag <etiqueta> | rollback <etiqueta>
 * Todos recorren el schema maestro y TODOS los tenants del registro.
 *
 * La conexion sale de las mismas variables de entorno que usa application.yml, para que el
 * paso de deploy y la aplicacion no puedan apuntar a bases distintas por descuido.
 */
@CompileStatic
class MigrationRunner {

    static void main(String[] args) {
        String command = args.length > 0 ? args[0] : 'migrate'
        String tag = args.length > 1 ? args[1] : null

        // El contexto decide que changesets aplican. Se toma del entorno para que el pipeline
        // de deploy no pueda cargar en produccion los changesets de desarrollo.
        String context = value('LIQUIBASE_CONTEXTS', null) ?: value('GRAILS_ENV', 'development')

        Map<String, String> target
        try {
            target = connectionSettings(context)
        }
        catch (IllegalStateException e) {
            System.err.println(e.message)
            System.exit(2)
            return
        }

        String host = target.host
        String port = target.port
        String database = target.database
        String user = target.user
        String password = target.password

        String url = "jdbc:mysql://${host}:${port}/${database}" +
                '?useUnicode=yes&characterEncoding=UTF-8&serverTimezone=UTC&nullCatalogMeansCurrent=true'

        println "Comando : ${command}${tag ? ' ' + tag : ''}"
        println "Destino : ${user}@${host}:${port}/${database}"
        println "Contexto: ${context}"

        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password)
        dataSource.driverClassName = 'com.mysql.cj.jdbc.Driver'

        try {
            SchemaMigrator migrator = new SchemaMigrator(dataSource, context)
            List<String> tenants
            switch (command) {
                case 'migrate':
                    tenants = migrator.migrateAll()
                    break
                case 'tag':
                    tenants = migrator.tagAll(tag)
                    break
                case 'rollback':
                    tenants = migrator.rollbackAll(tag)
                    break
                default:
                    System.err.println("Comando desconocido: ${command}. Use migrate | tag <etiqueta> | rollback <etiqueta>")
                    System.exit(2)
                    return
            }
            println "Schema maestro: ${database}"
            println tenants ? "Schemas de tenant (${tenants.size()}): ${tenants.join(', ')}"
                            : 'No hay tenants en el registro todavia'
            println 'Listo'
        }
        catch (Exception e) {
            System.err.println("Fallo el comando [${command}]: ${e.message}")
            e.printStackTrace()
            System.exit(1)
        }
    }

    /**
     * A que base apunta la migracion.
     *
     * En produccion NO hay valores por defecto para el host ni para la base. En cualquier otro
     * entorno apunta al MySQL local, que es lo comodo para desarrollar.
     *
     * La asimetria es a proposito y es la misma que tiene application.yml, donde el bloque de
     * produccion usa ${MYSQL_HOST} y ${MYSQL_DATABASE} sin fallback. Sin esto las dos mitades no
     * coincidian: un deploy que se olvidara de exportar las variables no fallaba, se iba a
     * localhost/bpmplus y migraba la base de desarrollo aplicando los changesets de produccion,
     * informando que todo salio bien. Un deploy a produccion que no encuentra produccion tiene
     * que parar, no elegir otra base.
     */
    static Map<String, String> connectionSettings(String context) {
        boolean production = context?.trim() == 'production'

        String host = value('MYSQL_HOST', production ? null : 'localhost')
        String database = value('MYSQL_DATABASE', production ? null : 'bpmplus')

        List<String> faltantes = []
        if (!host?.trim()) {
            faltantes << 'MYSQL_HOST'
        }
        if (!database?.trim()) {
            faltantes << 'MYSQL_DATABASE'
        }
        if (faltantes) {
            throw new IllegalStateException(
                    "Faltan variables de conexion para el contexto [${context}]: ${faltantes.join(', ')}. " +
                    'En produccion no se asume ningun destino: hay que indicarlo explicitamente, ' +
                    'las mismas variables que usa application.yml.')
        }

        [host    : host,
         port    : value('MYSQL_PORT', '3306'),
         database: database,
         user    : value('MYSQL_USER', 'root'),
         password: value('MYSQL_PASSWORD', '')]
    }

    /** Permite -D para pruebas puntuales, con la variable de entorno como fuente normal. */
    private static String value(String name, String fallback) {
        System.getProperty(name) ?: System.getenv(name) ?: fallback
    }
}
