package bpmplus.flowable

import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger

import javax.sql.DataSource

import groovy.transform.CompileStatic

import bpmplus.multitenancy.MySqlSchemaHandler

/**
 * Vista de un solo schema sobre el DataSource compartido de la aplicacion.
 *
 * Flowable pide un DataSource por tenant (TenantAwareDataSource elige uno segun el
 * TenantInfoHolder), pero en BPMPLUS no hay un DataSource por cliente: el modo SCHEMA de GORM
 * usa UNA sola conexion/pool y cambia de base con "USE <schema>" en cada checkout. Crear un
 * pool por tenant duplicaria las conexiones y la configuracion de credenciales.
 *
 * Asi que esto no es un DataSource nuevo: envuelve el que ya existe y apunta la conexion al
 * schema del tenant antes de entregarla. Un pool, N vistas.
 *
 * Apunta con setCatalog() y no con "USE <schema>", que es lo que hace MySqlSchemaHandler. Los
 * dos cambian de base para las consultas, pero un USE suelto deja al driver creyendo que sigue
 * en la base de la url: Connection.getCatalog() no lo sigue. Y de ahi cuelga la lectura de
 * metadatos, porque con nullCatalogMeansCurrent=true un getTables(null, ...) se resuelve contra
 * lo que el driver cree que es la base actual, no contra la de verdad.
 *
 * Eso importa porque asi es como Flowable decide si tiene que crear su esquema
 * (AbstractSqlScriptBasedDbSchemaManager.isTablePresent). Con USE, las tablas del tenant se
 * crean bien la primera vez, pero en el arranque siguiente el motor no las ve, vuelve a
 * emitir el DDL y falla con "Table 'act_ge_property' already exists".
 *
 * Ventaja adicional: HikariCP sabe restaurar el catalog al devolver la conexion al pool, cosa
 * que con un USE crudo no puede hacer.
 */
@CompileStatic
class TenantSchemaDataSource implements DataSource {

    private final DataSource target
    private final String schema

    TenantSchemaDataSource(DataSource target, String schema) {
        this.target = target
        // Valida el identificador una sola vez, al construir, en vez de en cada conexion.
        // setCatalog() lo entrecomilla por su cuenta, pero la validacion es la misma que usa
        // el resto de la aplicacion y no depende de lo que haga el driver.
        MySqlSchemaHandler.quoteIdentifier(schema)
        this.schema = schema
    }

    String getSchema() {
        schema
    }

    @Override
    Connection getConnection() throws SQLException {
        pointAtSchema(target.connection)
    }

    @Override
    Connection getConnection(String username, String password) throws SQLException {
        pointAtSchema(target.getConnection(username, password))
    }

    private Connection pointAtSchema(Connection connection) throws SQLException {
        try {
            connection.catalog = schema
        }
        catch (Throwable e) {
            // Una conexion que no se pudo apuntar al schema correcto no puede volver al pool
            // como si nada: leeria o escribiria en la base equivocada.
            try {
                connection.close()
            }
            catch (Throwable ignored) {
            }
            throw e
        }
        connection
    }

    @Override
    PrintWriter getLogWriter() throws SQLException {
        target.logWriter
    }

    @Override
    void setLogWriter(PrintWriter out) throws SQLException {
        target.logWriter = out
    }

    @Override
    void setLoginTimeout(int seconds) throws SQLException {
        target.loginTimeout = seconds
    }

    @Override
    int getLoginTimeout() throws SQLException {
        target.loginTimeout
    }

    @Override
    Logger getParentLogger() throws SQLFeatureNotSupportedException {
        target.parentLogger
    }

    @Override
    def <T> T unwrap(Class<T> iface) throws SQLException {
        iface.isInstance(this) ? (T) this : target.unwrap(iface)
    }

    @Override
    boolean isWrapperFor(Class<?> iface) throws SQLException {
        iface.isInstance(this) || target.isWrapperFor(iface)
    }
}
