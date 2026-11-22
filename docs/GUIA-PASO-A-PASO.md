# Guía paso a paso: Técnica Patito explicado fácil

Esta guía explica cómo funciona el sistema, cómo encenderlo y cómo mostrar el flujo completo con **Swagger** y con **Postman**. No hace falta saber nada de antemano: vamos de a poquito.

---

## 1. El sistema es un edificio

Imaginá que Técnica Patito es un **edificio** con varias salas. Cada sala hace una sola cosa:

| En el edificio | En el sistema | Qué hace |
|---|---|---|
| 🎟️ La **boletería** | **Keycloak** (puerto 9090) | Te da una **pulsera** (el *token*) que dice quién sos: CLIENTE, TECNICO o ADMINISTRADOR |
| 🚪 El **señor de la puerta** | **API Gateway** (puerto 8080) | Mira tu pulsera. Si no tenés o es falsa, no pasás (**401**). Si está bien, te lleva a la sala que corresponde |
| 👥 La **sala de clientes** | **ms-clientes** (8083) | Guarda a los clientes y dónde viven (calle, barrio, latitud y longitud) |
| 🔧 La **sala de técnicos** | **ms-tecnicos** (8082) | Guarda a los técnicos, qué saben arreglar (certificaciones) y busca a los que están cerca |
| 🧠 La **sala de control** | **ms-solicitudes** (8081) | Es el cerebro: recibe los pedidos de arreglo y los mueve paso a paso hasta terminar |
| 🗺️ El **mapa** | **OSRM** (internet) | Dice cuántos km hay de verdad por la calle entre el técnico y el cliente |

Algunas reglas del edificio:
- **Siempre se entra por la puerta** (8080). Nadie va directo a las salas.
- **Cada sala tiene su cuaderno** (su base de datos H2) y nadie escribe en el cuaderno de otra.
- **Cada sala mira tu pulsera de nuevo.** Si tu rol no puede hacer algo ahí, te dice **403** ("no tenés permiso").
- Cuando el cerebro necesita datos de otra sala, **le pregunta por teléfono** (una llamada REST) y le muestra tu misma pulsera.
- Si una sala no atiende el teléfono, el cerebro intenta otra vez y, si sigue sin atender, te dice **503** ("esa sala está cerrada"). Nunca se queda colgado esperando.

### ¿Qué es un token?
Es un texto larguísimo que empieza con `eyJ...`. Es tu pulsera: dice tu usuario, tu rol y hasta cuándo vale (**30 minutos**). Va en cada pedido, en un header así:

```
Authorization: Bearer eyJhbGciOiJSUzI1NiIs...
```

### ¿Qué es el Correlation ID?
Es como el **número de un ticket de cola**. Cuando entrás por la puerta, el gateway te da un número (`X-Correlation-ID`) y todas las salas lo anotan en sus cuadernos de actividad (los logs). Si algo sale mal, con ese número encontrás todo lo que pasó, en todas las salas. Aparece en cada respuesta de error como `correlationId`.

---

## 2. La historia que vamos a contar

Carlos García (cliente 1) tiene una impresora que no toma las hojas. Esto es lo que pasa:

1. **Carlos pide un arreglo** → se crea una **solicitud** en estado `PENDIENTE`. El sistema anota en la solicitud los datos de Carlos y dónde vive (eso es el *snapshot*: una "foto" de los datos de ese momento).
2. **El sistema busca técnicos**: tienen que saber "Redes" (la certificación pedida), estar disponibles y estar a menos de 13 km. Hay tres técnicos:
   - **Juan Pérez**: sabe Redes e Impresoras y está en el Centro, a 0.58 km. ✅
   - **Ana Gómez**: sabe Impresoras y Notebooks Apple, pero no Redes. ❌
   - **Lejano López**: sabe Redes, pero está en Carlos Paz, a unos 29 km. ❌
3. **Se le ofrece el trabajo a Juan** → `OFERTADA`. Juan tiene **15 minutos** para contestar.
4. **Juan acepta** → `ASIGNADA`. Ahí el sistema calcula cuánto cuesta la visita con el mapa: $5000 fijos más $800 por km.
5. **Juan revisa la impresora** y carga el diagnóstico y el presupuesto: "rodillo gastado, $1500".
6. **Carlos aprueba el presupuesto** → `EN_CURSO`.
7. **Juan termina el trabajo** → `FINALIZADA`.
8. **Miramos el historial**: está anotado cada paso, con fecha, hora y quién lo hizo.

```
PENDIENTE → OFERTADA → ASIGNADA → EN_CURSO → FINALIZADA
```

