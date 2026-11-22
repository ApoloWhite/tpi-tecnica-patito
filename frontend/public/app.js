// Frontend de Técnica Patito.
// 1) Inicia sesión en Keycloak (Authorization Code + PKCE) con la librería oficial keycloak-js.
// 2) Llama al API Gateway con el token en el header Authorization.
// 3) Muestra u oculta acciones según el rol, pero quien decide de verdad es el backend (401/403/422).
import Keycloak from "https://cdn.jsdelivr.net/npm/keycloak-js@26.0.7/+esm";

const CFG = window.PATITO_CONFIG;
const ESTADOS = ["PENDIENTE", "OFERTADA", "ASIGNADA", "EN_CURSO", "REPROGRAMADA", "DERIVADA", "CANCELADA", "FINALIZADA"];
const TIPOS = ["REPARACION", "INSTALACION", "MANTENIMIENTO"];
const REFRESCO_MS = 4000;

const keycloak = new Keycloak({ url: CFG.keycloakUrl, realm: CFG.realm, clientId: CFG.clientId });

const estado = {
    roles: [],
    pestania: "solicitudes",
    certificaciones: new Map(),
    tecnicos: [],
    solicitudes: [],
    filtroEstado: "",
    soloMias: true,
    seleccionada: null,
    historial: [],
    candidatos: null,
    clienteActual: Number(leer("patito.clienteActual", "1")),
    tecnicoActual: Number(leer("patito.tecnicoActual", "1")),
};

// ------------------------------------------------------------------ utilidades

