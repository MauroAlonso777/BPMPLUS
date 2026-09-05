package bpmplus.flowable

import groovy.transform.CompileStatic

import org.flowable.common.engine.impl.FlowableVersions

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

/**
 * Expone en /actuator/health el estado del motor de procesos.
 *
 * Es el artefacto de la Fase 2: hace visible que el motor corre embebido en este proceso
 * —uno solo, con N tenants enganchados— en vez de ser un servicio aparte al que se le pega.
 * Si un tenant esta en el registro pero no aparece aca, alguien se salteo
 * TenantProvisioningService y sus procesos no van a arrancar.
 *
 * A proposito NO consulta la base de cada tenant. /actuator/health lo suele pegar un
 * orquestador cada pocos segundos, y una consulta por tenant en cada pegada escala mal a medida
 * que entran clientes. La salud de las conexiones ya la cubre el indicador `db` estandar; lo que
 * falta y aporta esto es la topologia: que el motor exista y a que tenants esta enganchado.
 */
@CompileStatic
class ProcessEngineHealthIndicator implements HealthIndicator {

    private final ProcessEngineService processEngineService

    ProcessEngineHealthIndicator(ProcessEngineService processEngineService) {
        this.processEngineService = processEngineService
    }

    @Override
    Health health() {
        if (!processEngineService.running) {
            return Health.down()
                    .withDetail('motor', 'no arranco')
                    .build()
        }

        List<String> tenants = new ArrayList<String>(processEngineService.attachedTenants).sort()
        Health.up()
                .withDetail('version', FlowableVersions.CURRENT_VERSION)
                .withDetail('modo', 'embebido, un schema por tenant')
                .withDetail('tenants', tenants)
                .withDetail('cantidadDeTenants', tenants.size())
                .build()
    }
}
