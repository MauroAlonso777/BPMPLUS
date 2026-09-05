package bpmplus.flowable

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

import grails.gorm.MultiTenant
import grails.gorm.annotation.Entity
import grails.gorm.multitenancy.Tenants

import org.flowable.engine.ProcessEngine
import org.flowable.engine.RepositoryService
import org.flowable.engine.RuntimeService
import org.flowable.engine.TaskService
import org.flowable.engine.delegate.DelegateExecution
import org.flowable.engine.runtime.ProcessInstance
import org.flowable.task.api.Task
import org.flowable.task.api.history.HistoricTaskInstance

import org.hibernate.dialect.MySQL8Dialect

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletWebRequest

import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.multitenancy.MultiTenantCapableDatastore
import org.grails.orm.hibernate.HibernateDatastore

import bpmplus.migration.SchemaMigrator
import bpmplus.multitenancy.MySqlSchemaHandler
import bpmplus.multitenancy.TenantRegistryResolver
import bpmplus.security.TenantAwareUser

import spock.lang.Specification

/**
 * Fases 4 y 5: el ciclo de vida completo de un proceso, con una tarea de servicio que llama a
 * codigo de la aplicacion, y todo bajo dos tenants a la vez.
 *
 * Lo que importa que quede probado, mas alla de que el proceso avance:
 *
 *  - que una tarea de servicio pueda invocar un bean de la aplicacion por expresion
 *    (ApplicationContextBeans en produccion; aca un mapa armado a mano);
 *  - que lo que ese bean escribe con GORM caiga en el schema del tenant correcto, o sea que el
 *    motor y GORM esten resolviendo el MISMO tenant. Es el pago de la Fase 1: si cada uno
 *    resolviera por su lado, el proceso avanzaria en una base y el dato aterrizaria en otra.
 *
 * Necesita el MySQL local y la base bpmplus_test.
 */
class ProcessLifecycleSpec extends Specification {

    static final String ACME = 'lfc_acme'
    static final String GLOBEX = 'lfc_globex'
    static final String MASTER = 'bpmplus_test'
    static final String URL = "jdbc:mysql://localhost:3306/${MASTER}" +
            '?useUnicode=yes&characterEncoding=UTF-8&serverTimezone=UTC&nullCatalogMeansCurrent=true'

    static final String PROCESS_KEY = 'expediente'

    private static HibernateDatastore datastore
    private static MultiSchemaProcessEngineFactory factory
    private static ProcessEngine engine
    private static ProcessDefinitionDeployer deployer
    private static ExpedienteServiceDeLaPrueba servicio

    void setupSpec() {
        sql { Statement s -> [ACME, GLOBEX].each { s.execute("DROP SCHEMA IF EXISTS `${it}`") } }

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
        datastore = new HibernateDatastore(DatastoreUtils.createPropertyResolver(config), Tramite)

        // El equivalente de ApplicationContextBeans: lo que una tarea de servicio puede nombrar.
        servicio = new ExpedienteServiceDeLaPrueba(datastore)
        factory = new MultiSchemaProcessEngineFactory(datastore.dataSource, datastore,
                ['expedienteService': servicio] as Map<Object, Object>)
        engine = factory.build()
        deployer = new ProcessDefinitionDeployer(engine.repositoryService, factory.tenantInfoHolder)

        [ACME, GLOBEX].each { String code ->
            new SchemaMigrator(datastore.dataSource, 'test').migrateTenant(code)
            TenantRegistryResolver.register(code)
            datastore.addTenantForSchema(code)
            factory.registerTenant(code)
            // La tabla de la entidad la crearia Liquibase; aca se replica a mano.
            sql { Statement s ->
                s.execute("""CREATE TABLE IF NOT EXISTS `${code}`.tramite (
                        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        version BIGINT NOT NULL,
                        referencia VARCHAR(60) NOT NULL) ENGINE=InnoDB""")
            }
            deployer.deployTo(code)
        }
    }

    void cleanupSpec() {
        factory?.close()
        datastore?.close()
        [ACME, GLOBEX].each { TenantRegistryResolver.unregister(it) }
        sql { Statement s -> [ACME, GLOBEX].each { s.execute("DROP SCHEMA IF EXISTS `${it}`") } }
    }