function leer(clave, porDefecto) {
    try { return localStorage.getItem(clave) ?? porDefecto; } catch { return porDefecto; }
}
function guardar(clave, valor) {
    try { localStorage.setItem(clave, String(valor)); } catch { /* sin almacenamiento: no pasa nada */ }
}
function esc(valor) {
    return String(valor ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
const $ = sel => document.querySelector(sel);
const tiene = rol => estado.roles.includes(rol);
const esAdmin = () => tiene("ADMINISTRADOR");
const puedeCliente = () => esAdmin() || tiene("CLIENTE");
const puedeTecnico = () => esAdmin() || tiene("TECNICO");
const pesos = n => n == null ? "—" : Number(n).toLocaleString("es-AR", { style: "currency", currency: "ARS" });
const fecha = f => f ? new Date(f).toLocaleString("es-AR", { dateStyle: "short", timeStyle: "short" }) : "—";
const chipEstado = e => `<span class="chip estado ${esc(e)}">${esc(e.replace("_", " "))}</span>`;
const nombreEspecialidad = id => estado.certificaciones.get(id) ?? `#${id}`;

function aviso(titulo, detalle = "", error = false) {
    const div = document.createElement("div");
    div.className = "aviso" + (error ? " error" : "");
    div.innerHTML = `<strong>${esc(titulo)}</strong>${detalle ? `<small>${esc(detalle)}</small>` : ""}`;
    $("#avisos").append(div);
    setTimeout(() => div.remove(), error ? 9000 : 4000);
}

// ------------------------------------------------------------------ llamadas al backend

/** Llama al gateway con el token. Si el backend responde un ProblemDetail, lo muestra y lanza error. */
async function api(metodo, ruta, cuerpo) {
    await keycloak.updateToken(30).catch(() => keycloak.login());
    const respuesta = await fetch(CFG.apiUrl + ruta, {
        method: metodo,
        headers: {
            Authorization: `Bearer ${keycloak.token}`,
            ...(cuerpo !== undefined ? { "Content-Type": "application/json" } : {}),
        },
        body: cuerpo !== undefined ? JSON.stringify(cuerpo) : undefined,
    });
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

// ------------------------------------------------------------------ arranque

async function iniciar() {
    try {
        await keycloak.init({ onLoad: "login-required", pkceMethod: "S256", checkLoginIframe: false });
    } catch (e) {
        $("#contenido").innerHTML = `<div class="panel"><h2>No se pudo conectar con Keycloak</h2>
            <p class="ayuda">Verificá que Keycloak esté levantado en ${esc(CFG.keycloakUrl)} y que el realm "${esc(CFG.realm)}" exista.</p></div>`;
        return;
    }
    estado.roles = keycloak.tokenParsed?.realm_access?.roles ?? [];
    const rolPrincipal = ["ADMINISTRADOR", "TECNICO", "CLIENTE"].find(tiene) ?? "SIN ROL";
    $("#usuario-nombre").textContent = keycloak.tokenParsed?.preferred_username ?? "";
    $("#usuario-rol").textContent = rolPrincipal;
    $("#btn-salir").addEventListener("click", () => keycloak.logout({ redirectUri: window.location.origin }));

    try {
        const certs = await api("GET", "/api/v1/certificaciones");
        certs.forEach(c => estado.certificaciones.set(c.id, c.nombre));
        estado.tecnicos = (await api("GET", "/api/v1/tecnicos?size=100")).content;
    } catch { /* el aviso ya se mostró */ }

    dibujarPestanias();
    await mostrar("solicitudes");
    setInterval(refrescar, REFRESCO_MS);
}

function dibujarPestanias() {
    const pestanias = [["solicitudes", "Solicitudes"]];
    if (puedeCliente()) pestanias.push(["nueva", "Nueva solicitud"]);
    pestanias.push(["tecnicos", "Técnicos"]);
    if (esAdmin()) pestanias.push(["clientes", "Clientes"]);
    $("#pestanias").innerHTML = pestanias.map(([id, texto]) =>
        `<button type="button" data-pestania="${id}" ${estado.pestania === id ? 'aria-current="page"' : ""}>${texto}</button>`).join("");
    $("#pestanias").querySelectorAll("button").forEach(b => b.addEventListener("click", () => mostrar(b.dataset.pestania)));
}

async function mostrar(pestania) {
    estado.pestania = pestania;
    dibujarPestanias();
    if (pestania === "solicitudes") return vistaSolicitudes();
    if (pestania === "nueva") return vistaNueva();
    if (pestania === "tecnicos") return vistaTecnicos();
    if (pestania === "clientes") return vistaClientes();
}

/** Refresca solo la vista de solicitudes, para ver los cambios automáticos (ofertas y vencimientos). */
async function refrescar() {
    if (estado.pestania !== "solicitudes" || document.hidden) return;
    const ocupado = document.activeElement?.closest?.("#detalle form, #detalle input, #detalle textarea") || estado.candidatos;
    await cargarSolicitudes(true);
    if (estado.seleccionada && !ocupado) await cargarDetalle(estado.seleccionada.id, true);
}

// ------------------------------------------------------------------ Solicitudes

async function vistaSolicitudes() {
    const opciones = ESTADOS.map(e => `<option value="${e}" ${estado.filtroEstado === e ? "selected" : ""}>${e}</option>`).join("");
    const selectorIdentidad = tiene("TECNICO") && !esAdmin()
        ? `<label>Soy el técnico
             <select id="tecnico-actual">${estado.tecnicos.map(t =>
                `<option value="${t.id}" ${t.id === estado.tecnicoActual ? "selected" : ""}>${esc(t.nombre)} ${esc(t.apellido)}</option>`).join("")}</select>
           </label>`
        : tiene("CLIENTE") && !esAdmin()
            ? `<label>Soy el cliente nº <input id="cliente-actual" type="number" min="1" value="${estado.clienteActual}" style="width:80px"></label>`
            : "";
    $("#contenido").innerHTML = `
      <div class="grilla">
        <section class="panel">
          <div class="cabecera">
            <h2>Solicitudes</h2>
            <div class="filtros">
              ${selectorIdentidad}
              ${!esAdmin() ? `<label><input type="checkbox" id="solo-mias" ${estado.soloMias ? "checked" : ""}> Solo las mías</label>` : ""}
              <select id="filtro-estado" aria-label="Filtrar por estado"><option value="">Todos los estados</option>${opciones}</select>
            </div>
          </div>
          <p class="ayuda">Se actualiza sola cada ${REFRESCO_MS / 1000} segundos: vas a ver las ofertas automáticas y los vencimientos.</p>
          <div class="tabla-scroll" id="tabla-solicitudes"></div>
        </section>
        <section class="panel" id="detalle"><p class="ayuda">Elegí una solicitud para ver su detalle, sus acciones y su historial.</p></section>
      </div>`;
    $("#filtro-estado").addEventListener("change", e => { estado.filtroEstado = e.target.value; cargarSolicitudes(); });
    $("#solo-mias")?.addEventListener("change", e => { estado.soloMias = e.target.checked; cargarSolicitudes(); });
    $("#tecnico-actual")?.addEventListener("change", e => {
        estado.tecnicoActual = Number(e.target.value); guardar("patito.tecnicoActual", estado.tecnicoActual); cargarSolicitudes();
        if (estado.seleccionada) dibujarDetalle();
    });
    $("#cliente-actual")?.addEventListener("change", e => {
        estado.clienteActual = Number(e.target.value); guardar("patito.clienteActual", estado.clienteActual); cargarSolicitudes();
    });
    await cargarSolicitudes();
    if (estado.seleccionada) await cargarDetalle(estado.seleccionada.id);
}

async function cargarSolicitudes(silencioso = false) {
    let pagina;
    try {
        const filtro = estado.filtroEstado ? `&estado=${estado.filtroEstado}` : "";
        pagina = await api("GET", `/api/v1/solicitudes?size=50&sort=id,desc${filtro}`);
    } catch { return; }
    let lista = pagina.content;
    if (!esAdmin() && estado.soloMias) {
        if (tiene("TECNICO")) lista = lista.filter(s => s.tecnicoId === estado.tecnicoActual);
        else if (tiene("CLIENTE")) lista = lista.filter(s => s.clienteId === estado.clienteActual);
    }
    estado.solicitudes = lista;
    const contenedor = $("#tabla-solicitudes");
    if (!contenedor) return;
    if (!lista.length) {
        contenedor.innerHTML = `<p class="vacio">No hay solicitudes para mostrar.</p>`;
        return;
    }
    contenedor.innerHTML = `
      <table>
        <thead><tr><th>#</th><th>Estado</th><th>Cliente</th><th>Equipo</th><th>Especialidad</th><th>Técnico</th><th>Modo</th></tr></thead>
        <tbody>${lista.map(s => `
          <tr data-id="${s.id}" class="${estado.seleccionada?.id === s.id ? "activa" : ""}">
            <td class="num">${s.id}</td>
            <td>${chipEstado(s.estado)}</td>
            <td>${esc(s.clienteNombre)}</td>
            <td>${esc(s.marca)} ${esc(s.modelo)}</td>
            <td>${esc(nombreEspecialidad(s.especialidadId))}</td>
            <td>${esc(s.tecnicoNombre ?? "—")}</td>
            <td><span class="chip">${esc(s.modoAsignacion)}</span></td>
          </tr>`).join("")}
        </tbody>
      </table>`;
    contenedor.querySelectorAll("tbody tr").forEach(tr =>
        tr.addEventListener("click", () => { estado.candidatos = null; cargarDetalle(Number(tr.dataset.id)); }));
    if (!silencioso) contenedor.scrollTop = 0;
}

async function cargarDetalle(id, silencioso = false) {
    try {
        const [solicitud, historial] = await Promise.all([
            api("GET", `/api/v1/solicitudes/${id}`),
            api("GET", `/api/v1/solicitudes/${id}/historial`),
        ]);
        estado.seleccionada = solicitud;
        estado.historial = historial;
    } catch { return; }
    document.querySelectorAll("#tabla-solicitudes tbody tr").forEach(tr =>
        tr.classList.toggle("activa", Number(tr.dataset.id) === id));
    dibujarDetalle();
    if (!silencioso) $("#detalle")?.scrollIntoView({ behavior: "smooth", block: "nearest" });
}

function dibujarDetalle() {
    const s = estado.seleccionada;
    const panel = $("#detalle");
    if (!s || !panel) return;
    const dato = (titulo, valor) => `<div><dt>${titulo}</dt><dd>${valor}</dd></div>`;
    const cuenta = s.estado === "OFERTADA" && s.ofertaExpira
        ? `<span class="cuenta" data-expira="${esc(s.ofertaExpira)}"></span>` : "—";
    panel.innerHTML = `
      <div class="cabecera">
        <h2>Solicitud #${s.id}</h2>
        <div>${chipEstado(s.estado)} <span class="chip">${esc(s.modoAsignacion)}</span></div>
      </div>
      <dl class="datos">
        ${dato("Cliente", `${esc(s.clienteNombre)} (nº ${s.clienteId})`)}
        ${dato("Domicilio", esc(s.domicilio))}
        ${dato("Equipo", `${esc(s.marca)} ${esc(s.modelo)}`)}
        ${dato("Tipos de servicio", esc(s.tiposServicio.join(", ")))}
        ${dato("Especialidad", esc(nombreEspecialidad(s.especialidadId)))}
        ${dato("Prioridad", esc(s.prioridad) + (s.esFeriado ? " · feriado" : ""))}
        ${dato("Programada", fecha(s.fechaHoraProgramada))}
        ${dato("Técnico", esc(s.tecnicoNombre ?? "—"))}
        ${dato("Vence la oferta en", cuenta)}
        ${dato("Descartados", s.tecnicosDescartados.length ? esc(s.tecnicosDescartados.map(nombreTecnico).join(", ")) : "—")}
        ${dato("Falla", esc(s.descripcion))}
        ${s.diagnostico ? dato("Diagnóstico", esc(s.diagnostico)) : ""}
      </dl>
      ${s.total != null ? `
        <div class="costo">
          <div><span>Distancia</span><strong>${s.distanciaKm} km · ${s.tiempoEstimadoMin} min</strong></div>
          <div><span>Fuente</span><strong>${esc(s.fuenteDistancia)}</strong></div>
          <div><span>Estrategia</span><strong>${esc(s.estrategiaCosto)}</strong></div>
          <div><span>Base</span><strong>${pesos(s.costoBase)}</strong></div>
          <div><span>Recargo</span><strong>${pesos(s.recargo)}</strong></div>
          <div><span>Visita</span><strong>${pesos(s.total)}</strong></div>
          <div><span>Presupuesto</span><strong>${pesos(s.presupuesto)}</strong></div>
        </div>` : ""}
      <div class="acciones" id="acciones">${acciones(s)}</div>
      ${estado.candidatos ? dibujarCandidatos() : ""}
      <h3>Historial</h3>
      <ol class="linea">${estado.historial.map(h => `
        <li>
          <div class="cuando">${fecha(h.fechaHora)} · ${esc(h.usuario)}</div>
          <div>${h.estadoAnterior ? chipEstado(h.estadoAnterior) + " → " : ""}${chipEstado(h.estadoNuevo)}</div>
          <p>${esc(h.observaciones)}</p>
        </li>`).join("")}
      </ol>`;
    conectarAcciones(s);
    actualizarCuentas();
}

function nombreTecnico(id) {
    const t = estado.tecnicos.find(x => x.id === id);
    return t ? `${t.nombre} ${t.apellido}` : `#${id}`;
}

/** Botones según el estado y el rol (la regla real la valida el backend). */
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
            if (puedeTecnico() && !tecnicoDeLaOferta) botones.push(`<p class="nota">La oferta es para ${esc(s.tecnicoNombre)}. Elegilo arriba en "Soy el técnico" para responderla.</p>`);
            if (puedeCliente()) botones.push(b("cancelar", "Cancelar", "peligro"));
            break;
        case "ASIGNADA":
            if (puedeTecnico()) botones.push(`
              <form id="form-presupuesto" class="formulario" style="width:100%">
                <div class="campo ancho"><label for="p-diag">Diagnóstico</label><textarea id="p-diag" required>${esc(s.diagnostico ?? "")}</textarea></div>
                <div class="campo"><label for="p-imp">Importe</label><input id="p-imp" type="number" min="1" step="0.01" required value="${s.presupuesto ?? ""}"></div>
                <div class="campo" style="justify-content:end"><button class="btn" type="submit">${s.presupuesto ? "Actualizar" : "Cargar"} presupuesto</button></div>
              </form>`);
            if (puedeCliente()) {
                botones.push(`<button type="button" class="btn ok" data-accion="aprobar" ${s.presupuesto ? "" : "disabled"}>Aprobar presupuesto</button>`);
                botones.push(b("rechazar-presupuesto", "Rechazar presupuesto", "peligro"));
                if (!s.presupuesto) botones.push(`<p class="nota">El técnico todavía no cargó el presupuesto.</p>`);
            }
            break;
        case "EN_CURSO":
            if (puedeTecnico()) botones.push(b("finalizar", "Finalizar trabajo", "ok"), b("reprogramar", "Falta un repuesto", "secundario"), b("derivar", "Derivar a otro técnico", "secundario"));
            break;
        case "REPROGRAMADA":
            if (puedeTecnico()) botones.push(b("reanudar", "Llegó el repuesto: reanudar"));
            break;
        default:
            botones.push(`<p class="nota">La solicitud está cerrada.</p>`);
    }
    return botones.length ? botones.join("") : `<p class="nota">Tu rol no tiene acciones en este estado.</p>`;
}

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
    const motivo = texto => { const m = prompt(texto); return m === null ? null : encodeURIComponent(m); };
    $("#detalle").querySelectorAll("[data-accion]").forEach(boton => boton.addEventListener("click", async () => {
        switch (boton.dataset.accion) {
            case "candidatos": return buscarCandidatos(s);
            case "aceptar": return ejecutar("POST", `${base}/aceptar/${s.tecnicoId}`, undefined, "Oferta aceptada: se calculó el costo de visita");
            case "rechazar": return ejecutar("POST", `${base}/rechazar/${s.tecnicoId}`, undefined,
                s.modoAsignacion === "AUTOMATICA" ? "Oferta rechazada: el sistema buscó al siguiente técnico" : "Oferta rechazada");
            case "aprobar": return ejecutar("POST", `${base}/aprobar-presupuesto`, undefined, "Presupuesto aprobado: trabajo en curso");
            case "finalizar": return ejecutar("POST", `${base}/finalizar`, undefined, "Trabajo finalizado");
            case "reanudar": return ejecutar("POST", `${base}/reanudar`, undefined, "Trabajo reanudado");
            case "rechazar-presupuesto":
                if (!confirm("¿Rechazar el presupuesto? La solicitud se cancela.")) return;
                return ejecutar("POST", `${base}/cancelar?motivo=${encodeURIComponent("Cliente rechazó el presupuesto")}`, undefined, "Solicitud cancelada");
            case "cancelar": {
                const m = motivo("Motivo de la cancelación"); if (m === null) return;
                return ejecutar("POST", `${base}/cancelar?motivo=${m}`, undefined, "Solicitud cancelada");
            }
            case "reprogramar": {
                const m = motivo("¿Qué repuesto falta?"); if (m === null) return;
                return ejecutar("POST", `${base}/reprogramar?motivo=${m}`, undefined, "Solicitud reprogramada");
            }
            case "derivar": {
                const m = motivo("¿Por qué se deriva?"); if (m === null) return;
                return ejecutar("POST", `${base}/derivar?motivo=${m}`, undefined, "Solicitud derivada");
            }
            case "ofertar": return ejecutar("POST", `${base}/ofertar/${boton.dataset.tecnico}`, undefined, "Oferta enviada");
            case "cerrar-candidatos": estado.candidatos = null; return dibujarDetalle();
        }
    }));
    $("#form-presupuesto")?.addEventListener("submit", e => {
        e.preventDefault();
        ejecutar("POST", `${base}/presupuesto`, { diagnostico: $("#p-diag").value, importe: Number($("#p-imp").value) }, "Presupuesto cargado");
    });
}

