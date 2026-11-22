# Cómo funciona todo por dentro

La [guía paso a paso](GUIA-PASO-A-PASO.md) explica **qué** hace cada pieza. Este documento explica **cómo** lo hace: seguimos una petición desde que sale de Postman hasta que vuelve la respuesta, y al final hay un diccionario con todas las palabras técnicas.

---

## 1. Las capas de un microservicio: una pizzería

Cada microservicio está armado igual, como una pizzería con empleados que tienen **un solo trabajo cada uno**:

```
          Cliente (Postman / frontend)
                     │  pedido HTTP
                     ▼
 ┌──────────────────────────────────────────┐
 │ Filtros        → el patovica de la puerta │  CorrelationIdFilter, seguridad (token)
 ├──────────────────────────────────────────┤
 │ Controller     → el mozo                  │  recibe el pedido, lo anota y lo pasa a la cocina
 ├──────────────────────────────────────────┤
 │ Service        → el cocinero              │  TODA la lógica: reglas, cálculos, decisiones
 ├──────────────────────────────────────────┤
 │ Repository     → la heladera              │  guarda y busca ingredientes (datos)
 ├──────────────────────────────────────────┤
 │ Model (Entity) → los ingredientes         │  cómo es cada cosa guardada (tablas)
 └──────────────────────────────────────────┘
          DTO = la bandeja con la que el mozo lleva y trae los platos
          Exception handler = el encargado que atiende las quejas
```

Reglas de la pizzería (son los lineamientos de la cátedra):

- El **mozo** (controller) no cocina: no tiene lógica de negocio. Solo recibe, valida que el pedido esté bien escrito (`@Valid`) y devuelve la respuesta con el código HTTP correcto.
- El **cocinero** (service) es el único que decide. Si algo no se puede hacer, lanza una **excepción** ("no hay más masa").
- La **heladera** (repository) solo guarda y busca. No decide nada.
- Al cliente **nunca** se le muestra la heladera por dentro: se le muestra un plato servido (el **DTO**), no la entidad de la base de datos.
- Si algo sale mal, el **encargado** (`GlobalExceptionHandler`) le explica al cliente qué pasó con un formato prolijo (ProblemDetail).

---

## 2. El viaje de una petición (de punta a punta)

Seguimos este pedido: **el cliente crea una solicitud en modo automático**.

```
POST http://localhost:47380/api/v1/solicitudes
Authorization: Bearer eyJhbGciOi...
{ "clienteId": 1, "tiposServicio": ["REPARACION"], "especialidadId": 2, "modoAsignacion": "AUTOMATICA", ... }
```

### Paso 0: conseguir el token (antes de todo)
Postman le manda a **Keycloak** (`localhost:47390`) el usuario y la contraseña. Keycloak los revisa y devuelve un **JWT**: un texto firmado con tres partes separadas por puntos (`cabecera.datos.firma`). En la parte de datos dice, entre otras cosas:

```json
{
  "iss": "http://localhost:47390/realms/patito",     ← quién lo emitió
  "preferred_username": "cliente1",                 ← quién sos
  "realm_access": { "roles": ["CLIENTE"] },          ← qué rol tenés
  "exp": 1764000000                                  ← cuándo vence
}
```

La **firma** la hace Keycloak con una clave privada que solo él conoce. Cualquiera puede comprobarla con la clave **pública**, pero nadie puede falsificarla. Por eso el backend confía en el token sin preguntarle a Keycloak en cada pedido.

### Paso 1: el API Gateway (puerta 47380)
1. **`CorrelationIdFilter`** (gateway): si el pedido no trae el header `X-Correlation-ID`, inventa uno (un UUID) y lo agrega. Es el número de ticket que va a viajar por todo el sistema.
2. **Seguridad del gateway** (`SecurityConfig`): toma el token del header `Authorization` y:
   - baja las claves públicas de Keycloak (`jwk-set-uri`) y comprueba la firma;
   - revisa que no esté vencido (`exp`) y que lo haya emitido `localhost:47390/realms/patito` (`iss`);
   - si algo falla → responde **401** y el pedido no sigue.
3. **Rutas** (`GatewayRoutesConfig`): el path empieza con `/api/v1/solicitudes`, así que lo reenvía a `http://ms-solicitudes:47381`, con el mismo token y el mismo correlation id.

