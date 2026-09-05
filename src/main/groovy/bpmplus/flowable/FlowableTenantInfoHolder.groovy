package bpmplus.flowable

import java.util.concurrent.ConcurrentHashMap

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import org.flowable.common.engine.api.FlowableException
import org.flowable.common.engine.impl.cfg.multitenant.TenantInfoHolder

import org.grails.datastore.mapping.multitenancy.MultiTenantCapableDatastore

import grails.gorm.multitenancy.Tenants

/**
 * Le dice a Flowable sobre que tenant esta operando.
 *
 * Es el puente entre el motor y la resolucion de tenant que ya usa el resto de la aplicacion:
 * NO decide nada por su cuenta, delega en Tenants.currentId(datastore), que respeta
 * Tenants.withId(...) y, si no hay uno fijado a mano, cae en el TenantResolver configurado
 * (bpmplus.multitenancy.TenantRegistryResolver). Asi no hay dos mecanismos de resolucion en
 * paralelo: el usuario autenticado manda igual para GORM que para Flowable.
 *
 * La unica excepcion es el ThreadLocal engineTenant. Flowable lo fija a traves de
 * setCurrentTenantId() cuando necesita trabajar sobre un tenant concreto por fuera de un
 * request: crear el esquema ACT_* al registrar un tenant y cerrar el motor. Mientras esta
 * fijado tiene precedencia; el propio motor lo limpia despues.
 *
 * getAllTenants() devuelve los tenants que conoce FLOWABLE (los que tienen un DataSource
 * registrado en su TenantAwareDataSource), no los del registro global. Flowable recorre esa
 * lista para crear esquemas y ejecutores: si devolviera un tenant sin DataSource, el arranque
 * fallaria con "Could not find a dataSource for tenant".
 */
@CompileStatic
@Slf4j
class FlowableTenantInfoHolder implements TenantInfoHolder {

    /**
     * De instancia y no estaticos: son el estado de UN motor. La aplicacion tiene uno solo, pero
     * un spec puede levantar un segundo motor sobre las mismas bases (por ejemplo para simular el
     * arranque siguiente), y con ThreadLocals compartidos el tenant que fija uno se le aplicaria
     * al otro en el mismo hilo.
     */
    private final ThreadLocal<String> engineTenant = new ThreadLocal<String>()
    private final ThreadLocal<Boolean> resolutionSuspended = new ThreadLocal<Boolean>()

    private final MultiTenantCapableDatastore datastore
    private final Set<String> tenants = ConcurrentHashMap.newKeySet()

    FlowableTenantInfoHolder(MultiTenantCapableDatastore datastore) {
        this.datastore = datastore
    }

    /**
     * Anota que el motor va a tener un DataSource para este tenant. Flowable exige que el
     * tenant este en el holder ANTES de llamar a registerTenant().
     */
    void addTenant(String tenantId) {
        tenants.add(tenantId)
    }

    @Override
    Collection<String> getAllTenants() {
        new ArrayList<String>(tenants)
    }

    @Override
    void setCurrentTenantId(String tenantId) {
        engineTenant.set(tenantId)
    }

    @Override
    void clearCurrentTenantId() {
        engineTenant.remove()
    }

    /**
     * Ejecuta el bloque sin consultar al resolver: si no hay un tenant fijado a mano, la
     * respuesta es null en vez de una excepcion.
     *
     * Hace falta durante el arranque del motor. initAsyncExecutor() pregunta por el tenant en
     * curso (ExecutorPerTenantAsyncExecutor.determineAsyncExecutor) antes de que exista
     * ninguno, y el contrato de Flowable admite null ahi: lo trata como "sin tenant". El
     * resolver de la aplicacion, en cambio, rechaza esa pregunta a proposito, porque en un
     * request significa que alguien esta operando sin tenant.
     *
     * Devolver null no abre nada: el TenantAwareDataSource no encuentra DataSource para null
     * y falla. Lo que se pierde es el motivo del rechazo, y por eso la ventana es esta y no
     * el comportamiento por defecto.
     */
    def <T> T withoutTenantResolution(Closure<T> work) {
        resolutionSuspended.set(Boolean.TRUE)
        try {
            work.call()
        }
        finally {
            resolutionSuspended.remove()
        }
    }

    @Override
    String getCurrentTenantId() {
        String pinned = engineTenant.get()
        if (pinned) {
            return pinned
        }
        if (resolutionSuspended.get()) {
            return null
        }

        // Lanza TenantNotFoundException si no hay tenant, o si lo hay pero es un intento de
        // llegar al de otro. Es lo que queremos: el motivo del rechazo se ve donde ocurre.
        Serializable current = Tenants.currentId(datastore)
        String tenantId = current?.toString()
        if (!tenants.contains(tenantId)) {
            throw new FlowableException("El tenant [${tenantId}] no esta registrado en el motor " +
                    'de procesos. Se registra al darlo de alta, ver ProcessEngineService.')
        }
        tenantId
    }
}