Y si algo sale distinto:
- Juan **rechaza** o **no contesta en 15 minutos** → la solicitud vuelve a `PENDIENTE` para ofrecérsela a otro.
- Carlos **no acepta el presupuesto** → `CANCELADA`.
- **Falta un repuesto** → `REPROGRAMADA`, y cuando llega, vuelve a `EN_CURSO`.
- **Juan no sabe arreglar esa falla** → `DERIVADA`, y vuelve a `PENDIENTE` para buscar otro técnico.

---

## 3. Encender el edificio

### Paso 1: que no haya nada ocupando los puertos
Si tenés servicios corriendo con `mvn spring-boot:run`, cortalos con **Ctrl+C** en cada terminal. Para ver si queda algo en esos puertos:

```bash
ss -ltnp | grep -E ':(8080|8081|8082|8083|9090)'
```

Si aparece un proceso `java`, cerralo con `kill <pid>`.

### Paso 2: construir los programas
Desde la carpeta del proyecto:

```bash
mvn clean package -DskipTests
```

Esto crea un archivo `.jar` por sala, dentro de la carpeta `target/` de cada módulo. Los Dockerfile copian ese `.jar`.

### Paso 3: levantar todo con Docker

```bash
docker compose up -d --build
```

Esperá un minuto y fijate que todo diga **healthy** ("sano"):

```bash
docker compose ps
```

```
NAME             STATUS
api-gateway      Up (healthy)
keycloak         Up (healthy)
ms-clientes      Up (healthy)
ms-solicitudes   Up (healthy)
ms-tecnicos      Up (healthy)
```

> 💡 Docker los enciende en orden: primero Keycloak; cuando está sano, clientes y técnicos; después solicitudes y, al final, el gateway. Eso lo hacen los `healthcheck` y el `depends_on` del `docker-compose.yml`.

Para apagar: `docker compose down`. Para apagar y **borrar los datos** (empezar de cero): `docker compose down -v`.

---

## 4. Swagger: el menú del restaurante

**Swagger** es una página web que muestra **todos los pedidos que podés hacer** (los endpoints), qué datos llevan y qué te pueden contestar. Además tiene un botón para **probarlos ahí mismo**.

### Paso 1: abrir Swagger
Entrá a 👉 **http://localhost:8080/swagger-ui.html**

Arriba a la derecha hay un selector **"Select a definition"** con las tres salas: **MS-Solicitudes**, **MS-Técnicos** y **MS-Clientes**.

### Paso 2: conseguir la pulsera (token)
Swagger no la pide por vos, así que la sacamos de Keycloak con la terminal:

```bash
curl -s -X POST http://localhost:9090/realms/patito/protocol/openid-connect/token \
  -d grant_type=password -d client_id=bda-client \
  -d username=cliente1 -d password=cliente123
```

Te devuelve un JSON. Copiá **solo** el valor de `access_token` (el texto largo, sin las comillas).

> Para no copiar a mano, si tenés `jq`: agregá `| jq -r .access_token` al final del comando.

### Paso 3: ponerte la pulsera
1. Tocá el botón verde **Authorize** 🔓.
2. Pegá el token en el cuadro **Value** (sin escribir `Bearer`, Swagger lo agrega solo).
3. Tocá **Authorize** y después **Close**. El candado queda cerrado 🔒.

### Paso 4: probar un pedido
1. Elegí **MS-Clientes** en el selector.
2. Abrí `GET /api/v1/clientes/{clienteId}`.
3. Tocá **Try it out**, escribí `1` en `clienteId` y tocá **Execute**.
4. Abajo aparece la respuesta: **200** y los datos de Carlos García.

Mirá también la sección **Responses**: muestra todos los códigos posibles (200, 404, etc.) y qué significa cada uno.

### Paso 5: ver un "no tenés permiso"
Con el token de **cliente1**, andá a **MS-Técnicos** → `POST /api/v1/tecnicos` → Try it out → Execute. Responde **403**: un cliente no puede dar de alta técnicos.

> 💡 Para cambiar de rol: pedí otro token (por ejemplo `tecnico1` / `tecnico123`), tocá **Authorize → Logout** y pegá el nuevo.

---

## 5. Postman: el control remoto

Swagger sirve para probar de a un pedido. **Postman** sirve para guardar una **lista de pedidos en orden** y apretar un solo botón para que se ejecuten todos y te diga cuáles salieron bien. Es lo que vamos a usar para la demostración.

### Paso 1: importar la colección y el entorno
1. Abrí Postman.
2. Tocá **Import** (arriba a la izquierda).
3. Arrastrá los dos archivos de la carpeta `postman/` del proyecto:
   - `Tecnica-Patito.postman_collection.json` (la colección)
   - `Tecnica-Patito.postman_environment.json` (el entorno)
