package bpmplus.flowable

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

import javax.sql.DataSource

import grails.gorm.MultiTenant
import grails.gorm.annotation.Entity

import org.flowable.engine.ProcessEngine
import org.flowable.engine.repository.ProcessDefinition
import org.flowable.engine.runtime.ProcessInstance

import org.hibernate.dialect.MySQL8Dialect

import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
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

import bpmplus.migration.SchemaMigrator
import bpmplus.multitenancy.MySqlSchemaHandler
import bpmplus.multitenancy.TenantRegistryResolver
import bpmplus.security.TenantAwareUser

import spock.lang.Specification

/**
 * Spike de la Fase 0: comprueba contra MySQL de verdad que el motor Flowable respeta el
 * aislamiento por schema de BPMPLUS.
 *
 * Lo que se verifica, en este orden:
 *
 *  - que Flowable cree su propio esquema (tablas ACT_*) DENTRO de la base de cada tenant y no
 *    en la maestra;
 *  - que el tenant en curso lo resuelva el MISMO TenantResolver que usa el resto de la
 *    aplicacion, o sea a partir del usuario autenticado (Fase 1);
 *  - que un proceso arrancado bajo un tenant no se vea ni se pueda tocar desde el otro, que es
 *    el punto de todo esto: no alcanza con que las tablas existan por separado.
 *
 * Necesita el MySQL local y la base bpmplus_test, igual que TenantIsolationSpec.
 */
class FlowableTenantIsolationSpec extends Specification {

    static final String ACME = 'flw_acme'
    static final String GLOBEX = 'flw_globex'
    static final String MASTER = 'bpmplus_test'
    static final String URL = "jdbc:mysql://localhost:3306/${MASTER}" +
            '?useUnicode=yes&characterEncoding=UTF-8&serverTimezone=UTC&nullCatalogMeansCurrent=true'

    static final String PROCESS_KEY = 'spikeAislamiento'

    private static HibernateDatastore datastore
    private static MultiSchemaProcessEngineFactory factory
    private static ProcessEngine engine

    void setupSpec() {
        sql { Statement s ->
            [ACME, GLOBEX].each { s.execute("DROP SCHEMA IF EXISTS `${it}`") }
        }

        Map config = [
                'grails.gorm.multiTenancy.mode'               : 'SCHEMA',
                'grails.gorm.multiTenancy.tenantResolverClass': TenantRegistryResolver,
                'dataSource.url'                              : URL,
                'dataSource.driverClassName'                  : 'com.mysql.cj.jdbc.Driver',
                'dataSource.username'                         : 'root',
                'dataSource.password'                         : mysqlPassword(),
                'dataSource.dbCreate'                         : 'none',
                'dataSource.dialect'                          : MySQL8Dialect,
                'dataSource.schemaHandler'                    : MySqlSchemaHandler,
        ]
        datastore = new HibernateDatastore(DatastoreUtils.createPropertyResolver(config), Expediente)

        // El motor arranca sin tenants, igual que en la aplicacion, y despues se le enganchan
        // de a uno. Es el camino que importa validar: dar de alta un cliente en caliente.
        factory = new MultiSchemaProcessEngineFactory(datastore.dataSource, datastore)
        engine = factory.build()

        [ACME, GLOBEX].each { String code ->
            // Liquibase crea el schema y sus tablas, incluidas las de Flowable. El motor solo
            // las valida: es el mismo orden que sigue TenantProvisioningService.
            new SchemaMigrator(datastore.dataSource, 'test').migrateTenant(code)
            TenantRegistryResolver.register(code)
            datastore.addTenantForSchema(code)
            factory.registerTenant(code)
        }
    }

    void cleanupSpec() {
        factory?.close()
        datastore?.close()
        [ACME, GLOBEX].each { TenantRegistryResolver.unregister(it) }
        sql { Statement s -> [ACME, GLOBEX].each { s.execute("DROP SCHEMA IF EXISTS `${it}`") } }
    }

    /**
     * Los schemas viven durante todo el spec, asi que las instancias que deja una prueba se
     * verian desde la siguiente. Se borran antes de cada una para que cada prueba parta de
     * las dos bases sin procesos en curso.
     */
    void setup() {
        [ACME, GLOBEX].each { String code ->
            asEngineTenant(code) {
                engine.runtimeService.createProcessInstanceQuery().list().each {
                    engine.runtimeService.deleteProcessInstance(it.id, 'limpieza entre pruebas')
                }
            }
        }
    }

