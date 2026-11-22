# 07 – Docker y Keycloak: las cajas y la boletería

## ¿Qué es Docker? (explicado fácil)

Imaginá que cada microservicio es una planta. Para que crezca necesita tierra, agua y luz justas (Java 21, el `.jar` y la configuración). **Docker** mete cada planta en su **maceta** propia (un **contenedor**) con todo lo que necesita. Así funciona igual en tu computadora, en la de tu compañero o en un servidor.

- La **receta** para armar la maceta es el **Dockerfile**.
- La maceta armada (y todavía sin plantar) es la **imagen**.
- La maceta funcionando es el **contenedor**.
- El **docker-compose.yml** es el plano del **jardín**: qué macetas hay, cómo se conectan y en qué orden se plantan.

```
                    tu computadora
  ┌──────────────────────────────────────────────────────────┐
  │  red "tpi-net" (las macetas se hablan por su nombre)     │
  │                                                          │
  │  [keycloak]:8080 ◄───── 47390 ──── (Postman, navegador)    │
  │  [api-gateway]:47380 ◄── 47380 ──── (Postman)              │
  │  [frontend]:80 ◄─────── 47300 ──── (navegador)            │
  │  [ms-solicitudes]:47381  [ms-tecnicos]:47382               │
  │  [ms-clientes]:47383      (en desarrollo también se       │
  │                           publican 47381-47383)            │
  │                                                          │
  │  volúmenes: patito-clientes-data, patito-tecnicos-data,  │
  │             patito-solicitudes-data  (las bases H2)      │
  └──────────────────────────────────────────────────────────┘
```

---

### ¿Por qué estos puertos?

| Servicio | Puerto |
|---|---|
| frontend | 47300 (adentro, nginx usa el 80) |
| api-gateway | 47380 |
| ms-solicitudes | 47381 |
| ms-tecnicos | 47382 |
| ms-clientes | 47383 |
| Keycloak | 47390 (adentro, la imagen usa el 8080) |

Los puertos "famosos" (8080, 3000, 9090) los usan muchísimos programas: Tomcat, Jenkins, React, Prometheus… Si tenés alguno abierto, el sistema no arranca ("port is already allocated"). Por eso se eligió un rango alto y poco usado, con un número fácil de recordar: **473** + el número de servicio (00 frontend, 80 gateway, 81-83 microservicios, 90 Keycloak).

> ⚠️ Linux usa el rango 32768–60999 para los puertos temporales de las conexiones salientes. Que uno de esos coincida justo con el nuestro es muy raro. Si alguna vez pasa, reiniciá Docker o cambiá el puerto en el `.env`.

## El Dockerfile de un microservicio (ejemplo: ms-solicitudes)

```dockerfile
FROM eclipse-temurin:21.0.8_9-jre
```
- `FROM`: **de qué imagen partimos**. Es como comprar una maceta que ya trae tierra: Linux (Ubuntu) con Java 21 instalado.
- `eclipse-temurin` es la distribución de Java del proyecto Adoptium. `jre` = solo lo necesario para **ejecutar** Java (no compilar); es más liviana.
- **Versión fija** `21.0.8_9` y no `latest`: así la imagen es siempre la misma (lineamiento §1). Es la primera línea del archivo, como pide el lineamiento §10.
- Se usa en lugar de `openjdk:21-jdk-slim` (el ejemplo de la cátedra) porque las imágenes `openjdk` están discontinuadas.

```dockerfile
# ms-solicitudes - Ciclo de vida de las solicitudes
# Antes de construir la imagen: mvn clean package -DskipTests (desde la raíz del proyecto)
```
- Comentarios (`#`): le recuerdan a quien lo lea que el `.jar` tiene que estar compilado antes.

```dockerfile
WORKDIR /app
```
- "A partir de acá, trabajá en la carpeta `/app`" (la crea si no existe). Es como hacer `cd /app`.

```dockerfile
RUN groupadd --system patito && useradd --system --gid patito patito
```
- `RUN`: ejecuta un comando **mientras se arma la imagen**.
- Crea un grupo y un usuario llamados `patito`. Por seguridad, el programa **no** va a correr como `root` (el administrador del sistema): si alguien lo atacara, tendría menos poder.

```dockerfile
RUN mkdir -p /app/data && chown -R patito:patito /app/data
```
- Crea la carpeta de la base H2 y se la **da** al usuario `patito` (`chown` = *change owner*). Sin esto, el programa no podría escribir la base.
- El gateway y el frontend no tienen esta línea: no tienen base de datos.

