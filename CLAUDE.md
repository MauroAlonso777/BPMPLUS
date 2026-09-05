# BPMPLUS

## Contexto del proyecto

Backend construido con **Grails 7.2.3 + MySQL** que reemplaza una aplicación legacy escrita en **PowerBuilder**, actualmente en proceso de migración a **AWS**.

La aplicación legacy consiste en:
- ~80 ventanas (windows) de PowerBuilder
- ~200 DataWindows
- Framework **PFC** (PowerBuilder Foundation Classes)
- ~500 stored procedures / triggers implementados en **SQL Server**

El objetivo de este proyecto es reconstruir esa funcionalidad como un backend moderno, portando la lógica de negocio contenida en los stored procedures y triggers a **servicios Groovy** dentro de Grails, cuando corresponda (ver criterio más abajo), en lugar de mantenerla como lógica embebida en la base de datos.

## Stack tecnológico

- **Framework**: Grails 7.2.3
- **Lenguaje**: Groovy, compilado con Java 17 (`compileJava.options.release = 17`)
- **Persistencia**: GORM sobre Hibernate 5 (`grails-data-hibernate5`)
- **Base de datos**: MySQL (`mysql-connector-j`), pool de conexiones HikariCP
- **Build**: Gradle (usar `./gradlew` / `./grailsw`, no invocar `grails` global)
- **Vistas**: GSP + asset-pipeline (Bootstrap, jQuery vía webjars) — solo si se requiere alguna vista server-side; el foco principal es backend/API
- **Testing**: Spock (`grails-testing-support-web`, `grails-testing-support-datamapping`), JUnit Platform como runner
- **Multi-tenancy**: GORM en modo `SCHEMA` — una base MySQL por tenant, compartiendo conexión y pool
- **Seguridad**: `grails-spring-security` y `grails-spring-security-ui` 7.0.2
- **Migraciones**: Liquibase (`liquibase-core`) — `dbCreate: none` en todos los entornos
- **Otros**: Spring Boot Actuator, Spring Boot DevTools en desarrollo

## Convenciones de código Grails/Groovy

- **Estructura estándar de Grails**: domain classes en `grails-app/domain`, controllers en `grails-app/controllers`, lógica de negocio en `grails-app/services`, no mezclar responsabilidades entre capas.
- **Domain classes**: modelar las entidades reflejando las tablas MySQL migradas desde SQL Server; usar constraints de GORM (`static constraints`) para validaciones en vez de replicar checks que antes vivían en triggers.
- **Services**: la lógica de negocio migrada desde stored procedures va en clases de servicio (`grails-app/services`), con métodos con nombres claros orientados al caso de uso de negocio (no al nombre del SP original salvo que ayude a la trazabilidad durante la migración).
- **Transacciones**: usar `@Transactional` a nivel de servicio (default en Grails) en vez de replicar transacciones manuales; los services son el lugar correcto para orquestar operaciones multi-tabla que antes hacía un SP.
- **Controllers**: delgados — delegan a services, no contienen lógica de negocio ni acceso directo a GORM más allá de lo trivial.
- **Nombres**: seguir convenciones estándar de Groovy/Grails — `UpperCamelCase` para clases, `lowerCamelCase` para métodos y variables, paquete base `bpmplus`.
- **Sin lógica en la base de datos para código nuevo**: no crear nuevos stored procedures/triggers en MySQL; toda lógica nueva o portada debe vivir en Groovy (services), salvo casos justificados (ver criterio de migración).
- **Testing**: cubrir services con specs de Spock, especialmente los que porten lógica de stored procedures, dado que son el reemplazo directo de código crítico de negocio.

## Multi-tenancy: cada entidad nueva toca DOS lugares

La aplicación es multi-tenant en modo `SCHEMA`: cada cliente tiene su propia base MySQL, y el
tenant se resuelve por request a partir del usuario autenticado. Al crear una domain class que
guarde **datos de un cliente**, hay que hacer dos cosas, y olvidarse de cualquiera de ellas
falla en silencio o tarde:

