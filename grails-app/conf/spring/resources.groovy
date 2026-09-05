import bpmplus.flowable.ProcessEngineHealthIndicator
import bpmplus.security.ApiAwareAuthenticationEntryPoint
import bpmplus.security.TenantAwareUserDetailsService
import bpmplus.security.UserPasswordEncoderListener

// Place your Spring DSL code here
beans = {
    userPasswordEncoderListener(UserPasswordEncoderListener)

    // Pisa el userDetailsService del plugin para que el principal incluya el tenant.
    userDetailsService(TenantAwareUserDetailsService) {
        grailsApplication = ref('grailsApplication')
        transactionManager = ref('transactionManager')
        targetDatastore = ref('hibernateDatastore')
    }

    // Pisa el del plugin, que redirige al formulario. Un cliente que pide JSON recibe 401 en
    // vez de un 302 a un HTML; el navegador sigue yendo al formulario.
    authenticationEntryPoint(ApiAwareAuthenticationEntryPoint)

    // Se registra a mano porque el motor no es un bean de Spring Boot: lo construye
    // ProcessEngineService. El nombre del bean menos el sufijo "HealthIndicator" es la clave
    // que aparece bajo components en /actuator/health, o sea "processEngine".
    processEngineHealthIndicator(ProcessEngineHealthIndicator, ref('processEngineService'))
}
