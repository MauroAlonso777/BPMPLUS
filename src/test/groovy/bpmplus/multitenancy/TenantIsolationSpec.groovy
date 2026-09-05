package bpmplus.multitenancy

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

import grails.gorm.MultiTenant
import grails.gorm.annotation.Entity

import org.hibernate.dialect.MySQL8Dialect

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletWebRequest

import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.multitenancy.exceptions.TenantNotFoundException
import org.grails.orm.hibernate.HibernateDatastore

import bpmplus.security.TenantAwareUser

import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Prueba de aislamiento entre tenants contra MySQL de verdad.
 *
 * Los specs unitarios cubren a que tenant *decide* apuntar el resolver. Este cubre lo que
 * de verdad importa: que esa decision termine en el schema correcto y que un usuario de un
 * cliente no pueda leer los datos de otro.
 *
 * Necesita el MySQL local y la base bpmplus_test, igual que el entorno test.
 */
class TenantIsolationSpec extends Specification {

    static final String ACME = 'it_acme'
    static final String GLOBEX = 'it_globex'
    static final String URL = 'jdbc:mysql://localhost:3306/bpmplus_test' +
            '?useUnicode=yes&characterEncoding=UTF-8&serverTimezone=UTC&nullCatalogMeansCurrent=true'

    @Shared
    @AutoCleanup
    HibernateDatastore datastore

    void setupSpec() {
        Map config = [
                'grails.gorm.multiTenancy.mode'               : 'SCHEMA',
                'grails.gorm.multiTenancy.tenantResolverClass': TenantRegistryResolver,
                'dataSource.url'                              : URL,
                'dataSource.driverClassName'                  : 'com.mysql.cj.jdbc.Driver',
                'dataSource.username'                         : 'root',
                'dataSource.password'                         : mysqlPassword(),
                'dataSource.dbCreate'                         : 'update',
                'dataSource.dialect'                          : MySQL8Dialect,
                'dataSource.schemaHandler'                    : MySqlSchemaHandler,
        ]
        datastore = new HibernateDatastore(DatastoreUtils.createPropertyResolver(config), Factura)

        // Hay que dar de alta un tenant por vez: addTenantForSchema vuelve a registrar las
        // entidades en el enhancer, que pide resolveTenantIds() y exige que exista el datastore
        // de cada id devuelto. Registrar los dos antes de dar de alta el primero falla con
        // "DataSource not found for name [...]". Es el mismo orden que usa TenantProvisioningService.
        [ACME, GLOBEX].each { String code ->
            TenantRegistryResolver.register(code)
            datastore.addTenantForSchema(code)
        }

        // GORM no crea las tablas dentro del schema de cada tenant: en addTenantForSchemaInternal
        // guarda Environment.DEFAULT_SCHEMA ("hibernate.default_schema") dentro del mapa
        // HibernateSettings, y toProperties() vuelve a prefijar todas sus claves con "hibernate.",
        // con lo que Hibernate recibe "hibernate.hibernate.default_schema" y la ignora. El
        // aislamiento en runtime igual funciona, porque lo da el USE <schema> por conexion.
        // Las tablas del tenant las crea la herramienta de migraciones; aca se replican.
        sql { Statement s ->
            [ACME, GLOBEX].each {
                s.execute("CREATE TABLE IF NOT EXISTS `${it}`.factura LIKE `bpmplus_test`.factura")
                s.execute("DELETE FROM `${it}`.factura")
            }
            s.execute("INSERT INTO `${ACME}`.factura (id, version, numero) VALUES (1, 0, 'A-1')")
            s.execute("INSERT INTO `${GLOBEX}`.factura (id, version, numero) VALUES (1, 0, 'G-1')")
        }
    }

    void cleanupSpec() {
        [ACME, GLOBEX].each { TenantRegistryResolver.unregister(it) }
        sql { Statement s -> [ACME, GLOBEX].each { s.execute("DROP SCHEMA IF EXISTS `${it}`") } }
    }

    void cleanup() {
        SecurityContextHolder.clearContext()
        RequestContextHolder.resetRequestAttributes()
    }

    void 'cada usuario lee solo el schema de su tenant'() {
        when:
        asUserOf(ACME)
        List<String> deAcme = Factura.withTransaction { Factura.list()*.numero }

        and:
        asUserOf(GLOBEX)
        List<String> deGlobex = Factura.withTransaction { Factura.list()*.numero }

        then: 'cada uno ve lo suyo y nada del otro'
        deAcme.contains('A-1')
        !deAcme.contains('G-1')
        deGlobex.contains('G-1')
        !deGlobex.contains('A-1')
    }

    void 'lo que escribe un usuario cae en el schema de su tenant'() {
        given:
        asUserOf(ACME)

        when:
        Factura.withTransaction { new Factura(numero: 'A-nueva').save(flush: true) }

        then: 'la fila esta en acme y no en globex, comprobado por JDBC crudo'
        rowsIn(ACME).contains('A-nueva')
        !rowsIn(GLOBEX).contains('A-nueva')
    }

    void 'un usuario de acme no alcanza los datos de globex con la cabecera'() {
        given:
        asUserOf(ACME, GLOBEX)

        when:
        Factura.withTransaction { Factura.list() }

        then:
        thrown(TenantNotFoundException)
    }

    void 'una cuenta de plataforma llega al schema que indica la cabecera'() {
        given:
        asPlatformAdmin(GLOBEX)

        expect:
        Factura.withTransaction { Factura.list()*.numero } == ['G-1']
    }

    void 'sin autenticacion no se lee ningun tenant'() {
        given:
        SecurityContextHolder.clearContext()
        incomingRequest(ACME)

        when:
        Factura.withTransaction { Factura.list() }

        then:
        thrown(TenantNotFoundException)
    }

    // --- helpers ---

    private static String mysqlPassword() {
        System.getenv('MYSQL_PASSWORD') ?: ''
    }

    private static void asUserOf(String tenantCode, String header = null) {
        authenticate(tenantCode, ['ROLE_USER'])
        incomingRequest(header)
    }

    private static void asPlatformAdmin(String header) {
        authenticate(null, ['ROLE_ADMIN', TenantRegistryResolver.PLATFORM_ADMIN_ROLE])
        incomingRequest(header)
    }

    private static void authenticate(String tenantCode, List<String> roles) {
        List<GrantedAuthority> authorities = roles.collect { new SimpleGrantedAuthority(it) as GrantedAuthority }
        TenantAwareUser principal = new TenantAwareUser('alguien', 'secreto', true, true, true, true,
                authorities, 1L, tenantCode)
        SecurityContextHolder.context.authentication =
                new UsernamePasswordAuthenticationToken(principal, 'secreto', authorities)
    }

    private static void incomingRequest(String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest()
        if (headerValue != null) {
            request.addHeader(TenantRegistryResolver.HEADER_NAME, headerValue)
        }
        RequestContextHolder.requestAttributes = new ServletWebRequest(request)
    }

    /** Lee las filas por JDBC crudo, sin pasar por GORM, para comprobar donde quedaron. */
    private static List<String> rowsIn(String schema) {
        List<String> numeros = []
        sql { Statement s ->
            ResultSet rs = s.executeQuery("SELECT numero FROM `${schema}`.factura ORDER BY numero")
            while (rs.next()) {
                numeros << rs.getString(1)
            }
        }
        numeros
    }

    private static void sql(Closure work) {
        Connection connection = DriverManager.getConnection(URL, 'root', mysqlPassword())
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

@Entity
class Factura implements MultiTenant<Factura> {

    String numero

    static constraints = {
        numero blank: false, maxSize: 20
    }
}
