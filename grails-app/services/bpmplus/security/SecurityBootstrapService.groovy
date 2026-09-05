package bpmplus.security

import groovy.transform.Canonical

import grails.gorm.transactions.Transactional
import grails.util.Environment

import bpmplus.multitenancy.TenantRegistryResolver

/**
 * Siembra los roles base y, si hace falta, la primera cuenta de plataforma.
 *
 * User, Role y UserRole no implementan MultiTenant a proposito: la identidad vive en el
 * schema maestro, compartida por todos los tenants. La autenticacion ocurre antes de que se
 * resuelva el tenant del request, asi que no puede depender de el.
 *
 * Los roles se crean en TODOS los entornos. Antes solo en desarrollo, y eso dejaba una base de
 * produccion sin ningun rol: una cuenta creada por migracion no habria tenido a que asignarse.
 *
 * La cuenta, en cambio, depende del entorno:
 *
 * - En desarrollo y test se crea con una clave por defecto, para poder entrar sin configurar nada.
 * - En cualquier otro entorno se crea SOLO si la base de identidad esta vacia y se paso
 *   ADMIN_PASSWORD. Nunca con una clave por defecto, y nunca si ya hay usuarios.
 *
 * Ese arranque en frio es necesario, no una comodidad: la consola de administracion pide una
 * cuenta con ROLE_ADMIN, asi que sin una primera cuenta no hay forma de crear ninguna. Antes la
 * unica salida documentada era "crearlas desde la consola", que es circular.
 */
@Transactional
class SecurityBootstrapService {

    static final String ROLE_ADMIN = 'ROLE_ADMIN'
    static final String ROLE_USER = 'ROLE_USER'

    /** Clave de la cuenta sembrada en desarrollo. Nunca se usa fuera de desarrollo y test. */
    static final String DEV_PASSWORD = 'admin'

    void seed() {
        Role admin = findOrCreateRole(ROLE_ADMIN)
        Role platformAdmin = findOrCreateRole(TenantRegistryResolver.PLATFORM_ADMIN_ROLE)
        findOrCreateRole(ROLE_USER)

        String username = System.getenv('ADMIN_USERNAME') ?: 'admin'
        User existente = User.findByUsername(username)

        Decision decision = decide(
                Environment.current == Environment.DEVELOPMENT || Environment.current == Environment.TEST,
                User.count() as long,
                existente != null,
                System.getenv('ADMIN_PASSWORD'))

        if (decision.aviso) {
            log.warn(decision.aviso, username)
        }

        User user = existente
        if (decision.crear) {
            // Cuenta de plataforma: sin tenant propio, elige sobre cual trabajar con la cabecera
            // X-Tenant-Id. El password se cifra en UserPasswordEncoderListener (PreInsert).
            user = new User(username: username, password: decision.password, tenant: null)
                    .save(failOnError: true)
            log.info('Cuenta de plataforma creada: {}', username)
        }

        if (user) {
            // Las asignaciones se revisan siempre, no solo al crear la cuenta: de lo contrario, un
            // rol agregado despues nunca llegaria a una base que ya tiene el usuario.
            ensureRole(user, admin)
            ensureRole(user, platformAdmin)
        }
    }

    /**
     * Si corresponde crear la primera cuenta, y con que clave.
     *
     * Es una funcion aparte y sin GORM para poder cubrir las ramas sin levantar un datastore:
     * son decisiones de seguridad y conviene que esten fijadas por un test.
     */
    static Decision decide(boolean desarrollo, long usuarios, boolean cuentaYaExiste, String adminPassword) {
        if (cuentaYaExiste) {
            return new Decision(false, null, null)
        }

        if (adminPassword) {
            if (!desarrollo && usuarios > 0) {
                // Ya hay identidad cargada: esto no es un arranque en frio. Crear una cuenta de
                // plataforma nueva porque alguien exporto una variable de entorno seria una
                // puerta trasera, no un bootstrap.
                return new Decision(false, null,
                        'ADMIN_PASSWORD esta definida pero la base ya tiene usuarios: no se crea ' +
                        'ninguna cuenta. Las cuentas nuevas se crean desde la consola de administracion.')
            }
            return new Decision(true, adminPassword, null)
        }

        if (desarrollo) {
            return new Decision(true, DEV_PASSWORD,
                    'Creando el usuario {} con la clave por defecto. Definir ADMIN_PASSWORD para ' +
                    'usar otra; esta cuenta no debe existir fuera de desarrollo.')
        }

        if (usuarios == 0) {
            return new Decision(false, null,
                    'No hay ninguna cuenta y no se definio ADMIN_PASSWORD: nadie va a poder entrar. ' +
                    'Arrancar una vez con ADMIN_PASSWORD (y opcionalmente ADMIN_USERNAME) para crear ' +
                    'la primera cuenta de plataforma, y cambiar la clave desde la consola.')
        }

        new Decision(false, null, null)
    }

    @Canonical
    static class Decision {
        boolean crear
        String password
        /** Mensaje para el log, con {} en el lugar del nombre de usuario. Null si no hay nada que decir. */
        String aviso
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
