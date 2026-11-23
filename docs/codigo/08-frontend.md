# 08 – Frontend: la vidriera

## ¿Para qué sirve? (explicado fácil)

Postman y Swagger son herramientas de programadores: hay que escribir JSON y pegar tokens. El **frontend** es una **página web con botones**, como la usaría de verdad un cliente o un técnico: inicia sesión, ve sus solicitudes y aprieta "Aceptar oferta" o "Aprobar presupuesto".

Está hecho con **HTML, CSS y JavaScript** sin frameworks: el navegador lo entiende directamente, sin compilar nada. Lo sirve **nginx**, un servidor web muy liviano, en un contenedor.

```
frontend/
├── Dockerfile          ← receta de la imagen (nginx + archivos)
├── .dockerignore
├── nginx.conf          ← configuración de nginx (archivos + proxy /api)
└── public/
    ├── index.html      ← el esqueleto de la página
    ├── styles.css      ← los colores y el diseño
    ├── config.js       ← direcciones de Keycloak y de la API
    └── app.js          ← toda la lógica
```

### Cómo viaja todo

```
 Navegador (localhost:47300)
   │
   ├─ 1. pide la página ──────────────► nginx (frontend) devuelve index.html, app.js, styles.css
   │
   ├─ 2. login ───────────────────────► Keycloak (localhost:47390)  ← directo, sin pasar por nginx
   │      (te muestra SU pantalla de login, vos ponés la contraseña AHÍ)
   │◄────── vuelve a la página con un "código" ── se cambia por el token
   │
   └─ 3. /api/v1/... + token ─────────► nginx ──proxy──► api-gateway:47380 ──► microservicios
```

---

## `Dockerfile` y `nginx.conf`

```dockerfile
FROM nginx:1.27.3-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY public/ /usr/share/nginx/html/
EXPOSE 80
```
- `FROM nginx:1.27.3-alpine`: nginx con versión fija, sobre **Alpine** (un Linux mínimo, de pocos MB).
- Copia nuestra configuración en el lugar donde nginx la busca, y los archivos de la página en la carpeta que sirve.
- No hace falta `ENTRYPOINT`: la imagen de nginx ya trae el suyo.

```nginx
server {
    listen 80;
    server_name localhost;

    root /usr/share/nginx/html;
    index index.html;
```
- Escucha en el puerto 80. `root`: la carpeta de donde salen los archivos. `index`: el archivo que se muestra si pedís `/`.

```nginx
    location /api/ {
        proxy_pass http://api-gateway:47380;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
```
- `location /api/`: todo lo que empiece con `/api/`…
- `proxy_pass http://api-gateway:47380`: …se **reenvía** al gateway (por su nombre en la red de Docker). Es un **proxy reverso**.
- **¿Por qué?** Para el navegador, la página y la API están en el **mismo origen** (`localhost:47300`). Si la página llamara directo a `localhost:47380`, el navegador lo consideraría "otro sitio" y bloquearía la respuesta por seguridad (**CORS**), salvo que el gateway diera permisos especiales. Con el proxy, el problema no existe y el gateway sigue siendo la única puerta al backend.
- `proxy_set_header ...`: le pasa al gateway datos del pedido original (el host y la IP del cliente).

```nginx
    location / {
        try_files $uri $uri/ /index.html;
        add_header Cache-Control "no-store";
    }
}
```
- Para todo lo demás: si existe el archivo pedido, lo devuelve; si no, devuelve `index.html`.
- `Cache-Control "no-store"`: que el navegador no guarde copias. Así, si cambiás el código, ves la versión nueva sin "Ctrl+F5".

---

## `public/config.js`

```javascript
window.PATITO_CONFIG = {
    keycloakUrl: "http://localhost:47390",
    realm: "patito",
    clientId: "bda-client",
    apiUrl: ""
};
```
- `window.PATITO_CONFIG`: una variable **global** (visible para toda la página) con la configuración.
- Keycloak se accede **directo** en el 47390: el login tiene que ocurrir en la página de Keycloak, no en la nuestra.
- `apiUrl: ""` = "el mismo origen" (`/api/...` → nginx → gateway).

---

## `public/index.html`