    void cleanup() {
        SecurityContextHolder.clearContext()
        RequestContextHolder.resetRequestAttributes()
    }

    void 'el esquema de Flowable queda dentro de la base de cada tenant'() {
        expect: 'las tablas de runtime y de definiciones estan en los dos schemas de tenant'
        ['ACT_RU_EXECUTION', 'ACT_RU_TASK', 'ACT_RE_PROCDEF', 'ACT_GE_BYTEARRAY'].every {
            tableExists(ACME, it) && tableExists(GLOBEX, it)
        }
    }

    /**
     * El motor arranca con databaseSchemaUpdate=false: comprueba que el esquema que dejo
     * Liquibase es el que espera su version. Ahi importa que TenantSchemaDataSource apunte la
     * conexion con setCatalog() y no con un USE suelto: la comprobacion lee metadatos, y con USE
     * el motor no ve las tablas del tenant y da el esquema por ausente.
     */
    void 'el motor valida el esquema que dejo Liquibase, en cada arranque'() {
        when: 'arranca otro motor sobre las mismas bases, como en el arranque siguiente'
        MultiSchemaProcessEngineFactory segundo =
                new MultiSchemaProcessEngineFactory(datastore.dataSource, datastore)
        segundo.build()
        [ACME, GLOBEX].each { segundo.registerTenant(it) }

        then:
        noExceptionThrown()

        cleanup:
        segundo?.close()
    }

    void 'el esquema de Flowable no se crea en la base maestra'() {
        expect: 'la maestra queda para la identidad y el registro de tenants, sin tablas ACT_*'
        actTablesIn(MASTER).isEmpty()
    }

    void 'cada tenant despliega su propio proceso'() {
        when:
        deployAs(ACME)
        deployAs(GLOBEX)

        then: 'la definicion vive en la base de cada uno, con su propio id'
        String enAcme = asUserOf(ACME) { definitionId() }
        String enGlobex = asUserOf(GLOBEX) { definitionId() }
        enAcme
        enGlobex
        enAcme != enGlobex
    }

    void 'un proceso arrancado en un tenant no se ve desde el otro'() {
        given: 'el proceso desplegado en los dos'
        deployAs(ACME)
        deployAs(GLOBEX)

        when: 'se arranca una instancia solo bajo acme'
        String instanceId = asUserOf(ACME) {
            ProcessInstance pi = engine.runtimeService
                    .startProcessInstanceByKey(PROCESS_KEY, 'expediente-de-acme')
            pi.id
        }

        then: 'acme la ve'
        asUserOf(ACME) { runningBusinessKeys() } == ['expediente-de-acme']

        and: 'globex no la ve, ni listando ni buscandola por id'
        asUserOf(GLOBEX) { runningBusinessKeys() } == []
        asUserOf(GLOBEX) {
            engine.runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).singleResult()
        } == null