1. **Declararla `MultiTenant`**:

   ```groovy
   class Factura implements MultiTenant<Factura> { ... }
   ```

   Si se omite, GORM la resuelve por la conexión por defecto y su tabla queda en el schema
   maestro, **compartida por todos los clientes**. No hay error: los datos de un cliente
   simplemente quedan visibles para los demás.

2. **Agregar su changeset a `src/main/resources/db/changelog/tenant/changelog.xml`**, nunca al
   changelog maestro.

   Si el changeset va al maestro, la tabla se crea una sola vez en la base maestra y ninguna
   base de tenant la tiene: la entidad falla en runtime con `table doesn't exist` la primera
   vez que se la usa. Si no se agrega en ningún lado, la tabla no existe (con `dbCreate: none`
   Hibernate no crea nada).

Las entidades que **no** son de un cliente —identidad (`User`, `Role`, `UserRole`), el registro
`Tenant`— van sin `MultiTenant` y su changeset en `db/changelog/master/changelog.xml`. La
autenticación ocurre antes de que se resuelva el tenant, así que la identidad no puede vivir
dentro de una base de tenant.

### Por qué las migraciones son obligatorias acá

GORM **no** crea las tablas dentro de las bases de tenant, ni siquiera con `dbCreate: update`:
en `addTenantForSchemaInternal` guarda `Environment.DEFAULT_SCHEMA` (`hibernate.default_schema`)
dentro del mapa `HibernateSettings`, y `toProperties()` vuelve a prefijar todas sus claves con
`hibernate.`, así que Hibernate recibe `hibernate.hibernate.default_schema` y la descarta. El
aislamiento en runtime no se ve afectado —lo da el `USE <schema>` por conexión—, pero el DDL por
tenant nunca apunta al schema correcto. Liquibase es el único mecanismo que puebla esas bases.

### Cómo se aplican las migraciones

Tareas Gradle (grupo `database`). Todas recorren el schema maestro y **todos** los tenants del
registro, cada uno con su propio `DATABASECHANGELOG`:

```bash
./gradlew dbMigrate  -Penv=production            # aplicar
./gradlew dbTag      -Ptag=v1.4 -Penv=production # etiquetar antes de un deploy
./gradlew dbRollback -Ptag=v1.4 -Penv=production # volver a esa etiqueta
```

- **Conexión**: variables de entorno `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_DATABASE`, `MYSQL_USER`,
  `MYSQL_PASSWORD` — las mismas que usa `application.yml`, para que el deploy y la aplicación no
  puedan apuntar a bases distintas por descuido.
- **En deploy**: correr `dbTag` y después `dbMigrate` con la aplicación apagada, antes de
  arrancarla.
- **En el arranque**: `BootStrap` migra el maestro y `TenantProvisioningService` migra la base de
  cada tenant al darlo de alta o registrarlo. Sirve para desarrollo; en producción es tarde.
- Se migran **todos** los tenants, activos o no: `active` decide si la aplicación atiende al
  cliente, no si se mantiene su esquema.
- `dbRollback` revierte primero los tenants y al final el maestro, y lee la lista de tenants
  antes de tocar nada: revertir el maestro puede borrar la tabla `tenant`.

### Contexto: qué changesets aplican en cada entorno

El contexto sale de `-Penv=<entorno>` o de `GRAILS_ENV`; en el arranque de la aplicación es el
entorno de Grails. Un changeset sin `context` se aplica siempre; uno con `context="development"`
solo en desarrollo.

**`SchemaMigrator` exige contexto y falla si no se lo pasan.** No es una formalidad: cuando
Liquibase corre sin contexto aplica **todos** los changesets, incluidos los marcados para
desarrollo. Un default silencioso significaría cargar datos de desarrollo en producción.

### Rollback

