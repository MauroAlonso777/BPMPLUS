package bpmplus.migration

import groovy.transform.CompileStatic

import liquibase.resource.AbstractResource
import liquibase.resource.ClassLoaderResourceAccessor
import liquibase.resource.Resource
import liquibase.resource.ResourceAccessor

/**
 * ResourceAccessor de Liquibase que lee los changelogs por el ClassLoader, en vez de abrir la URL
 * del recurso.
 *
 * El ClassLoaderResourceAccessor que trae Liquibase devuelve URIResource, y su openInputStream()
 * hace uri.toURL().openStream(). Eso funciona corriendo desde build/resources, pero NO desde un
 * jar ejecutable de Spring Boot: ahi los recursos viven anidados y su URI es de la forma
 * `jar:nested:/app/bpmplus.jar/!BOOT-INF/classes/!/db/changelog/...`, que el manejador de Spring
 * Boot rechaza al construir la URL con "no !/ in spec".
 *
 * Es decir: sin esto las migraciones andan en desarrollo y fallan empaquetadas, que es la unica
 * forma en que se despliegan. Rompe las dos vias, la del arranque (BootStrap) y la del paso de
 * deploy (MigrationRunner).
 *
 * getResourceAsStream() no arma ninguna URL, asi que sirve igual en los dos casos.
 */
@CompileStatic
class ClasspathResourceAccessor extends ClassLoaderResourceAccessor {

    private final ClassLoader classLoader

    ClasspathResourceAccessor(ClassLoader classLoader = ClasspathResourceAccessor.classLoader) {
        super(classLoader)
        this.classLoader = classLoader
    }

    @Override
    List<Resource> getAll(String path) throws IOException {
        wrap(super.getAll(path))
    }

    @Override
    List<Resource> search(String path, ResourceAccessor.SearchOptions searchOptions) throws IOException {
        wrap(super.search(path, searchOptions))
    }

    @Override
    List<Resource> search(String path, boolean recursive) throws IOException {
        wrap(super.search(path, recursive))
    }

    private List<Resource> wrap(List<Resource> resources) {
        resources ? resources.collect { Resource r -> (Resource) new ClasspathResource(r, classLoader) } : []
    }

    /**
     * Conserva la ruta y la URI del recurso original —Liquibase las usa para identificarlo en
     * DATABASECHANGELOG— y solo cambia por donde se lee el contenido.
     */
    @CompileStatic
    private static class ClasspathResource extends AbstractResource {

        private final ClassLoader classLoader

        ClasspathResource(Resource original, ClassLoader classLoader) {
            super(original.path, original.uri)
            this.classLoader = classLoader
        }

        @Override
        InputStream openInputStream() throws IOException {
            InputStream stream = classLoader.getResourceAsStream(path)
            if (stream == null) {
                throw new IOException("No se encontro el recurso en el classpath: ${path}")
            }
            stream
        }

        @Override
        boolean exists() {
            classLoader.getResource(path) != null
        }

        @Override
        Resource resolve(String other) {
            fromPath(resolvePath(other))
        }

        @Override
        Resource resolveSibling(String other) {
            fromPath(resolveSiblingPath(other))
        }

        private Resource fromPath(String path) {
            java.net.URL url = classLoader.getResource(path)
            if (url == null) {
                throw new IllegalArgumentException("No se encontro el recurso en el classpath: ${path}")
            }
            new ClasspathResource(new SimpleResource(path, url.toURI()), classLoader)
        }
    }

    /** Solo para transportar ruta + URI hacia el constructor de ClasspathResource. */
    @CompileStatic
    private static class SimpleResource extends AbstractResource {

        SimpleResource(String path, URI uri) {
            super(path, uri)
        }

        @Override
        InputStream openInputStream() throws IOException {
            throw new UnsupportedOperationException()
        }

        @Override
        boolean exists() {
            false
        }

        @Override
        Resource resolve(String other) {
            throw new UnsupportedOperationException()
        }

        @Override
        Resource resolveSibling(String other) {
            throw new UnsupportedOperationException()
        }
    }
}
