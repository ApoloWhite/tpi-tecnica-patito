# Accesos: enlaces, usuarios y credenciales

Todo lo que hace falta para entrar a cada parte del sistema, en una sola hoja. Todos los enlaces y credenciales fueron probados con el sistema levantado con `docker compose up -d`.

> ⚠️ **Son credenciales de desarrollo.** Sirven solo para correr el TPI en tu máquina. En un sistema real se cambiarían y se cargarían como secretos del entorno.

> 📌 Los puertos **47381, 47382 y 47383** (Swagger directo, consola H2 y Actuator de cada microservicio) solo están abiertos en **modo desarrollo**: `docker compose up -d`, que aplica `docker-compose.override.yml`. En modo producción (`docker compose --file docker-compose.yml up -d`) solo quedan abiertos el frontend (47300), el gateway (47380) y Keycloak (47390).

---

## Resumen rápido

| Qué | Enlace | Usuario / contraseña |
|---|---|---|
| 🖥️ Frontend (la página web) | http://localhost:47300 | `cliente1` / `cliente123` · `tecnico1` / `tecnico123` · `tecnico2` / `tecnico123` · `admin1` / `admin123` |
| 📘 Swagger unificado (gateway) | http://localhost:47380/swagger-ui.html | Token de Keycloak en **Authorize** |
| 🔑 Keycloak · consola de administración | http://localhost:47390/admin/master/console/#/patito | `admin` / `admin` |
| 👤 Keycloak · login del ADMINISTRADOR | http://localhost:47390/realms/patito/account | `admin1` / `admin123` |
| 👤 Keycloak · login del CLIENTE (Carlos García) | http://localhost:47390/realms/patito/account | `cliente1` / `cliente123` |
| 👤 Keycloak · login del TÉCNICO (Juan Pérez) | http://localhost:47390/realms/patito/account | `tecnico1` / `tecnico123` |
| 👤 Keycloak · login de la TÉCNICA (Ana Gómez) | http://localhost:47390/realms/patito/account | `tecnico2` / `tecnico123` |
| 🗄️ H2 · ms-solicitudes | http://localhost:47381/h2-console<br>JDBC URL: `jdbc:h2:file:/app/data/solicitudes-db;AUTO_SERVER=TRUE` | `sa` / *(vacía)* |
| 🗄️ H2 · ms-tecnicos | http://localhost:47382/h2-console<br>JDBC URL: `jdbc:h2:file:/app/data/tecnicos-db;AUTO_SERVER=TRUE` | `sa` / *(vacía)* |
| 🗄️ H2 · ms-clientes | http://localhost:47383/h2-console<br>JDBC URL: `jdbc:h2:file:/app/data/clientes-db;AUTO_SERVER=TRUE` | `sa` / *(vacía)* |

> Las JDBC URL del resumen son las de **Docker**. Si levantaste los microservicios con `mvn spring-boot:run`, usá las de la sección 2.

---

## 1. Keycloak (puerto 47390, realm `patito`)

### ¿Dónde me logueo?

Hay **tres pantallas de login**. Las tres son de Keycloak (la página la dibuja Keycloak, no nuestro sistema):

| Pantalla | Enlace para loguearse | Quién entra | Para qué |
|---|---|---|---|
| **Consola de administración** | http://localhost:47390/admin/master/console/#/patito | Solo `admin` / `admin` | Ver y cambiar usuarios, roles, clients y mappers del realm `patito` |
| **Cuenta de usuario** | http://localhost:47390/realms/patito/account | `admin1`, `cliente1`, `tecnico1`, `tecnico2` | Ver los datos del usuario en Keycloak y cerrar sus sesiones |
| **Frontend** (la app) | http://localhost:47300 | `admin1`, `cliente1`, `tecnico1`, `tecnico2` | Usar el sistema: te redirige al login de Keycloak y vuelve a la app |

> ⚠️ `admin` y `admin1` **no son lo mismo**. `admin` es el administrador **de Keycloak** (realm `master`) y solo sirve para la consola de administración. `admin1` es el **ADMINISTRADOR de Técnica Patito** (realm `patito`) y es el que se usa en la app, en Postman y en Swagger. Si ponés `admin1` en la consola de administración (o `admin` en la app), te va a decir "Invalid username or password".

### 🔑 Administrador de Keycloak (consola)

| Campo | Valor |
|---|---|
| **Link para loguearse** | http://localhost:47390/admin/master/console/#/patito |
| **Usuario** | `admin` |
| **Contraseña** | `admin` |
| Realm | `master` (el de administración de Keycloak) |
| Se configura en | `.env` → `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` (por defecto `admin` / `admin`) |

### 👤 admin1: ADMINISTRADOR de Técnica Patito