    void setup() {
        servicio.invocaciones.clear()
        [ACME, GLOBEX].each { String code ->
            asEngineTenant(code) {
                engine.runtimeService.createProcessInstanceQuery().list().each {
                    engine.runtimeService.deleteProcessInstance(it.id, 'limpieza')
                }
                engine.historyService.createHistoricProcessInstanceQuery().list().each {
                    engine.historyService.deleteHistoricProcessInstance(it.id)
                }
            }
            sql { Statement s -> s.execute("DELETE FROM `${code}`.tramite") }
        }
    }

    void cleanup() {
        SecurityContextHolder.clearContext()
        RequestContextHolder.resetRequestAttributes()
    }

    void 'el deployer despliega los procesos del classpath en cada tenant'() {
        expect: 'la definicion esta en la base de los dos, cada una con su id'
        asUserOf(ACME) { definicion() }
        asUserOf(GLOBEX) { definicion() }
        asUserOf(ACME) { definicion() } != asUserOf(GLOBEX) { definicion() }
    }

    void 'desplegar de nuevo el mismo diagrama no crea otra version'() {
        given:
        int versionAntes = asUserOf(ACME) { engine.repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(PROCESS_KEY).latestVersion().singleResult().version }

        when: 'se vuelve a desplegar, como en el arranque siguiente de la aplicacion'
        deployer.deployTo(ACME)

        then: 'sigue la misma version: enableDuplicateFiltering hizo su trabajo'
        asUserOf(ACME) { engine.repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(PROCESS_KEY).latestVersion().singleResult().version } == versionAntes
    }

    /**
     * enableDuplicateFiltering() compara el despliegue contra el ultimo del mismo nombre y, si
     * difiere, lo vuelve a desplegar entero. Si todos los diagramas fueran en un solo despliegue,
     * tocar uno le subiria la version a todos. Que haya un despliegue por archivo es la garantia
     * estructural de que un cambio queda acotado a su proceso.
     */
    void 'cada diagrama se despliega por separado'() {
        given:
        int diagramas = deployer.findProcessResources().size()

        expect: 'hay mas de uno, si no la prueba no dice nada'
        diagramas > 1

        and: 'un despliegue por diagrama, con un unico recurso cada uno'
        asEngineTenant(ACME) {
            List despliegues = engine.repositoryService.createDeploymentQuery().list()
            assert despliegues.size() == diagramas
            despliegues.every { engine.repositoryService.getDeploymentResourceNames(it.id).size() == 1 }
        }
    }

    void 'una tarea de servicio invoca un bean de la aplicacion'() {
        when:
        asUserOf(ACME) {
            engine.runtimeService.startProcessInstanceByKey(PROCESS_KEY, 'exp-1',
                    [responsable: 'ana', referencia: 'REF-1'] as Map<String, Object>)
        }

        then: 'el bean se ejecuto, y supo bajo que tenant estaba corriendo'
        servicio.invocaciones.size() == 1
        servicio.invocaciones[0].tenant == ACME
        servicio.invocaciones[0].referencia == 'REF-1'
    }

    void 'lo que escribe la tarea de servicio cae en el schema del tenant'() {
        when: 'el mismo proceso corre bajo los dos tenants, con referencias distintas'
        asUserOf(ACME) {
            engine.runtimeService.startProcessInstanceByKey(PROCESS_KEY, 'exp-acme',
                    [responsable: 'ana', referencia: 'DE-ACME'] as Map<String, Object>)
        }
        asUserOf(GLOBEX) {
            engine.runtimeService.startProcessInstanceByKey(PROCESS_KEY, 'exp-globex',
                    [responsable: 'beto', referencia: 'DE-GLOBEX'] as Map<String, Object>)
        }

        then: 'cada fila quedo en la base de su tenant, comprobado por JDBC crudo'
        referenciasEn(ACME) == ['DE-ACME']
        referenciasEn(GLOBEX) == ['DE-GLOBEX']
    }