Liquibase deduce solo el rollback de los cambios estructurales (`createTable`, `addColumn`,
`addForeignKeyConstraint`, `createIndex`…). Un changeset con `<sql>`, `<update>`, `<delete>` o
`<insert>` **no** es reversible por sí solo y tiene que declarar su `<rollback>` explícito; si no,
`dbRollback` falla al llegar a él.

### El esquema no se puede desviar de los mapeos

`MasterSchemaValidationSpec` construye una base vacía solo con Liquibase y le pide a Hibernate que
la valide contra las domain classes. Si un changeset se desvía —un tipo distinto, una columna que
falta, un nullable que no coincide— el test falla. Corre con `./gradlew test`, así que es el
control de CI.

Es lo que detectó que el `BOOLEAN` de Liquibase genera `TINYINT` en MySQL mientras Hibernate
espera `BIT(1)`. Sin esa comprobación, la deriva aparece recién en producción.

Nota: `dbCreate: validate` no sirve como configuración permanente, porque Hibernate valida al
construir el `SessionFactory`, antes de que corran las migraciones. El spec hace los dos pasos en
el orden correcto.

## Flowable (motor BPM)

Estado: **plan completo (Fases 0 a 6)**. El motor corre embebido, su esquema lo gobierna
Liquibase igual que el resto, los procesos se despliegan solos en cada tenant y una tarea de
servicio puede invocar los servicios Groovy de la aplicación.

Se usa `org.flowable:flowable-engine` 7.2.0 **pelado**, no `flowable-spring-boot-starter`: el
starter autoconfigura el motor sobre un único `DataSource`, y acá cada cliente tiene su propia
base MySQL. La configuración se arma a mano con `MultiSchemaMultiTenantProcessEngineConfiguration`.

Las piezas:

- `bpmplus.flowable.MultiSchemaProcessEngineFactory` — arma y sostiene el `ProcessEngine`. Sin
  Spring, igual que `SchemaMigrator`, para que la puedan usar tanto la aplicación como un spec.
- `bpmplus.flowable.FlowableTenantInfoHolder` — le dice al motor sobre qué tenant opera.
- `bpmplus.flowable.TenantSchemaDataSource` — vista de un solo schema sobre el pool compartido.
- `bpmplus.flowable.ApplicationContextBeans` — expone los beans de Spring a las expresiones de un
  proceso; es lo que permite que una tarea de servicio llame a un servicio Groovy.
- `bpmplus.flowable.ProcessDefinitionDeployer` — despliega los `.bpmn20.xml` del classpath en
  cada tenant, de forma idempotente.
- `bpmplus.flowable.ProcessEngineHealthIndicator` — publica en `/actuator/health` el motor y los
  tenants que tiene enganchados.
- `bpmplus.flowable.ProcessEngineService` — el envoltorio de Grails, con el motor y sus servicios.

### Un tenant nuevo se registra en TRES lugares

A los dos de la sección anterior (GORM y el changelog) se suma el motor. Los tres los hace
`TenantProvisioningService.attachSchema()`, en este orden y no en otro:

1. `schemaMigrationService.migrateTenant(code)` — crea la base y sus tablas.
2. `TenantRegistryResolver.register(code)` + `hibernateDatastore.addTenantForSchema(code)`.
3. `processEngineService.attachTenant(code)` — engancha la base al motor, que valida ahí el
   esquema, y le despliega los procesos del classpath.

El paso 3 va al final porque el motor resuelve el tenant con el mismo resolver que el resto de
la aplicación: si el código no está registrado todavía, la resolución falla.

### La resolución de tenant es una sola

`FlowableTenantInfoHolder.getCurrentTenantId()` delega en `Tenants.currentId(datastore)`, que
respeta `Tenants.withId(...)` y, si no, cae en `TenantRegistryResolver`. O sea: el usuario
autenticado manda igual para GORM que para Flowable, y la cabecera `X-Tenant-Id` se rechaza
igual en los dos. No hay un segundo mecanismo que mantener sincronizado.

Hay dos escapes, los dos acotados y documentados en la clase:

