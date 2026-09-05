package bpmplus.flowable

import groovy.transform.CompileStatic

import org.flowable.common.engine.api.FlowableObjectNotFoundException

import org.grails.datastore.mapping.multitenancy.exceptions.TenantNotFoundException

import grails.converters.JSON
import grails.plugin.springsecurity.annotation.Secured

/**
 * Superficie HTTP del motor de procesos.
 *
 * Delgado a proposito: traduce request a llamada de servicio y respuesta a JSON, nada mas.
 *
 * No recibe el tenant por ningun lado. Lo resuelve el TenantResolver a partir del usuario
 * autenticado, igual que el resto de la aplicacion, asi que cada quien ve y toca lo de su
 * cliente. Una cuenta de plataforma elige con la cabecera X-Tenant-Id, que el resolver acepta
 * solo para esas cuentas.
 */
@CompileStatic
// Un usuario de un cliente opera sobre el suyo; una cuenta de plataforma elige con la cabecera
// X-Tenant-Id. Quien decide si esa cabecera vale es el TenantResolver, no esta anotacion.
@Secured(['ROLE_USER', 'ROLE_PLATFORM_ADMIN'])
class ProcesoController {

    static allowedMethods = [iniciar: 'POST', completar: 'POST']
    static responseFormats = ['json']

    ProcesoService procesoService

    /** GET /proceso/definiciones */
    def definiciones() {
        render(procesoService.definiciones() as JSON)
    }

    /** GET /proceso/instancias */
    def instancias() {
        render(procesoService.instancias() as JSON)
    }

    /** GET /proceso/tareas?asignadoA=ana */
    def tareas() {
        render(procesoService.tareas(params.asignadoA as String) as JSON)
    }

    /**
     * POST /proceso/iniciar/<clave>
     * Cuerpo JSON opcional: { "referencia": "...", "variables": { ... } }
     */
    def iniciar(String id) {
        if (!id) {
            return responder(400, [error: 'Falta la clave del proceso'])
        }
        Map cuerpo = (request.JSON ?: [:]) as Map
        responder(201, procesoService.iniciar(id, cuerpo.referencia as String,
                cuerpo.variables as Map<String, Object>))
    }

    /**
     * POST /proceso/completar/<idDeTarea>
     * Cuerpo JSON opcional: { "variables": { ... } }
     */
    def completar(String id) {
        if (!id) {
            return responder(400, [error: 'Falta el id de la tarea'])
        }
        Map cuerpo = (request.JSON ?: [:]) as Map
        procesoService.completar(id, cuerpo.variables as Map<String, Object>)
        responder(200, [completada: id])
    }

    /**
     * Sin tenant no hay nada que responder, y el motivo es del cliente: o no mando credenciales
     * utiles, o pidio un tenant que no le corresponde. 403 y no 500.
     */
    def handleTenantNotFound(TenantNotFoundException e) {
        responder(403, [error: e.message])
    }

    /**
     * Lo pedido no existe en la base del cliente. Pasa tambien cuando alguien usa el id de una
     * tarea de OTRO cliente: como el aislamiento es fisico, el motor no la encuentra. Para quien
     * llama es lo mismo que si no existiera, y eso es un 404 — no un 500, que ademas sugeriria
     * que el problema es del servidor.
     */
    def handleNoEncontrado(FlowableObjectNotFoundException e) {
        responder(404, [error: 'No existe en este cliente'])
    }

    private responder(int estado, Object cuerpo) {
        response.status = estado
        render(cuerpo as JSON)
    }
}
