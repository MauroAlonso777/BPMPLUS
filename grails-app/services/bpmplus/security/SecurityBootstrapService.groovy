package bpmplus.security

import grails.gorm.transactions.Transactional
import grails.util.Environment

import bpmplus.multitenancy.TenantRegistryResolver

/**
 * Siembra los roles base y una cuenta de administrador de plataforma para poder entrar a la
 * consola de Spring Security UI. Solo corre en desarrollo y test: en produccion las cuentas
 * se crean por migracion o desde la propia consola.
 *
 * User, Role y UserRole no implementan MultiTenant a proposito: la identidad vive en el
 * schema maestro, compartida por todos los tenants. La autenticacion ocurre antes de que se
 * resuelva el tenant del request, asi que no puede depender de el.
 */
@Transactional
class SecurityBootstrapService {

    static final String ROLE_ADMIN = 'ROLE_ADMIN'
    static final String ROLE_USER = 'ROLE_USER'

    void seed() {
        if (Environment.current != Environment.DEVELOPMENT && Environment.current != Environment.TEST) {
            return
        }

        Role admin = findOrCreateRole(ROLE_ADMIN)
        Role platformAdmin = findOrCreateRole(TenantRegistryResolver.PLATFORM_ADMIN_ROLE)
        findOrCreateRole(ROLE_USER)

        String username = System.getenv('ADMIN_USERNAME') ?: 'admin'
        User user = User.findByUsername(username)

        if (!user) {
            String password = System.getenv('ADMIN_PASSWORD')
            if (!password) {
                password = 'admin'
                log.warn('Creando el usuario {} con la clave por defecto. Definir ADMIN_PASSWORD ' +
                        'para usar otra; esta cuenta no debe existir fuera de desarrollo.', username)
            }
            // Cuenta de plataforma: sin tenant propio, elige sobre cual trabajar con la cabecera
            // X-Tenant-Id. El password se cifra en UserPasswordEncoderListener (PreInsert).
            user = new User(username: username, password: password, tenant: null).save(failOnError: true)
            log.info('Cuenta de plataforma creada: {}', username)
        }

        // Las asignaciones se revisan siempre, no solo al crear la cuenta: de lo contrario, un
        // rol agregado despues nunca llegaria a una base de desarrollo que ya tiene el usuario.
        ensureRole(user, admin)
        ensureRole(user, platformAdmin)
    }

    private Role findOrCreateRole(String authority) {
        Role.findByAuthority(authority) ?: new Role(authority: authority).save(failOnError: true)
    }

    private void ensureRole(User user, Role role) {
        if (!UserRole.exists(user.id, role.id)) {
            UserRole.create(user, role, true)
            log.info('Rol {} asignado a {}', role.authority, user.username)
        }
    }
}
