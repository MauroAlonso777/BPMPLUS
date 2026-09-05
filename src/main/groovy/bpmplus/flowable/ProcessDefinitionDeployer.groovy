package bpmplus.flowable

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import org.flowable.engine.RepositoryService
import org.flowable.engine.repository.DeploymentBuilder

import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.ResourcePatternResolver

/**
 * Despliega en un tenant las definiciones de proceso que vienen en el classpath.
 *
 * Convencion: los diagramas viven en `src/main/resources/processes/*.bpmn20.xml`. El patron es
 * `classpath*:processes/&#42;.bpmn20.xml`, asi que en un spec tambien se toman los de
 * `src/test/resources/processes`.
 *
 * Se despliega una vez por tenant, porque cada uno tiene su propia base y por lo tanto su propio
 * repositorio de definiciones. Es idempotente: `enableDuplicateFiltering()` hace que Flowable
 * compare el recurso con la ultima version desplegada y no cree una nueva si no cambio. Sin eso,
 * cada arranque de la aplicacion generaria una version nueva de cada proceso en cada tenant.
 *
 * El despliegue NO pasa por el TenantResolver: no ocurre dentro del request de un usuario, asi
 * que el tenant se fija a mano en el holder del motor.
 */
@CompileStatic
@Slf4j
class ProcessDefinitionDeployer {

    static final String RESOURCE_PATTERN = 'classpath*:processes/*.bpmn20.xml'

    private final RepositoryService repositoryService
    private final FlowableTenantInfoHolder tenantInfoHolder
    private final ResourcePatternResolver resourceResolver

    ProcessDefinitionDeployer(RepositoryService repositoryService,
                              FlowableTenantInfoHolder tenantInfoHolder,
                              ResourcePatternResolver resourceResolver =
                                      new PathMatchingResourcePatternResolver()) {
        this.repositoryService = repositoryService
        this.tenantInfoHolder = tenantInfoHolder
        this.resourceResolver = resourceResolver
    }

    /**
     * Despliega en el tenant todos los procesos del classpath.
     *
     * @return los nombres de los recursos desplegados, en orden; vacio si no hay ninguno
     */
    List<String> deployTo(String tenantCode) {
        List<Resource> resources = findProcessResources()
        if (!resources) {
            log.info('No hay definiciones de proceso en el classpath ({})', RESOURCE_PATTERN)
            return []
        }

        tenantInfoHolder.setCurrentTenantId(tenantCode)
        try {
            List<String> names = []
            resources.each { Resource resource ->
                String name = resourceName(resource)
                DeploymentBuilder deployment = repositoryService.createDeployment()
                        .name(deploymentName(name))
                        .enableDuplicateFiltering()
                        .addInputStream(name, resource.inputStream)
                deployment.deploy()
                names << name
            }
            log.info('Procesos desplegados en el tenant [{}]: {}', tenantCode, names)
            names
        }
        finally {
            tenantInfoHolder.clearCurrentTenantId()
        }
    }

    /**
     * Un despliegue POR ARCHIVO, no uno con todos adentro.
     *
     * enableDuplicateFiltering() compara el despliegue nuevo contra el ultimo con el mismo
     * nombre, y si difiere en algo lo despliega ENTERO: con un solo despliegue que agrupe todos
     * los diagramas, tocar uno le sube la version a todos los demas. Las instancias en curso no
     * se ven afectadas —siguen con la version con la que arrancaron—, pero el repositorio de cada
     * cliente se llena de versiones que no cambiaron nada, y deja de poder leerse para saber
     * cuando cambio de verdad un proceso.
     *
     * El nombre no lleva el tenant: cada uno tiene su propia base y por lo tanto su propio
     * repositorio, asi que no hay con quien chocar.
     */
    private static String deploymentName(String resourceName) {
        "bpmplus-${resourceName}".toString()
    }

    List<Resource> findProcessResources() {
        // Orden estable: el nombre del recurso entra en el calculo de duplicados de Flowable,
        // y un orden que cambie entre arranques generaria versiones nuevas sin cambios reales.
        resourceResolver.getResources(RESOURCE_PATTERN)
                .findAll { Resource r -> r.readable }
                .sort { Resource r -> resourceName(r) }
    }

    /**
     * Flowable identifica el recurso por su nombre, y exige que termine en .bpmn20.xml o .bpmn
     * para tratarlo como BPMN. Se usa el nombre del archivo, no la ruta completa: la ruta
     * cambia entre correr desde el jar y correr desde build/resources, y eso rompe el filtrado
     * de duplicados.
     */
    private static String resourceName(Resource resource) {
        resource.filename
    }
}
