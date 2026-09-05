package bpmplus.multitenancy

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.Statement

import javax.sql.DataSource

import spock.lang.Specification

class MySqlSchemaHandlerSpec extends Specification {

    MySqlSchemaHandler handler = new MySqlSchemaHandler()

    Connection connection = Mock(Connection)
    Statement statement = Mock(Statement)

    def setup() {
        MySqlSchemaHandler.defaultSchema = null
    }

    def cleanup() {
        MySqlSchemaHandler.defaultSchema = null
    }

    void 'useSchema emite USE con el identificador entre backticks'() {
        when:
        handler.useSchema(connection, 'acme')

        then:
        1 * connection.getCatalog() >> 'bpmplus'
        1 * connection.createStatement() >> statement
        1 * statement.execute('USE `acme`')
        1 * statement.close()
    }

    void 'useSchema captura el schema por defecto de la primera conexion que ve'() {
        when:
        handler.useSchema(connection, 'acme')

        then:
        1 * connection.getCatalog() >> 'bpmplus'
        1 * connection.createStatement() >> statement

        and:
        MySqlSchemaHandler.defaultSchema == 'bpmplus'
    }

    void 'createSchema crea la base con utf8mb4 si no existe'() {
        when:
        handler.createSchema(connection, 'acme')

        then:
        1 * connection.createStatement() >> statement
        1 * statement.execute('CREATE SCHEMA IF NOT EXISTS `acme` ' +
                'DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
        1 * statement.close()
    }

    void 'useDefaultSchema vuelve al schema capturado'() {
        given:
        MySqlSchemaHandler.defaultSchema = 'bpmplus'

        when:
        handler.useDefaultSchema(connection)

        then:
        1 * connection.createStatement() >> statement
        1 * statement.execute('USE `bpmplus`')
    }

    void 'useDefaultSchema no toca la conexion si todavia no se capturo el schema por defecto'() {
        when:
        handler.useDefaultSchema(connection)

        then:
        0 * connection.createStatement()
    }

    void 'rechaza nombres de schema que no son identificadores simples'() {
        when:
        handler.useSchema(connection, name)

        then:
        thrown(IllegalArgumentException)

        and: 'no llega ninguna sentencia a la base'
        0 * connection.createStatement()

        where:
        name << ['acme; DROP DATABASE bpmplus', 'acme`', 'acme-1', 'acme.otro', '1acme',
                 'acme otro', '', 'a' * 64]
    }

    void 'createSchema tambien rechaza identificadores invalidos'() {
        when:
        handler.createSchema(connection, 'acme`; DROP DATABASE bpmplus; -- ')

        then:
        thrown(IllegalArgumentException)
        0 * connection.createStatement()
    }

    void 'resolveSchemaNames lee los catalogs de MySQL y descarta los del sistema'() {
        given:
        DataSource dataSource = Mock(DataSource)
        DatabaseMetaData metaData = Mock(DatabaseMetaData)
        ResultSet catalogs = Mock(ResultSet)

        when:
        Collection<String> names = handler.resolveSchemaNames(dataSource)

        then:
        1 * dataSource.getConnection() >> connection
        1 * connection.getCatalog() >> 'bpmplus'
        1 * connection.getMetaData() >> metaData
        1 * metaData.getCatalogs() >> catalogs
        6 * catalogs.next() >>> [true, true, true, true, true, false]
        5 * catalogs.getString('TABLE_CAT') >>> ['information_schema', 'mysql', 'sys', 'bpmplus', 'acme']
        1 * connection.close()

        and:
        names == ['bpmplus', 'acme']
    }
}
