# Plan de trabajo: incorporación de Flowable a BPMPLUS

> **Estado (2026-09-05):** plan completo — Fases 0 a 6 ejecutadas y verificadas. El código está
> **listo para desplegar pero no desplegado**: desde la máquina de desarrollo no hay a dónde. El
> runbook completo sí se ensayó en local, en modo `production` y desde el jar
> (ver [Puesta en producción](#puesta-en-producción)). Otras dos salvedades, anotadas en sus
> fases: la imagen Docker no se pudo construir y la suite de la Fase 5 no cubre timers ni tareas
> asíncronas, porque el ejecutor asincrónico está apagado.
> Ver [Resultado](#resultado) y
> [Correcciones respecto de la versión original](#correcciones-respecto-de-la-versión-original).
>
> El original de este documento era un `.docx` con extensión `.md`. Se conserva sin tocar en
> `PLAN_FLOWABLE_BPMPLUS.docx`; esta es la versión en Markdown, con las correcciones que
> impuso la ejecución.

## Objetivo

Incorporar el motor BPM **Flowable Open Source** al proyecto BPMPLUS, construido sobre
IntelliJ + Grails + MySQL, reutilizando la base de código de `proyecto001` (que ya cuenta con
multi-tenancy por base de datos independiente y Spring Security implementados).

## Estado actual

- [x] Bases de datos `BPMPLUS` y `BPMPLUS_TEST` creadas en MySQL.
- [x] Repositorio en GitHub creado.
- [x] Base de código reutilizada de `proyecto001` (multi-tenancy + Spring Security).
- [x] **Integración de Flowable, Fases 0 a 6.**

## Verificación de compatibilidad

Flowable Open Source 7.x requiere **Java 17** como base y soporta Spring Boot 3 de forma
nativa — coincide con el stack actual (Corretto 17.0.20.1, Grails 7.2.3 sobre Spring Boot
3.5.16).

**Versión elegida: 7.2.0**, la última de la línea 7.x. Existe una 8.0.0 en Maven Central, no
evaluada: el plan especifica 7.x y no había razón para ampliar el alcance del spike. Nota para
cuando se evalúe: la línea 8 puede haber movido o cambiado
`MultiSchemaMultiTenantProcessEngineConfiguration`, que es la pieza sobre la que se apoya toda
esta integración.

**MySQL 8.4: verificado.** El spike corrió contra **MySQL 8.4.11** con
`mysql-connector-j 9.7.0`. El motor crea y usa su esquema sin errores de compatibilidad. Lo que
sí apareció fueron tres problemas de integración, ninguno propio de 8.4 sino del uso de una base
por cliente; están en [Resultado](#resultado).

Un cambio relevante de la versión 7: se eliminaron las aplicaciones web de Flowable UI (el
modelador visual de procesos BPMN incluido antes), por lo que se necesita una herramienta
externa para diseñar los diagramas BPMN (Fase 3).

## Decisión de arquitectura clave

Flowable incluye soporte nativo para multi-tenancy por esquema independiente
(`MultiSchemaMultiTenantProcessEngineConfiguration`), donde cada tenant tiene su propio esquema
de base de datos y los tenants se registran dinámicamente en tiempo de ejecución —incluso
después de que el motor ya arrancó— con `registerTenant(tenantId, dataSource)`.

Esto coincide con el modelo de "base de datos independiente por cliente" ya implementado en
`proyecto001` vía GORM. Por lo tanto, la integración usa el motor base de Flowable
(`org.flowable:flowable-engine`) configurado manualmente, **no** el starter de autoconfiguración
de Spring Boot (`flowable-spring-boot-starter`), que asume un único datasource.

### Corrección: no hay "datasources de GORM" que reutilizar

La versión original de este plan asumía que se podían pasarle a `registerTenant()` "los mismos
datasources ya definidos para el multi-tenancy de GORM". **Esos datasources no existen.** El
modo `SCHEMA` de GORM usa **un solo** `DataSource` y un solo pool, y cambia de base por conexión
(`USE <schema>`, vía `MySqlSchemaHandler`). Flowable, en cambio, exige un `DataSource` por
tenant, porque su `TenantAwareDataSource` elige entre un mapa de datasources.

Las dos salidas eran crear un pool por cliente —duplicando conexiones y configuración de
credenciales— o darle a Flowable una vista por tenant sobre el pool que ya existe. Se eligió la
segunda: `bpmplus.flowable.TenantSchemaDataSource` envuelve el pool compartido y apunta cada
conexión al schema del tenant antes de entregarla. **Un pool, N vistas.**

## Fases

| Fase | Qué | Estado |
| --- | --- | --- |
| 0 | Spike de validación | ✅ Hecha |
| 1 | Integración con el TenantResolver existente | ✅ Hecha |
| 2 | Decisión de arquitectura de despliegue | ✅ Confirmada: embebido |
| 3 | Herramienta de diseño de procesos BPMN | ✅ Camunda Modeler + convención |
| 4 | Integración con la lógica de negocio | ✅ Hecha |
| 5 | Pruebas | ✅ Hecha (sin timers, ver la fase) |
| 6 | Despliegue | ✅ Hecha (Docker sin verificar) |

### Fase 0 — Spike de validación ✅

1. [x] Agregar la dependencia `org.flowable:flowable-engine` en `build.gradle` (dependencias de
   la app).
2. [x] Configurar manualmente un `MultiSchemaMultiTenantProcessEngineConfiguration`, registrando
   los tenants vía `registerTenant(tenantId, dataSource)`, **sin crear pools nuevos** (ver la
   corrección de arriba).
3. [x] Arrancar la aplicación y confirmar que Flowable crea su esquema (tablas `ACT_*`) en cada
   base de datos de tenant por separado, sin errores de compatibilidad con MySQL 8.4.
4. [x] Iniciar un proceso BPMN de prueba bajo un tenant y verificar que no sea visible ni
   accesible desde el contexto de otro (aislamiento real, no solo creación de tablas).
5. [x] Escribir un test Spock que automatice esta verificación de aislamiento.

> **Corrección sobre los tenants de prueba.** El plan original proponía registrar `BPMPLUS` y
> `BPMPLUS_TEST` como los dos tenants del spike. No sirven: `BPMPLUS` es el **schema maestro**
> del entorno de desarrollo (identidad y registro de tenants) y `BPMPLUS_TEST` el del entorno de
> test. Usarlos como tenants habría mezclado las tablas `ACT_*` con las de identidad y habría
> hecho pasar por aislamiento lo que en realidad es la separación entre dos entornos. El spec usa
> dos schemas propios, `flw_acme` y `flw_globex`, que crea y borra él mismo.

### Fase 1 — Integración con el TenantResolver existente ✅

Conectar la resolución del tenant activo de Flowable con el mismo `TenantResolver` que ya usa el
resto de la aplicación (GORM), para evitar mantener dos mecanismos de resolución en paralelo.

Hecho: `bpmplus.flowable.FlowableTenantInfoHolder` delega en `Tenants.currentId(datastore)`, que
respeta `Tenants.withId(...)` y, si no, cae en `TenantRegistryResolver`. El usuario autenticado
manda igual para GORM que para Flowable, y la cabecera `X-Tenant-Id` se rechaza igual en los dos.

### Fase 2 — Decisión de arquitectura de despliegue ✅

**Confirmado: embebido**, dentro del mismo proceso de BPMPLUS. Comparte la JVM, el pool de
conexiones y el modelo de tenant con el resto de la aplicación.

Queda visible en `/actuator/health`, bajo el componente `processEngine`: un motor, N tenants
enganchados (`ProcessEngineHealthIndicator`). Si un tenant está en el registro pero no aparece
ahí, alguien se salteó `TenantProvisioningService` y sus procesos no van a arrancar.

Con una corrección al fundamento del plan original, porque cambia lo que se puede prometer en la
Fase 4:

> **No comparte transacciones.** `MultiSchemaMultiTenantProcessEngineConfiguration.createTransactionInterceptor()`
> devuelve `null`: el motor maneja sus propias transacciones JDBC y **nunca** participa de la
> transacción de Spring del servicio que lo llamó. Comparte JVM y modelo de tenant, no
> transacciones. Es una restricción del modo multi-schema, no algo que se pueda configurar, y
> condiciona la Fase 4.

### Fase 3 — Herramienta de diseño de procesos BPMN ✅

**Herramienta: Camunda Modeler** (escritorio, Apache-2.0). Flowable 7 OSS eliminó su propio
modeler, y de las alternativas evaluadas —bpmn-js embebido, el Flowable Modeler 6.8 en Docker, una
extensión de IDE— es la más madura, funciona offline y produce archivos que se versionan como
cualquier fuente.

**Convención:** `src/main/resources/processes/<idDelProceso>.bpmn20.xml`. Hay un `README.md` en
ese directorio con las reglas.

El riesgo de usar el modelador de otro producto es concreto y hubo que cubrirlo: Camunda escribe
sus propiedades como `camunda:...` y **Flowable las descarta en silencio**. El proceso se
despliega, se ejecuta, y el `assignee` o la `expression` de una tarea de servicio no están.
`ProcessDefinitionValidationSpec` convierte ese silencio en un test rojo: parsea cada diagrama,
lo pasa por el validador de Flowable y rechaza cualquier prefijo ajeno.

`ProcessDefinitionDeployer` los despliega en la base de cada tenant, al alta y en cada arranque,
con `enableDuplicateFiltering()` para no crear una versión nueva en cada reinicio.

### Fase 4 — Integración con la lógica de negocio ✅

Una tarea de servicio invoca cualquier bean de la aplicación por expresión:

```xml
<serviceTask id="registrar" flowable:expression="${expedienteService.registrar(execution)}"/>
```

`ApplicationContextBeans` resuelve la raíz de la expresión contra el contexto de Spring, así que
todo `grails-app/services` está disponible por su nombre. Es lo que en una app Spring Boot normal
aporta `flowable-spring`, módulo que acá no se usa porque su autoconfiguración asume un único
datasource.

El servicio **no** declara ningún tenant: GORM lo resuelve con el mismo resolver que el motor, así
que lo que escriba cae en la base del cliente cuyo proceso está corriendo. Es el pago concreto de
la Fase 1. `ProcessLifecycleSpec` lo comprueba escribiendo desde una tarea de servicio y leyendo
la fila por JDBC crudo en el schema esperado.

**Pendiente de diseño por proceso, no de implementación:** por lo dicho en la Fase 2 el motor no
comparte transacción con el servicio. Si el proceso avanza y el servicio falla, o al revés, no hay
un rollback que abarque a los dos. Cada proceso que se escriba tiene que decidirlo.

### Fase 5 — Pruebas ✅

**66 tests, todos verdes** (`./gradlew clean test`), 26 de ellos sobre Flowable, contra MySQL real:

| Spec | Casos | Qué cubre |
| --- | --- | --- |
| `FlowableTenantIsolationSpec` | 9 | Aislamiento entre tenants, esquema por base, arranque repetido |
| `ProcessLifecycleSpec` | 7 | Ciclo de vida completo, tarea de servicio, historial, dos tenants |
| `ProcessDefinitionValidationSpec` | 7 | Todo `.bpmn20.xml` commiteado parsea y valida |
| `ProcessEngineHealthIndicatorSpec` | 3 | El indicador de salud |

El ciclo de vida cubierto: arranque con variables, tarea de servicio que invoca un bean y escribe
con GORM, tarea humana con `assignee` por expresión, gateway exclusivo por sus dos ramas, final e
historial. Todo bajo dos tenants a la vez, con la comprobación de que el historial de uno no se ve
desde el otro.

**Lo que no cubre:** timers ni tareas asíncronas, porque el ejecutor asincrónico está apagado (ver
*Qué queda apagado*). Encenderlo es una decisión aparte y se prueba con ella.

### Fase 6 — Despliegue ✅

**El esquema de Flowable ahora lo gobierna Liquibase**, como todo lo demás. Los changesets
`flowable-001-common`, `-002-engine` y `-003-history` viven en el changelog de tenant y ejecutan
los scripts del **propio jar de Flowable** (`<sqlFile>` sobre rutas del classpath), así que no hay
una copia en el repositorio que pueda desincronizarse de la dependencia.

El motor pasó a `databaseSchemaUpdate = false`: no crea nada, comprueba. Se verificó alterando a
mano la versión del esquema: la aplicación **se niega a arrancar**.

```
FlowableWrongDbException: version mismatch: library version is '7.2.0.2', db version is 6.8.0.0
```

Al subir la versión de Flowable hay que agregar el changeset de upgrade que corresponda; si nadie
lo hace, el arranque falla con ese mensaje, que es el aviso que se busca.

**Empaquetado:** `Dockerfile` (multi-etapa sobre Corretto 17, usuario sin privilegios) y
`docker-compose.yml` con `mysql`, `migraciones` y `app`. `migraciones` no es un servicio sino un
paso: corre `MigrationRunner` desde el mismo jar y termina; `app` depende de que haya terminado
bien. Ese orden es el punto del archivo — el esquema se migra con la aplicación apagada.

> **Sin verificar:** la imagen no se construyó ni se levantó, porque en esta máquina no hay
> Docker. Sí se verificó por separado lo que se podía: `bootJar` produce el jar ejecutable, y
> `MigrationRunner` corre **desde ese jar** y migra el maestro y el tenant. Esa prueba encontró un
> fallo real que de otro modo habría aparecido recién en el primer despliegue: Liquibase no puede
> leer los changelogs desde un jar de Spring Boot. Ver
> [Hallazgos de las Fases 2 a 6](#hallazgos-de-las-fases-2-a-6).

## Resultado

**66 tests verdes** (`./gradlew clean test`), 26 de ellos nuevos, contra MySQL real. Verificado
además a mano, extremo a extremo:

- Liquibase deja las **32 tablas de Flowable dentro de la base de cada tenant, ninguna en la
  maestra**, sin errores contra MySQL 8.4.11 — y lo hace corriendo `MigrationRunner` **desde el
  jar empaquetado**, que es como se va a desplegar.
- La aplicación arranca sin errores y `/actuator/health` responde.
- Con la versión del esquema alterada a mano, la aplicación **se niega a arrancar**.

### Las piezas

| Clase | Qué hace |
| --- | --- |
| `bpmplus.flowable.MultiSchemaProcessEngineFactory` | Arma y sostiene el `ProcessEngine`. Sin Spring, igual que `SchemaMigrator`. |
| `bpmplus.flowable.FlowableTenantInfoHolder` | Le dice al motor sobre qué tenant opera, delegando en el resolver de la aplicación. |
| `bpmplus.flowable.TenantSchemaDataSource` | Vista de un solo schema sobre el pool compartido. |
| `bpmplus.flowable.ApplicationContextBeans` | Expone los beans de Spring a las expresiones de un proceso (Fase 4). |
| `bpmplus.flowable.ProcessDefinitionDeployer` | Despliega los `.bpmn20.xml` del classpath en cada tenant (Fase 3). |
| `bpmplus.flowable.ProcessEngineHealthIndicator` | Publica el motor y sus tenants en `/actuator/health` (Fase 2). |
| `bpmplus.flowable.ProcessEngineService` | Envoltorio de Grails, con el motor y sus servicios. |
| `bpmplus.migration.ClasspathResourceAccessor` | Deja que Liquibase lea los changelogs desde el jar empaquetado (Fase 6). |

Un tenant nuevo pasa a registrarse en **tres** lugares, los tres desde
`TenantProvisioningService.attachSchema()`: Liquibase, GORM y el motor. El detalle está en
`CLAUDE.md`.

### Tres trampas que encontró el spike (Fases 0 y 1)

Las tres fallan en silencio o tarde, que es el patrón que ya venía documentado para el
multi-tenancy de este proyecto.

1. **`nullCatalogMeansCurrent=true`** (agregado a la url JDBC de los tres entornos). Sin eso,
   Connector/J 8+ resuelve `DatabaseMetaData.getTables(null, ...)` contra **todas** las bases del
   servidor, no contra la de la conexión. Con una base por cliente, preguntar "¿existe esta
   tabla?" da que sí porque la tiene **otro** cliente: el segundo tenant se quedaba sin tablas.

2. **El bean `dataSource` de Grails es un `TransactionAwareDataSourceProxy`.** Le prestaba al
   motor la conexión de la transacción de Spring en curso. Como el motor maneja sus propias
   transacciones, hacía commit sobre la transacción de la aplicación y la dejaba en
   `autocommit=true`: el alta de tenants rompía con `Can't call commit when autocommit=true`.
   La fábrica ahora desenvuelve hasta el pool.

3. **`setCatalog()`, no `USE`.** Un `USE` suelto deja al driver creyendo que sigue en la base de
   la url —`Connection.getCatalog()` no lo sigue—, y de ahí cuelga la lectura de metadatos. Las
   tablas se creaban bien la primera vez y el arranque siguiente fallaba con
   `Table 'act_ge_property' already exists`.

Las trampas 2 y 3 las encontró el arranque real de la aplicación, no el spec.

### Hallazgos de las Fases 2 a 6

Dos más, del mismo tipo: fallan tarde y los encontró ejecutar las cosas, no leerlas.

4. **Liquibase no puede leer el changelog desde el jar de Spring Boot.** Su
   `ClassLoaderResourceAccessor` abre los recursos con `uri.toURL().openStream()`, y dentro de un
   jar ejecutable la URI es `jar:nested:/app/bpmplus.jar/!BOOT-INF/classes/!/db/changelog/...`,
   que el manejador de Spring Boot rechaza con `no !/ in spec`. O sea: las migraciones andaban en
   desarrollo y fallaban empaquetadas, que es la única forma en que se despliegan. Rompía las dos
   vías, la de `BootStrap` y la del paso de deploy. Lo resuelve
   `bpmplus.migration.ClasspathResourceAccessor`, que lee con `getResourceAsStream()`.

   Apareció recién al probar `MigrationRunner` desde el jar; construir la imagen sin esa prueba
   habría dado una imagen que falla al arrancar.

5. **El SQL crudo de un changeset caía en el schema maestro.** `SchemaMigrator` le fijaba a
   Liquibase el `defaultSchemaName` del tenant, pero no movía la conexión. Liquibase califica con
   ese nombre lo que entiende (`createTable`, `addColumn`…) y manda el SQL crudo de un `<sql>` o
   un `<sqlFile>` tal cual: las sentencias iban a la base de la url. El primer tenant que se
   migrara se llevaba las tablas de todos.

   Estaba latente desde antes; salió a la luz porque los scripts de Flowable son los primeros
   `<sqlFile>` del proyecto. Lo cubre el caso *el esquema de Flowable no se crea en la base
   maestra*.

### Qué queda apagado, y por qué

- **Motor de IDM** (`disableIdmEngine = true`): la identidad es Spring Security contra el schema
  maestro; el IDM replicaría un segundo padrón de usuarios por cliente.
- **Registro de eventos** (`disableEventRegistry = true`): consulta sus definiciones de canal
  apenas se construye, cuando todavía no hay tenant en curso — `NullPointerException` dentro del
  `TenantAwareDataSource`.
- **Ejecutor asincrónico** (`asyncExecutorActivate = false`): levanta un pool de hilos *por
  tenant*. Encenderlo (timers, tareas `async`) es una decisión aparte, con su propio
  dimensionamiento — y es lo único del alcance que las pruebas de la Fase 5 no cubren.

## Puesta en producción

### Estado: listo para desplegar, **no desplegado**

El código está terminado y verde, pero no se ejecutó ningún despliegue. No es una decisión: desde
la máquina de desarrollo no hay a dónde desplegar. Lo comprobado el 2026-09-05:

| Hace falta | Estado |
| --- | --- |
| Docker (construir la imagen) | ❌ no instalado |
| AWS CLI / credenciales (`~/.aws`) | ❌ ausentes |
| `kubectl`, `terraform` | ❌ ausentes |
| Base de datos de producción (`MYSQL_HOST`, `MYSQL_DATABASE`) | ❌ sin definir |
| Registro de imágenes | ❌ sin definir |
| Rama mergeada a `main` | ❌ el trabajo está en una rama aparte |
| Ensayo del despliegue en local | ✅ runbook completo + un proceso ejecutado por HTTP |

El único MySQL alcanzable es el `localhost` de desarrollo. Ejecutar el deploy contra eso no sería
pasar a producción: sería migrar la base de desarrollo con los changesets de producción.

Lo que sí se hizo es **ensayar el despliegue completo en local**, en modo `production` y desde el
jar, contra una base aparte que hace de maestra. Ver [Ensayo local del
despliegue](#ensayo-local-del-despliegue): encontró cuatro fallos que sólo se ven así.

> **Esto ya no puede pasar por accidente.** `MigrationRunner` rellenaba `MYSQL_HOST` con
> `localhost` y `MYSQL_DATABASE` con `bpmplus` cuando no estaban definidas, mientras que el bloque
> de producción de `application.yml` no les pone fallback. Un deploy que se olvidara de exportar
> las variables no fallaba: migraba la base de desarrollo e informaba que todo salió bien. Ahora,
> en contexto `production`, las dos son obligatorias y el comando para. Lo cubre
> `MigrationRunnerTargetSpec`.
>
> ```
> $ ./gradlew dbMigrate -Penv=production
> Faltan variables de conexion para el contexto [production]: MYSQL_HOST, MYSQL_DATABASE.
> ```

### Ensayo local del despliegue

No hay producción a la que desplegar, pero sí se ejecutó el runbook **completo** en local: el jar
ejecutable, en modo `production`, contra una base `bpmplus_prod` que hace de maestra. Cubre todo
menos construir la imagen.

```bash
export MYSQL_HOST=localhost MYSQL_PORT=3306 MYSQL_DATABASE=bpmplus_prod \
       MYSQL_USER=root MYSQL_PASSWORD=... GRAILS_ENV=production

./gradlew bootJar

R() { java -cp build/libs/BPMPLUS-0.1.jar \
        -Dloader.main=bpmplus.migration.MigrationRunner \
        org.springframework.boot.loader.launch.PropertiesLauncher "$@"; }

R tag v1.0      # etiquetar, con la aplicación apagada
R migrate       # migrar maestro + cada tenant del registro

# primera cuenta de plataforma: sólo se crea si la base de identidad está vacía
ADMIN_USERNAME=plataforma ADMIN_PASSWORD='...' \
  java -XX:MaxRAMPercentage=75 -jar build/libs/BPMPLUS-0.1.jar
```

Resultado, verificado contra la base:

| Comprobación | Resultado |
| --- | --- |
| `dbTag` sobre base virgen | ✅ etiqueta `v1.0` registrada |
| `dbMigrate` maestro + 2 tenants | ✅ `Schemas de tenant (2): acme, globex` |
| Tablas de Flowable por cliente | ✅ 32 en `acme`, 32 en `globex` |
| Tablas de Flowable en la maestra | ✅ ninguna |
| Arranque de la aplicación | ✅ `environment: production`, 0 errores |
| Primera cuenta de plataforma | ✅ `plataforma`, sin tenant, bcrypt, con los dos roles |
| Inicio de sesión | ✅ 302 a `/` |
| `/actuator/health` anónimo | ✅ `{"status":"UP"}`, sin detalle |
| `/actuator/health` autenticado | ✅ motor 7.2.0.2, `tenants: [acme, globex]` |

### Lo que encontró el ensayo

Cuatro cosas que ninguna prueba unitaria habría encontrado, porque sólo aparecen con la aplicación
empaquetada, en modo producción y con varios tenants enganchados.

1. **El login iba a la base de un cliente.** `Table 'acme.user' doesn't exist`. La identidad vive
   en el schema maestro, pero las conexiones volvían al pool apuntando al último tenant que las
   usó, y la consulta heredaba ese schema. Intermitente, según qué conexión tocara. Se cierra
   configurando el `catalog` del pool y cambiando de schema con `setCatalog` en vez de `USE`.

2. **Un despliegue nuevo quedaba sin nadie que pudiera entrar.** Los roles sólo se creaban en
   desarrollo, y la cuenta también. La documentación decía que en producción las cuentas se crean
   «desde la consola», pero la consola exige una cuenta: circular. Ahora hay arranque en frío, y
   sólo con `ADMIN_PASSWORD` explícita y la base de identidad vacía.

3. **`dbTag` fallaba en el primer despliegue.** Etiquetar recorre los tenants del registro, y en
   ese momento la tabla `tenant` todavía no existe. Justo el único despliegue en que no hay nada a
   que volver.

4. **`setCatalog` no falla donde `USE` sí.** GORM decide si tiene que crear el schema de un tenant
   probando a usarlo, y el `DataSource` que entrega es un `LazyConnectionDataSourceProxy`, donde
   `setCatalog` sólo se anota. Por eso el handler hace las dos cosas: `USE` para fallar a tiempo,
   `setCatalog` para que el driver y el pool se enteren.

### Ejecutar un proceso en la versión local

El plan no incluía una API, así que el despliegue arrancaba pero no había forma de ejecutar nada
en él. Se agregó una capa mínima (`ProcesoController` + `ProcesoService`, ver `CLAUDE.md`) y se
ejecutó un proceso completo por HTTP contra el despliegue local, autenticado como cuenta de
plataforma y eligiendo cliente con `X-Tenant-Id`:

```bash
curl -c ck -d "username=plataforma&password=..." localhost:8080/login/authenticate

A() { curl -s -b ck -H "X-Tenant-Id: acme"   -H "Content-Type: application/json" "$@"; }
G() { curl -s -b ck -H "X-Tenant-Id: globex" -H "Content-Type: application/json" "$@"; }

A -X POST -d '{"referencia":"EXP-2026-001"}' localhost:8080/proceso/iniciar/spikeAislamiento
A localhost:8080/proceso/instancias
G localhost:8080/proceso/instancias
```

| Paso | Resultado |
| --- | --- |
| `acme` inicia el proceso | ✅ instancia creada, referencia `EXP-2026-001` |
| Instancias de `acme` | ✅ la suya |
| Instancias de `globex` | ✅ `[]` |
| Tareas de `acme` / de `globex` | ✅ una / ninguna |
| `globex` completa la tarea de `acme` con su id | ✅ **404**, y sigue pendiente para `acme` |
| `acme` la completa | ✅ 200, el proceso termina |
| Sin autenticar | ✅ 302 al login |

Ese ensayo encontró una cosa más: el intento de un cliente sobre la tarea de otro devolvía **500**.
El aislamiento se cumplía —el motor no encuentra la tarea, está en otra base—, pero el código era
el equivocado. Ahora es 404.

El diagrama usado era un fixture de pruebas, montado sólo para el ensayo y quitado después:
`src/main/resources/processes/` queda con su `README.md` y ningún proceso. Los procesos de negocio
se agregan ahí siguiendo esa convención.

### Qué falta decidir antes del primer despliegue

1. **Dónde corre.** CLAUDE.md dice que la migración es a AWS, pero no hay nada elegido: ECS, EKS,
   EC2 con compose, App Runner. El `docker-compose.yml` sirve tal cual para un host único; para
   ECS/EKS hay que traducirlo, respetando que `migraciones` es un paso previo que corre hasta
   terminar, no un servicio.
2. **La base.** RDS o MySQL propio. Tiene que ser **8.0 o superior** y el usuario necesita permiso
   de `CREATE SCHEMA`: cada tenant es una base nueva que crea `SchemaMigrator`.
3. **Los secretos.** `MYSQL_PASSWORD` no puede ir en el compose de producción; va por Secrets
   Manager, SSM o el mecanismo del orquestador.
4. **El registro de imágenes** (ECR u otro) y quién construye — hoy la construcción de la imagen
   es lo único del plan que no se pudo verificar, porque no hay Docker en la máquina.

### Secuencia de despliegue

El orden no es negociable: **las migraciones van con la aplicación apagada**. `BootStrap` también
migra, pero eso es para desarrollo; en producción es tarde, porque para entonces la aplicación ya
empezó a atender.

```bash
export MYSQL_HOST=... MYSQL_PORT=3306 MYSQL_DATABASE=... MYSQL_USER=... MYSQL_PASSWORD=...
export GRAILS_ENV=production

# 1. Etiquetar el estado actual, para poder volver. Recorre el maestro y CADA tenant.
./gradlew dbTag -Ptag=v1.0 -Penv=production

# 2. Apagar la aplicación.

# 3. Migrar. Crea el schema de los tenants nuevos y les aplica el changelog, incluido el
#    esquema de Flowable.
./gradlew dbMigrate -Penv=production

# 4. Arrancar la aplicación.
```

Con la imagen, los pasos 1 y 3 son el mismo `MigrationRunner` desde el jar
(`docker compose run --rm migraciones tag v1.0` / `... migrate`), y `docker-compose.yml` ya
encadena el orden con `service_completed_successfully`.

**Volver atrás:** `./gradlew dbRollback -Ptag=v1.0 -Penv=production`. Revierte primero los tenants
y el maestro al final, y lee la lista de tenants antes de tocar nada, porque revertir el maestro
puede borrar la tabla `tenant`.

### Qué verificar después del primer arranque

- `/actuator/health` responde `UP`. El detalle (motor, tenants enganchados) sólo lo ve una cuenta
  `ROLE_PLATFORM_ADMIN`, porque incluye los códigos de tenant, o sea la lista de clientes.
- Cada base de tenant tiene sus 32 tablas `ACT_*`/`FLW_*`, y la maestra **ninguna**.
- El arranque no reportó `FlowableWrongDbException`. Si lo hizo, el esquema no coincide con la
  versión de la biblioteca y falta el changeset de upgrade.

## Correcciones respecto de la versión original

| Decía | Dice ahora | Por qué |
| --- | --- | --- |
| Formato `.md`, contenido `.docx` | Markdown de verdad; el original queda en `PLAN_FLOWABLE_BPMPLUS.docx` | El archivo no se podía leer ni versionar como texto |
| "Flowable 7.x" | 7.2.0, fijada | Hay que fijar la versión; además existe una 8.0.0 no evaluada |
| MySQL 8.4 "no confirmado, se valida en la Fase 0" | Confirmado sobre 8.4.11 | Es el resultado de la Fase 0 |
| "Reutilizando los datasources ya definidos para GORM" | No existen; se usa una vista por tenant sobre el pool compartido | El modo `SCHEMA` de GORM tiene un solo pool |
| Registrar `BPMPLUS` y `BPMPLUS_TEST` como tenants de prueba | Dos schemas propios del spec | Son los schemas maestros de desarrollo y de test, no tenants |
| Fase 2: "comparte JVM, transacciones y el modelo de tenant" | Comparte JVM y modelo de tenant, **no** transacciones | El modo multi-schema no integra transacciones con Spring |
| Fases 0 y 1 como trabajo futuro | Marcadas como hechas, con resultados | Ya se ejecutaron |
| Fase 6: "creación de esquema gestionada de forma consistente con Liquibase" (a futuro) | Hecho: changesets que ejecutan los scripts del jar de Flowable, y el motor pasa a validar | Se ejecutó la fase |
| Fase 3 sin herramienta definida | Camunda Modeler, con un spec que ataja el namespace ajeno | Decisión tomada; el riesgo que introduce hay que cubrirlo |

## Apéndice — Prompt de referencia de la Fase 0

Se conserva como registro de lo que se pidió. **Ya ejecutado**; los puntos donde la realidad no
coincidió con el enunciado están arriba, en
[Correcciones](#correcciones-respecto-de-la-versión-original).

```text
Contexto: proyecto001 (base de BPMPLUS) ya tiene multi-tenancy implementado
por base de datos independiente (GORM Database-per-Tenant) y Spring Security
con resolución de tenant vía TenantResolver. Ya existen las bases de datos
BPMPLUS y BPMPLUS_TEST en MySQL, configuradas como datasources de tenant.

Objetivo de este spike: incorporar el motor Flowable (BPM open source,
versión 7.x) usando SU PROPIO mecanismo nativo de multi-tenancy por esquema
(MultiSchemaMultiTenantProcessEngineConfiguration), NO el starter de
auto-configuración de Spring Boot (flowable-spring-boot-starter), porque
ese asume un único datasource y no encaja con nuestro modelo de base de
datos independiente por cliente.

Tareas:

1. Agregá la dependencia `org.flowable:flowable-engine` en build.gradle
   (dependencias de la app, no del buildscript). Verificá que la versión
   sea 7.x y compatible con Spring Boot 3.5.16 / Java 17.

2. Configurá un bean de MultiSchemaMultiTenantProcessEngineConfiguration
   y registrá BPMPLUS y BPMPLUS_TEST como tenants de prueba usando
   registerTenant(tenantId, dataSource), reutilizando los mismos
   datasources que ya están definidos para el multi-tenancy de GORM
   (no crees datasources nuevos ni duplicados).

3. Conectá la resolución del tenant activo de Flowable con el mismo
   TenantResolver que ya usa el resto de la aplicación, para que no
   existan dos mecanismos de resolución de tenant en paralelo.

4. Arrancá la aplicación y confirmá:

   a. Que Flowable creó su propio esquema (tablas ACT_*) en CADA base
      (BPMPLUS y BPMPLUS_TEST) por separado.

   b. Que no hay errores de compatibilidad con MySQL 8.4.

5. Escribí un test Spock que inicie un proceso BPMN de prueba bajo el
   tenant BPMPLUS y verifique que NO sea visible ni accesible desde el
   contexto del tenant BPMPLUS_TEST (aislamiento real, no solo
   creación de tablas).

No avances a integrar lógica de negocio ni a elegir herramienta de
modelado BPMN todavía — este spike es solo para validar que el
aislamiento multi-tenant de Flowable funciona correctamente sobre
nuestra arquitectura existente. Mostrame los resultados antes de seguir.
```

## Fuentes

- [Flowable Open Source](https://www.flowable.com/open-source)
- [Flowable Open Source 7.0.0 Release](https://www.flowable.com/blog/releases/flowable-open-source-7-0-0-release)
- [Flowable System Requirements](https://documentation.flowable.com/latest/admin/installs/system-requirements)
- [Flowable Spring Boot Integration Docs](https://www.flowable.com/open-source/docs/bpmn/ch05a-Spring-Boot/)
- [`MultiSchemaMultiTenantProcessEngineConfiguration` — código fuente](https://github.com/flowable/flowable-engine/blob/main/modules/flowable-engine/src/main/java/org/flowable/engine/impl/cfg/multitenant/MultiSchemaMultiTenantProcessEngineConfiguration.java)
