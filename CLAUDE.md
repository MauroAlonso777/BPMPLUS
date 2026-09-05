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

## Criterio para portar stored procedures a servicios Groovy

- Priorizar portar a **servicios Groovy** los SPs que contienen reglas de negocio, validaciones o cálculos (candidatos naturales a lógica de aplicación).
- Evaluar caso por caso los SPs puramente de acceso a datos (reportes pesados, bulk operations) — pueden quedar como consultas SQL nativas o vistas si portarlos a Groovy no aporta valor o degrada performance.
- Documentar en el código (comentario breve o nombre del método) la trazabilidad al SP/trigger original cuando ayude a validar que la migración es funcionalmente equivalente, mientras dure la migración.
