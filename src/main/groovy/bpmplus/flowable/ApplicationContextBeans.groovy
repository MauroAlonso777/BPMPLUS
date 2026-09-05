package bpmplus.flowable

import groovy.transform.CompileStatic

import org.springframework.beans.BeansException
import org.springframework.context.ApplicationContext

/**
 * Expone los beans de Spring a las expresiones de un proceso BPMN.
 *
 * Es lo que permite que una tarea de servicio invoque un servicio Groovy de los que ya existen:
 *
 *   <serviceTask id="registrar" flowable:expression="${expedienteService.registrar(execution)}"/>
 *
 * Flowable resuelve el nombre de la raiz de una expresion contra el mapa `beans` de la
 * configuracion (ReadOnlyMapELResolver), que solo usa containsKey() y get(). Por eso esto es un
 * Map y no un BeanFactory: no hace falta materializar nada, se consulta el contexto en el momento.
 *
 * En una aplicacion Spring Boot normal esto lo aporta flowable-spring; aca no se usa ese modulo,
 * porque su autoconfiguracion asume un unico datasource (ver MultiSchemaProcessEngineFactory).
 *
 * Solo lectura: entrySet(), keySet() y values() estan vacios a proposito. El contexto de Spring
 * no se puede recorrer barato ni tiene sentido volcarlo en un proceso; enumerarlo tampoco lo
 * necesita Flowable.
 */
@CompileStatic
class ApplicationContextBeans implements Map<Object, Object> {

    private final ApplicationContext applicationContext

    ApplicationContextBeans(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext
    }

    @Override
    boolean containsKey(Object key) {
        key instanceof String && applicationContext.containsBean((String) key)
    }

    @Override
    Object get(Object key) {
        if (!(key instanceof String)) {
            return null
        }
        try {
            applicationContext.getBean((String) key)
        }
        catch (BeansException ignored) {
            // containsBean() y getBean() pueden discrepar (un bean con scope que no aplica).
            // Devolver null deja que la expresion falle en su propio contexto, con el nombre
            // del proceso y la tarea, en vez de romper la resolucion entera.
            null
        }
    }

    // --- el resto del contrato de Map: solo lectura y sin enumeracion ---

    @Override
    int size() {
        0
    }

    @Override
    boolean isEmpty() {
        true
    }

    @Override
    boolean containsValue(Object value) {
        false
    }

    @Override
    Set<Object> keySet() {
        Collections.emptySet()
    }

    @Override
    Collection<Object> values() {
        Collections.emptyList()
    }

    @Override
    Set<Map.Entry<Object, Object>> entrySet() {
        Collections.emptySet()
    }

    @Override
    Object put(Object key, Object value) {
        throw new UnsupportedOperationException('Los beans de la aplicacion son de solo lectura')
    }

    @Override
    Object remove(Object key) {
        throw new UnsupportedOperationException('Los beans de la aplicacion son de solo lectura')
    }

    @Override
    void putAll(Map<?, ?> m) {
        throw new UnsupportedOperationException('Los beans de la aplicacion son de solo lectura')
    }

    @Override
    void clear() {
        throw new UnsupportedOperationException('Los beans de la aplicacion son de solo lectura')
    }
}