```html
<!doctype html>
<html lang="es">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Técnica Patito</title>
    <link rel="stylesheet" href="styles.css">
    <script src="config.js"></script>
    <script type="module" src="app.js"></script>
</head>
```
- `<!doctype html>`: "esto es HTML moderno".
- `charset="utf-8"`: para que se vean bien los acentos y las ñ.
- `viewport`: para que en el celular no se vea diminuta.
- `<link ... styles.css>`: carga los estilos.
- Primero `config.js` (así `PATITO_CONFIG` ya existe) y después `app.js`.
- `type="module"`: `app.js` es un **módulo** de JavaScript, y eso le permite usar `import` para traer la librería de Keycloak.

```html
<body>
<header class="barra">
    <div class="marca"> ... 🦆 Técnica Patito ... </div>
    <nav class="pestanias" id="pestanias" aria-label="Secciones"></nav>
    <div class="usuario">
        <span id="usuario-nombre">Conectando…</span>
        <span class="chip" id="usuario-rol"></span>
        <button class="btn secundario" id="btn-salir" type="button">Salir</button>
    </div>
</header>

<main id="contenido">
    <p class="cargando">Iniciando sesión con Keycloak…</p>
</main>

<div id="avisos" class="avisos" aria-live="polite"></div>
</body>
```
- La página es casi vacía: una **barra** arriba (logo, pestañas, usuario y botón Salir), un **contenido** que `app.js` va llenando y una zona de **avisos** (los cartelitos de abajo a la derecha).
- `id="..."`: un nombre único para que JavaScript encuentre el elemento.
- `aria-live="polite"`: los lectores de pantalla (para personas ciegas) leen los avisos nuevos.

---

## `public/styles.css` (en resumen)

- `:root { --fondo: ...; --texto: ...; }`: **variables de CSS** con todos los colores. Así se cambia un color en un solo lugar.
- `@media (prefers-color-scheme: dark) { :root { ... } }`: si tu sistema está en **modo oscuro**, usa otros valores para las mismas variables.
- `.estado.PENDIENTE { color: ...; background: ...; }`: un color por estado (amarillo para PENDIENTE, violeta para OFERTADA, verde para FINALIZADA…).
- `.grilla { display: grid; grid-template-columns: ... }`: el diseño en dos columnas (lista y detalle). Con `@media (max-width: 960px)` pasa a una columna en pantallas chicas.
- `.linea li::before`: los "puntitos" de la línea de tiempo del historial.

---

## `public/app.js`: la lógica, parte por parte

### 1. La librería de Keycloak y las constantes

```javascript
import Keycloak from "https://cdn.jsdelivr.net/npm/keycloak-js@26.0.7/+esm";

const CFG = window.PATITO_CONFIG;
const ESTADOS = ["PENDIENTE", "OFERTADA", "ASIGNADA", "EN_CURSO", "REPROGRAMADA", "DERIVADA", "CANCELADA", "FINALIZADA"];
const TIPOS = ["REPARACION", "INSTALACION", "MANTENIMIENTO"];
const REFRESCO_MS = 4000;

const keycloak = new Keycloak({ url: CFG.keycloakUrl, realm: CFG.realm, clientId: CFG.clientId });
```
- `import Keycloak from "https://..."`: trae **keycloak-js**, la librería oficial de Keycloak para navegadores, desde un CDN (un servidor público de librerías). La versión es fija (26.0.7, la misma que el servidor). `+esm` = en formato de módulo moderno.
- `const`: una variable que no se reasigna.
- `REFRESCO_MS = 4000`: la lista se actualiza cada 4 segundos.
- `new Keycloak({...})`: crea el "cliente de Keycloak" de la página, apuntando a nuestro realm y a `bda-client`.

```javascript
const estado = {
    roles: [],
    pestania: "solicitudes",
    certificaciones: new Map(),
    tecnicos: [],
    solicitudes: [],
    filtroEstado: "",
    seleccionada: null,
    historial: [],
    candidatos: null,
    clienteActual: null,   // sale del token (claim clienteId)
    tecnicoActual: null,   // sale del token (claim tecnicoId)
};
```
- La **memoria** de la página: qué roles tenés, qué pestaña está abierta, qué solicitud elegiste, etc.
- `clienteActual` y `tecnicoActual`: **quién sos en el padrón**. Arrancan vacíos y se llenan con lo que dice el token al iniciar sesión (ver el punto 4). No se pueden elegir ni cambiar desde la página.