        and: 'las filas estan fisicamente en el schema de acme, comprobado por JDBC crudo'
        businessKeysIn(ACME) == ['expediente-de-acme']
        businessKeysIn(GLOBEX) == []
    }

    void 'un tenant no puede completar la tarea de otro'() {
        given:
        deployAs(ACME)
        deployAs(GLOBEX)
        asUserOf(ACME) { engine.runtimeService.startProcessInstanceByKey(PROCESS_KEY, 'solo-de-acme') }

        and: 'la tarea que acme tiene pendiente'
        String taskId = asUserOf(ACME) { engine.taskService.createTaskQuery().singleResult().id }

        when: 'globex intenta completarla usando su id'
        asUserOf(GLOBEX) { engine.taskService.complete(taskId) }

        then: 'para globex esa tarea no existe'
        thrown(Exception)

        and: 'y sigue pendiente para acme'
        asUserOf(ACME) { engine.taskService.createTaskQuery().count() } == 1L
    }

    void 'el motor resuelve el tenant con el mismo resolver que el resto de la aplicacion'() {
        given: 'un usuario de acme que manda una cabecera apuntando a globex'
        authenticate(ACME, ['ROLE_USER'])
        incomingRequest(GLOBEX)

        when:
        engine.runtimeService.createProcessInstanceQuery().list()

        then: 'lo rechaza el TenantRegistryResolver, no una regla propia de Flowable'
        thrown(TenantNotFoundException)
    }

    /**
     * El bean `dataSource` de Grails envuelve el pool en un TransactionAwareDataSourceProxy,
     * que devuelve la conexion de la transaccion de Spring en curso en vez de una del pool.
     * Si el motor recibiera esa, haria commit sobre la transaccion de la aplicacion y la
     * dejaria en autocommit=true al cerrar: dar de alta un tenant (@Transactional) rompia con
     * "Can't call commit when autocommit=true".
     */
    void 'el motor trabaja sobre el pool y no sobre la conexion de la transaccion de Spring'() {
        given: 'el datasource que entrega GORM, que ya viene envuelto'
        DataSource deGorm = datastore.dataSource
        DataSource pool = factory.@dataSource

        expect: 'GORM lo envuelve, no entrega el pool pelado'
        deGorm instanceof DelegatingDataSource

        and: 'pero la fabrica se quedo con el pool'
        !(pool instanceof DelegatingDataSource)

        when: 'se le agrega otra capa de envoltorio'
        MultiSchemaProcessEngineFactory otra = new MultiSchemaProcessEngineFactory(
                new TransactionAwareDataSourceProxy(deGorm), datastore)

        then: 'llega al mismo pool, sin importar cuantas capas haya'
        otra.@dataSource.is(pool)
    }

    void 'sin usuario autenticado el motor no toca ninguna base'() {
        given:
        SecurityContextHolder.clearContext()
        incomingRequest(ACME)

        when:
        engine.runtimeService.createProcessInstanceQuery().list()

        then:
        thrown(TenantNotFoundException)
    }

    // --- helpers ---

    /**
     * El despliegue se hace por fuera del resolver, fijando el tenant a mano: desplegar es una
     * tarea de administracion, no ocurre dentro del request de un usuario.
     */
    private static void deployAs(String tenantCode) {
        asEngineTenant(tenantCode) {
            if (engine.repositoryService.createDeploymentQuery().count() == 0L) {
                engine.repositoryService.createDeployment()
                        .addClasspathResource('processes/spikeAislamiento.bpmn20.xml')
                        .name("spike-${tenantCode}")
                        .deploy()
            }
        }
    }

    /** Fija el tenant a mano en el motor, para las tareas que no ocurren dentro de un request. */
    private static <T> T asEngineTenant(String tenantCode, Closure<T> work) {
        FlowableTenantInfoHolder holder = factory.tenantInfoHolder
        holder.setCurrentTenantId(tenantCode)
        try {
            work.call()
        }
        finally {
            holder.clearCurrentTenantId()
        }
    }

    private static String definitionId() {
        ProcessDefinition definition = engine.repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(PROCESS_KEY).singleResult()
        definition?.id
    }

    private static List<String> runningBusinessKeys() {
        engine.runtimeService.createProcessInstanceQuery().list()*.businessKey.sort()
    }

    /** Ejecuta el bloque como un usuario del tenant, es decir por el camino normal de la app. */
    private static <T> T asUserOf(String tenantCode, Closure<T> work) {
        authenticate(tenantCode, ['ROLE_USER'])
        incomingRequest(null)
        try {
            work.call()
        }
        finally {
            SecurityContextHolder.clearContext()
            RequestContextHolder.resetRequestAttributes()
        }
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

    private static String mysqlPassword() {
        System.getenv('MYSQL_PASSWORD') ?: ''
    }

    private static boolean tableExists(String schema, String table) {
        query("SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = '${schema}' AND upper(table_name) = '${table}'").size() == 1
    }

    private static List<String> actTablesIn(String schema) {
        query("SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = '${schema}' AND upper(table_name) LIKE 'ACT\\_%'")
    }

    /** Lee las filas sin pasar por Flowable, para comprobar en que base quedaron de verdad. */
    private static List<String> businessKeysIn(String schema) {
        query("SELECT BUSINESS_KEY_ FROM `${schema}`.ACT_RU_EXECUTION " +
                'WHERE BUSINESS_KEY_ IS NOT NULL ORDER BY BUSINESS_KEY_')
    }

    private static List<String> query(String statement) {
        List<String> values = []
        sql { Statement s ->
            ResultSet rs = s.executeQuery(statement)
            while (rs.next()) {
                values << rs.getString(1)
            }
        }
        values
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

/**
 * GORM necesita al menos una entidad para construir el datastore. No se usa para nada mas:
 * lo que se prueba aca son las tablas de Flowable.
 */
@Entity
class Expediente implements MultiTenant<Expediente> {

    String referencia

    static constraints = {
        referencia nullable: true, maxSize: 40
    }
}
