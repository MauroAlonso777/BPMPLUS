package bpmplus.migration

import spock.lang.Specification

/**
 * A que base apunta un deploy.
 *
 * Lo que se cuida acá es que un deploy a produccion al que le falten variables **pare**, en vez
 * de irse a localhost/bpmplus y migrar la base de desarrollo aplicando los changesets de
 * produccion, informando que todo salio bien. La misma regla que ya tenia application.yml, donde
 * el bloque de produccion no le pone fallback ni a MYSQL_HOST ni a MYSQL_DATABASE.
 */
class MigrationRunnerTargetSpec extends Specification {

    static final List<String> PROPIEDADES =
            ['MYSQL_HOST', 'MYSQL_PORT', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD']

    void cleanup() {
        PROPIEDADES.each { System.clearProperty(it) }
    }

    void 'en desarrollo apunta al MySQL local sin que haya que decir nada'() {
        when:
        Map<String, String> destino = MigrationRunner.connectionSettings('development')

        then:
        destino.host == 'localhost'
        destino.database == 'bpmplus'
        destino.port == '3306'
    }

    void 'en produccion sin MYSQL_HOST ni MYSQL_DATABASE falla'() {
        when:
        MigrationRunner.connectionSettings('production')

        then:
        IllegalStateException e = thrown()
        e.message.contains('MYSQL_HOST')
        e.message.contains('MYSQL_DATABASE')
    }

    void 'en produccion falta #variable y tambien falla'() {
        given:
        definidas.each { String k, String v -> System.setProperty(k, v) }

        when:
        MigrationRunner.connectionSettings('production')

        then:
        IllegalStateException e = thrown()
        e.message.contains(variable)

        where:
        variable         | definidas
        'MYSQL_DATABASE' | ['MYSQL_HOST': 'db.interno']
        'MYSQL_HOST'     | ['MYSQL_DATABASE': 'bpmplus_prod']
    }

    void 'en produccion con las dos variables apunta a donde le dicen'() {
        given:
        System.setProperty('MYSQL_HOST', 'db.interno')
        System.setProperty('MYSQL_DATABASE', 'bpmplus_prod')
        System.setProperty('MYSQL_PORT', '3307')

        when:
        Map<String, String> destino = MigrationRunner.connectionSettings('production')

        then:
        destino.host == 'db.interno'
        destino.database == 'bpmplus_prod'
        destino.port == '3307'
    }

    void 'un host en blanco no cuenta como definido'() {
        given: 'una variable exportada vacia es un error de pipeline tan comun como no exportarla'
        System.setProperty('MYSQL_HOST', '   ')
        System.setProperty('MYSQL_DATABASE', 'bpmplus_prod')

        when:
        MigrationRunner.connectionSettings('production')

        then:
        IllegalStateException e = thrown()
        e.message.contains('MYSQL_HOST')
    }
}