| Campo | Valor |
|---|---|
| **Link para loguearse (cuenta)** | http://localhost:47390/realms/patito/account |
| **Link para loguearse (app)** | http://localhost:47300 |
| **Usuario** | `admin1` |
| **Contraseña** | `admin123` |
| Rol | `ADMINISTRADOR` |
| En la app ve | Solicitudes (todas) · Nueva solicitud · Informe · Técnicos · Clientes |

### 👤 cliente1: CLIENTE (Carlos García)

| Campo | Valor |
|---|---|
| **Link para loguearse (cuenta)** | http://localhost:47390/realms/patito/account |
| **Link para loguearse (app)** | http://localhost:47300 |
| **Usuario** | `cliente1` |
| **Contraseña** | `cliente123` |
| Rol | `CLIENTE` |
| Quién es en el padrón | Carlos García, cliente nº 1 (claim `clienteId: 1`) |
| En la app ve | Mis solicitudes · Nueva solicitud · Mis datos |

### 👤 tecnico1: TÉCNICO (Juan Pérez)

| Campo | Valor |
|---|---|
| **Link para loguearse (cuenta)** | http://localhost:47390/realms/patito/account |
| **Link para loguearse (app)** | http://localhost:47300 |
| **Usuario** | `tecnico1` |
| **Contraseña** | `tecnico123` |
| Rol | `TECNICO` |
| Quién es en el padrón | Juan Pérez, técnico nº 1 (claim `tecnicoId: 1`) |
| En la app ve | Mis trabajos · Mis datos |

### 👤 tecnico2: TÉCNICA (Ana Gómez)

| Campo | Valor |
|---|---|
| **Link para loguearse (cuenta)** | http://localhost:47390/realms/patito/account |
| **Link para loguearse (app)** | http://localhost:47300 |
| **Usuario** | `tecnico2` |
| **Contraseña** | `tecnico123` |
| Rol | `TECNICO` |
| Quién es en el padrón | Ana Gómez, técnica nº 2 (claim `tecnicoId: 2`) |
| En la app ve | Mis trabajos · Mis datos |

Los tokens de estos usuarios duran **30 minutos**. Para cambiar de usuario en la app: botón **Salir**, arriba a la derecha. En la cuenta de Keycloak: menú del usuario, arriba a la derecha → **Sign out**.

> 💡 Si el navegador "recuerda" al usuario anterior y no te deja elegir otro, abrí el enlace en una **ventana de incógnito** (`Ctrl+Shift+N`).

### Clients del realm

| Client | Tipo | Para qué | Credencial |
|---|---|---|---|
| `bda-client` | Público (sin secreto) | Postman (*password grant*) y frontend (*authorization code + PKCE*) | — |
| `ms-solicitudes` | Confidencial · cuenta de servicio | El scheduler de ms-solicitudes (*client credentials*, rol `SISTEMA`) | secreto `patito-secret-dev` (variable `MS_SOLICITUDES_CLIENT_SECRET` del `.env`) |

### Enlaces de Keycloak

| Para qué | Enlace | Con qué usuario |
|---|---|---|
| Consola de administración, ya en el realm `patito` | http://localhost:47390/admin/master/console/#/patito | `admin` / `admin` (es el admin del realm `master`, no del realm `patito`) |
| Página principal de Keycloak | http://localhost:47390 | — |
| Cuenta personal de un usuario (ver sus datos, cerrar sesiones) | http://localhost:47390/realms/patito/account | `cliente1`, `tecnico1`, `tecnico2` o `admin1` |
| Pedir un token (lo usa Postman) | `POST http://localhost:47390/realms/patito/protocol/openid-connect/token` | ver abajo |
| Configuración OpenID del realm (todas las URL) | http://localhost:47390/realms/patito/.well-known/openid-configuration | — |
| Claves públicas para validar la firma de los tokens | http://localhost:47390/realms/patito/protocol/openid-connect/certs | — |

Dentro de la consola de administración:
- **Users**: los 4 usuarios. Pestaña *Attributes* para ver `clienteId`/`tecnicoId`; pestaña *Role mapping* para el rol.
- **Realm roles**: `ADMINISTRADOR`, `CLIENTE`, `TECNICO` y `SISTEMA`.
- **Clients** → `bda-client` → *Client scopes* → *bda-client-dedicated*: los mappers `clienteId` y `tecnicoId`.

> Si cambiás algo en la consola, se pierde al recrear el contenedor: Keycloak vuelve a importar `keycloak/realm-export.json`. Los cambios permanentes van en ese archivo.

### Pedir un token desde la terminal (uno por usuario)

