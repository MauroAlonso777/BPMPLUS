package bpmplus.multitenancy

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

import com.zaxxer.hikari.HikariDataSource

import spock.lang.Specification

/**
 * En que schema queda una conexion cuando vuelve al pool.
 *
 * Es la comprobacion de un fallo que costo caro: iniciar sesion fallaba con
 * "Table 'acme.user' doesn't exist". La identidad vive en el schema maestro y no es multi-tenant,
 * pero la consulta caia en la base de un cliente — de forma intermitente, segun que conexion del
 * pool tocara.
 *
 * La causa: las conexiones se apuntan al schema del tenant en cada checkout, y volvian al pool
 * apuntando ahi. La siguiente consulta al maestro heredaba el schema del ultimo cliente que uso
 * esa conexion.
 *
 * El pool sabe restaurar el catalog al devolver la conexion, pero hacen falta las dos mitades:
 * que este configurado (dataSource.properties.catalog) y que el cambio pase por setCatalog, no
 * por un "USE" crudo. Los dos casos de abajo fijan exactamente eso.
 *
 * Necesita el MySQL local y la base bpmplus_test.
 */
class PoolCatalogRestoreSpec extends Specification {

    static final String MASTER = 'bpmplus_test'
    static final String TENANT = 'pool_tenant'
    static final String URL = "jdbc:mysql://localhost:3306/${MASTER}" +
            '?useUnicode=yes&characterEncoding=UTF-8&serverTimezone=UTC&nullCatalogMeansCurrent=true'

    HikariDataSource pool

    void setup() {
        pool = new HikariDataSource()
        pool.jdbcUrl = URL
        pool.username = 'root'
        pool.password = System.getenv('MYSQL_PASSWORD') ?: ''
        pool.driverClassName = 'com.mysql.cj.jdbc.Driver'
        // El schema maestro. Es lo que el pool restaura al devolver una conexion.
        pool.catalog = MASTER
        // Una sola conexion: asi la que se devuelve es, con seguridad, la que se vuelve a sacar.
        // Sin esto la prueba pasaria por casualidad al tocarle una conexion limpia.
        pool.maximumPoolSize = 1

        pool.connection.withCloseable { Connection c ->
            c.createStatement().withCloseable { Statement s ->
                s.execute("CREATE SCHEMA IF NOT EXISTS `${TENANT}`")
            }
        }
    }

    void cleanup() {
        pool?.connection?.withCloseable { Connection c ->
            c.createStatement().withCloseable { Statement s -> s.execute("DROP SCHEMA IF EXISTS `${TENANT}`") }
        }
        pool?.close()
    }

    void 'una conexion que uso un tenant vuelve al pool apuntando al maestro'() {
        given: 'se la apunta al schema del tenant, como en cada checkout'
        Connection primera = pool.connection
        new MySqlSchemaHandler().useSchema(primera, TENANT)

        expect: 'mientras se usa, esta en el tenant'
        primera.catalog == TENANT
        currentDatabase(primera) == TENANT

        when: 'vuelve al pool y se la vuelve a sacar'
        primera.close()
        Connection segunda = pool.connection

        then: 'esta en el maestro, no donde la dejo el tenant'
        segunda.catalog == MASTER
        currentDatabase(segunda) == MASTER

        cleanup:
        segunda?.close()
    }

    void 'un USE crudo deja la conexion sucia: por eso el handler ya no lo usa'() {
        given: 'lo que hacia MySqlSchemaHandler antes'
        Connection primera = pool.connection
        primera.createStatement().withCloseable { Statement s -> s.execute("USE `${TENANT}`") }

        expect: 'el driver no sigue un USE, asi que dice que sigue en el maestro'
        primera.catalog == MASTER

        when:
        primera.close()
        Connection segunda = pool.connection

        then: 'el pool tampoco se entero, asi que no restaura nada'
        segunda.catalog == MASTER

        and: 'pero la sesion del servidor quedo en el tenant: aca aterrizaba el login'
        currentDatabase(segunda) == TENANT

        cleanup:
        segunda?.close()
    }

    /**
     * GORM decide si tiene que crear el schema de un tenant PROBANDO a usarlo: llama a useSchema
     * y, si lanza, crea (HibernateDatastore.addTenantForSchemaInternal). O sea que useSchema tiene
     * que fallar cuando el schema no existe; si se tragara el error, GORM no crearia nada y el
     * fallo aparecería mucho despues, al construir el SessionFactory del tenant.
     */
    void 'useSchema falla si el schema no existe: es como GORM se entera'() {
        given:
        Connection c = pool.connection

        when:
        new MySqlSchemaHandler().useSchema(c, 'no_existe_jamas')

        then:
        thrown(java.sql.SQLException)

        cleanup:
        c?.close()
    }

    private static String currentDatabase(Connection connection) {
        connection.createStatement().withCloseable { Statement s ->
            ResultSet rs = s.executeQuery('SELECT DATABASE()')
            rs.next()
            rs.getString(1)
        }
    }
}