    void 'el ciclo de vida completo: arranque, tarea de servicio, tarea humana y fin'() {
        when: 'arranca'
        String instanceId = asUserOf(ACME) {
            ProcessInstance pi = engine.runtimeService.startProcessInstanceByKey(PROCESS_KEY, 'exp-2',
                    [responsable: 'ana', referencia: 'REF-2'] as Map<String, Object>)
            pi.id
        }

        then: 'quedo esperando en la tarea humana, asignada por expresion'
        Task tarea = asUserOf(ACME) { engine.taskService.createTaskQuery().singleResult() }
        tarea.taskDefinitionKey == 'revisar'
        tarea.assignee == 'ana'

        when: 'se completa aprobando'
        asUserOf(ACME) { engine.taskService.complete(tarea.id, [aprobado: true] as Map<String, Object>) }

        then: 'el proceso termino y no queda nada en ejecucion'
        asUserOf(ACME) {
            engine.runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).count()
        } == 0L

        and: 'el historial lo registra, con la rama que tomo'
        asUserOf(ACME) {
            engine.historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(instanceId).singleResult().endActivityId
        } == 'aprobado'

        and: 'y la tarea humana quedo en el historial con quien la completo'
        HistoricTaskInstance historica = asUserOf(ACME) {
            engine.historyService.createHistoricTaskInstanceQuery()
                    .processInstanceId(instanceId).singleResult()
        }
        historica.assignee == 'ana'
        historica.endTime != null
    }

    void 'la rama por defecto se toma cuando no se aprueba'() {
        given:
        String instanceId = asUserOf(ACME) {
            engine.runtimeService.startProcessInstanceByKey(PROCESS_KEY, 'exp-3',
                    [responsable: 'ana', referencia: 'REF-3'] as Map<String, Object>).id
        }

        when:
        asUserOf(ACME) {
            Task t = engine.taskService.createTaskQuery().singleResult()
            engine.taskService.complete(t.id, [aprobado: false] as Map<String, Object>)
        }

        then:
        asUserOf(ACME) {
            engine.historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(instanceId).singleResult().endActivityId
        } == 'rechazado'
    }

    void 'el historial de un tenant no se ve desde el otro'() {
        given:
        asUserOf(ACME) {
            engine.runtimeService.startProcessInstanceByKey(PROCESS_KEY, 'solo-acme',
                    [responsable: 'ana', referencia: 'REF-4'] as Map<String, Object>)
        }

        expect:
        asUserOf(ACME) { engine.historyService.createHistoricProcessInstanceQuery().count() } == 1L
        asUserOf(GLOBEX) { engine.historyService.createHistoricProcessInstanceQuery().count() } == 0L
    }


    // --- la capa que expone el motor por HTTP (ProcesoService) ---

    void 'ProcesoService lista las definiciones desplegadas en el cliente'() {
        expect:
        asUserOf(ACME) { procesoService().definiciones()*.clave }.sort() == ['expediente', 'spikeAislamiento']
    }

    void 'ProcesoService arranca, lista, completa'() {
        given:
        Map arranque = asUserOf(ACME) {
            procesoService().iniciar(PROCESS_KEY, 'EXP-100', [responsable: 'ana', referencia: 'R-100'])
        }

        expect: 'la instancia existe y se la encuentra por su referencia de negocio'
        arranque.id
        asUserOf(ACME) { procesoService().instancias() }*.referencia == ['EXP-100']

        when: 'se completa la tarea pendiente'
        Map tarea = asUserOf(ACME) { procesoService().tareas('ana') }.first()
        asUserOf(ACME) { procesoService().completar(tarea.id as String, [aprobado: true]) }

        then: 'el proceso termino'
        asUserOf(ACME) { procesoService().instancias() } == []
    }

    void 'ProcesoService no deja ver ni completar lo de otro cliente'() {
        given:
        asUserOf(ACME) {
            procesoService().iniciar(PROCESS_KEY, 'SOLO-ACME', [responsable: 'ana', referencia: 'R-200'])
        }
        String tareaDeAcme = asUserOf(ACME) { procesoService().tareas() }.first().id as String

        expect: 'globex no la ve'
        asUserOf(GLOBEX) { procesoService().instancias() } == []
        asUserOf(GLOBEX) { procesoService().tareas() } == []

        when: 'ni la puede completar con su id'
        asUserOf(GLOBEX) { procesoService().completar(tareaDeAcme, [:]) }

        then:
        thrown(Exception)

        and: 'y para acme sigue pendiente'
        asUserOf(ACME) { procesoService().tareas() }.size() == 1
    }

    /**
     * El ProcesoService que usa el controller, apoyado sobre el motor de este spec. Se sustituye
     * ProcessEngineService porque aca no hay contexto de Spring; lo que se prueba es la capa de
     * arriba, no como se construye el motor.
     */
    private static ProcesoService procesoService() {
        ProcessEngineService motor = new ProcessEngineService() {
            @Override ProcessEngine getProcessEngine() { engine }
            @Override RepositoryService getRepositoryService() { engine.repositoryService }
            @Override RuntimeService getRuntimeService() { engine.runtimeService }
            @Override TaskService getTaskService() { engine.taskService }
        }
        new ProcesoService(processEngineService: motor)
    }

    // --- helpers ---

    private static String definicion() {
        engine.repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(PROCESS_KEY).latestVersion().singleResult()?.id
    }

    private static <T> T asUserOf(String tenantCode, Closure<T> work) {
        List<GrantedAuthority> authorities = [new SimpleGrantedAuthority('ROLE_USER') as GrantedAuthority]
        TenantAwareUser principal = new TenantAwareUser('alguien', 'secreto', true, true, true, true,
                authorities, 1L, tenantCode)
        SecurityContextHolder.context.authentication =
                new UsernamePasswordAuthenticationToken(principal, 'secreto', authorities)
        RequestContextHolder.requestAttributes = new ServletWebRequest(new MockHttpServletRequest())
        try {
            work.call()
        }
        finally {
            SecurityContextHolder.clearContext()
            RequestContextHolder.resetRequestAttributes()
        }
    }

    private static <T> T asEngineTenant(String tenantCode, Closure<T> work) {
        factory.tenantInfoHolder.setCurrentTenantId(tenantCode)
        try {
            work.call()
        }
        finally {
            factory.tenantInfoHolder.clearCurrentTenantId()
        }
    }

    private static String mysqlPassword() {
        System.getenv('MYSQL_PASSWORD') ?: ''
    }

    private static List<String> referenciasEn(String schema) {
        List<String> valores = []
        sql { Statement s ->
            ResultSet rs = s.executeQuery("SELECT referencia FROM `${schema}`.tramite ORDER BY referencia")
            while (rs.next()) {
                valores << rs.getString(1)
            }
        }
        valores
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
 * El equivalente de un servicio Groovy de la aplicacion: lo que en produccion seria un
 * grails-app/services/... resuelto por ApplicationContextBeans.
 *
 * Escribe con GORM a proposito. Es la parte que importa: no declara ningun tenant, lo resuelve
 * GORM por su cuenta, y tiene que coincidir con el que esta ejecutando el proceso.
 */
class ExpedienteServiceDeLaPrueba {

    /**
     * Hace falta para preguntar por el tenant en curso. Tenants.currentId() tiene una sobrecarga
     * que toma una clase, pero es la clase del *Datastore*, no la de una entidad: pasarle una
     * domain class falla con "No GORM implementation configured for type".
     */
    final MultiTenantCapableDatastore datastore

    List<Map> invocaciones = [].asSynchronized()

    ExpedienteServiceDeLaPrueba(MultiTenantCapableDatastore datastore) {
        this.datastore = datastore
    }

    void registrar(DelegateExecution execution) {
        String referencia = execution.getVariable('referencia') as String
        String tenant = Tenants.currentId(datastore).toString()

        Tramite.withTransaction {
            new Tramite(referencia: referencia).save(failOnError: true, flush: true)
        }

        invocaciones << [tenant: tenant, referencia: referencia, proceso: execution.processInstanceId]
    }
}

@Entity
class Tramite implements MultiTenant<Tramite> {

    String referencia

    static constraints = {
        referencia blank: false, maxSize: 60
    }
}