### 2. Utilidades

```javascript
function esc(valor) {
    return String(valor ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
```
- **Escapa** el texto antes de meterlo en el HTML: convierte `<` en `&lt;`, etc.
- **¿Por qué es importante?** Si un cliente escribiera en la descripción `<script>robarTodo()</script>` y lo pusiéramos tal cual en la página, el navegador lo **ejecutaría** (un ataque llamado **XSS**). Escapado, se muestra como texto inofensivo. Todo dato que viene del backend pasa por `esc(...)`.

```javascript
const $ = sel => document.querySelector(sel);
const tiene = rol => estado.roles.includes(rol);
const esAdmin = () => tiene("ADMINISTRADOR");
const puedeCliente = () => esAdmin() || tiene("CLIENTE");
const puedeTecnico = () => esAdmin() || tiene("TECNICO");
const esSoloCliente = () => tiene("CLIENTE") && !esAdmin();
const esSoloTecnico = () => tiene("TECNICO") && !esAdmin();
const pesos = n => n == null ? "—" : Number(n).toLocaleString("es-AR", { style: "currency", currency: "ARS" });
const fecha = f => f ? new Date(f).toLocaleString("es-AR", { dateStyle: "short", timeStyle: "short" }) : "—";
const chipEstado = e => `<span class="chip estado ${esc(e)}">${esc(e.replace("_", " "))}</span>`;
```
- Funciones flecha de una línea:
  - `$("#id")`: atajo para buscar un elemento de la página;
  - `tiene(rol)`, `esAdmin()`, `puedeCliente()`, `puedeTecnico()`: preguntas sobre tus roles, para decidir qué botones mostrar;
  - `esSoloCliente()` / `esSoloTecnico()`: "soy cliente (o técnico) y **no** administrador". Con estas se decide que veas solo lo tuyo;
  - `pesos(1500)` → `$ 1.500,00` (formato argentino);
  - `fecha(...)` → `22/11/26, 10:56`;
  - `chipEstado("EN_CURSO")` → una etiqueta de color con el estado.

```javascript
function aviso(titulo, detalle = "", error = false) {
    const div = document.createElement("div");
    div.className = "aviso" + (error ? " error" : "");
    div.innerHTML = `<strong>${esc(titulo)}</strong>${detalle ? `<small>${esc(detalle)}</small>` : ""}`;
    $("#avisos").append(div);
    setTimeout(() => div.remove(), error ? 9000 : 4000);
}
```
- Muestra un cartelito abajo a la derecha y lo borra solo a los 4 segundos (9 si es un error, para que dé tiempo a leerlo).
- Los textos entre comillas invertidas (`` `...${x}...` ``) son **template strings**: `${x}` se reemplaza por el valor.

### 3. ⭐ La función que llama al backend

```javascript
async function api(metodo, ruta, cuerpo) {
    await keycloak.updateToken(30).catch(() => keycloak.login());
```
- `async` / `await`: llamar a un servidor tarda. `await` significa "esperá a que esto termine, sin congelar la página".
- `keycloak.updateToken(30)`: "si al token le quedan **menos de 30 segundos**, renovalo". Así nunca se manda un token vencido. Si no se puede renovar, vuelve a la pantalla de login.

```javascript
    const respuesta = await fetch(CFG.apiUrl + ruta, {
        method: metodo,
        headers: {
            Authorization: `Bearer ${keycloak.token}`,
            ...(cuerpo !== undefined ? { "Content-Type": "application/json" } : {}),
        },
        body: cuerpo !== undefined ? JSON.stringify(cuerpo) : undefined,
    });
```
- `fetch(...)`: la función del navegador para hacer pedidos HTTP.
- `Authorization: Bearer <token>`: **la pulsera** en cada pedido.
- `...(condición ? {...} : {})`: el **spread** (`...`) agrega el header `Content-Type` solo si hay cuerpo.
- `JSON.stringify(cuerpo)`: convierte el objeto JavaScript en texto JSON.

