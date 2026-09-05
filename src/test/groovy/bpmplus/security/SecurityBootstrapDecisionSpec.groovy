package bpmplus.security

import bpmplus.security.SecurityBootstrapService.Decision

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Cuando se crea la primera cuenta de plataforma y con que clave.
 *
 * Importa por dos motivos opuestos. Uno: sin arranque en frio, una base de produccion recien
 * migrada queda sin nadie que pueda entrar, porque la consola donde se crean las cuentas exige
 * una cuenta. Dos: ese mismo arranque en frio no puede convertirse en una puerta trasera que
 * cree administradores en una base que ya esta en uso.
 */
class SecurityBootstrapDecisionSpec extends Specification {

    void 'en desarrollo se siembra una cuenta sin configurar nada'() {
        when:
        Decision d = SecurityBootstrapService.decide(true, 0L, false, null)

        then:
        d.crear
        d.password == SecurityBootstrapService.DEV_PASSWORD
        d.aviso?.contains('clave por defecto')
    }

    void 'fuera de desarrollo NUNCA se usa la clave por defecto'() {
        when: 'base vacia pero sin ADMIN_PASSWORD'
        Decision d = SecurityBootstrapService.decide(false, 0L, false, null)

        then: 'no se crea nada, y el log explica como hacerlo'
        !d.crear
        d.password == null
        d.aviso?.contains('ADMIN_PASSWORD')
    }

    void 'en produccion, con la base vacia y ADMIN_PASSWORD, se crea la primera cuenta'() {
        when:
        Decision d = SecurityBootstrapService.decide(false, 0L, false, 'una-clave-larga')

        then:
        d.crear
        d.password == 'una-clave-larga'
        d.aviso == null
    }

    void 'en produccion con usuarios ya cargados, ADMIN_PASSWORD no crea nada'() {
        when: 'seria una puerta trasera: exportar una variable y tener un administrador nuevo'
        Decision d = SecurityBootstrapService.decide(false, 12L, false, 'otra-clave')

        then:
        !d.crear
        d.aviso?.contains('ya tiene usuarios')
    }

    @Unroll
    void 'si la cuenta ya existe no se toca (desarrollo=#desarrollo, clave=#clave)'() {
        when:
        Decision d = SecurityBootstrapService.decide(desarrollo, 3L, true, clave)

        then: 'ni se recrea ni se le cambia la clave; los roles se revisan igual afuera'
        !d.crear
        d.aviso == null

        where:
        desarrollo | clave
        true       | null
        true       | 'x'
        false      | null
        false      | 'x'
    }

    void 'en desarrollo ADMIN_PASSWORD pisa la clave por defecto'() {
        when:
        Decision d = SecurityBootstrapService.decide(true, 0L, false, 'la-mia')

        then:
        d.crear
        d.password == 'la-mia'
    }
}