```dockerfile
COPY target/ms-solicitudes-1.0.0.jar app.jar
```
- Copia el `.jar` compilado por Maven adentro de la imagen y lo renombra `app.jar`. El nombre tiene que coincidir **exactamente** con el que genera Maven (lineamiento §10).

```dockerfile
USER patito
```
- Desde acá, todo corre como el usuario sin privilegios.

```dockerfile
EXPOSE 47381
```
- **Documenta** que el programa escucha en el 47381. No abre nada hacia afuera por sí solo: eso lo decide el compose.

```dockerfile
ENTRYPOINT ["java", "-jar", "app.jar"]
```
- El comando que se ejecuta cuando arranca el contenedor: `java -jar app.jar`.
- La forma **exec** (con corchetes y comillas) hace que Java sea el proceso principal y reciba bien la señal de apagado de `docker stop` (lineamiento §10).

### `.dockerignore`

```
# Solo se envía al build el JAR ya compilado
*
!target/ms-solicitudes-1.0.0.jar
```
- Al construir, Docker le manda a su motor **todos** los archivos de la carpeta (el *contexto*). Esto dice: "ignorá **todo** (`*`) **menos** (`!`) el `.jar`". El build es más rápido y no se cuela nada innecesario.

---

## `docker-compose.yml`: el plano del jardín

### Encabezado y bloques reutilizables

```yaml
name: tpi-tecnica-patito
```
- El nombre del proyecto. Docker lo usa como prefijo de los volúmenes (`tpi-tecnica-patito_patito-clientes-data`).

```yaml
x-logging: &logging
  driver: json-file
  options:
    max-size: "10m"
    max-file: "3"
```
- `x-...`: un bloque **extra** que Docker ignora, pero que podemos reutilizar.
- `&logging`: un **ancla** YAML ("guardá este bloque con el nombre `logging`"). Después cada servicio pone `logging: *logging` para copiarlo.
- La configuración: los logs (lo que el programa escribe en la consola) se guardan en archivos JSON de hasta **10 MB**, y se conservan **3**. Así no llenan el disco (lineamiento §10).

```yaml
x-jwt-env: &jwt-env
  KEYCLOAK_ISSUER_URI: http://localhost:47390/realms/patito
  KEYCLOAK_JWK_SET_URI: http://keycloak:8080/realms/patito/protocol/openid-connect/certs
  TZ: America/Argentina/Cordoba
```
- Las variables de entorno que comparten todos los servicios Java:
  - **la "trampa" de Docker resuelta:** el token dice `iss = http://localhost:47390/...` (lo pediste desde tu compu), pero adentro de Docker no existe ese `localhost:47390`: Keycloak se llama `keycloak:8080`. Por eso el issuer se **compara** con `localhost:47390` y las claves se **bajan** de `keycloak:8080`;
  - `TZ`: la zona horaria de Córdoba. Sin esto, los contenedores estarían en UTC (3 horas adelantadas) y el "fuera de horario" o el vencimiento de las ofertas se calcularían mal.

### Keycloak

```yaml
services:

  keycloak:
    image: quay.io/keycloak/keycloak:26.0.7
    container_name: keycloak
    command: start-dev --import-realm
```
- `image`: una imagen ya hecha (no se construye, se baja de internet). Versión fija.
- `container_name`: el nombre del contenedor (y su nombre en la red).
- `command: start-dev --import-realm`: arranca en **modo desarrollo** (HTTP sin certificados y base interna) e **importa** el realm del archivo JSON.

```yaml
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: ${KEYCLOAK_ADMIN:-admin}
      KC_BOOTSTRAP_ADMIN_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD:-admin}
      KC_HOSTNAME: http://localhost:47390
      KC_HOSTNAME_BACKCHANNEL_DYNAMIC: "true"
      KC_HEALTH_ENABLED: "true"
      TZ: America/Argentina/Cordoba
```
- `${VARIABLE:-valor}`: "usá la variable del archivo `.env`, o este valor si no está". Los secretos no quedan fijos en el compose (lineamiento §10).
- `KC_BOOTSTRAP_ADMIN_*`: el usuario de la **consola de administración** de Keycloak.
- `KC_HOSTNAME: http://localhost:47390`: Keycloak **siempre** pone ese issuer en los tokens, los pida quien los pida. Así el token que pide ms-solicitudes desde adentro de Docker también dice `localhost:47390` y los demás servicios lo aceptan.
- `KC_HOSTNAME_BACKCHANNEL_DYNAMIC`: permite que los servicios internos usen `keycloak:8080` para las comunicaciones "de atrás" (bajar claves y pedir tokens).
- `KC_HEALTH_ENABLED`: activa `/health/ready` para el healthcheck.