```javascript
    if (respuesta.status === 204) return null;
    const datos = await respuesta.json().catch(() => null);
    if (!respuesta.ok) {
        const titulo = `${respuesta.status} · ${datos?.title ?? respuesta.statusText}`;
        const errores = datos?.errores ? " " + Object.entries(datos.errores).map(([k, v]) => `${k}: ${v}`).join("; ") : "";
        const detalle = (datos?.detail ?? "") + errores + (datos?.correlationId ? ` (correlationId ${datos.correlationId})` : "");
        aviso(titulo, detalle, true);
        throw new Error(titulo);
    }
    return datos;
}
```
- 204 = sin contenido → devuelve `null`.
- `respuesta.json()`: lee el cuerpo como JSON.
- `respuesta.ok`: `true` si el status es 2xx.
- Si hubo error, lee el **ProblemDetail** que armó el backend (`title`, `detail`, `errores`, `correlationId`) y lo muestra en un aviso rojo. Por ejemplo: *"422 · Regla de negocio incumplida — El técnico 1 alcanzó el límite…"*.
- `datos?.title`: el **encadenamiento opcional** `?.` evita un error si `datos` es `null`.
- `throw new Error(...)`: corta la ejecución, para que quien llamó sepa que falló.

> 💡 La página **muestra u oculta** botones según tu rol, pero **quien decide de verdad es el backend**. Aunque alguien "trucara" la página para ver un botón, el backend respondería 403. La seguridad nunca depende del frontend.

### 4. El arranque y el login

```javascript
async function iniciar() {
    try {
        await keycloak.init({ onLoad: "login-required", pkceMethod: "S256", checkLoginIframe: false });
    } catch (e) {
        $("#contenido").innerHTML = `<div class="panel"><h2>No se pudo conectar con Keycloak</h2> ...`;
        return;
    }
```
- `keycloak.init(...)` hace todo el **flujo Authorization Code + PKCE**:
  - `onLoad: "login-required"`: si no estás logueado, **te manda a la página de login de Keycloak**;
  - **PKCE** (`pkceMethod: "S256"`): antes de mandarte, la librería inventa un **secreto al azar** (el *code verifier*) y le manda a Keycloak solo su **huella** (un hash SHA-256, el *code challenge*). Cuando volvés con el código, la librería muestra el secreto original. Así, aunque alguien robara el código en el camino, no podría cambiarlo por un token porque no conoce el secreto;
  - al volver, la librería cambia el código por el **token** y lo guarda en `keycloak.token`;
  - `checkLoginIframe: false`: desactiva un chequeo periódico con un iframe oculto (no lo necesitamos y genera errores con algunos navegadores).
- Si Keycloak no responde, se muestra un mensaje claro.

```javascript
    estado.roles = keycloak.tokenParsed?.realm_access?.roles ?? [];
    const rolPrincipal = ["ADMINISTRADOR", "TECNICO", "CLIENTE"].find(tiene) ?? "SIN ROL";
    $("#usuario-nombre").textContent = keycloak.tokenParsed?.preferred_username ?? "";
    $("#usuario-rol").textContent = rolPrincipal;
    $("#btn-salir").addEventListener("click", () => keycloak.logout({ redirectUri: window.location.origin }));
```
- `keycloak.tokenParsed`: el token **decodificado** (la parte de datos). De ahí salen los roles y el nombre de usuario.
- `.find(tiene)`: el primer rol de la lista que tengas (el admin gana).
- `textContent`: pone **texto** (no HTML) en el elemento: seguro, no hace falta escapar.
- El botón **Salir** cierra la sesión en Keycloak y vuelve a la página.

```javascript
    estado.clienteActual = keycloak.tokenParsed?.clienteId ?? null;
    estado.tecnicoActual = keycloak.tokenParsed?.tecnicoId ?? null;
    if ((esSoloCliente() && estado.clienteActual == null) || (esSoloTecnico() && estado.tecnicoActual == null)) {
        $("#contenido").innerHTML = `<div class="panel"><h2>Tu usuario no está vinculado al padrón</h2> ...`;
        return;
    }
```
- **Quién sos en el padrón** sale del token: Keycloak agrega los claims `clienteId` (en `cliente1` vale 1) y `tecnicoId` (en `tecnico1` vale 1 y en `tecnico2` vale 2). Ver el documento 07, sección del realm.
- Si un cliente o un técnico no tiene ese dato, la página no sigue: muestra un aviso en vez de enseñar datos de otro.