- `setCurrentTenantId()` / `clearCurrentTenantId()`: el `ThreadLocal` que fija el propio motor
  para trabajar sobre un tenant fuera de un request (crear su esquema, cerrarse). Es también el
  camino para tareas de administración como desplegar un `.bpmn20.xml`.
- `withoutTenantResolution { ... }`: solo durante el arranque del motor. `initAsyncExecutor()`
  pregunta por el tenant en curso antes de que exista ninguno, y el contrato de Flowable admite
  `null` ahí; el resolver de la aplicación, en cambio, rechaza esa pregunta a propósito.

### Los diagramas BPMN y cómo llegan a cada tenant

Los `.bpmn20.xml` viven en `src/main/resources/processes/`. Ahí hay un `README.md` con la
convención de nombres y, sobre todo, con las dos cosas que hay que mirar al guardar desde el
modelador; conviene leerlo antes de agregar el primero.

La herramienta es **Camunda Modeler** (escritorio, Apache-2.0), porque Flowable 7 OSS eliminó su
propio modeler. El riesgo de usar el modelador de otro producto es concreto: Camunda escribe sus
propiedades como `camunda:...` y **Flowable las descarta en silencio**. El proceso se despliega,
se ejecuta, y el `assignee` o la `expression` de una tarea de servicio simplemente no están.
`ProcessDefinitionValidationSpec` convierte ese silencio en un test rojo: parsea y valida cada
diagrama con Flowable y rechaza cualquier prefijo ajeno.

`ProcessDefinitionDeployer` los despliega en la base de cada tenant, al darlo de alta y en cada
arranque, con `enableDuplicateFiltering()`: si el archivo no cambió, no se crea una versión nueva.
Sin eso, cada reinicio generaría una versión más de cada proceso en cada cliente.

Es **un despliegue por archivo**, no uno que los agrupe. `enableDuplicateFiltering()` compara
contra el último despliegue del mismo nombre y, si difiere en algo, lo despliega entero: agrupados,
tocar un diagrama le subiría la versión a todos los demás. Las instancias en curso no se verían
afectadas —siguen con la versión con la que arrancaron—, pero el repositorio de cada cliente se
llenaría de versiones que no cambiaron nada.

### Cómo una tarea de servicio llama a un servicio Groovy

```xml
<serviceTask id="registrar" flowable:expression="${expedienteService.registrar(execution)}"/>
```

`ApplicationContextBeans` resuelve la raíz de la expresión contra el contexto de Spring, así que
cualquier bean de `grails-app/services` está disponible por su nombre. Es lo que en una app Spring
Boot normal aporta `flowable-spring`, módulo que acá no se usa porque su autoconfiguración asume
un único datasource.

El servicio **no** tiene que declarar ningún tenant: GORM lo resuelve por su cuenta con el mismo
resolver que el motor, así que lo que escriba cae en la base del cliente cuyo proceso está
corriendo. Es el pago concreto de la Fase 1, y `ProcessLifecycleSpec` lo comprueba escribiendo
desde una tarea de servicio y leyendo la fila por JDBC crudo en el schema esperado.

Lo que sí hay que tener presente es lo de la Fase 2: **el motor no comparte transacción** con el
servicio que invoca. Si el proceso avanza y el servicio falla, o al revés, no hay un rollback que
los abarque a los dos; hay que decidirlo explícitamente en cada proceso.

### El motor no puede recibir el bean `dataSource` tal cual

`MultiSchemaProcessEngineFactory` desenvuelve el `DataSource` que le pasan hasta llegar al pool
(`unwrapTransactionAware`). No es una optimización: el bean `dataSource` de Grails es un
`TransactionAwareDataSourceProxy`, y si hay una transacción de Spring abierta en el hilo,
`getConnection()` devuelve **la conexión de esa transacción** en vez de una del pool.

