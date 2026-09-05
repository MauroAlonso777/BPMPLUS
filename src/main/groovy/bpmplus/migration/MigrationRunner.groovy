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

        String host = value('MYSQL_HOST', 'localhost')
        String port = value('MYSQL_PORT', '3306')
        String database = value('MYSQL_DATABASE', 'bpmplus')
        String user = value('MYSQL_USER', 'root')
        String password = value('MYSQL_PASSWORD', '')

        // El contexto decide que changesets aplican. Se toma del entorno para que el pipeline
        // de deploy no pueda cargar en produccion los changesets de desarrollo.
        String context = value('LIQUIBASE_CONTEXTS', null) ?: value('GRAILS_ENV', 'development')

        String url = "jdbc:mysql://${host}:${port}/${database}" +
                '?useUnicode=yes&characterEncoding=UTF-8&serverTimezone=UTC'

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

    /** Permite -D para pruebas puntuales, con la variable de entorno como fuente normal. */
    private static String value(String name, String fallback) {
        System.getProperty(name) ?: System.getenv(name) ?: fallback
    }
}
