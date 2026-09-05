package bpmplus.migration

import javax.sql.DataSource

import groovy.transform.CompileStatic

import grails.util.Environment

/**
 * Envoltorio de Grails sobre SchemaMigrator, para poder inyectarlo donde haga falta.
 *
 * El contexto de Liquibase es el entorno de Grails, de modo que un changeset marcado
 * context="development" solo se aplique corriendo en desarrollo.
 *
 * No es transaccional: Liquibase administra su propia conexion, su lock y sus commits.
 */
@CompileStatic
class SchemaMigrationService {

    static transactional = false

    DataSource dataSource

    void migrateMaster() {
        migrator().migrateMaster()
    }

    void migrateTenant(String schema) {
        migrator().migrateTenant(schema)
    }

    private SchemaMigrator migrator() {
        new SchemaMigrator(dataSource, Environment.current.name)
    }
}
