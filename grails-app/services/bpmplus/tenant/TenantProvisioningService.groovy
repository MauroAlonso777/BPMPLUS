package bpmplus.tenant

import grails.gorm.transactions.Transactional

import org.grails.orm.hibernate.HibernateDatastore

import bpmplus.flowable.ProcessEngineService
import bpmplus.migration.SchemaMigrationService
import bpmplus.multitenancy.TenantRegistryResolver

/**
 * Alta y registro de tenants para el modo de multi-tenancy SCHEMA.
 *
 * "Registrar" un tenant significa pedirle a GORM que cree un datastore hijo con su propio
 * SessionFactory apuntando al schema del tenant (HibernateDatastore.addTenantForSchema).
 * Hay que hacerlo en cada arranque de la aplicacion, no solo cuando el tenant se crea.
 */
@Transactional
class TenantProvisioningService {

    HibernateDatastore hibernateDatastore
    SchemaMigrationService schemaMigrationService
    ProcessEngineService processEngineService

    /**
     * Da de alta en GORM los tenants que ya existen en el registro. Se invoca desde BootStrap,
     * porque en el momento en que GORM construye el datastore todavia no se puede consultar
     * el registro con GORM.
     */
    void registerExistingTenants() {
        List<Tenant> tenants = Tenant.findAllByActive(true)
        tenants.each { Tenant tenant ->
            attachSchema(tenant.code)
        }
        log.info('Tenants activos registrados en el arranque: {}', tenants*.code)
    }

    /**
     * Alta de un tenant nuevo: lo guarda en el registro, crea y migra su schema y lo engancha.
     *
     * Nota: el DDL provoca un commit implicito en MySQL, asi que la fila de Tenant queda
     * confirmada antes de que termine esta transaccion.
     */
    Tenant provision(String code, String name) {
        Tenant tenant = new Tenant(code: code, name: name).save(failOnError: true, flush: true)
        attachSchema(tenant.code)
        tenant
    }

    private void attachSchema(String code) {
        // Primero el schema y sus tablas: GORM ya no las crea (dbCreate: none), y ademas su
        // hbm2ddl por tenant nunca apunto al schema correcto. Ver el changelog de tenant.
        schemaMigrationService.migrateTenant(code)

        // El orden importa: addTenantForSchema vuelve a registrar las entidades en el enhancer,
        // que consulta resolveTenantIds() del resolver y exige que exista el datastore de cada
        // id devuelto. Por eso hay que dar de alta un tenant por vez.
        TenantRegistryResolver.register(code)
        hibernateDatastore.addTenantForSchema(code)

        // Va al final y despues del registro en el resolver: el motor resuelve el tenant en
        // curso con el mismo TenantResolver, asi que el codigo ya tiene que ser conocido.
        // Aca es donde Flowable engancha el schema del tenant y le despliega sus procesos.
        processEngineService.attachTenant(code)
    }
}