```javascript
        if (esAdmin()) estado.tecnicos = (await api("GET", "/api/v1/tecnicos?size=100")).content;
```
- La lista completa de técnicos se pide **solo si sos administrador**. El cliente nunca la descarga.

```javascript
    try {
        const certs = await api("GET", "/api/v1/certificaciones");
        certs.forEach(c => estado.certificaciones.set(c.id, c.nombre));
        estado.tecnicos = (await api("GET", "/api/v1/tecnicos?size=100")).content;
    } catch { /* el aviso ya se mostró */ }

    dibujarPestanias();
    await mostrar("solicitudes");
    setInterval(refrescar, REFRESCO_MS);
}
```
- Trae los catálogos que se usan para mostrar nombres (certificaciones y técnicos).
- Dibuja las pestañas y muestra "Solicitudes".
- `setInterval(refrescar, 4000)`: cada 4 segundos llama a `refrescar`.

### 5. Pestañas

```javascript
function dibujarPestanias() {
    const titulo = esSoloCliente() ? "Mis solicitudes" : esSoloTecnico() ? "Mis trabajos" : "Solicitudes";
    const pestanias = [["solicitudes", titulo]];
    if (puedeCliente()) pestanias.push(["nueva", "Nueva solicitud"]);
    if (esSoloCliente() || esSoloTecnico()) pestanias.push(["mis-datos", "Mis datos"]);
    if (esAdmin()) pestanias.push(["tecnicos", "Técnicos"], ["clientes", "Clientes"]);
    $("#pestanias").innerHTML = pestanias.map(([id, texto]) =>
        `<button type="button" data-pestania="${id}" ${estado.pestania === id ? 'aria-current="page"' : ""}>${texto}</button>`).join("");
    $("#pestanias").querySelectorAll("button").forEach(b => b.addEventListener("click", () => mostrar(b.dataset.pestania)));
}
```
- Arma la lista de pestañas **según el rol**:
  - **cliente**: "Mis solicitudes", "Nueva solicitud" y "Mis datos". **No** ve la pestaña de técnicos;
  - **técnico**: "Mis trabajos" y "Mis datos";
  - **administrador**: "Solicitudes", "Nueva solicitud", "Técnicos" y "Clientes".
- `.map(...).join("")`: convierte cada pestaña en un `<button>` y los junta en un texto HTML.
- `data-pestania="..."`: un dato guardado en el botón, que se lee con `b.dataset.pestania`.
- `addEventListener("click", ...)`: "cuando hagan clic, ejecutá esto".

```javascript
async function refrescar() {
    if (estado.pestania !== "solicitudes" || document.hidden) return;
    const ocupado = document.activeElement?.closest?.("#detalle form, #detalle input, #detalle textarea") || estado.candidatos;
    await cargarSolicitudes(true);
    if (estado.seleccionada && !ocupado) await cargarDetalle(estado.seleccionada.id, true);
}
```
- Solo refresca si estás en "Solicitudes" y la pestaña del navegador está visible (`document.hidden`).
- **No** refresca el detalle si estás escribiendo (por ejemplo, el presupuesto) o mirando los candidatos: si no, te borraría lo que estás tipeando.
- Gracias a esto se ven **solos** los cambios automáticos: la oferta que pasa a otro técnico o la que vence.

### 6. La lista de solicitudes

```javascript
async function cargarSolicitudes(silencioso = false) {
    let pagina;
    try {
        const filtro = estado.filtroEstado ? `&estado=${estado.filtroEstado}` : "";
        pagina = await api("GET", `/api/v1/solicitudes?size=50&sort=id,desc${filtro}`);
    } catch { return; }
    let lista = pagina.content;
    if (esSoloCliente()) lista = lista.filter(s => s.clienteId === estado.clienteActual);
    else if (esSoloTecnico()) lista = lista.filter(s => s.tecnicoId === estado.tecnicoActual);
```
- Pide las últimas 50 solicitudes (las más nuevas primero) con el filtro de estado.
- **Cada uno ve lo suyo, siempre** (no hay casilla para desactivarlo):
  - el cliente, las solicitudes con **su** `clienteId`;
  - el técnico, las que hoy están a **su** nombre (`tecnicoId`). Si rechaza una oferta, desaparece de su lista porque pasa a otro;
  - el administrador, todas.