```yaml
    ports:
      - "47390:8080"
```
- **Publica** el puerto: `HOST:CONTENEDOR`. Lo que entra por el 47390 de tu computadora va al 8080 del contenedor (el puerto interno de la imagen de Keycloak, que no cambiamos).

```yaml
    volumes:
      - ./keycloak/realm-export.json:/opt/keycloak/data/import/patito.json:ro,z
```
- Monta el archivo del realm adentro del contenedor, en la carpeta de donde Keycloak importa.
- `ro` = solo lectura. `z` = una etiqueta para **SELinux** (la seguridad de Fedora). Sin ella, Fedora no deja que el contenedor lea el archivo.

```yaml
    healthcheck:
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/9000 && printf 'GET /health/ready HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '200 OK' <&3"]
      interval: 10s
      timeout: 5s
      retries: 30
      start_period: 30s
```
- El **chequeo de salud**: cada 10 s Docker le pregunta a Keycloak "¿estás listo?".
- La imagen de Keycloak no trae `curl`, así que se usa un truco de **bash**:
  - `exec 3<>/dev/tcp/127.0.0.1/9000`: abre una conexión TCP al puerto de administración (9000);
  - `printf 'GET /health/ready HTTP/1.1...' >&3`: escribe un pedido HTTP a mano por esa conexión;
  - `grep -q '200 OK' <&3`: lee la respuesta y busca "200 OK".
- `start_period: 30s`: los primeros 30 s no cuentan como fallas (Keycloak tarda en arrancar). `retries: 30`: recién después de 30 fallas seguidas se lo considera "enfermo".

```yaml
    networks:
      - tpi-net
    logging: *logging
```
- Lo conecta a la red `tpi-net` y le aplica la configuración de logs.

### Un microservicio (ms-clientes)

```yaml
  ms-clientes:
    build:
      context: ./ms-clientes
    image: tpi-tecnica-patito/ms-clientes:1.0.0
    container_name: ms-clientes
```
- `build.context`: "construí la imagen con el Dockerfile de esta carpeta" (por eso hace falta `--build`).
- `image`: el nombre y la versión que se le pone a la imagen construida.

```yaml
    environment:
      <<: *jwt-env
      H2_DIR: /app/data
```
- `<<: *jwt-env`: **pega** las variables del bloque `x-jwt-env` (issuer, jwk y TZ) y agrega `H2_DIR`, para que la base vaya a `/app/data`.

```yaml
    expose:
      - "47383"
    volumes:
      - patito-clientes-data:/app/data
```
- `expose`: el puerto es visible **solo dentro de la red** de Docker, no desde tu computadora (lineamiento §10: solo el gateway publica puertos).
- `volumes`: monta el **volumen nombrado** `patito-clientes-data` en `/app/data`. La base H2 vive ahí y **sobrevive** aunque borres el contenedor.

```yaml
    depends_on:
      keycloak:
        condition: service_healthy
    healthcheck: &healthcheck-ms
      test: ["CMD-SHELL", "curl -fs http://localhost:47383/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 20
      start_period: 30s
```
- `depends_on` + `condition: service_healthy`: "no me arranques hasta que Keycloak esté **sano**". `depends_on` solo, sin la condición, esperaría a que el contenedor **arranque**, no a que esté **listo** (lineamiento §10).
- `healthcheck`: pregunta a `/actuator/health` con `curl`. `-f` = falla si el status no es 2xx; `-s` = sin mensajes.
- `&healthcheck-ms`: se guarda como ancla para reutilizarla en los otros servicios, cambiando solo el `test` (el puerto).

### ms-solicitudes (lo distinto)