4. Arriba a la derecha, en el selector de entornos, elegí **"Técnica Patito – Local"**.

A la izquierda aparece la colección **"Técnica Patito – Backend"** con sus carpetas:

```
Técnica Patito – Backend
├── 00 – Seguridad              ← las 3 pulseras
├── 01 – Clientes
├── 02 – Técnicos
├── 03 – Solicitudes
├── 04 – Flujo principal        ← la historia de Carlos y Juan
├── 05 – Escenarios negativos   ← cosas que TIENEN que fallar
└── 06 – Escenarios manuales    ← necesitan que hagas algo afuera
```

### Paso 2: conseguir las pulseras
Abrí **00 – Seguridad** y ejecutá las tres peticiones (abrís cada una y tocás **Send**):
- `Token ADMINISTRADOR (admin1)`
- `Token CLIENTE (cliente1)`
- `Token TECNICO (tecnico1)`

Cada una guarda su token en una **variable** (`token_admin`, `token_cliente`, `token_tecnico`). Las demás peticiones ya están configuradas para usar la que les corresponde: no tenés que copiar nada.

> ⏰ Los tokens duran 30 minutos. Si de repente todo da **401**, volvé a ejecutar la carpeta 00.

### Paso 3: ¿qué son los "tests"?
Cada petición tiene una pestaña **Tests** con chequeos automáticos. Por ejemplo, "el status tiene que ser 201" o "el estado tiene que ser PENDIENTE". Después de tocar **Send**, mirá abajo la pestaña **Test Results**: si todo está en verde ✅, salió como esperábamos.

Algunos tests también **guardan datos para el paso siguiente**. Por ejemplo, al crear una solicitud se guarda su `id` en la variable `solicitudId`, y el pedido de ofertar usa `{{solicitudId}}` en la URL.

### Paso 4: mostrar el flujo principal (la historia de la sección 2)
Abrí la carpeta **04 – Flujo principal** y ejecutá los pedidos **en orden**, uno por uno. Contá qué pasa en cada uno:

| # | Pedido | Quién | Qué mostrar en la respuesta |
|---|---|---|---|
| 1 | Consultar cliente (coordenadas) | CLIENTE | Carlos vive en `-31.42, -64.188` |
| 2 | **EP-01** Crear solicitud | CLIENTE | **201**, `estado: PENDIENTE`, `clienteNombre: Carlos García` (el snapshot) |
| 3 | **EP-02** Elegir candidatos | CLIENTE | Solo aparece **Juan**, a 0.58 km, estrategia `ESTANDAR` |
| 4 | Ofertar al técnico | CLIENTE | `estado: OFERTADA` y `ofertaExpira` = dentro de 15 minutos |
| 5 | **EP-03** Técnico acepta | TECNICO | `estado: ASIGNADA`, con `distanciaKm`, `fuenteDistancia: OSRM`, `costoBase` y `total` |
| 6 | Cargar presupuesto | TECNICO | `presupuesto: 1500` |
| 7 | **EP-04** Aprobar presupuesto | CLIENTE | `estado: EN_CURSO` |
| 8 | Finalizar | TECNICO | `estado: FINALIZADA` |
| 9 | **EP-05** Historial | ADMIN | La lista de pasos: PENDIENTE → OFERTADA → ASIGNADA → ASIGNADA (presupuesto) → EN_CURSO → FINALIZADA, cada uno con fecha, hora y usuario |

> 💡 Fijate en la columna "Quién": cada paso lo hace el rol que corresponde en la vida real. El cliente crea y aprueba; el técnico acepta, presupuesta y finaliza.

### Paso 5: mostrar que las reglas funcionan (lo que tiene que fallar)
Abrí **05 – Escenarios negativos**:

| Pedido | Resultado | Por qué |
|---|---|---|
| **EN-01** Sin token | **401** | No trajiste pulsera |
| **EN-02** Token inválido | **401** | La pulsera es trucha |
| **EN-03** CLIENTE registra técnico | **403** | Tenés pulsera, pero tu rol no puede hacer eso |
| **EN-04** Solicitud sin tipos de servicio | **400** | Falta un dato obligatorio. El campo `errores` dice cuál |
| **EN-05** Cliente inexistente | **404** | El cerebro le preguntó a la sala de clientes y ese cliente no existe |
| **EN-06** Técnico al límite | **422** | Juan ya tiene 1 trabajo en curso y 2 esperando: no puede recibir más (RN-5) |
| **EN-08** Aprobar en PENDIENTE | **422** | No se puede aprobar un presupuesto que todavía no existe (RN-6) |
| **EN-10** DNI duplicado | **422** | Ya hay un técnico con ese DNI |

