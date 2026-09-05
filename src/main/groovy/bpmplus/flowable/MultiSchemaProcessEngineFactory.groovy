package bpmplus.flowable

import javax.sql.DataSource

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import org.flowable.common.engine.impl.AbstractEngineConfiguration
import org.flowable.engine.ProcessEngine
import org.flowable.engine.impl.cfg.multitenant.MultiSchemaMultiTenantProcessEngineConfiguration

import org.springframework.jdbc.datasource.DelegatingDataSource

import org.grails.datastore.mapping.multitenancy.MultiTenantCapableDatastore

/**
 * Arma y sostiene el ProcessEngine de Flowable en modo un-schema-por-tenant.
 *
 * No depende de Spring ni de Grails a proposito, igual que SchemaMigrator: asi la puede usar
 * tanto el contexto de la aplicacion (ProcessEngineService) como un spec que levanta su propio
 * datastore.
 *
 * Se usa MultiSchemaMultiTenantProcessEngineConfiguration y NO flowable-spring-boot-starter:
 * el starter autoconfigura el motor sobre un unico DataSource, que es justo lo que aca no hay.
 *
 * El motor arranca SIN tenants y se le van agregando con registerTenant(); Flowable soporta
 * darlos de alta despues del arranque, que es lo que necesita el alta de clientes en caliente.
 */
@CompileStatic
@Slf4j
class MultiSchemaProcessEngineFactory {

    private final DataSource dataSource
    private final FlowableTenantInfoHolder tenantInfoHolder
    private final MultiSchemaMultiTenantProcessEngineConfiguration configuration
    private ProcessEngine processEngine

    MultiSchemaProcessEngineFactory(DataSource dataSource, MultiTenantCapableDatastore datastore) {
        this(dataSource, datastore, null)
    }

    /**
     * @param beans los objetos que las expresiones de un proceso pueden nombrar como raiz, es
     *        decir los servicios que puede invocar una tarea de servicio. En la aplicacion es
     *        ApplicationContextBeans; un spec puede pasar un mapa armado a mano.
     */
    MultiSchemaProcessEngineFactory(DataSource dataSource, MultiTenantCapableDatastore datastore,
                                    Map<Object, Object> beans) {
        this.dataSource = unwrapTransactionAware(dataSource)
        this.tenantInfoHolder = new FlowableTenantInfoHolder(datastore)
        this.configuration = new MultiSchemaMultiTenantProcessEngineConfiguration(tenantInfoHolder)

        if (beans != null) {
            configuration.beans = beans
        }

        // Obligatorio fijarlo a mano. Si queda en null, Flowable lo deduce abriendo una
        // conexion, y su DataSource es el TenantAwareDataSource: sin tenant en curso, el
        // arranque falla con "Could not find a dataSource for tenant null".
        configuration.databaseType = AbstractEngineConfiguration.DATABASE_TYPE_MYSQL

        // El motor NO crea ni actualiza su esquema: lo gobierna Liquibase, igual que el resto
        // (ver db/changelog/tenant/changelog.xml). Con "false" el motor comprueba al enganchar
        // cada tenant que la version del esquema es la que espera, y falla si no. Es lo que
        // convierte una desviacion entre la dependencia y las migraciones en un error de
        // arranque en vez de un error en produccion meses despues.
        configuration.databaseSchemaUpdate = AbstractEngineConfiguration.DB_SCHEMA_UPDATE_FALSE

        // El ejecutor asincronico levanta un pool de hilos POR TENANT (ExecutorPerTenantAsyncExecutor).
        // Los timers y las tareas async no son parte de este spike, asi que queda apagado.
        configuration.asyncExecutorActivate = false

        // La identidad la maneja Spring Security contra el schema maestro: usuarios, roles y
        // la pertenencia a un tenant. El motor de IDM de Flowable replicaria eso en tablas
        // ACT_ID_* dentro de la base de cada cliente, con dos padrones de usuarios que se irian
        // separando. Se apaga.
        configuration.disableIdmEngine = true

        // El motor de eventos no es compatible con este arranque: apenas termina de construirse
        // consulta sus definiciones de canal (EventRegistryEngineImpl.handleDeployedChannelDefinitions),
        // y en ese momento no hay ningun tenant en curso ni una base unica sobre la que preguntar.
        // Da NullPointerException dentro del TenantAwareDataSource. Como no hace falta para
        // ejecutar procesos BPMN, se apaga; si mas adelante se quieren eventos, habra que
        // arrancarlo por tenant, no junto con el motor.
        configuration.disableEventRegistry = true
    }

    MultiSchemaMultiTenantProcessEngineConfiguration getConfiguration() {
        configuration
    }

    FlowableTenantInfoHolder getTenantInfoHolder() {
        tenantInfoHolder
    }

    /** Arranca el motor. Idempotente. */
    ProcessEngine build() {
        if (processEngine == null) {
            // El arranque pregunta por el tenant en curso cuando todavia no hay ninguno; ver
            // FlowableTenantInfoHolder.withoutTenantResolution.
            processEngine = tenantInfoHolder.withoutTenantResolution { configuration.buildEngine() }
            log.info('Motor de procesos Flowable arrancado (multi-schema, un schema por tenant)')
        }
        processEngine
    }

    ProcessEngine getProcessEngine() {
        if (processEngine == null) {
            throw new IllegalStateException('El motor de procesos todavia no arranco; llamar a build() primero.')
        }
        processEngine
    }

    /**
     * Engancha el schema de un tenant al motor y crea/actualiza ahi las tablas ACT_*.
     *
     * El orden importa: el tenant tiene que estar en el TenantInfoHolder ANTES de
     * registerTenant(), porque Flowable consulta el holder para saber sobre que base crear
     * el esquema.
     */
    void registerTenant(String tenantId) {
        tenantInfoHolder.addTenant(tenantId)
        configuration.registerTenant(tenantId, new TenantSchemaDataSource(dataSource, tenantId))
        log.info('Tenant [{}] registrado en el motor de procesos', tenantId)
    }

    void close() {
        if (processEngine != null) {
            processEngine.close()
            processEngine = null
        }
    }

    /**
     * Devuelve el pool de verdad, salteando los envoltorios de Spring.
     *
     * El bean `dataSource` de Grails es un TransactionAwareDataSourceProxy: si hay una
     * transaccion de Spring abierta en el hilo, getConnection() no saca una conexion del pool,
     * devuelve LA de esa transaccion. Flowable no puede recibir esa: con esta configuracion
     * multi-schema el motor maneja sus propias transacciones JDBC (createTransactionInterceptor()
     * devuelve null), asi que hace commit y, al cerrar, MyBatis restaura autocommit=true sobre
     * la conexion prestada. Lo que sigue es que Spring falla al cerrar SU transaccion con
     * "Can't call commit when autocommit=true", y de paso el motor confirmo cambios que la
     * aplicacion todavia no habia decidido confirmar.
     *
     * Se ve al dar de alta un tenant, porque TenantProvisioningService es @Transactional.
     *
     * La contrapartida es que el motor NUNCA comparte transaccion con el codigo que lo llama:
     * lo que hace Flowable se confirma por su cuenta. Hay que tenerlo presente al conectar la
     * logica de negocio (Fase 4).
     */
    private static DataSource unwrapTransactionAware(DataSource dataSource) {
        DataSource current = dataSource
        while (current instanceof DelegatingDataSource) {
            DataSource target = ((DelegatingDataSource) current).targetDataSource
            if (target == null) {
                break
            }
            current = target
        }
        current
    }
}