- El filtro se hace **en el navegador** con los números que vienen del token. La API sigue devolviendo todas las solicitudes a cualquier rol (así quedó en el PDF); para que el backend también filtre habría que cambiar ms-solicitudes.

Después arma la tabla con un `<tr>` por solicitud. Al hacer clic en una fila, llama a `cargarDetalle(id)`.

### 7. El detalle y sus botones

```javascript
async function cargarDetalle(id, silencioso = false) {
    try {
        const [solicitud, historial] = await Promise.all([
            api("GET", `/api/v1/solicitudes/${id}`),
            api("GET", `/api/v1/solicitudes/${id}/historial`),
        ]);
        ...
    dibujarDetalle();
```
- `Promise.all([...])`: hace los **dos pedidos a la vez** (detalle e historial) y espera a que terminen ambos. Es más rápido que uno detrás del otro.

`dibujarDetalle()` arma el panel derecho: los datos, el recuadro del **costo** (si ya se calculó), los **botones de acción** y la **línea de tiempo** del historial.

```javascript
function acciones(s) {
    const b = (accion, texto, clase = "") => `<button type="button" class="btn ${clase}" data-accion="${accion}">${texto}</button>`;
    const botones = [];
    const tecnicoDeLaOferta = esAdmin() || s.tecnicoId === estado.tecnicoActual;
    switch (s.estado) {
        case "PENDIENTE":
            if (puedeCliente()) botones.push(b("candidatos", "Buscar candidatos y ofertar"), b("cancelar", "Cancelar", "peligro"));
            break;
        case "OFERTADA":
            if (puedeTecnico() && tecnicoDeLaOferta) botones.push(b("aceptar", "Aceptar oferta", "ok"), b("rechazar", "Rechazar", "secundario"));
            ...
        case "ASIGNADA": ... formulario de presupuesto (técnico), Aprobar / Rechazar presupuesto (cliente)
        case "EN_CURSO": ... Finalizar, Falta un repuesto, Derivar (técnico)
        case "REPROGRAMADA": ... Reanudar (técnico)
        default: "La solicitud está cerrada."
    }
```
- La **máquina de estados en pantalla**: según el estado de la solicitud y tu rol, decide qué botones mostrar. Es la misma lógica que el mapa de transiciones del backend.
- `switch (s.estado) { case ...: }`: un `if` con muchas ramas según el valor.
- Cada botón lleva `data-accion="..."` para saber qué hacer al hacer clic.

```javascript
function conectarAcciones(s) {
    const base = `/api/v1/solicitudes/${s.id}`;
    const ejecutar = async (metodo, ruta, cuerpo, mensaje) => {
        try {
            await api(metodo, ruta, cuerpo);
            aviso(mensaje);
            estado.candidatos = null;
            await cargarSolicitudes(true);
            await cargarDetalle(s.id, true);
        } catch { /* aviso ya mostrado */ }
    };
```
- `ejecutar(...)`: hace el pedido, muestra un aviso verde y **recarga** la lista y el detalle para ver el nuevo estado. Si falla, `api` ya mostró el aviso rojo.

```javascript
    $("#detalle").querySelectorAll("[data-accion]").forEach(boton => boton.addEventListener("click", async () => {
        switch (boton.dataset.accion) {
            case "aceptar": return ejecutar("POST", `${base}/aceptar/${s.tecnicoId}`, undefined, "Oferta aceptada: ...");
            case "rechazar": return ejecutar("POST", `${base}/rechazar/${s.tecnicoId}`, undefined, ...);
            case "aprobar": return ejecutar("POST", `${base}/aprobar-presupuesto`, undefined, "Presupuesto aprobado: ...");
            ...
            case "cancelar": {
                const m = motivo("Motivo de la cancelación"); if (m === null) return;
                return ejecutar("POST", `${base}/cancelar?motivo=${m}`, undefined, "Solicitud cancelada");
            }
            case "ofertar": return ejecutar("POST", `${base}/ofertar/${boton.dataset.tecnico}`, undefined, "Oferta enviada");
        }
    }));
```
- A cada botón le conecta su pedido al backend: **cada botón es un endpoint**.
- `motivo(...)` usa `prompt()` (la ventanita del navegador que pide un texto) y `encodeURIComponent` para que espacios y acentos viajen bien en la URL.

