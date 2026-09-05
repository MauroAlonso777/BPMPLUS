package bpmplus

import bpmplus.migration.SchemaMigrationService
import bpmplus.security.SecurityBootstrapService
import bpmplus.tenant.TenantProvisioningService

class BootStrap {

    SchemaMigrationService schemaMigrationService
    TenantProvisioningService tenantProvisioningService
    SecurityBootstrapService securityBootstrapService

    def init = {
        // El orden es obligatorio: sin las tablas del schema maestro no se puede consultar
        // ni el registro de tenants ni los usuarios.
        schemaMigrationService.migrateMaster()
        securityBootstrapService.seed()
        tenantProvisioningService.registerExistingTenants()
    }

    def destroy = {
    }

}
