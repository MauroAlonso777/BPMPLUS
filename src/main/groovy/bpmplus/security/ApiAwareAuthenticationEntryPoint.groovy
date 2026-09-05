package bpmplus.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic

import org.springframework.beans.BeansException
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.DefaultRedirectStrategy
import org.springframework.security.web.RedirectStrategy

import grails.plugin.springsecurity.SpringSecurityUtils
import grails.plugin.springsecurity.web.authentication.AjaxAwareAuthenticationEntryPoint

/**
 * Que se responde cuando llega una peticion sin autenticar.
 *
 * El plugin redirige al formulario de login. Para un navegador esta bien; para un cliente de la
 * API de procesos no: recibe un 302 y un HTML donde esperaba JSON, y un 302 no es un error, asi
 * que un cliente descuidado lo toma por una respuesta valida. La forma de decir "te falta
 * autenticacion" en una API es 401.
 *
 * El criterio es la cabecera Accept, que es donde el cliente declara que espera. Un navegador
 * pide text/html; quien pide application/json esta hablando con la API. No se mira la ruta a
 * proposito: si mañana hay mas controllers REST, esto los cubre sin tocarse.
 *
 * Para todo lo demas delega en el punto de entrada del plugin, asi que el login por formulario
 * sigue funcionando igual.
 */
@CompileStatic
class ApiAwareAuthenticationEntryPoint implements AuthenticationEntryPoint, ApplicationContextAware {

    private AuthenticationEntryPoint paraNavegador
    private ApplicationContext applicationContext

    ApiAwareAuthenticationEntryPoint() {
    }

    @Override
    void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext
    }

    /** Para pruebas: permite inyectar el delegado sin levantar la configuracion del plugin. */
    ApiAwareAuthenticationEntryPoint(AuthenticationEntryPoint paraNavegador) {
        this.paraNavegador = paraNavegador
    }

    @Override
    void commence(HttpServletRequest request, HttpServletResponse response,
                  AuthenticationException authException) throws IOException {
        if (esClienteDeApi(request)) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = 'application/json;charset=UTF-8'
            response.writer.write('{"error":"Se requiere autenticacion"}')
            response.writer.flush()
            return
        }
        navegador().commence(request, response, authException)
    }

    static boolean esClienteDeApi(HttpServletRequest request) {
        String accept = request.getHeader('Accept')
        if (accept?.contains('application/json')) {
            return true
        }
        'XMLHttpRequest' == request.getHeader('X-Requested-With')
    }

    /**
     * Se arma tarde y no en el constructor: la url del formulario sale de la configuracion del
     * plugin, que no esta lista cuando se instancian los beans. Leerla de ahi, y no fijarla aca,
     * es lo que evita que cambiar `grails.plugin.springsecurity.auth.loginFormUrl` deje esto
     * apuntando a una url que ya no existe.
     */
    @CompileDynamic
    private AuthenticationEntryPoint navegador() {
        if (paraNavegador == null) {
            String loginFormUrl = SpringSecurityUtils.securityConfig.auth.loginFormUrl as String
            AjaxAwareAuthenticationEntryPoint delegado =
                    new AjaxAwareAuthenticationEntryPoint(loginFormUrl ?: '/login/auth')
            // AjaxAwareAuthenticationEntryPoint redeclara redirectStrategy, asi que NO hereda el
            // valor por defecto de Spring Security: construirlo a mano y no darselo lo deja en
            // null, y el redirect al formulario revienta con NullPointerException. Se toma el del
            // contexto para respetar la configuracion del plugin (contextRelative y demas).
            delegado.redirectStrategy = redirectStrategy()
            paraNavegador = delegado
        }
        paraNavegador
    }

    private RedirectStrategy redirectStrategy() {
        try {
            return applicationContext?.getBean('redirectStrategy', RedirectStrategy)
                    ?: new DefaultRedirectStrategy()
        }
        catch (BeansException ignored) {
            return new DefaultRedirectStrategy()
        }
    }
}
