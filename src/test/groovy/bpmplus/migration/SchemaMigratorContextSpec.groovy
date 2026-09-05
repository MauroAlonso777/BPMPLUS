package bpmplus.migration

import javax.sql.DataSource

import spock.lang.Specification

class SchemaMigratorContextSpec extends Specification {

    DataSource dataSource = Mock(DataSource)

    void 'exige un contexto de Liquibase'() {
        when: 'se construye sin contexto'
        new SchemaMigrator(dataSource, context)

        then: 'se rechaza, porque sin contexto Liquibase aplica todos los changesets'
        thrown(IllegalArgumentException)

        and: 'ni siquiera se pidio una conexion'
        0 * dataSource.getConnection()

        where:
        context << [null, '', '   ']
    }

    void 'acepta el nombre del entorno como contexto'() {
        expect:
        new SchemaMigrator(dataSource, 'production').contextNames.contains('production')
    }

    void 'tag y rollback exigen una etiqueta'() {
        given:
        SchemaMigrator migrator = new SchemaMigrator(dataSource, 'test')

        when:
        operation.call(migrator)

        then:
        thrown(IllegalArgumentException)

        where:
        operation << [{ SchemaMigrator m -> m.tagAll(null) },
                      { SchemaMigrator m -> m.tagAll('  ') },
                      { SchemaMigrator m -> m.rollbackAll(null) }]
    }
}
