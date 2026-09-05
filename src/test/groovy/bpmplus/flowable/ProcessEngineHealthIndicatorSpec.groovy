package bpmplus.flowable

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.Status

import spock.lang.Specification

/**
 * El indicador de salud del motor. No necesita base de datos ni motor: lo que reporta sale de
 * ProcessEngineService, y eso es justamente lo que se quiere probar por separado.
 */
class ProcessEngineHealthIndicatorSpec extends Specification {

    void 'reporta DOWN si el motor no arranco'() {
        given:
        Health health = new ProcessEngineHealthIndicator(servicio(false, [])).health()

        expect:
        health.status == Status.DOWN
        health.details.motor == 'no arranco'
    }

    void 'reporta UP con los tenants enganchados, ordenados'() {
        given:
        Health health = new ProcessEngineHealthIndicator(servicio(true, ['zeta', 'acme', 'globex'])).health()

        expect:
        health.status == Status.UP
        health.details.tenants == ['acme', 'globex', 'zeta']
        health.details.cantidadDeTenants == 3
        health.details.modo == 'embebido, un schema por tenant'
        health.details.version
    }

    void 'un motor arrancado sin tenants sigue estando UP'() {
        given: 'es el estado normal entre que arranca el contexto y corre BootStrap'
        Health health = new ProcessEngineHealthIndicator(servicio(true, [])).health()

        expect:
        health.status == Status.UP
        health.details.tenants == []
        health.details.cantidadDeTenants == 0
    }

    private static ProcessEngineService servicio(boolean arrancado, List<String> tenants) {
        new ProcessEngineService() {
            @Override
            boolean isRunning() { arrancado }

            @Override
            Collection<String> getAttachedTenants() { tenants }
        }
    }
}