async function buscarCandidatos(s) {
    try {
        estado.candidatos = await api("POST", "/api/v1/solicitudes/elegir", {
            especialidadId: s.especialidadId, latitud: s.latitud, longitud: s.longitud,
            prioridad: s.prioridad, fechaHoraProgramada: s.fechaHoraProgramada, esFeriado: s.esFeriado,
        });
        dibujarDetalle();
    } catch { /* aviso ya mostrado */ }
}

function dibujarCandidatos() {
    const r = estado.candidatos;
    const lista = r.candidatos.map(c => `
      <div class="candidato">
        <div>
          <strong>${esc(c.tecnicoNombre)}</strong><br>
          <small>${c.distanciaKm} km · ~${c.tiempoEstimadoMin} min · ${esc(c.estrategiaCosto)} · visita ${pesos(c.total)}</small>
        </div>
        <button type="button" class="btn chico" data-accion="ofertar" data-tecnico="${c.tecnicoId}">Ofertar</button>
      </div>`).join("");
    return `<div class="candidatos">
      <h3>Candidatos (menos de 13 km, con la certificación)</h3>
      <p class="ayuda">${esc(r.mensaje)}</p>
      ${lista}
      <button type="button" class="btn secundario chico" data-accion="cerrar-candidatos">Cerrar</button>
    </div>`;
}

