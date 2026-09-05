package bpmplus.security

import groovy.transform.CompileDynamic

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

import grails.plugin.springsecurity.userdetails.GormUserDetailsService

/**
 * Extiende el UserDetailsService del plugin para que el principal lleve el tenant.
 *
 * Se registra como bean 'userDetailsService' en grails-app/conf/spring/resources.groovy,
 * pisando el que define el plugin.
 */
@CompileDynamic
class TenantAwareUserDetailsService extends GormUserDetailsService {

    @Override
    protected UserDetails createUserDetails(user, Collection<GrantedAuthority> authorities) {
        User u = user as User
        // La lectura de u.tenant ocurre dentro de la transaccion que abre loadUserByUsername,
        // asi que la asociacion perezosa se puede resolver aca.
        new TenantAwareUser(u.username, u.password, u.enabled,
                !u.accountExpired, !u.passwordExpired, !u.accountLocked,
                authorities, u.id, u.tenant?.code)
    }
}