```yaml
    environment:
      <<: *jwt-env
      H2_DIR: /app/data
      CLIENTES_URL: http://ms-clientes:47383
      TECNICOS_URL: http://ms-tecnicos:47382
      GEO_URL: ${GEO_URL:-https://router.project-osrm.org}
      OFERTA_VENTANA_MINUTOS: ${OFERTA_VENTANA_MINUTOS:-15}
      OFERTA_SCHEDULER_MS: ${OFERTA_SCHEDULER_MS:-30000}
      KEYCLOAK_TOKEN_URI: http://keycloak:8080/realms/patito/protocol/openid-connect/token
      MS_SOLICITUDES_CLIENT_SECRET: ${MS_SOLICITUDES_CLIENT_SECRET:-patito-secret-dev}
```
- `CLIENTES_URL` y `TECNICOS_URL`: los servicios se llaman **por su nombre** (`ms-clientes`), nunca por `localhost` (lineamiento §10). Docker tiene un "DNS" interno que traduce el nombre a la dirección del contenedor.
- `OFERTA_VENTANA_MINUTOS`: la ventana de 15 minutos, configurable desde el `.env` (ponela en 1 para la demo de EN-07).
- `KEYCLOAK_TOKEN_URI` y `MS_SOLICITUDES_CLIENT_SECRET`: para la cuenta de servicio.

```yaml
    depends_on:
      ms-clientes:
        condition: service_healthy
      ms-tecnicos:
        condition: service_healthy
```
- Arranca después de que clientes y técnicos estén sanos.

### api-gateway y frontend

```yaml
  api-gateway:
    ...
    environment:
      <<: *jwt-env
      CLIENTES_URL: http://ms-clientes:47383
      TECNICOS_URL: http://ms-tecnicos:47382
      SOLICITUDES_URL: http://ms-solicitudes:47381
    ports:
      - "${GATEWAY_PORT:-47380}:47380"
    depends_on: (los 3 microservicios healthy)
```
- El **único** servicio del backend con `ports`: la puerta de entrada (lineamiento §10). `GATEWAY_PORT` permite cambiar el puerto de afuera si el 47380 está ocupado.

```yaml
  frontend:
    build:
      context: ./frontend
    image: tpi-tecnica-patito/frontend:1.0.0
    container_name: frontend
    ports:
      - "${FRONTEND_PORT:-47300}:80"
    depends_on:
      api-gateway:
        condition: service_healthy
```
- nginx escucha en el 80 adentro; afuera es el 47300.

### Volúmenes y red

```yaml
volumes:
  patito-clientes-data:
  patito-tecnicos-data:
  patito-solicitudes-data:

networks:
  tpi-net:
    name: tpi-net
    driver: bridge
```
- Se **declaran** los tres volúmenes nombrados (lineamiento §10: nombres descriptivos y únicos).
- La red `tpi-net` de tipo `bridge`: una red privada entre los contenedores.

---

## `docker-compose.override.yml`: el agregado de desarrollo

```yaml
services:
  ms-solicitudes:
    ports:
      - "${MS_SOLICITUDES_PORT:-47381}:47381"
  ms-tecnicos:
    ports:
      - "${MS_TECNICOS_PORT:-47382}:47382"
  ms-clientes:
    ports:
      - "${MS_CLIENTES_PORT:-47383}:47383"
```
- `docker compose up` lee **automáticamente** el `docker-compose.yml` **y** el `docker-compose.override.yml`, y los **combina**.
- Este archivo agrega `ports` a los microservicios: en **desarrollo** podés entrar a su Swagger o a su consola H2 directamente.
- En **producción** se usa `docker compose --file docker-compose.yml up -d --build`: con `--file`, el override **no** se lee, y los microservicios quedan solo accesibles por el gateway.

---

## `.env.example`: el molde de las variables

```
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=admin
OFERTA_VENTANA_MINUTOS=15
OFERTA_SCHEDULER_MS=30000
MS_SOLICITUDES_CLIENT_SECRET=patito-secret-dev
GEO_URL=https://router.project-osrm.org
GATEWAY_PORT=47380
FRONTEND_PORT=47300
MS_SOLICITUDES_PORT=47381
MS_TECNICOS_PORT=47382
MS_CLIENTES_PORT=47383
```
- Copiándolo como `.env`, Docker Compose lo lee solo y reemplaza los `${...}`.
- **Se sube a git el `.env.example`, nunca el `.env`** (está en el `.gitignore`). En un sistema real, el `.env` tendría secretos de verdad (lineamiento §10).

---

## El realm de Keycloak (`keycloak/realm-export.json`)