function actualizarCuentas() {
    document.querySelectorAll(".cuenta[data-expira]").forEach(el => {
        const resto = Math.max(0, new Date(el.dataset.expira) - new Date());
        const min = Math.floor(resto / 60000), seg = Math.floor((resto % 60000) / 1000);
        el.textContent = resto > 0 ? `${min}:${String(seg).padStart(2, "0")}` : "vencida (esperando al scheduler)";
    });
}
setInterval(actualizarCuentas, 1000);

// ------------------------------------------------------------------ Nueva solicitud

async function vistaNueva() {
    let clientes = [];
    if (esAdmin()) {
        try { clientes = (await api("GET", "/api/v1/clientes?size=100")).content; } catch { /* aviso ya mostrado */ }
    }
    const manana = new Date(Date.now() + 86400000);
    manana.setHours(10, 0, 0, 0);
    const local = new Date(manana.getTime() - manana.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
    const campoCliente = esAdmin()
        ? `<select id="n-cliente">${clientes.map(c => `<option value="${c.id}">${c.id} · ${esc(c.nombre)} ${esc(c.apellido)} (${esc(c.barrio ?? "")})</option>`).join("")}</select>`
        : `<input id="n-cliente" type="number" min="1" value="${estado.clienteActual}" required>`;
    $("#contenido").innerHTML = `
      <section class="panel">
        <h2>Nueva solicitud de servicio</h2>
        <p class="ayuda">El sistema copia los datos y el domicilio del cliente en la solicitud (snapshot).</p>
        <form id="form-nueva" class="formulario">
          <div class="campo"><label for="n-cliente">Cliente</label>${campoCliente}<span class="pista" id="n-cliente-nombre"></span></div>
          <div class="campo"><label for="n-especialidad">Especialidad requerida</label>
            <select id="n-especialidad">${[...estado.certificaciones].map(([id, n]) => `<option value="${id}">${esc(n)}</option>`).join("")}</select></div>
          <div class="campo"><label for="n-prioridad">Prioridad</label>
            <select id="n-prioridad"><option>NORMAL</option><option>URGENTE</option></select></div>
          <div class="campo"><label for="n-fecha">Fecha y hora programada</label><input id="n-fecha" type="datetime-local" value="${local}" required></div>
          <div class="campo"><label for="n-marca">Marca</label><input id="n-marca" value="HP" required></div>
          <div class="campo"><label for="n-modelo">Modelo</label><input id="n-modelo" value="LaserJet 1020" required></div>
          <fieldset class="campo ancho"><legend>Tipos de servicio</legend>
            ${TIPOS.map((t, i) => `<label><input type="checkbox" name="tipo" value="${t}" ${i === 0 ? "checked" : ""}> ${t}</label>`).join("")}
          </fieldset>
          <div class="campo ancho"><label for="n-desc">Descripción de la falla</label><textarea id="n-desc" required>No toma las hojas</textarea></div>
          <fieldset class="campo ancho"><legend>Asignación del técnico</legend>
            <label><input type="radio" name="modo" value="AUTOMATICA" checked> Automática: el sistema oferta al más cercano</label>
            <label><input type="radio" name="modo" value="MANUAL"> Manual: elijo yo a quién ofertar</label>
          </fieldset>
          <div class="campo"><label><input type="checkbox" id="n-feriado"> Es feriado</label></div>
          <div class="campo ancho"><button class="btn" type="submit">Crear solicitud</button></div>
        </form>
      </section>`;
    const mostrarNombre = async () => {
        if (esAdmin()) return;
        const id = Number($("#n-cliente").value);
        try {
            const c = await api("GET", `/api/v1/clientes/${id}`);
            $("#n-cliente-nombre").textContent = `${c.nombre} ${c.apellido} · ${c.calle}, ${c.barrio ?? ""}`;
        } catch { $("#n-cliente-nombre").textContent = ""; }
    };
    $("#n-cliente").addEventListener("change", mostrarNombre);
    mostrarNombre();
    $("#form-nueva").addEventListener("submit", async e => {
        e.preventDefault();
        const cuerpo = {
            clienteId: Number($("#n-cliente").value),
            tiposServicio: [...document.querySelectorAll('input[name="tipo"]:checked')].map(i => i.value),
            marca: $("#n-marca").value,
            modelo: $("#n-modelo").value,
            descripcion: $("#n-desc").value,
            especialidadId: Number($("#n-especialidad").value),
            prioridad: $("#n-prioridad").value,
            fechaHoraProgramada: $("#n-fecha").value + ":00",
            esFeriado: $("#n-feriado").checked,
            modoAsignacion: document.querySelector('input[name="modo"]:checked').value,
        };
        try {
            const creada = await api("POST", "/api/v1/solicitudes", cuerpo);
            if (!esAdmin()) { estado.clienteActual = creada.clienteId; guardar("patito.clienteActual", creada.clienteId); }
            aviso(`Solicitud #${creada.id} creada`, creada.estado === "OFERTADA"
                ? `Se ofertó automáticamente a ${creada.tecnicoNombre}` : `Estado ${creada.estado}`);
            estado.seleccionada = creada;
            estado.filtroEstado = "";
            await mostrar("solicitudes");
        } catch { /* aviso ya mostrado */ }
    });
}

// ------------------------------------------------------------------ Técnicos y clientes

async function vistaTecnicos() {
    try { estado.tecnicos = (await api("GET", "/api/v1/tecnicos?size=100")).content; } catch { return; }
    $("#contenido").innerHTML = `
      <section class="panel">
        <h2>Técnicos</h2>
        <p class="ayuda">Solo los técnicos disponibles reciben ofertas.</p>
        <div class="tabla-scroll"><table>
          <thead><tr><th>#</th><th>Legajo</th><th>Nombre</th><th>Certificaciones</th><th>Zonas</th><th>Ubicación</th><th>Disponible</th></tr></thead>
          <tbody>${estado.tecnicos.map(t => `
            <tr>
              <td class="num">${t.id}</td><td>${esc(t.legajo)}</td><td>${esc(t.nombre)} ${esc(t.apellido)}</td>
              <td>${esc(t.certificaciones.map(c => c.nombre).join(", "))}</td>
              <td>${esc(t.zonas.map(z => z.nombre).join(", ") || "—")}</td>
              <td class="num">${t.latitud}, ${t.longitud}</td>
              <td>${puedeTecnico()
                ? `<button type="button" class="btn chico ${t.disponible ? "ok" : "secundario"}" data-tecnico="${t.id}" data-disponible="${t.disponible}">${t.disponible ? "Sí" : "No"}</button>`
                : (t.disponible ? "Sí" : "No")}</td>
            </tr>`).join("")}
          </tbody>
        </table></div>
      </section>`;
    $("#contenido").querySelectorAll("[data-tecnico]").forEach(b => b.addEventListener("click", async () => {
        try {
            await api("PATCH", `/api/v1/tecnicos/${b.dataset.tecnico}/disponibilidad`, { disponible: b.dataset.disponible !== "true" });
            aviso("Disponibilidad actualizada");
            vistaTecnicos();
        } catch { /* aviso ya mostrado */ }
    }));
}

async function vistaClientes() {
    let clientes;
    try { clientes = (await api("GET", "/api/v1/clientes?size=100")).content; } catch { return; }
    $("#contenido").innerHTML = `
      <section class="panel">
        <h2>Clientes</h2>
        <div class="tabla-scroll"><table>
          <thead><tr><th>#</th><th>Nombre</th><th>DNI</th><th>Domicilio</th><th>Coordenadas</th></tr></thead>
          <tbody>${clientes.map(c => `
            <tr><td class="num">${c.id}</td><td>${esc(c.nombre)} ${esc(c.apellido)}</td><td>${esc(c.dni)}</td>
            <td>${esc(c.calle)}${c.barrio ? ", " + esc(c.barrio) : ""}</td><td class="num">${c.latitud}, ${c.longitud}</td></tr>`).join("")}
          </tbody>
        </table></div>
      </section>`;
}

iniciar();