```bash
# ADMINISTRADOR
curl -s -X POST http://localhost:47390/realms/patito/protocol/openid-connect/token \
  -d grant_type=password -d client_id=bda-client -d username=admin1 -d password=admin123

# CLIENTE (Carlos García)
curl -s -X POST http://localhost:47390/realms/patito/protocol/openid-connect/token \
  -d grant_type=password -d client_id=bda-client -d username=cliente1 -d password=cliente123

# TECNICO (Juan Pérez)
curl -s -X POST http://localhost:47390/realms/patito/protocol/openid-connect/token \
  -d grant_type=password -d client_id=bda-client -d username=tecnico1 -d password=tecnico123

# TECNICO (Ana Gómez)
curl -s -X POST http://localhost:47390/realms/patito/protocol/openid-connect/token \
  -d grant_type=password -d client_id=bda-client -d username=tecnico2 -d password=tecnico123

# Cuenta de servicio de ms-solicitudes (rol SISTEMA)
curl -s -X POST http://localhost:47390/realms/patito/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=ms-solicitudes -d client_secret=patito-secret-dev
```

Para quedarte solo con el token, agregá al final: `| python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'`

Para usarlo, ponelo en el header de cada pedido: `Authorization: Bearer <token>`. En Swagger, pegalo **sin** la palabra `Bearer` en el botón **Authorize**.

---

## 2. Consolas H2 (una base por microservicio)

La consola H2 te muestra primero una pantalla de login. Completala así (un bloque por microservicio; copiá y pegá cada valor):

### 🗄️ ms-solicitudes

| Campo | Valor |
|---|---|
| **Abrir en el navegador** | http://localhost:47381/h2-console |
| **Saved Settings** | `Generic H2 (Embedded)` |
| **Driver Class** | `org.h2.Driver` |
| **JDBC URL** (con Docker) | `jdbc:h2:file:/app/data/solicitudes-db;AUTO_SERVER=TRUE` |
| **JDBC URL** (sin Docker, `mvn spring-boot:run`) | `jdbc:h2:file:./data/solicitudes-db;AUTO_SERVER=TRUE` |
| **User Name** | `sa` |
| **Password** | *(dejar vacía)* |
| Tablas que vas a ver | `SOLICITUD`, `SOLICITUD_TIPO`, `SOLICITUD_TECNICO_DESCARTADO`, `HISTORIAL_ESTADO` |

### 🗄️ ms-tecnicos

| Campo | Valor |
|---|---|
| **Abrir en el navegador** | http://localhost:47382/h2-console |
| **Saved Settings** | `Generic H2 (Embedded)` |
| **Driver Class** | `org.h2.Driver` |
| **JDBC URL** (con Docker) | `jdbc:h2:file:/app/data/tecnicos-db;AUTO_SERVER=TRUE` |
| **JDBC URL** (sin Docker, `mvn spring-boot:run`) | `jdbc:h2:file:./data/tecnicos-db;AUTO_SERVER=TRUE` |
| **User Name** | `sa` |
| **Password** | *(dejar vacía)* |
| Tablas que vas a ver | `TECNICO`, `CERTIFICACION`, `ZONA_COBERTURA`, `TECNICO_CERTIFICACION`, `TECNICO_ZONA` |

### 🗄️ ms-clientes

| Campo | Valor |
|---|---|
| **Abrir en el navegador** | http://localhost:47383/h2-console |
| **Saved Settings** | `Generic H2 (Embedded)` |
| **Driver Class** | `org.h2.Driver` |
| **JDBC URL** (con Docker) | `jdbc:h2:file:/app/data/clientes-db;AUTO_SERVER=TRUE` |
| **JDBC URL** (sin Docker, `mvn spring-boot:run`) | `jdbc:h2:file:./data/clientes-db;AUTO_SERVER=TRUE` |
| **User Name** | `sa` |
| **Password** | *(dejar vacía)* |
| Tablas que vas a ver | `CLIENTE` |

Después tocá **Connect**. A la izquierda aparecen las tablas; hacé clic en una para armar un `SELECT`, y **Run** (o `Ctrl+Enter`) para ejecutarlo.

> ⚠️ **Escribí la JDBC URL exacta.** Si la ruta está mal, H2 **no da error**: crea una base nueva y vacía, y vas a pensar que se borraron los datos. Si no ves las tablas de la lista, revisá la URL.

> La consola no pide token: es pública solo para el desarrollo y la defensa del TPI (está en las rutas permitidas de cada `SecurityConfig`).

### Dónde están los archivos de las bases

| Cómo lo levantaste | Dónde quedan |
|---|---|
| Docker | En volúmenes de Docker: `tpi-tecnica-patito_patito-solicitudes-data`, `..._patito-tecnicos-data`, `..._patito-clientes-data`. Se borran con `docker compose down -v` |
| `mvn spring-boot:run` | En la carpeta `data/` de cada módulo, por ejemplo `ms-clientes/data/clientes-db.mv.db` |

### Consultas útiles para la demo

