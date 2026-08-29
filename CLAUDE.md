# proyecto001

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
- **Otros**: Spring Boot Actuator, Spring Boot DevTools en desarrollo

## Convenciones de código Grails/Groovy

- **Estructura estándar de Grails**: domain classes en `grails-app/domain`, controllers en `grails-app/controllers`, lógica de negocio en `grails-app/services`, no mezclar responsabilidades entre capas.
- **Domain classes**: modelar las entidades reflejando las tablas MySQL migradas desde SQL Server; usar constraints de GORM (`static constraints`) para validaciones en vez de replicar checks que antes vivían en triggers.
- **Services**: la lógica de negocio migrada desde stored procedures va en clases de servicio (`grails-app/services`), con métodos con nombres claros orientados al caso de uso de negocio (no al nombre del SP original salvo que ayude a la trazabilidad durante la migración).
- **Transacciones**: usar `@Transactional` a nivel de servicio (default en Grails) en vez de replicar transacciones manuales; los services son el lugar correcto para orquestar operaciones multi-tabla que antes hacía un SP.
- **Controllers**: delgados — delegan a services, no contienen lógica de negocio ni acceso directo a GORM más allá de lo trivial.
- **Nombres**: seguir convenciones estándar de Groovy/Grails — `UpperCamelCase` para clases, `lowerCamelCase` para métodos y variables, paquete base `proyecto001`.
- **Sin lógica en la base de datos para código nuevo**: no crear nuevos stored procedures/triggers en MySQL; toda lógica nueva o portada debe vivir en Groovy (services), salvo casos justificados (ver criterio de migración).
- **Testing**: cubrir services con specs de Spock, especialmente los que porten lógica de stored procedures, dado que son el reemplazo directo de código crítico de negocio.

## Criterio para portar stored procedures a servicios Groovy

- Priorizar portar a **servicios Groovy** los SPs que contienen reglas de negocio, validaciones o cálculos (candidatos naturales a lógica de aplicación).
- Evaluar caso por caso los SPs puramente de acceso a datos (reportes pesados, bulk operations) — pueden quedar como consultas SQL nativas o vistas si portarlos a Groovy no aporta valor o degrada performance.
- Documentar en el código (comentario breve o nombre del método) la trazabilidad al SP/trigger original cuando ayude a validar que la migración es funcionalmente equivalente, mientras dure la migración.
