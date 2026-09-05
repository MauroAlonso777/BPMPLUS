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
}
