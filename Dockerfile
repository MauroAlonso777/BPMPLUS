# Imagen de BPMPLUS, con el motor Flowable embebido en el mismo proceso (ver CLAUDE.md).
#
# El esquema NO se crea al arrancar la imagen. Las migraciones son un paso de deploy aparte,
# con la aplicacion apagada: ver `docker compose run --rm migraciones` en docker-compose.yml.
# BootStrap tambien migra, pero eso es para desarrollo; en produccion es tarde, porque para
# entonces la aplicacion ya empezo a atender.

# --- build ---
FROM amazoncorretto:17 AS build
WORKDIR /src

# Primero lo que cambia poco, para que la capa de dependencias se reutilice entre builds.
COPY gradlew gradlew.bat gradle.properties settings.gradle build.gradle ./
COPY gradle ./gradle
COPY grails-wrapper.jar grailsw grailsw.bat ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

COPY grails-app ./grails-app
COPY src ./src

# Sin tests: necesitan un MySQL de verdad, y el build de la imagen no es el lugar.
# La suite corre en CI, antes de construir la imagen.
RUN ./gradlew --no-daemon -x test bootJar

# --- runtime ---
FROM amazoncorretto:17
WORKDIR /app

# Usuario sin privilegios: la aplicacion no necesita root para nada.
RUN yum install -y shadow-utils && yum clean all \
    && groupadd --system bpmplus && useradd --system --gid bpmplus --no-create-home bpmplus

# El HEALTHCHECK de mas abajo necesita curl. Las imagenes de Corretto sobre Amazon Linux 2023
# ya lo traen (curl-minimal), pero eso es una propiedad de la imagen base, no algo garantizado:
# si cambia, un HEALTHCHECK que no puede ejecutarse reporta "unhealthy" para siempre y parece un
# problema de la aplicacion. Se intenta instalarlo y, si despues sigue sin estar, el build falla
# acá, que es donde se entiende el error.
RUN command -v curl > /dev/null 2>&1 \
    || (yum install -y --allowerasing curl && yum clean all) \
    || yum install -y curl \
    || true
RUN command -v curl > /dev/null 2>&1 \
    || (echo 'ERROR: la imagen base no trae curl y no se pudo instalar; el HEALTHCHECK lo necesita' >&2 \
        && exit 1)

COPY --from=build /src/build/libs/*.jar /app/bpmplus.jar
RUN chown bpmplus:bpmplus /app/bpmplus.jar
USER bpmplus

EXPOSE 8080

# El health del motor de procesos sale por aca (indicador "processEngine"): dice a que tenants
# esta enganchado. Ver ProcessEngineHealthIndicator.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENV GRAILS_ENV=production
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/bpmplus.jar"]