Con esta configuración multi-schema el motor maneja sus propias transacciones JDBC
(`createTransactionInterceptor()` devuelve `null`). Sobre una conexión prestada eso significa
que confirma cambios que la aplicación no decidió confirmar y, al cerrar, MyBatis le restaura
`autocommit=true`. Se ve al dar de alta un tenant, porque `TenantProvisioningService` es
`@Transactional`: `Could not commit Hibernate transaction ... Can't call commit when
autocommit=true`.

La contrapartida hay que tenerla presente en la **Fase 4**: lo que hace Flowable se confirma por
su cuenta y nunca comparte transacción con el servicio Groovy que lo llamó.

### `setCatalog()`, no `USE`, para apuntar la conexión del motor

`TenantSchemaDataSource` apunta la conexión con `setCatalog(schema)`. `MySqlSchemaHandler`, que
es el camino de GORM, usa `USE <schema>`: los dos cambian de base para las consultas, pero un
`USE` suelto deja al driver creyendo que sigue en la base de la url — `Connection.getCatalog()`
no lo sigue.

Eso importa porque de ahí cuelga la lectura de metadatos, que es como Flowable decide si tiene
que crear su esquema. Con `USE`, las tablas del tenant se crean bien la primera vez y en el
arranque siguiente el motor no las ve, vuelve a emitir el DDL y falla con
`Table 'act_ge_property' already exists`. Como beneficio adicional, HikariCP sabe restaurar el
catalog al devolver la conexión al pool, cosa que con un `USE` crudo no puede hacer.

### `nullCatalogMeansCurrent=true` no es decorativo

Está en la url JDBC de los tres entornos y en la de los specs. Sin esa propiedad, Connector/J 8+
resuelve `DatabaseMetaData.getTables(null, ...)` contra **todas** las bases del servidor, no
contra la de la conexión. Con una base por cliente, preguntar "¿existe esta tabla?" da que sí
porque la tiene **otro** cliente.

Flowable crea su esquema justo así (`AbstractSqlScriptBasedDbSchemaManager.isTablePresent`):
sin la propiedad, el primer tenant obtiene sus tablas, el segundo se da por hecho y queda vacío,
y el fallo aparece más tarde como `Table 'x.act_ge_property' doesn't exist`. Es la misma clase
de error silencioso que una entidad sin `MultiTenant`.

### Qué queda apagado, y por qué

- **Motor de IDM** (`disableIdmEngine = true`). La identidad la maneja Spring Security contra el
  schema maestro. El IDM de Flowable replicaría usuarios y grupos en tablas `ACT_ID_*` dentro de
  la base de cada cliente: dos padrones que se irían separando.
- **Registro de eventos** (`disableEventRegistry = true`). Apenas termina de construirse consulta
  sus definiciones de canal, y en ese momento no hay tenant en curso ni una base única sobre la
  que preguntar: da `NullPointerException` dentro del `TenantAwareDataSource`. Si más adelante se
  quieren eventos, hay que arrancarlo por tenant, no junto con el motor.
- **Ejecutor asincrónico** (`asyncExecutorActivate = false`). Levanta un pool de hilos *por
  tenant*. Los timers y las tareas `async` no forman parte del alcance actual; encenderlo es una
  decisión aparte, con su propio dimensionamiento.

### El esquema de Flowable lo gobierna Liquibase

Los changesets `flowable-001-common`, `-002-engine` y `-003-history` viven en el changelog de
tenant y ejecutan **los scripts del propio jar de Flowable** (`<sqlFile>` sobre rutas del
classpath). No hay copia en el repositorio que pueda desincronizarse de la dependencia.

El motor arranca con `databaseSchemaUpdate = false`: no crea nada, **comprueba** que la versión
del esquema sea la que espera su versión de la biblioteca. Una desviación deja de ser un
problema de producción y pasa a ser un error de arranque:

```
FlowableWrongDbException: version mismatch: library version is '7.2.0.2', db version is 6.8.0.0
```

**Al subir la versión de Flowable** hay que agregar un changeset nuevo con el script de upgrade
que corresponda (`org/flowable/common/db/upgrade/...`, `org/flowable/db/upgrade/...`). Los
changesets existentes ya corrieron y Liquibase no los repite; si nadie agrega el upgrade, el
arranque falla con el mensaje de arriba, que es justamente el aviso que se busca.