```sql
-- ms-clientes (47383): los 3 clientes de prueba
SELECT ID, NOMBRE, APELLIDO, DNI, CALLE, BARRIO, LATITUD, LONGITUD FROM CLIENTE;

-- ms-tecnicos (47382): técnicos con sus certificaciones
SELECT T.ID, T.NOMBRE, T.APELLIDO, T.DISPONIBLE, C.NOMBRE AS CERTIFICACION
FROM TECNICO T JOIN TECNICO_CERTIFICACION TC ON TC.TECNICO_ID = T.ID
JOIN CERTIFICACION C ON C.ID = TC.CERTIFICACION_ID ORDER BY T.ID;

-- ms-solicitudes (47381): solicitudes con estado, técnico y costo
SELECT ID, ESTADO, MODO_ASIGNACION, CLIENTE_NOMBRE, TECNICO_NOMBRE, ESTRATEGIA_COSTO, TOTAL, VERSION FROM SOLICITUD;

-- ms-solicitudes: el historial (auditoría RN-7) de la solicitud 1
SELECT ESTADO_ANTERIOR, ESTADO_NUEVO, FECHA_HORA, USUARIO, OBSERVACIONES
FROM HISTORIAL_ESTADO WHERE SOLICITUD_ID = 1 ORDER BY FECHA_HORA, ID;

-- ms-solicitudes: técnicos que rechazaron, dejaron vencer o derivaron cada solicitud
SELECT * FROM SOLICITUD_TECNICO_DESCARTADO;
```

> En la tabla `SOLICITUD` se ve la columna `VERSION` (el bloqueo optimista, `@Version`), que **no** aparece en el JSON de la API porque el DTO no la incluye.

---

## 3. Swagger (documentación interactiva de la API)

| Qué | Enlace |
|---|---|
| **Swagger unificado** (los 3 servicios, a través del gateway) | http://localhost:47380/swagger-ui.html |
| ms-solicitudes directo | http://localhost:47381/swagger-ui.html |
| ms-tecnicos directo | http://localhost:47382/swagger-ui.html |
| ms-clientes directo | http://localhost:47383/swagger-ui.html |
| JSON OpenAPI de cada uno | `http://localhost:4738X/v3/api-docs` (X = 1, 2 o 3) |

Botón **Authorize** → pegá un `access_token` de la sección 1 (sin la palabra `Bearer`). El rol del token define qué podés hacer: por ejemplo, con `cliente1` el informe responde 403.

---

## 4. Frontend

| Qué | Enlace |
|---|---|
| Página web | http://localhost:47300 |

Te lleva al login de Keycloak. Cada usuario ve lo suyo:

| Usuario | Pestañas |
|---|---|
| `cliente1` / `cliente123` | Mis solicitudes · Nueva solicitud · Mis datos |
| `tecnico1` / `tecnico123` (Juan) | Mis trabajos · Mis datos |
| `tecnico2` / `tecnico123` (Ana) | Mis trabajos · Mis datos |
| `admin1` / `admin123` | Solicitudes · Nueva solicitud · Informe · Técnicos · Clientes |

Para cambiar de usuario: botón **Salir**, arriba a la derecha.

---

## 5. Actuator (monitoreo)

| Qué | Enlace | Acceso |
|---|---|---|
| Salud del gateway | http://localhost:47380/actuator/health | Público |
| Salud de ms-solicitudes (incluye el estado de los circuit breakers) | http://localhost:47381/actuator/health | Público |
| Salud de ms-tecnicos | http://localhost:47382/actuator/health | Público |
| Salud de ms-clientes | http://localhost:47383/actuator/health | Público |
| Info de cada microservicio | `http://localhost:4738X/actuator/info` | Público |
| Métricas y niveles de log | `http://localhost:4738X/actuator/metrics` · `/actuator/loggers` | Token de `admin1` |

Ejemplo, cambiar el nivel de log de ms-solicitudes sin reiniciar:

```bash
TOKEN=$(curl -s -X POST http://localhost:47390/realms/patito/protocol/openid-connect/token \
  -d grant_type=password -d client_id=bda-client -d username=admin1 -d password=admin123 \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
curl -X POST http://localhost:47381/actuator/loggers/utnfc.isi.back.tpi \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"configuredLevel":"TRACE"}'
```

---

## 6. Puertos

| Servicio | Puerto en tu máquina | Puerto dentro de Docker | Abierto en producción |
|---|---|---|---|
| Frontend (nginx) | 47300 | 80 | ✅ |
| API Gateway | 47380 | 47380 | ✅ |
| ms-solicitudes | 47381 | 47381 | ❌ (solo desarrollo) |
| ms-tecnicos | 47382 | 47382 | ❌ (solo desarrollo) |
| ms-clientes | 47383 | 47383 | ❌ (solo desarrollo) |
| Keycloak | 47390 | 8080 | ✅ |
