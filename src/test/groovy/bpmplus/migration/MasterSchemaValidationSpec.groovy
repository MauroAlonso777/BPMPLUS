package bpmplus.migration

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

import grails.plugin.springsecurity.ui.RegistrationCode

import org.hibernate.dialect.MySQL8Dialect

import org.springframework.jdbc.datasource.DriverManagerDataSource

import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.orm.hibernate.HibernateDatastore

import bpmplus.security.Role
import bpmplus.security.User
import bpmplus.security.UserRole
import bpmplus.tenant.Tenant

import spock.lang.Specification

/**
 * Comprueba que el changelog maestro produzca exactamente el esquema que esperan los mapeos
 * de GORM.
 *
 * Es la version automatizada de la comprobacion en dos pasos: se construye el esquema solo
 * con Liquibase sobre una base vacia y despues se le pide a Hibernate que lo valide. Si un
 * changeset se desvia de la domain class (un tipo distinto, una columna que falta, un
 * nullable que no coincide), este test falla.
 *
 * Es lo que detecto que el BOOLEAN de Liquibase genera TINYINT en MySQL mientras Hibernate
 * espera BIT(1). Sin esta comprobacion, esa deriva aparece recien en produccion.
 *
 * Usa un schema propio y descartable para no depender del estado de ninguna otra base.
 */
class MasterSchemaValidationSpec extends Specification {

    static final String SCHEMA = 'bpmplus_schemacheck'
    static final String BASE_URL = 'jdbc:mysql://localhost:3306/'
    static final String URL_OPTIONS = '?useUnicode=yes&characterEncoding=UTF-8&serverTimezone=UTC'

    void setup() {
        onServer { Statement s ->
            s.execute("DROP SCHEMA IF EXISTS `${SCHEMA}`")
            s.execute("CREATE SCHEMA `${SCHEMA}` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
        }
    }

    void cleanup() {
        onServer { Statement s -> s.execute("DROP SCHEMA IF EXISTS `${SCHEMA}`") }
    }

    void 'el changelog maestro produce el esquema que esperan los mapeos de GORM'() {
        given: 'una base vacia construida unicamente por Liquibase'
        new SchemaMigrator(dataSource(), 'test').migrateMaster()

        when: 'Hibernate valida ese esquema contra las domain classes'
        HibernateDatastore datastore = new HibernateDatastore(
                DatastoreUtils.createPropertyResolver(validatingConfig()),
                Tenant, Role, User, UserRole, RegistrationCode)

        then: 'no hay ninguna diferencia'
        noExceptionThrown()

        cleanup:
        datastore?.close()
    }

    void 'sobre una base vacia la validacion falla, para probar que el test no es vacuo'() {
        when: 'se valida sin haber corrido las migraciones'
        new HibernateDatastore(
                DatastoreUtils.createPropertyResolver(validatingConfig()),
                Tenant, Role, User, UserRole, RegistrationCode)

        then: 'Hibernate se queja de las tablas que faltan'
        thrown(Exception)
    }

    private static Map validatingConfig() {
        [
                'dataSource.url'            : BASE_URL + SCHEMA + URL_OPTIONS,
                'dataSource.driverClassName': 'com.mysql.cj.jdbc.Driver',
                'dataSource.username'       : 'root',
                'dataSource.password'       : mysqlPassword(),
                'dataSource.dbCreate'       : 'validate',
                'dataSource.dialect'        : MySQL8Dialect,
        ]
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                BASE_URL + SCHEMA + URL_OPTIONS, 'root', mysqlPassword())
        ds.driverClassName = 'com.mysql.cj.jdbc.Driver'
        ds
    }

    private static String mysqlPassword() {
        System.getenv('MYSQL_PASSWORD') ?: ''
    }

    private static void onServer(Closure work) {
        Connection connection = DriverManager.getConnection(BASE_URL + URL_OPTIONS, 'root', mysqlPassword())
        try {
            Statement statement = connection.createStatement()
            try {
                work(statement)
            }
            finally {
                statement.close()
            }
        }
        finally {
            connection.close()
        }
    }
}