### Paso 2: los filtros de ms-solicitudes
1. **`CorrelationIdFilter`** (microservicio): lee el `X-Correlation-ID` y lo guarda en el **MDC**, una "mochila" del hilo que Logback lee para escribir `[cid:...]` en cada línea de log.
2. **Spring Security** (Resource Server): vuelve a validar el token (nunca hay que confiar ciegamente en la red interna) y convierte `realm_access.roles` en *authorities*: `CLIENTE` → `ROLE_CLIENTE`.

### Paso 3: el controller (`SolicitudController.crear`)
1. Spring ve `POST /api/v1/solicitudes` y elige el método anotado con `@PostMapping`.
2. **Jackson** convierte el JSON del cuerpo en un objeto `SolicitudRequest` (un DTO).
3. **`@Valid`** revisa las reglas del DTO (`@NotEmpty tiposServicio`, `@NotBlank marca`…). Si alguna falla → **400** con la lista de errores, y el método ni se ejecuta.
4. **`@PreAuthorize("hasAnyRole('ADMINISTRADOR','CLIENTE')")`** revisa el rol. Si no corresponde → **403**.
5. Llama a `solicitudService.crear(request)`.

### Paso 4: el service (`SolicitudService.crear`), donde vive la lógica
1. `@Transactional` abre una **transacción**: o se guarda todo, o no se guarda nada.
2. `clientesClient.obtener(1)` → **llamada REST a ms-clientes** (`GET http://ms-clientes:47383/api/v1/clientes/1`):
   - el interceptor del `RestClient` le copia el **mismo token** del usuario y el **mismo correlation id**;
   - ms-clientes hace su propio recorrido (filtros → controller → service → repository) y devuelve a Carlos;
   - si ms-clientes no contesta: **Resilience4j** reintenta una vez y, si sigue fallando, lanza `ServicioNoDisponibleException` → **503**.
3. Arma la entidad `Solicitud` copiando los datos de Carlos (el *snapshot*) y la guarda con `solicitudRepository.save(...)` → **Hibernate** genera el `INSERT` en la base H2.
4. Anota en el historial: `null → PENDIENTE`.
5. Como el modo es `AUTOMATICA`, llama a `ofertarAutomaticamente`:
   - le pide a **ms-tecnicos** los candidatos (`GET /api/v1/tecnicos/candidatos?...`); ms-tecnicos filtra con **Haversine** a menos de 13 km;
   - descarta los técnicos que ya rechazaron y los que llegaron al límite (RN-5);
   - toma el primero (el más cercano), le pone `ofertaExpira = ahora + 15 min` y pasa a `OFERTADA`, anotando en el historial con el usuario `sistema`.
6. Termina el método → la transacción hace **commit** (se confirman todos los cambios).

### Paso 5: la respuesta
1. El service devuelve un `SolicitudResponse` (DTO). El controller lo envuelve en `ResponseEntity.created(...)` → **201** con el header `Location: .../api/v1/solicitudes/7`.
2. Jackson lo convierte a JSON.
3. `CorrelationIdFilter` escribe en el log: `POST /api/v1/solicitudes -> 201 (85 ms)`.
4. El gateway devuelve la respuesta a Postman con el `X-Correlation-ID`.

### ¿Y si algo sale mal en el medio?
Cualquier excepción que se lance en el service "sube" hasta el **`GlobalExceptionHandler`**, que la traduce:

| Excepción | Código | Ejemplo |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | Falta la marca |
| `AccessDeniedException` | 403 | Un TECNICO quiere crear una solicitud |
| `RecursoNoEncontradoException` | 404 | El cliente 999 no existe |
| `ReglaNegocioException` | 422 | El técnico ya tiene 2 solicitudes en espera |
| `ServicioNoDisponibleException` | 503 | ms-clientes está caído |
| cualquier otra | 500 | Un error que nadie esperaba |

Y como la transacción no llegó al final, hace **rollback**: no queda nada guardado a medias.