```javascript
async function buscarCandidatos(s) {
    estado.candidatos = await api("POST", "/api/v1/solicitudes/elegir", {
        especialidadId: s.especialidadId, latitud: s.latitud, longitud: s.longitud,
        prioridad: s.prioridad, fechaHoraProgramada: s.fechaHoraProgramada, esFeriado: s.esFeriado,
    });
    dibujarDetalle();
}
```
- **Modo manual**: llama a `/elegir` con los datos de la solicitud y muestra la lista de técnicos, con su distancia y su costo, y un botón **Ofertar** al lado de cada uno.

```javascript
function actualizarCuentas() {
    document.querySelectorAll(".cuenta[data-expira]").forEach(el => {
        const resto = Math.max(0, new Date(el.dataset.expira) - new Date());
        const min = Math.floor(resto / 60000), seg = Math.floor((resto % 60000) / 1000);
        el.textContent = resto > 0 ? `${min}:${String(seg).padStart(2, "0")}` : "vencida (esperando al scheduler)";
    });
}
setInterval(actualizarCuentas, 1000);
```
- La **cuenta regresiva** de la oferta, actualizada cada segundo.
- Restar dos fechas en JavaScript da milisegundos. `/ 60000` → minutos y `% 60000 / 1000` → los segundos que sobran.
- `padStart(2, "0")`: `5` → `"05"`.
- Cuando llega a cero, muestra "vencida (esperando al scheduler)": el backend la va a pasar a PENDIENTE en la próxima pasada del reloj (como mucho 30 s).

### 8. Nueva solicitud

```javascript
async function vistaNueva() {
    ...
    const manana = new Date(Date.now() + 86400000);
    manana.setHours(10, 0, 0, 0);
    const local = new Date(manana.getTime() - manana.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
```
- Propone como fecha **mañana a las 10:00** (86 400 000 ms = 1 día).
- El campo `datetime-local` necesita el formato `2026-11-23T10:00` en **hora local**. `toISOString()` da hora UTC, así que primero se corre la fecha según la zona horaria (`getTimezoneOffset`) y después se cortan los primeros 16 caracteres.

```javascript
    $("#form-nueva").addEventListener("submit", async e => {
        e.preventDefault();
        const cuerpo = {
            clienteId: Number($("#n-cliente").value),
            tiposServicio: [...document.querySelectorAll('input[name="tipo"]:checked')].map(i => i.value),
            ...
            fechaHoraProgramada: $("#n-fecha").value + ":00",
            modoAsignacion: document.querySelector('input[name="modo"]:checked').value,
        };
        const creada = await api("POST", "/api/v1/solicitudes", cuerpo);
        aviso(`Solicitud #${creada.id} creada`, creada.estado === "OFERTADA"
            ? `Se ofertó automáticamente a ${creada.tecnicoNombre}` : `Estado ${creada.estado}`);
        ...
    });
