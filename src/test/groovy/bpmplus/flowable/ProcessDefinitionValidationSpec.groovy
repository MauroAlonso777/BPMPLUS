package bpmplus.flowable

import org.flowable.bpmn.converter.BpmnXMLConverter
import org.flowable.bpmn.model.BpmnModel
import org.flowable.common.engine.api.io.InputStreamProvider
import org.flowable.validation.ProcessValidator
import org.flowable.validation.ProcessValidatorFactory
import org.flowable.validation.ValidationError

import org.springframework.core.io.Resource

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Control de los diagramas BPMN commiteados.
 *
 * Los diagramas se dibujan en Camunda Modeler, que no es Flowable: guarda sus propiedades en el
 * namespace camunda:, y Flowable las descarta **en silencio**. El proceso se despliega igual, se
 * ejecuta igual, y el assignee o la expresion de una tarea de servicio simplemente no estan.
 * Este spec es lo que convierte ese silencio en un test rojo.
 *
 * Recorre los mismos recursos que despliega ProcessDefinitionDeployer, asi que cubre tanto los
 * procesos de la aplicacion (src/main/resources/processes) como los de los specs.
 *
 * No necesita base de datos: parsea y valida, no despliega.
 */
class ProcessDefinitionValidationSpec extends Specification {

    static final ProcessValidator VALIDATOR = new ProcessValidatorFactory().createDefaultProcessValidator()

    /** Prefijos de otras herramientas BPMN que Flowable ignora sin avisar. */
    static final List<String> FOREIGN_PREFIXES = ['camunda:', 'activiti:', 'zeebe:', 'bioc:']

    void 'hay al menos un diagrama que revisar'() {
        expect: 'si esto falla, el patron de recursos dejo de encontrar los diagramas'
        !processResources().isEmpty()
    }

    @Unroll
    void 'el diagrama #nombre es valido para Flowable'() {
        given:
        BpmnModel model = parse(resource)

        expect: 'define al menos un proceso ejecutable'
        model.processes.any { it.executable }

        and: 'y el validador de Flowable no encuentra errores'
        List<ValidationError> errores = VALIDATOR.validate(model).findAll { !it.warning }
        errores.collect { describe(it) } == []

        where:
        resource << processResources()
        nombre = resource.filename
    }

    @Unroll
    void 'el diagrama #nombre no trae extensiones de otra herramienta'() {
        given: 'el xml sin comentarios, que pueden nombrar un prefijo sin usarlo'
        String xml = withoutComments(resource.inputStream.getText('UTF-8'))

        expect: 'un camunda:assignee o un camunda:expression no hace nada en Flowable'
        FOREIGN_PREFIXES.findAll { xml.contains(it) } == []

        where:
        resource << processResources()
        nombre = resource.filename
    }

    @Unroll
    void 'el diagrama #nombre se llama como el proceso que define'() {
        given:
        BpmnModel model = parse(resource)
        List<String> ids = model.processes*.id

        expect: 'la convencion del README: <id del proceso>.bpmn20.xml'
        resource.filename - '.bpmn20.xml' in ids

        where:
        resource << processResources()
        nombre = resource.filename
    }

    // --- helpers ---

    private static List<Resource> processResources() {
        // Se usa el propio deployer para no tener dos definiciones del patron que se separen.
        new ProcessDefinitionDeployer(null, null).findProcessResources()
    }

    private static String withoutComments(String xml) {
        xml.replaceAll(/(?s)<!--.*?-->/, '')
    }

    private static BpmnModel parse(Resource resource) {
        InputStreamProvider provider = new InputStreamProvider() {
            @Override
            InputStream getInputStream() {
                resource.inputStream
            }
        }
        // validateSchema=true: el XSD de BPMN 2.0 es la primera linea de defensa.
        new BpmnXMLConverter().convertToBpmnModel(provider, true, false)
    }

    private static String describe(ValidationError error) {
        "[${error.validatorSetName}] ${error.activityId ?: error.processDefinitionId}: ${error.problem}"
    }
}
