package bpmplus.multitenancy

import java.util.concurrent.ConcurrentHashMap

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import jakarta.servlet.http.HttpServletRequest

import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletWebRequest

import org.grails.datastore.mapping.multitenancy.AllTenantsResolver
import org.grails.datastore.mapping.multitenancy.exceptions.TenantNotFoundException

import bpmplus.security.TenantAwareUser

/**
 * Decide a que tenant pertenece la operacion en curso.
 *
 * La regla es: manda el tenant del usuario autenticado, no la cabecera. La cabecera
 * X-Tenant-Id solo sirve para las cuentas de plataforma, que no pertenecen a ningun cliente
 * y necesitan poder elegir sobre cual trabajar. Si un usuario de un tenant manda una
 * cabecera que apunta a otro, se rechaza la operacion.
 *
 * Sin esta comprobacion la cabecera seria por si sola la llave de acceso a los datos de
 * cualquier cliente, porque en modo SCHEMA el id de tenant es el nombre del schema.
 *
 * Implementa AllTenantsResolver a proposito: en modo SCHEMA, si el resolver NO lo implementa,
 * HibernateDatastore arranca dando de alta como tenant *toda* base que exista en el servidor
 * MySQL. Con esta implementacion la lista sale del registro en la base maestra.
 *
 * GORM instancia esta clase por reflexion (constructor sin argumentos), por eso el registro
 * es estatico y lo puebla TenantProvisioningService en el arranque y en cada alta.
 *
 * Fuera de un request HTTP (jobs, tareas de arranque) hay que envolver el trabajo en
 * Tenants.withId('<codigo>') { ... }, que tiene precedencia sobre este resolver.
 */
@CompileStatic
@Slf4j
class TenantRegistryResolver implements AllTenantsResolver {

    static final String HEADER_NAME = 'X-Tenant-Id'

    /** Rol de las cuentas que operan por encima de los tenants (soporte, administracion). */
    static final String PLATFORM_ADMIN_ROLE = 'ROLE_PLATFORM_ADMIN'

    private static final Set<String> KNOWN_TENANTS = ConcurrentHashMap.newKeySet()

    static void register(String tenantId) {
        if (KNOWN_TENANTS.add(tenantId)) {
            log.info('Tenant registrado: {}', tenantId)
        }
    }

    static void unregister(String tenantId) {
        KNOWN_TENANTS.remove(tenantId)
    }

    static Set<String> knownTenants() {
        Collections.unmodifiableSet(new LinkedHashSet<String>(KNOWN_TENANTS))
    }

    @Override
    Iterable<Serializable> resolveTenantIds() {
        // En el arranque esto devuelve vacio a proposito: el datastore todavia no existe y no
        // se puede consultar el registro con GORM. TenantProvisioningService da de alta los
        // tenants desde BootStrap, una vez que el contexto esta listo.
        new ArrayList<Serializable>(KNOWN_TENANTS)
    }

    @Override
    Serializable resolveTenantIdentifier() throws TenantNotFoundException {
        String userTenant = tenantOfAuthenticatedUser()
        String headerTenant = tenantFromHeader()

        if (userTenant) {
            if (headerTenant && headerTenant != userTenant) {
                log.warn('Un usuario del tenant [{}] pidio [{}] en la cabecera {}: rechazado',
                        userTenant, headerTenant, HEADER_NAME)
                throw new TenantNotFoundException(
                        "La cabecera ${HEADER_NAME} no coincide con el tenant del usuario")
            }
            return requireKnown(userTenant)
        }

        if (isPlatformAdmin()) {
            if (!headerTenant) {
                throw new TenantNotFoundException(
                        "Una cuenta de plataforma debe indicar el tenant en la cabecera ${HEADER_NAME}")
            }
            return requireKnown(headerTenant)
        }

        throw new TenantNotFoundException('No hay un tenant asociado al usuario autenticado. ' +
                'Fuera de un request HTTP usar Tenants.withId(codigo) { ... }')
    }

    private static String tenantOfAuthenticatedUser() {
        Authentication authentication = currentAuthentication()
        Object principal = authentication?.principal
        principal instanceof TenantAwareUser ? ((TenantAwareUser) principal).tenantCode : null
    }

    private static boolean isPlatformAdmin() {
        Authentication authentication = currentAuthentication()
        Collection<? extends GrantedAuthority> authorities = authentication?.authorities
        if (!authorities) {
            return false
        }
        for (GrantedAuthority authority : authorities) {
            if (PLATFORM_ADMIN_ROLE == authority.authority) {
                return true
            }
        }
        false
    }

    private static Authentication currentAuthentication() {
        SecurityContext context = SecurityContextHolder.context
        Authentication authentication = context?.authentication
        authentication?.authenticated ? authentication : null
    }

    private static String tenantFromHeader() {
        RequestAttributes requestAttributes = RequestContextHolder.requestAttributes
        if (!(requestAttributes instanceof ServletWebRequest)) {
            return null
        }
        HttpServletRequest request = ((ServletWebRequest) requestAttributes).request
        request.getHeader(HEADER_NAME) ?: null
    }

    private static String requireKnown(String tenantId) {
        if (!KNOWN_TENANTS.contains(tenantId)) {
            log.warn('Tenant no registrado: [{}]', tenantId)
            throw new TenantNotFoundException("Tenant desconocido: ${tenantId}")
        }
        tenantId
    }
}
