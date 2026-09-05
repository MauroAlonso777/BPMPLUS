package bpmplus.flowable

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import javax.sql.DataSource

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import org.flowable.engine.HistoryService
import org.flowable.engine.ProcessEngine
import org.flowable.engine.RepositoryService
import org.flowable.engine.RuntimeService
import org.flowable.engine.TaskService

import org.springframework.beans.BeansException
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware

import org.grails.orm.hibernate.HibernateDatastore

/**
 * Envoltorio de Grails sobre MultiSchemaProcessEngineFactory, para poder inyectar el motor
 * y sus servicios donde haga falta.
 *
 * El motor corre EMBEBIDO en el mismo proceso que el resto de BPMPLUS (Fase 2): comparte la
 * JVM, el pool de conexiones y el modelo de tenant. Lo que NO comparte son las transacciones:
 * MultiSchemaMultiTenantProcessEngineConfiguration no instala un interceptor transaccional y
 * maneja sus propias transacciones JDBC. Ver MultiSchemaProcessEngineFactory.
 *
 * Por eso este servicio no es transaccional: envolverlo en una transaccion de Spring no la
 * integraria con la del motor, solo dejaria en duda quien hace el commit.
 */
@CompileStatic
@Slf4j
class ProcessEngineService implements ApplicationContextAware {

    static transactional = false
    static lazyInit = false

    DataSource dataSource
    HibernateDatastore hibernateDatastore

    private ApplicationContext applicationContext
    private MultiSchemaProcessEngineFactory factory
    private ProcessDefinitionDeployer deployer

    @Override
    void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext
    }

    @PostConstruct
    void startEngine() {
        // Los beans de la aplicacion quedan disponibles para las expresiones de un proceso:
        // es lo que permite que una tarea de servicio invoque un servicio Groovy (Fase 4).
        factory = new MultiSchemaProcessEngineFactory(dataSource, hibernateDatastore,
                new ApplicationContextBeans(applicationContext))

        // Arranca sin tenants: los engancha TenantProvisioningService, uno por uno, tanto en el
        // arranque (BootStrap) como cuando se da de alta un cliente nuevo.
        ProcessEngine engine = factory.build()
        deployer = new ProcessDefinitionDeployer(engine.repositoryService, factory.tenantInfoHolder)
    }

    @PreDestroy
    void stopEngine() {
        factory?.close()
    }

    /**
     * Engancha el schema del tenant al motor, crea ahi sus tablas y le despliega los procesos
     * del classpath. Es idempotente: se llama en cada arranque, no solo al alta.
     */
    void attachTenant(String tenantCode) {
        factory.registerTenant(tenantCode)
        deployer.deployTo(tenantCode)
    }

    /** Los tenants que el motor tiene enganchados. Lo usa el health indicator. */
    Collection<String> getAttachedTenants() {
        factory ? factory.tenantInfoHolder.allTenants : []
    }

    boolean isRunning() {
        factory != null
    }

    ProcessEngine getProcessEngine() {
        factory.processEngine
    }

    RepositoryService getRepositoryService() {
        factory.processEngine.repositoryService
    }

    RuntimeService getRuntimeService() {
        factory.processEngine.runtimeService
    }

    TaskService getTaskService() {
        factory.processEngine.taskService
    }

    HistoryService getHistoryService() {
        factory.processEngine.historyService
    }
}