### Liquibase no puede leer el changelog desde el jar empaquetado

Por eso existe `bpmplus.migration.ClasspathResourceAccessor`. El `ClassLoaderResourceAccessor`
de Liquibase abre los recursos con `uri.toURL().openStream()`, y dentro de un jar ejecutable de
Spring Boot la URI es `jar:nested:/app/bpmplus.jar/!BOOT-INF/classes/!/db/changelog/...`, que el
manejador de Spring Boot rechaza con `no !/ in spec`.

Sin eso las migraciones andan en desarrollo y fallan empaquetadas, que es la única forma en que
se despliegan — y rompe las dos vías, la de `BootStrap` y la de `MigrationRunner`. El accessor
propio lee con `getResourceAsStream()`, que no arma ninguna URL.

### El SQL crudo necesita que la conexión esté en el schema del tenant

`SchemaMigrator.onSchema()` hace `connection.catalog = schema` además de fijarle a Liquibase el
`defaultSchemaName`. Liquibase califica con ese nombre lo que entiende (`createTable`,
`addColumn`…), pero el SQL crudo de un `<sql>` o un `<sqlFile>` lo manda tal cual: sin mover la
conexión, esas sentencias caen en la base de la **url**, o sea en el schema maestro, y el primer
tenant que se migre se lleva las tablas de todos.

Apareció al meter los scripts de Flowable, que son los primeros `<sqlFile>` del proyecto. Lo
cubre el caso `el esquema de Flowable no se crea en la base maestra` de
`FlowableTenantIsolationSpec`.

### La API de procesos

Añadida **fuera del plan**, por una razón concreta: el plan cubre las siete fases y deja el motor
embebido y funcionando, pero sin ninguna superficie HTTP. El despliegue arrancaba, enganchaba los
tenants y no había forma de ejecutar nada en él.

`ProcesoController` (delgado, sólo traduce request y respuesta) sobre `ProcesoService` (las
operaciones, devolviendo mapas planos para que los tipos de Flowable no lleguen a la capa web):

| | |
| --- | --- |
| `GET /proceso/definiciones` | procesos desplegados, última versión |
| `GET /proceso/instancias` | instancias en curso |
| `GET /proceso/tareas?asignadoA=` | tareas pendientes |
| `POST /proceso/iniciar/<clave>` | arranca; cuerpo `{"referencia": "...", "variables": {...}}` |
| `POST /proceso/completar/<idTarea>` | completa; cuerpo `{"variables": {...}}` |

**Ninguna acepta el tenant como parámetro.** Lo resuelve el `TenantResolver` a partir del usuario
autenticado; una cuenta de plataforma elige con `X-Tenant-Id`, que el resolver acepta sólo para
esas cuentas. Aceptarlo por parámetro abriría exactamente el agujero que todo el diseño evita.

Códigos de respuesta que importan:

- **404** cuando lo pedido no existe *en la base de ese cliente*. Incluye el caso de usar el id de
  una tarea de otro cliente: como el aislamiento es físico, el motor no la encuentra, y para quien
  llama es indistinguible de que no exista. Antes salía 500, que además sugería un problema del
  servidor.
- **403** cuando no se pudo resolver el tenant: o faltan credenciales útiles, o se pidió un tenant
  que no corresponde.

### Una conexión no puede volver al pool apuntando a un tenant

Es el fallo más caro que apareció en todo esto, y se veía como un error de login:

```
Table 'acme.user' doesn't exist
```

La identidad **no** es multi-tenant: vive en el schema maestro. Pero la consulta caía en la base
de un cliente, y de forma intermitente, según qué conexión del pool tocara.

La causa: las conexiones se apuntan al schema del tenant en cada checkout y volvían al pool
apuntando ahí. La siguiente consulta a una tabla no multi-tenant heredaba el schema del último
cliente que usó esa conexión. Cierra con dos mitades, y hacen falta las dos:

1. `dataSource.properties.catalog` (en los tres entornos) es el schema maestro. HikariCP restaura
   ahí la conexión al devolverla — pero sólo si tiene un catalog configurado.
2. `MySqlSchemaHandler` cambia de schema con `setCatalog`, porque Hikari sólo se entera si el
   cambio pasó por ahí. Un `USE` crudo no marca nada.

Lo fija `PoolCatalogRestoreSpec`, que además deja pinchado el comportamiento viejo: tras un `USE`
crudo, `getCatalog()` dice «maestro» mientras `SELECT DATABASE()` dice «tenant».

### Por qué el handler hace `USE` **y** `setCatalog`

Parece redundante y no lo es. El `DataSource` que entrega GORM es un
`LazyConnectionDataSourceProxy`: ahí `setCatalog` sólo se **anota** y se aplica cuando alguien
ejecuta algo. Apuntar con `setCatalog` a un schema inexistente no lanza nada.

Y de esa excepción depende algo importante: GORM decide si tiene que **crear** el schema de un
tenant probando a usarlo, y creándolo si `useSchema` lanza
(`HibernateDatastore.addTenantForSchemaInternal`). Sin excepción no crea nada, y el fallo aparece
mucho después, al construir el `SessionFactory` del tenant, como `Unknown database`.

Así que el `USE` va primero —obliga a que el cambio ocurra ahora y falla si el schema no existe— y
el `setCatalog` después, que es lo que ven el driver y el pool. Cuesta un viaje más por checkout;
es el precio de que las dos cosas sean ciertas a la vez.

### La primera cuenta de plataforma

`SecurityBootstrapService` crea los roles en **todos** los entornos. Antes sólo en desarrollo, y
eso dejaba una base de producción sin ningún rol: una cuenta creada por migración no habría tenido
a qué asignarse.

La cuenta se siembra con clave por defecto sólo en desarrollo y test. Fuera de ahí se crea **sólo
si la base de identidad está vacía y se pasó `ADMIN_PASSWORD`**. Nunca con clave por defecto, y
nunca si ya hay usuarios — eso sería una puerta trasera: exportar una variable de entorno y
tener un administrador nuevo en una base en uso.

Ese arranque en frío es necesario, no una comodidad. La consola donde se crean las cuentas exige
una cuenta con `ROLE_ADMIN`, así que sin una primera cuenta no hay forma de crear ninguna; la
única salida documentada antes era «crearlas desde la consola», que es circular. Las ramas están
fijadas en `SecurityBootstrapDecisionSpec`.

### El registro de tenants puede no existir todavía

`SchemaMigrator.registeredTenantCodes()` devuelve vacío si la tabla `tenant` no existe, en vez de
fallar. Es el primer despliegue: el runbook dice etiquetar y después migrar, y etiquetar recorre
los tenants del registro — que todavía no está creado. Sin esto, `./gradlew dbTag` fallaba con
`Table 'tenant' doesn't exist` justo en el único despliegue en que no hay nada a que volver.

La comprobación es por metadatos y no un `SELECT` dentro de un `try/catch`: tragarse una excepción
de SQL escondería también un error de permisos, y el resultado sería recorrer cero tenants creyendo
que no hay ninguno. Lo cubre `SchemaMigratorRegistrySpec`.

### Empaquetado y despliegue

**Nada está desplegado todavía.** El runbook de producción, con lo que falta decidir y la
secuencia exacta, está en `PLAN_FLOWABLE_BPMPLUS.md`, sección *Puesta en producción*.

`Dockerfile` (build multi-etapa sobre Corretto 17, usuario sin privilegios, `bootJar`) y
`docker-compose.yml` con tres servicios: `mysql`, `migraciones` y `app`.

`migraciones` no es un servicio sino un paso: corre `MigrationRunner` **desde el mismo jar** de la
aplicación y termina; `app` depende de que haya terminado bien
(`condition: service_completed_successfully`). Ese orden es el punto del archivo: el esquema se
migra con la aplicación apagada. `BootStrap` también migra, pero eso es para desarrollo — en
producción es tarde, porque para entonces la aplicación ya empezó a atender.

