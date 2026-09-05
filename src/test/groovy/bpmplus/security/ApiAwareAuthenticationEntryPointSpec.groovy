package bpmplus.security

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Que recibe una peticion sin autenticar.
 *
 * Un 302 al formulario de login no es una respuesta util para un cliente de la API: espera JSON y
 * recibe HTML, y como 302 no es un error, un cliente descuidado lo toma por bueno. Para el
 * navegador, en cambio, el redirect es exactamente lo que hace falta.
 */
class ApiAwareAuthenticationEntryPointSpec extends Specification {

    AuthenticationEntryPoint delegado = Mock(AuthenticationEntryPoint)
    ApiAwareAuthenticationEntryPoint entryPoint = new ApiAwareAuthenticationEntryPoint(delegado)
    AuthenticationException excepcion = Stub(AuthenticationException)

    @Unroll
    void 'un cliente de API recibe 401 (#descripcion)'() {
        given:
        MockHttpServletRequest request = new MockHttpServletRequest()
        cabeceras.each { String k, String v -> request.addHeader(k, v) }
        MockHttpServletResponse response = new MockHttpServletResponse()

        when:
        entryPoint.commence(request, response, excepcion)

        then: 'responde el, no el del formulario'
        0 * delegado.commence(_, _, _)
        response.status == 401
        response.contentType.startsWith('application/json')
        response.contentAsString.contains('error')

        where:
        descripcion            | cabeceras
        'Accept json'          | ['Accept': 'application/json']
        'Accept json entre varios' | ['Accept': 'text/plain, application/json;q=0.9']
        'peticion ajax'        | ['X-Requested-With': 'XMLHttpRequest']
    }

    @Unroll
    void 'un navegador sigue yendo al formulario (#descripcion)'() {
        given:
        MockHttpServletRequest request = new MockHttpServletRequest()
        if (accept) {
            request.addHeader('Accept', accept)
        }
        MockHttpServletResponse response = new MockHttpServletResponse()

        when:
        entryPoint.commence(request, response, excepcion)

        then: 'delega, y no escribe nada por su cuenta'
        1 * delegado.commence(request, response, excepcion)
        response.status == 200
        response.contentAsString == ''

        where:
        descripcion        | accept
        'Accept html'      | 'text/html,application/xhtml+xml'
        'sin Accept'       | null
        'Accept cualquiera'| '*/*'
    }
}
