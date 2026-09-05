package bpmplus.flowable

import groovy.transform.CompileStatic

import org.flowable.engine.repository.ProcessDefinition
import org.flowable.engine.runtime.ProcessInstance
import org.flowable.task.api.Task

/**
 * Operaciones de negocio sobre los procesos, en el vocabulario de la aplicacion.
 *
 * El controller delega aca y no habla con Flowable directamente. Ademas de la convencion del
 * proyecto, esto es lo que mantiene los tipos de Flowable fuera de la capa web: lo que sale de
 * aca son mapas planos, no entidades del motor con estado perezoso.
 *
 * NO recibe ni acepta un tenant. Lo resuelve el motor con el mismo TenantResolver que el resto
 * de la aplicacion, a partir del usuario autenticado, asi que un usuario de un cliente solo
 * puede ver y tocar los procesos de su cliente. Aceptar el tenant por parametro seria abrir
 * justamente el agujero que todo el diseño evita.
 *
 * No es transaccional: el motor maneja sus propias transacciones JDBC (ver
 * MultiSchemaProcessEngineFactory).
 */
@CompileStatic
class ProcesoService {

    static transactional = false

    ProcessEngineService processEngineService

    /** Los procesos desplegados en el cliente del usuario, en su ultima version. */
    List<Map> definiciones() {
        processEngineService.repositoryService.createProcessDefinitionQuery()
                .latestVersion()
                .orderByProcessDefinitionKey().asc()
                .list()
                .collect { ProcessDefinition d ->
                    [clave: d.key, nombre: d.name, version: d.version] as Map
                }
    }

    /**
     * Arranca una instancia.
     *
     * @param referencia la clave de negocio: el identificador con el que el cliente reconoce el
     *        caso (un numero de expediente, de factura). Queda en ACT_RU_EXECUTION y sirve para
     *        encontrar la instancia sin conocer el id que genera el motor.
     */
    Map iniciar(String clave, String referencia, Map<String, Object> variables) {
        ProcessInstance instancia = processEngineService.runtimeService
                .startProcessInstanceByKey(clave, referencia, variables ?: [:])
        [id: instancia.id, clave: clave, referencia: instancia.businessKey,
         terminada: instancia.ended] as Map
    }

    List<Map> instancias() {
        processEngineService.runtimeService.createProcessInstanceQuery()
                .orderByProcessInstanceId().asc()
                .list()
                .collect { ProcessInstance i ->
                    [id: i.id, clave: i.processDefinitionKey, referencia: i.businessKey] as Map
                }
    }

    /** Las tareas pendientes. Con asignadoA, solo las de esa persona. */
    List<Map> tareas(String asignadoA = null) {
        def consulta = processEngineService.taskService.createTaskQuery()
        if (asignadoA) {
            consulta = consulta.taskAssignee(asignadoA)
        }
        consulta.orderByTaskCreateTime().asc().list().collect { Task t ->
            [id: t.id, nombre: t.name, definicion: t.taskDefinitionKey,
             asignadoA: t.assignee, instancia: t.processInstanceId] as Map
        }
    }

    /**
     * Completa una tarea.
     *
     * Si la tarea es de otro cliente, el motor no la encuentra —esta en otra base— y lanza. No
     * hace falta comprobar la pertenencia a mano: el aislamiento es fisico.
     */
    void completar(String tareaId, Map<String, Object> variables) {
        processEngineService.taskService.complete(tareaId, variables ?: [:])
    }
}