### Paso 6: lo que pasa "solo", sin que nadie lo pida
Cada 30 segundos, **`OfertaScheduler`** se despierta (`@Scheduled`) y busca ofertas vencidas. Si encuentra una, la pasa a `PENDIENTE` y, si es automática, se la ofrece al siguiente técnico. Como en ese momento no hay ningún usuario con token, ms-solicitudes usa su **cuenta de servicio**: le pide a Keycloak un token propio (`client_credentials`, rol `SISTEMA`) para poder consultar a ms-tecnicos.

---

## 3. Diccionario: las palabras raras explicadas fácil

### Java y Spring

| Palabra | Explicación fácil |
|---|---|
| **Spring Boot** | Un "kit" que arma solo un servidor web en Java. Vos escribís las clases y él las conecta entre sí |
| **Bean** | Un objeto que Spring crea y guarda para prestárselo a quien lo pida. Las clases con `@Service`, `@Component`, `@RestController`, `@Repository` o los métodos `@Bean` son beans |
| **Inyección de dependencias** | En vez de que una clase haga `new ClienteRepository()`, lo pide en su **constructor** y Spring se lo pasa. Es como pedir herramientas al pañol en vez de fabricarlas |
| **Anotación** (`@Algo`) | Una etiqueta que le da instrucciones a Spring o a otra librería. No hace nada sola: alguien la lee |
| **`@Configuration`** | "Esta clase tiene configuración": métodos `@Bean` que fabrican objetos |
| **`@Value("${clave:defecto}")`** | Lee un valor de `application.yml` (o de una variable de entorno). Si no existe, usa el valor después de los `:` |
| **`application.yml`** | El archivo de configuración: puertos, URLs, base de datos, etc. `${VARIABLE:valor}` significa "usá la variable de entorno VARIABLE o, si no está, este valor" |
| **Record** (`record X(...)`) | Una clase cortita de Java para "solo datos": Java genera solo el constructor, los getters (`x.nombre()`), `equals` y `toString`. Ideal para DTOs |
| **Lombok** | Una librería que escribe código aburrido por vos: `@Data` crea getters y setters, `@Builder` crea un "armador" (`Cliente.builder().nombre("X").build()`), `@NoArgsConstructor` crea un constructor vacío |
| **Stream** (`lista.stream().filter(...).map(...).toList()`) | Una "cinta transportadora": cada elemento pasa por filtros (`filter`) y transformaciones (`map`) y al final se junta (`toList`) |
| **Lambda** (`c -> c.getId()`) | Una función chiquita escrita en una línea: "dado `c`, devolvé `c.getId()`" |
| **Optional** | Una caja que puede tener algo o estar vacía. Evita los `null`: `findById(id).orElseThrow(...)` = "dame lo que hay o lanzá un error" |

### Web y REST

| Palabra | Explicación fácil |
|---|---|
| **HTTP** | El idioma con el que se hablan el navegador, Postman y los servidores. Un pedido tiene método (GET, POST…), URL, headers y a veces un cuerpo |
| **REST** | Una forma ordenada de usar HTTP: las URLs son **cosas** (sustantivos en plural, como `/clientes`) y el método dice **qué hacer** (GET leer, POST crear, PUT reemplazar, PATCH cambiar una parte, DELETE borrar) |
| **Endpoint** | Una "ventanilla": método + URL. Por ejemplo, `GET /api/v1/clientes/{clienteId}` |
| **JSON** | El formato de texto de los datos: `{"nombre": "Carlos", "latitud": -31.42}` |
| **Header** | Un dato extra del pedido, como una etiqueta en un paquete: `Authorization`, `Content-Type`, `X-Correlation-ID` |
| **Código de estado** | El número de la respuesta: 2xx salió bien, 4xx el pedido está mal, 5xx el servidor falló |
| **ProblemDetail** | El formato estándar (RFC 7807) para explicar errores: `title`, `status`, `detail`, `instance` |
| **DTO** | *Data Transfer Object*: la "bandeja" con los datos que entran o salen por la API. Separa lo que ve el cliente de cómo se guarda en la base |
| **Paginación** | Devolver los resultados de a pedazos (`?page=0&size=20`) en vez de todos juntos |

### Base de datos