`/actuator/health` es público a propósito (lo consulta el `HEALTHCHECK`), pero el **detalle** está
restringido a `ROLE_PLATFORM_ADMIN` vía `management.endpoint.health.roles`: incluye los códigos de
tenant enganchados, o sea la lista de clientes.

**Sin verificar:** la imagen no se construyó ni se levantó, porque en esta máquina no hay Docker.
Sí se verificó lo que se pudo por separado: `bootJar` produce el jar ejecutable, y `MigrationRunner`
corre desde ese jar y migra maestro y tenants. `.github/workflows/ci.yml` repite las dos cosas en
cada push, además de la suite.

### Un deploy a producción sin destino tiene que parar, no elegir otra base

`MigrationRunner.connectionSettings()` exige `MYSQL_HOST` y `MYSQL_DATABASE` cuando el contexto es
`production`; en cualquier otro entorno apunta al MySQL local. Es la misma asimetría que ya tenía
`application.yml`, donde el bloque de producción usa `${MYSQL_HOST}` y `${MYSQL_DATABASE}` sin
fallback.

Antes no coincidían: el runner rellenaba con `localhost`/`bpmplus`, así que un deploy que se
olvidara de exportar las variables migraba **la base de desarrollo** aplicando los changesets de
producción, e informaba que todo salió bien. Lo cubre `MigrationRunnerTargetSpec`.

### Dónde se comprueba

`FlowableTenantIsolationSpec` corre contra el MySQL local y `bpmplus_test`. Verifica que las
tablas se creen en la base de cada tenant y **no** en la maestra, que un segundo arranque sobre
schemas ya creados no repita el DDL, que un proceso arrancado en un tenant no se vea ni se pueda
completar desde el otro (comprobado además por JDBC crudo, sin pasar por el motor), que la
cabecera y la falta de autenticación se rechacen igual que en GORM, y que la fábrica se quede
con el pool y no con el envoltorio transaccional.

`ProcessLifecycleSpec` cubre el ciclo de vida completo bajo dos tenants: despliegue idempotente,
tarea de servicio que invoca un bean y escribe con GORM en el schema correcto, tarea humana,
gateway, historial y aislamiento del historial entre tenants.

`ProcessDefinitionValidationSpec` valida todo `.bpmn20.xml` commiteado (ver
`src/main/resources/processes/README.md`).

El despliegue completo se ensayó en local, en modo `production` y desde el jar ejecutable, contra
una base que hace de producción — es lo que está en `PLAN_FLOWABLE_BPMPLUS.md`, sección *Ensayo
local del despliegue*. Ese ensayo es el que destapó el fallo del login y el de la primera cuenta.

Además se comprobó a mano, sobre la aplicación corriendo:

- Migrar **desde el jar empaquetado** deja las 32 tablas dentro de la base del tenant y ninguna en
  la maestra.
- La aplicación arranca sin errores y `/actuator/health` responde.
- Con un diagrama en `src/main/resources/processes/`, el arranque lo despliega en la base del
  tenant (versión 1, un despliegue por archivo), y un segundo arranque **no** crea otra versión.
- Con la versión del esquema alterada a mano, la aplicación **se niega a arrancar**.

## Criterio para portar stored procedures a servicios Groovy

- Priorizar portar a **servicios Groovy** los SPs que contienen reglas de negocio, validaciones o cálculos (candidatos naturales a lógica de aplicación).
- Evaluar caso por caso los SPs puramente de acceso a datos (reportes pesados, bulk operations) — pueden quedar como consultas SQL nativas o vistas si portarlos a Groovy no aporta valor o degrada performance.
- Documentar en el código (comentario breve o nombre del método) la trazabilidad al SP/trigger original cuando ayude a validar que la migración es funcionalmente equivalente, mientras dure la migración.