La subcarpeta **EN-06** primero **prepara la escena**: le arma a Juan 1 trabajo en curso y 2 asignados. Después prueba la oferta que tiene que fallar y, al final, **limpia** todo para poder repetirla.

Todos los errores tienen la misma forma (se llama **ProblemDetail**):

```json
{
  "title": "Regla de negocio incumplida",
  "status": 422,
  "detail": "El técnico 1 alcanzó el límite de solicitudes: tiene 2 en espera (máximo 2) y 1 en curso (RN-5)",
  "instance": "/api/v1/solicitudes/12/ofertar/1",
  "correlationId": "3f1c..."
}
```

### Paso 6: ejecutar todo de una con el Runner
1. Hacé clic derecho sobre la colección → **Run collection**.
2. Destildá la carpeta **06 – Escenarios manuales**.
3. Tocá **Run Técnica Patito – Backend**.

Postman ejecuta las carpetas 00 a 05 (61 peticiones) y muestra cuántos tests pasaron: tienen que ser **todos verdes**. Lo podés correr las veces que quieras, porque los escenarios limpian lo que crean.

### Paso 7: los escenarios manuales (carpeta 06)

**EN-09: una sala cerrada → 503**
1. En la terminal: `docker stop ms-clientes` (cerramos la sala de clientes).
2. En Postman: ejecutá `06 → EN-09 → Crear solicitud con MS-Clientes detenido`.
3. Responde **503**: "El servicio de clientes no se encuentra disponible". No se creó nada a medias.
4. En la terminal: `docker start ms-clientes` (volvemos a abrir la sala).

**EN-07: la oferta vence sola**

Esperar 15 minutos en una demo es mucho. Por eso la ventana se puede achicar:
1. Creá un archivo `.env` (copiá `.env.example`) y poné `OFERTA_VENTANA_MINUTOS=1`.
2. Reiniciá solo el cerebro: `docker compose up -d ms-solicitudes`.
3. En Postman, carpeta `06 → EN-07`: ejecutá **1. Crear** y **2. Ofertar**.
4. Esperá **un minuto y medio**: el reloj del sistema revisa cada 30 segundos.
5. Ejecutá **3. Consultar**: volvió a `PENDIENTE`. **4. Historial** muestra "Oferta vencida" y el usuario `sistema`.
6. Al terminar, volvé el `.env` a `15` y ejecutá `docker compose up -d ms-solicitudes`.

---

## 6. Mirar adentro de las salas

**Los logs (el cuaderno de actividad):**

```bash
docker compose logs -f ms-solicitudes
```

Cada línea tiene el nombre del servicio y el ticket `[cid:...]`. Para seguir una petición por todo el edificio, copiá el `correlationId` de una respuesta y buscalo:

```bash
docker compose logs | grep <correlationId>
```

Vas a ver la misma petición pasando por el gateway, por ms-solicitudes y por ms-clientes.

**La base de datos (el cuaderno de cada sala):**
Entrá a http://localhost:8083/h2-console (clientes) y completá:
- JDBC URL: `jdbc:h2:file:/app/data/clientes-db;AUTO_SERVER=TRUE`
- User: `sa`
- Password: (vacío)

Para técnicos usá el puerto 8082 y `tecnicos-db`; para solicitudes, el 8081 y `solicitudes-db`.

---

## 7. Si algo no anda

| Problema | Qué hacer |
|---|---|
| `La dirección ya se está usando` / `port is already allocated` | Hay otro programa en ese puerto. Cerrá los `mvn spring-boot:run` o buscalo con `ss -ltnp` |
| Todo da **401** | El token venció (dura 30 min). Volvé a ejecutar la carpeta 00 |
| `docker compose up` falla con `target/...jar not found` | Te faltó `mvn clean package -DskipTests` |
| Un contenedor queda `unhealthy` | Mirá qué le pasa con `docker compose logs <nombre>` |
| `fuenteDistancia` dice `HAVERSINE` en vez de `OSRM` | No hay internet o OSRM no respondió: el sistema usó el cálculo en línea recta. Está bien, es el respaldo de RN-8 |
| Los datos quedaron raros | `docker compose down -v` y volvé a levantar: los DataLoader recargan los datos de prueba |
| VS Code marca errores rojos pero Maven compila | `Ctrl+Shift+P` → **Java: Clean Java Language Server Workspace** → **Reload and delete** |