| Palabra | Explicación fácil |
|---|---|
| **H2** | Una base de datos chiquita escrita en Java. Acá se guarda en un archivo (`data/clientes-db.mv.db`) |
| **JPA / Hibernate** | El "traductor" entre objetos Java y tablas: vos guardás un `Cliente` y él escribe el `INSERT INTO cliente ...` |
| **Entidad** (`@Entity`) | Una clase que representa una tabla. Cada objeto es una fila |
| **`ddl-auto: update`** | Hibernate crea o agrega solo las tablas y columnas que falten, al arrancar |
| **Repository** (`JpaRepository`) | Una interfaz a la que Spring le escribe solo los métodos: `save`, `findById`, `findAll`… y hasta los que inventás por nombre, como `existsByDni` |
| **Transacción** (`@Transactional`) | "Todo o nada": si algo falla en el medio, se deshace todo lo que se hizo (*rollback*) |
| **LAZY** | Las relaciones se cargan solo cuando las usás, no antes. Ahorra consultas |
| **`@Version`** | Un contador en la fila. Si dos personas modifican la misma fila al mismo tiempo, la segunda falla en vez de pisar a la primera (*bloqueo optimista*) |
| **Problema N+1** | Hacer 1 consulta para la lista y después 1 más por cada elemento. Se evita trayendo todo junto (`join fetch`) |

### Seguridad

| Palabra | Explicación fácil |
|---|---|
| **Keycloak** | El servidor que guarda usuarios y contraseñas y emite tokens |
| **Realm** | Un "mundo" dentro de Keycloak con sus propios usuarios y roles. El nuestro se llama `patito` |
| **Client** | Una aplicación registrada en Keycloak que puede pedir tokens: `bda-client` (Postman y el frontend) y `ms-solicitudes` (cuenta de servicio) |
| **JWT** | *JSON Web Token*: el token firmado con los datos del usuario |
| **Resource Server** | Un servidor que **recibe** tokens y los valida (nuestros microservicios y el gateway) |
| **401 vs 403** | 401 = "no sé quién sos" (sin token o con un token inválido). 403 = "sé quién sos, pero no podés hacer esto" |
| **`@PreAuthorize`** | Revisa el rol antes de ejecutar un método |
| **Password grant** | Pedir el token mandando usuario y contraseña directamente (lo usa Postman). Simple, pero la app ve tu contraseña |
| **Authorization Code + PKCE** | La app te manda a la página de login de Keycloak, vos ponés la contraseña **ahí**, y Keycloak le devuelve a la app un código que cambia por el token. La app nunca ve tu contraseña (lo usa el frontend) |
| **Client credentials** | Una aplicación pide un token **para sí misma**, sin usuario (lo usa ms-solicitudes desde el scheduler) |

### Microservicios y resiliencia

| Palabra | Explicación fácil |
|---|---|
| **Microservicio** | Un programa chiquito que hace una sola cosa y tiene su propia base de datos |
| **API Gateway** | La única puerta de entrada: recibe todo y lo reparte |
| **RestClient** | La herramienta de Spring para que un servicio llame a otro por HTTP |
| **Timeout** | Cuánto se espera una respuesta antes de rendirse (2 s para conectar y 5 s para leer) |
| **Retry** | Si falla, intentar otra vez (acá, una vez más, después de 300 ms) |
| **Circuit breaker** | Un "disyuntor": si un servicio falla mucho, se deja de llamarlo por 20 segundos para no empeorar las cosas, y se responde 503 al toque |
| **Correlation ID** | El número de ticket que permite seguir un pedido por todos los servicios en los logs |
| **Snapshot** | Copiar datos de otro servicio en el momento, para que no cambien después |
| **Scheduler** | Una tarea que se ejecuta sola cada cierto tiempo (`@Scheduled`) |

### Docker

| Palabra | Explicación fácil |
|---|---|
| **Imagen** | Una "foto" con todo lo necesario para correr un programa: sistema, Java y el `.jar` |
| **Contenedor** | Una imagen funcionando. Como una computadora chiquita y aislada |
| **Dockerfile** | La receta para armar una imagen |
| **docker-compose.yml** | La receta para levantar **varios** contenedores juntos, conectados en una red |
| **Volumen** | Una carpeta que sobrevive aunque borres el contenedor (acá se guardan las bases H2) |
| **Healthcheck** | Una pregunta que Docker le hace al contenedor cada 10 segundos: "¿estás bien?" |