**Qué es:** la "configuración de la boletería": qué roles existen, qué aplicaciones pueden pedir pulseras y qué usuarios hay. Keycloak lo importa al arrancar.

```json
{
  "realm": "patito",
  "enabled": true,
  "sslRequired": "none",
  "registrationAllowed": false,
  "accessTokenLifespan": 1800,
```
- `realm: "patito"`: el nombre del "mundo".
- `sslRequired: "none"`: permite HTTP sin certificados (solo para desarrollo).
- `registrationAllowed: false`: nadie puede crearse una cuenta solo.
- `accessTokenLifespan: 1800`: el token dura **1800 segundos = 30 minutos**.

```json
  "roles": {
    "realm": [
      { "name": "ADMINISTRADOR", "description": "Administrador de Tecnica Patito" },
      { "name": "CLIENTE", "description": "Cliente que solicita servicios" },
      { "name": "TECNICO", "description": "Tecnico que atiende solicitudes" },
      { "name": "SISTEMA", "description": "Cuenta de servicio para llamadas automáticas entre microservicios" }
    ]
  },
```
- Los 4 roles del realm. Aparecen en el token dentro de `realm_access.roles`.

```json
  "clients": [
    {
      "clientId": "bda-client",
      "publicClient": true,
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": true,
      "redirectUris": ["https://oauth.pstmn.io/v1/browser-callback", "http://localhost:*", "http://localhost:47300/*"],
      "webOrigins": ["*"],
      "attributes": {
        "post.logout.redirect.uris": "+",
        "pkce.code.challenge.method": "S256"
      }
    },
```
- **`bda-client`**: la aplicación que usan las **personas** (Postman y el frontend).
  - `publicClient: true`: no tiene secreto (un navegador o Postman no pueden guardar uno de forma segura);
  - `standardFlowEnabled`: permite el flujo **Authorization Code** (el del frontend);
  - `directAccessGrantsEnabled`: permite el **password grant** (el de Postman: usuario y contraseña directo);
  - `redirectUris`: a qué direcciones puede volver Keycloak después del login. Por seguridad, solo esas;
  - `webOrigins: ["*"]`: desde qué páginas se puede llamar a Keycloak con JavaScript (CORS);
  - `post.logout.redirect.uris: "+"`: después de "Salir", puede volver a las mismas URLs de `redirectUris`;
  - `pkce.code.challenge.method: "S256"`: **exige PKCE** en el flujo Authorization Code (explicado en el documento del frontend).

```json
    {
      "clientId": "ms-solicitudes",
      "publicClient": false,
      "clientAuthenticatorType": "client-secret",
      "secret": "patito-secret-dev",
      "serviceAccountsEnabled": true,
      "standardFlowEnabled": false,
      "directAccessGrantsEnabled": false
    }
  ],
```
- **`ms-solicitudes`**: la **cuenta de servicio** del microservicio.
  - `publicClient: false` + `secret`: es **confidencial**, se identifica con un secreto. El valor es solo de desarrollo y tiene que coincidir con `MS_SOLICITUDES_CLIENT_SECRET`;
  - `serviceAccountsEnabled: true`: puede pedir tokens **para sí misma** (`client_credentials`);
  - los otros flujos apagados: nadie puede iniciar sesión "como" ms-solicitudes.

```json
  "users": [
    { "username": "admin1", ..., "credentials": [{ "type": "password", "value": "admin123", "temporary": false }],
      "realmRoles": ["ADMINISTRADOR"] },
    { "username": "cliente1", ..., "realmRoles": ["CLIENTE"] },
    { "username": "tecnico1", ..., "realmRoles": ["TECNICO"] },
    { "username": "service-account-ms-solicitudes", "enabled": true,
      "serviceAccountClientId": "ms-solicitudes", "realmRoles": ["SISTEMA"] }
  ]
}
```
- Los 3 usuarios de prueba, con su contraseña (`temporary: false` = no hay que cambiarla al entrar) y su rol.
- `service-account-ms-solicitudes`: el **usuario especial** que Keycloak asocia a la cuenta de servicio (`serviceAccountClientId`). Así se le da el rol `SISTEMA`.

> ⚠️ Keycloak en modo `start-dev` guarda todo **adentro del contenedor**. Si lo recreás (`docker compose down` + `up`, o `--force-recreate`), vuelve a importar este archivo. Por eso cualquier cambio permanente se hace en el JSON y no en la consola web.
