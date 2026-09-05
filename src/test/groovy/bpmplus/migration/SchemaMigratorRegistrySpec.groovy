package bpmplus.migration

import java.sql.Connection
import java.sql.Statement

import com.zaxxer.hikari.HikariDataSource

import spock.lang.Specification

/**
 * Leer el registro de tenants cuando el schema maestro todavia no se migro.
 *
 * Es el primer despliegue: el runbook dice etiquetar y despues migrar, y etiquetar recorre los
 * tenants del registro. Pero en ese momento la tabla `tenant` no existe todavia, asi que
 * `./gradlew dbTag` fallaba con "Table 'tenant' doesn't exist" justo en el unico despliegue en
 * que no hay nada a que volver.
 *
 * Necesita el MySQL local.
 */
class SchemaMigratorRegistrySpec extends Specification {

    static final String VACIA = 'reg_vacia'

    HikariDataSource pool

    void setup() {
        crear()
        pool = new HikariDataSource()
        pool.jdbcUrl = "jdbc:mysql://localhost:3306/${VACIA}" +
                '?useUnicode=yes&characterEncoding=UTF-8&serverTimezone=UTC&nullCatalogMeansCurrent=true'
        pool.username = 'root'
        pool.password = clave()
        pool.driverClassName = 'com.mysql.cj.jdbc.Driver'
        pool.catalog = VACIA
    }

    void cleanup() {
        pool?.close()
        sobreElServidor { Statement s -> s.execute("DROP SCHEMA IF EXISTS `${VACIA}`") }
    }

    void 'una base sin migrar no tiene tenants, y no es un error'() {
        expect: 'es el estado del primer despliegue, antes de la primera migracion'
        new SchemaMigrator(pool, 'production').registeredTenantCodes() == []
    }

    void 'con el registro creado devuelve los codigos, ordenados'() {
        given:
        sobre(VACIA) { Statement s ->
            s.execute('CREATE TABLE tenant (id BIGINT PRIMARY KEY AUTO_INCREMENT, code VARCHAR(63))')
            s.execute("INSERT INTO tenant (code) VALUES ('globex'), ('acme')")
        }

        expect:
        new SchemaMigrator(pool, 'production').registeredTenantCodes() == ['acme', 'globex']
    }

    void 'un registro creado pero vacio tampoco es un error'() {
        given: 'la tabla existe y no hay clientes todavia'
        sobre(VACIA) { Statement s ->
            s.execute('CREATE TABLE tenant (id BIGINT PRIMARY KEY AUTO_INCREMENT, code VARCHAR(63))')
        }

        expect:
        new SchemaMigrator(pool, 'production').registeredTenantCodes() == []
    }

    // --- helpers ---

    private static String clave() {
        System.getenv('MYSQL_PASSWORD') ?: ''
    }

    private void crear() {
        sobreElServidor { Statement s ->
            s.execute("DROP SCHEMA IF EXISTS `${VACIA}`")
            s.execute("CREATE SCHEMA `${VACIA}`")
        }
    }

    private void sobre(String schema, Closure work) {
        pool.connection.withCloseable { Connection c ->
            c.createStatement().withCloseable { Statement s -> work(s) }
        }
    }

    private static void sobreElServidor(Closure work) {
        Connection c = java.sql.DriverManager.getConnection(
                'jdbc:mysql://localhost:3306/?serverTimezone=UTC', 'root', clave())
        try {
            c.createStatement().withCloseable { Statement s -> work(s) }
        }
        finally {
            c.close()
        }
    }
}