```
- `e.preventDefault()`: evita que el navegador recargue la página al enviar el formulario (lo que haría por defecto).
- Arma el mismo JSON que mandarías desde Postman, leyendo los campos del formulario:
  - `[...querySelectorAll(":checked")].map(i => i.value)`: los tipos de servicio marcados;
  - `+ ":00"`: agrega los segundos, porque el backend espera `HH:mm:ss`.
- Si quedó **OFERTADA** apenas creada, es que la **oferta automática** ya eligió técnico, y el aviso lo dice.

### 9. Mis datos

```javascript
async function vistaMisDatosCliente() {
    let c;
    try { c = await api("GET", `/api/v1/clientes/${estado.clienteActual}`); } catch { return; }
    ...
    $("#form-datos").addEventListener("submit", async e => {
        e.preventDefault();
        await api("PUT", `/api/v1/clientes/${estado.clienteActual}`, { nombre: ..., calle: ..., latitud: ..., ... });
        aviso("Datos actualizados");
    });
}
```
- El cliente ve **solo su** registro (`GET /api/v1/clientes/{su número}`) en un formulario y puede corregirlo con `PUT`.
- El número sale del token, no de la URL de la página: no puede pedir el de otro.

```javascript
async function vistaMisDatosTecnico() {
    let t;
    try { t = await api("GET", `/api/v1/tecnicos/${estado.tecnicoActual}`); } catch { return; }
    ... botón que hace PATCH /api/v1/tecnicos/{id}/disponibilidad con !t.disponible
}
```
- El técnico ve **su** perfil (legajo, base, certificaciones y zonas) y un botón para marcarse disponible o no disponible.

### 10. El informe (solo administrador)

```javascript
function barras(titulo, filas, formato = v => v) {
    const max = Math.max(1, ...filas.map(f => f.valor));
    const total = filas.reduce((a, f) => a + f.valor, 0);
    return `<figure class="grafico">
      <figcaption>${esc(titulo)}</figcaption>
      ${filas.map(f => { ...
        return `<div class="barra-fila" title="${esc(detalle)}">
          <span class="barra-etiqueta">${esc(f.etiqueta)}</span>
          <span class="barra-pista"><span class="grafico-barra" style="width:${f.valor ? Math.max(2, f.valor * 100 / max) : 0}%"></span></span>
          <span class="barra-valor">${esc(formato(f.valor))}...</span>
        </div>`;
      }).join("")}
    </figure>`;
}
```
- Un **gráfico de barras horizontales hecho con HTML y CSS**, sin librerías. Cada fila tiene la etiqueta, la barra y el valor escrito.
- El **ancho** de cada barra es su valor dividido por el **máximo**, en porcentaje: la más grande ocupa el 100 %. `Math.max(1, ...)` evita dividir por cero si todo es 0. Una barra en 0 queda vacía y una muy chica se ve al menos con un 2 %.
- `reduce((a, f) => a + f.valor, 0)`: suma todos los valores (para el porcentaje).
- `title="..."`: al pasar el mouse muestra el detalle ("Cancelada: 6 (67 %)").
- **Un solo color** (`--serie`) para todas las barras, porque cada gráfico es una sola serie: el color no significa nada, lo importante es el largo y el número escrito. El azul está validado para que se vea bien en modo claro y oscuro.
- `<figure>` y `<figcaption>`: las etiquetas HTML correctas para "un gráfico con su título".

```javascript
async function vistaInforme(desde = "", hasta = "") {
    ...
    const parametros = new URLSearchParams();
    if (desde) parametros.set("desde", desde);
    if (hasta) parametros.set("hasta", hasta);
    let r;
    try { r = await api("GET", `/api/v1/solicitudes/informe?${parametros}`); } catch { ...; return; }
```
- Arma la URL con las fechas elegidas. `URLSearchParams` escribe `desde=2026-11-01&hasta=...` bien codificado.

```javascript
    const tile = (titulo, valor, pie = "") =>
        `<div class="kpi"><span>${esc(titulo)}</span><strong>${valor}</strong>${pie ? `<small>${esc(pie)}</small>` : ""}</div>`;
```
- Las **tarjetas de números grandes** (KPI = indicador clave): título, número y una aclaración debajo.

```javascript
    $("#i-imprimir").addEventListener("click", () => window.print());
```
- **Imprimir / PDF**: abre el diálogo de impresión del navegador (ahí se puede elegir "Guardar como PDF"). En `styles.css`, `@media print { ... }` esconde la barra superior y los botones, y evita que una tarjeta o una barra quede cortada entre dos hojas (`break-inside: avoid`).

### 11. Técnicos y clientes (solo administrador)

- `vistaTecnicos()`: la tabla de todos los técnicos. La columna "Disponible" es un botón que hace `PATCH /api/v1/tecnicos/{id}/disponibilidad` con el valor contrario.
- `vistaClientes()`: la tabla de clientes (solo admin, porque `GET /api/v1/clientes` es solo para ADMINISTRADOR).

```javascript
iniciar();
```
- La última línea **arranca todo**.
