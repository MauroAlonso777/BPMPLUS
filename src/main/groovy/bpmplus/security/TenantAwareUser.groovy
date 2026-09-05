package bpmplus.security

import groovy.transform.CompileStatic

import org.springframework.security.core.GrantedAuthority

import grails.plugin.springsecurity.userdetails.GrailsUser

/**
 * Principal que ademas de la identidad lleva el tenant del usuario.
 *
 * Sin esto, resolver el tenant en cada operacion GORM exigiria ir a la base a buscar a que
 * cliente pertenece el usuario. Al viajar dentro del principal, queda resuelto una sola vez
 * en el login y disponible durante toda la sesion.
 *
 * tenantCode es nulo en las cuentas de plataforma (soporte, administracion), que no
 * pertenecen a ningun cliente.
 */
@CompileStatic
class TenantAwareUser extends GrailsUser {

    private static final long serialVersionUID = 1

    final String tenantCode

    TenantAwareUser(String username, String password, boolean enabled, boolean accountNonExpired,
                    boolean credentialsNonExpired, boolean accountNonLocked,
                    Collection<GrantedAuthority> authorities, Object id, String tenantCode) {
        super(username, password, enabled, accountNonExpired, credentialsNonExpired,
                accountNonLocked, authorities, id)
        this.tenantCode = tenantCode
    }
}
