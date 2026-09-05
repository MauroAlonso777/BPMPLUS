package bpmplus.multitenancy

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletWebRequest

import org.grails.datastore.mapping.multitenancy.exceptions.TenantNotFoundException

import bpmplus.security.TenantAwareUser

import spock.lang.Specification

class TenantRegistryResolverSpec extends Specification {

    TenantRegistryResolver resolver = new TenantRegistryResolver()

    def setup() {
        TenantRegistryResolver.register('acme')
        TenantRegistryResolver.register('globex')
    }

    def cleanup() {
        RequestContextHolder.resetRequestAttributes()
        SecurityContextHolder.clearContext()
        TenantRegistryResolver.knownTenants().each { TenantRegistryResolver.unregister(it) }
    }

    private static void incomingRequest(String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest()
        if (headerValue != null) {
            request.addHeader(TenantRegistryResolver.HEADER_NAME, headerValue)
        }
        RequestContextHolder.requestAttributes = new ServletWebRequest(request)
    }

    private static void authenticatedAs(String tenantCode, List<String> roles = ['ROLE_USER']) {
        List<GrantedAuthority> authorities = roles.collect { new SimpleGrantedAuthority(it) as GrantedAuthority }
        TenantAwareUser principal = new TenantAwareUser('alguien', 'secreto', true, true, true, true,
                authorities, 1L, tenantCode)
        SecurityContextHolder.context.authentication =
                new UsernamePasswordAuthenticationToken(principal, 'secreto', authorities)
    }

    void 'el tenant del usuario se usa aunque no haya cabecera'() {
        given:
        authenticatedAs('acme')
        incomingRequest(null)

        expect:
        resolver.resolveTenantIdentifier() == 'acme'
    }

    void 'una cabecera que coincide con el tenant del usuario se acepta'() {
        given:
        authenticatedAs('acme')
        incomingRequest('acme')

        expect:
        resolver.resolveTenantIdentifier() == 'acme'
    }

    void 'un usuario no puede alcanzar otro tenant por cabecera'() {
        given: 'un usuario de acme que pide los datos de globex'
        authenticatedAs('acme')
        incomingRequest('globex')

        when:
        resolver.resolveTenantIdentifier()

        then:
        thrown(TenantNotFoundException)
    }

    void 'una cuenta de plataforma elige el tenant por cabecera'() {
        given:
        authenticatedAs(null, ['ROLE_ADMIN', TenantRegistryResolver.PLATFORM_ADMIN_ROLE])
        incomingRequest('globex')

        expect:
        resolver.resolveTenantIdentifier() == 'globex'
    }

    void 'una cuenta de plataforma sin cabecera no resuelve ningun tenant'() {
        given:
        authenticatedAs(null, [TenantRegistryResolver.PLATFORM_ADMIN_ROLE])
        incomingRequest(null)

        when:
        resolver.resolveTenantIdentifier()

        then:
        thrown(TenantNotFoundException)
    }

    void 'una cuenta de plataforma no puede apuntar a un tenant que no existe'() {
        given:
        authenticatedAs(null, [TenantRegistryResolver.PLATFORM_ADMIN_ROLE])
        incomingRequest('no_existe')

        when:
        resolver.resolveTenantIdentifier()

        then:
        thrown(TenantNotFoundException)
    }

    void 'un usuario sin tenant y sin rol de plataforma no llega a ningun tenant'() {
        given:
        authenticatedAs(null, ['ROLE_USER'])
        incomingRequest('acme')

        when:
        resolver.resolveTenantIdentifier()

        then:
        thrown(TenantNotFoundException)
    }

    void 'sin autenticacion la cabecera por si sola no abre ningun tenant'() {
        given:
        SecurityContextHolder.clearContext()
        incomingRequest('acme')

        when:
        resolver.resolveTenantIdentifier()

        then:
        thrown(TenantNotFoundException)
    }

    void 'un usuario cuyo tenant ya no esta registrado es rechazado'() {
        given:
        authenticatedAs('acme')
        TenantRegistryResolver.unregister('acme')
        incomingRequest(null)

        when:
        resolver.resolveTenantIdentifier()

        then:
        thrown(TenantNotFoundException)
    }

    void 'fuera de un request el tenant del usuario sigue resolviendo'() {
        given: 'contexto de seguridad propagado a un hilo sin request'
        authenticatedAs('globex')
        RequestContextHolder.resetRequestAttributes()

        expect:
        resolver.resolveTenantIdentifier() == 'globex'
    }

    void 'resolveTenantIds devuelve los tenants dados de alta'() {
        expect:
        resolver.resolveTenantIds().toList().toSet() == ['acme', 'globex'].toSet()
    }
}
