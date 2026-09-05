package bpmplus.tenant

/**
 * Registro de tenants. Vive en el schema maestro (la base a la que apunta dataSource.url):
 * al no estar anotada con @MultiTenant, GORM la resuelve siempre por la conexion por defecto.
 *
 * El campo code es el nombre del schema MySQL del tenant, por eso se restringe a minusculas:
 * en MySQL sobre Linux los nombres de base distinguen mayusculas de minusculas.
 */
class Tenant {

    String code
    String name
    boolean active = true

    Date dateCreated
    Date lastUpdated

    static constraints = {
        code blank: false, unique: true, maxSize: 63, matches: /^[a-z][a-z0-9_]{0,62}$/
        name blank: false, maxSize: 200
    }

    static mapping = {
        table 'tenant'
        code index: 'tenant_code_idx'
    }

    String toString() {
        code
    }
}
