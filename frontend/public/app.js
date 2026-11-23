// Frontend de Técnica Patito.
// 1) Inicia sesión en Keycloak (Authorization Code + PKCE) con la librería oficial keycloak-js.
// 2) Llama al API Gateway con el token en el header Authorization.
// 3) Muestra u oculta acciones según el rol, pero quien decide de verdad es el backend (401/403/422).
// 4) Cada usuario ve solo lo suyo: el número de cliente o de técnico viene DENTRO del token
//    (claims clienteId / tecnicoId que agrega Keycloak), así nadie puede hacerse pasar por otro desde la página.
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
    seleccionada: null,
    historial: [],
    candidatos: null,
    accionAbierta: null,   // formulario de motivo abierto: { tipo, id } (cancelar, reprogramar, derivar, rechazar-presupuesto)
    clienteActual: null,   // sale del token (claim clienteId)
    tecnicoActual: null,   // sale del token (claim tecnicoId)
};

// ------------------------------------------------------------------ utilidades

function esc(valor) {
    return String(valor ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
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

    // Quién soy en el padrón: lo dice el token, no se elige en la página
    estado.clienteActual = keycloak.tokenParsed?.clienteId ?? null;
    estado.tecnicoActual = keycloak.tokenParsed?.tecnicoId ?? null;
    if ((esSoloCliente() && estado.clienteActual == null) || (esSoloTecnico() && estado.tecnicoActual == null)) {
        $("#contenido").innerHTML = `<div class="panel"><h2>Tu usuario no está vinculado al padrón</h2>
            <p class="ayuda">En Keycloak, el usuario necesita el atributo ${esSoloCliente() ? "clienteId" : "tecnicoId"}.</p></div>`;
        return;
    }
    if (estado.clienteActual != null) $("#usuario-nombre").textContent += ` · cliente nº ${estado.clienteActual}`;
    if (estado.tecnicoActual != null) $("#usuario-nombre").textContent += ` · técnico nº ${estado.tecnicoActual}`;

    try {
        const certs = await api("GET", "/api/v1/certificaciones");
        certs.forEach(c => estado.certificaciones.set(c.id, c.nombre));
        // La lista de técnicos solo la necesita (y la puede ver) el administrador
        if (esAdmin()) estado.tecnicos = (await api("GET", "/api/v1/tecnicos?size=100")).content;
    } catch { /* el aviso ya se mostró */ }

    dibujarPestanias();
    await mostrar("solicitudes");
    setInterval(refrescar, REFRESCO_MS);
}

function dibujarPestanias() {
    const titulo = esSoloCliente() ? "Mis solicitudes" : esSoloTecnico() ? "Mis trabajos" : "Solicitudes";
    const pestanias = [["solicitudes", titulo]];
    if (puedeCliente()) pestanias.push(["nueva", "Nueva solicitud"]);
    if (esSoloCliente() || esSoloTecnico()) pestanias.push(["mis-datos", "Mis datos"]);
    if (esAdmin()) pestanias.push(["informe", "Informe"], ["tecnicos", "Técnicos"], ["clientes", "Clientes"]);
    $("#pestanias").innerHTML = pestanias.map(([id, texto]) =>
        `<button type="button" data-pestania="${id}" ${estado.pestania === id ? 'aria-current="page"' : ""}>${texto}</button>`).join("");
    $("#pestanias").querySelectorAll("button").forEach(b => b.addEventListener("click", () => mostrar(b.dataset.pestania)));
}

async function mostrar(pestania) {
    estado.pestania = pestania;
    dibujarPestanias();
    if (pestania === "solicitudes") return vistaSolicitudes();
    if (pestania === "nueva") return vistaNueva();
    if (pestania === "mis-datos") return esSoloCliente() ? vistaMisDatosCliente() : vistaMisDatosTecnico();
    if (pestania === "informe") return vistaInforme();
    if (pestania === "tecnicos") return vistaTecnicos();
    if (pestania === "clientes") return vistaClientes();
}

/** Refresca solo la vista de solicitudes, para ver los cambios automáticos (ofertas y vencimientos). */
async function refrescar() {
    if (estado.pestania !== "solicitudes" || document.hidden) return;
    const ocupado = document.activeElement?.closest?.("#detalle form, #detalle input, #detalle textarea")
        || estado.candidatos || estado.accionAbierta;
    await cargarSolicitudes(true);
    if (estado.seleccionada && !ocupado) await cargarDetalle(estado.seleccionada.id, true);
}

// ------------------------------------------------------------------ Solicitudes

async function vistaSolicitudes() {
    const opciones = ESTADOS.map(e => `<option value="${e}" ${estado.filtroEstado === e ? "selected" : ""}>${e}</option>`).join("");
    const quien = esSoloCliente() ? `Ves solo tus solicitudes (cliente nº ${estado.clienteActual}).`
        : esSoloTecnico() ? `Ves solo las ofertas y trabajos asignados a vos (técnico nº ${estado.tecnicoActual}).`
        : "Como administrador ves todas las solicitudes.";
    $("#contenido").innerHTML = `
      <div class="grilla">
        <section class="panel">
          <div class="cabecera">
            <h2>${esSoloCliente() ? "Mis solicitudes" : esSoloTecnico() ? "Mis trabajos" : "Solicitudes"}</h2>
            <div class="filtros">
              <select id="filtro-estado" aria-label="Filtrar por estado"><option value="">Todos los estados</option>${opciones}</select>
            </div>
          </div>
          <p class="ayuda">${quien} Se actualiza sola cada ${REFRESCO_MS / 1000} segundos: vas a ver las ofertas automáticas y los vencimientos.</p>
          <div class="tabla-scroll" id="tabla-solicitudes"></div>
        </section>
        <section class="panel" id="detalle"><p class="ayuda">Elegí una solicitud para ver su detalle, sus acciones y su historial.</p></section>
      </div>`;
    $("#filtro-estado").addEventListener("change", e => { estado.filtroEstado = e.target.value; cargarSolicitudes(); });
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
    if (esSoloCliente()) lista = lista.filter(s => s.clienteId === estado.clienteActual);
    else if (esSoloTecnico()) lista = lista.filter(s => s.tecnicoId === estado.tecnicoActual);
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
        tr.addEventListener("click", () => { estado.candidatos = null; estado.accionAbierta = null; cargarDetalle(Number(tr.dataset.id)); }));
    if (!silencioso) contenedor.scrollTop = 0;
}

async function cargarDetalle(id, silencioso = false) {
    try {
        const [solicitud, historial] = await Promise.all([
            api("GET", `/api/v1/solicitudes/${id}`),
            api("GET", `/api/v1/solicitudes/${id}/historial`),
        ]);
        const sinCambios = silencioso && estado.seleccionada?.id === id
            && JSON.stringify(solicitud) === JSON.stringify(estado.seleccionada)
            && historial.length === estado.historial.length;
        estado.seleccionada = solicitud;
        estado.historial = historial;
        if (sinCambios) return;   // nada nuevo: no se redibuja (si se redibujara, un clic en curso se perdería)
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
        ${esAdmin() ? dato("Descartados", s.tecnicosDescartados.length ? esc(s.tecnicosDescartados.map(nombreTecnico).join(", ")) : "—") : ""}
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
    const abierta = estado.accionAbierta?.id === s.id ? ACCIONES_CON_MOTIVO[estado.accionAbierta.tipo] : null;
    if (abierta) {
        return `<form id="form-motivo" class="formulario" style="width:100%">
          <div class="campo ancho">
            <label for="m-texto">${esc(abierta.pregunta)}</label>
            <input id="m-texto" maxlength="200" value="${esc(abierta.porDefecto)}" placeholder="${esc(abierta.ejemplo)}">
          </div>
          <div class="campo ancho" style="flex-direction:row; gap:8px">
            <button class="btn ${abierta.clase}" type="submit">${esc(abierta.confirmar)}</button>
            <button class="btn secundario" type="button" data-accion="cerrar-motivo">Volver</button>
          </div>
        </form>`;
    }
    switch (s.estado) {
        case "PENDIENTE":
            if (puedeCliente()) botones.push(b("candidatos", "Buscar candidatos y ofertar"), b("cancelar", "Cancelar", "peligro"));
            break;
        case "OFERTADA":
            if (puedeTecnico() && tecnicoDeLaOferta) botones.push(b("aceptar", "Aceptar oferta", "ok"), b("rechazar", "Rechazar", "secundario"));
            if (puedeTecnico() && !tecnicoDeLaOferta) botones.push(`<p class="nota">La oferta es para ${esc(s.tecnicoNombre)}: solo esa persona puede responderla.</p>`);
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

/** Acciones que piden un texto (motivo). Se completan en un formulario dentro del panel, sin prompt() ni confirm(). */
const ACCIONES_CON_MOTIVO = {
    "cancelar": { pregunta: "Motivo de la cancelación (opcional)", ejemplo: "Ya no lo necesito", porDefecto: "",
        confirmar: "Confirmar cancelación", clase: "peligro", ruta: "cancelar", mensaje: "Solicitud cancelada" },
    "rechazar-presupuesto": { pregunta: "Rechazar el presupuesto cancela la solicitud. Motivo:", ejemplo: "", porDefecto: "Cliente rechazó el presupuesto",
        confirmar: "Confirmar rechazo", clase: "peligro", ruta: "cancelar", mensaje: "Presupuesto rechazado: solicitud cancelada" },
    "reprogramar": { pregunta: "¿Qué repuesto falta?", ejemplo: "Rodillo de arrastre", porDefecto: "",
        confirmar: "Reprogramar", clase: "", ruta: "reprogramar", mensaje: "Solicitud reprogramada" },
    "derivar": { pregunta: "¿Por qué se deriva a otro técnico?", ejemplo: "La falla requiere otra certificación", porDefecto: "",
        confirmar: "Derivar", clase: "", ruta: "derivar", mensaje: "Solicitud derivada" },
};

function conectarAcciones(s) {
    const base = `/api/v1/solicitudes/${s.id}`;
    const ejecutar = async (metodo, ruta, cuerpo, mensaje) => {
        try {
            await api(metodo, ruta, cuerpo);
            aviso(mensaje);
            estado.candidatos = null;
            estado.accionAbierta = null;
            await cargarSolicitudes(true);
            await cargarDetalle(s.id, true);
        } catch { /* aviso ya mostrado */ }
    };
    const abrirMotivo = tipo => {
        estado.accionAbierta = { tipo, id: s.id };
        dibujarDetalle();
        $("#m-texto")?.focus();
    };
    $("#detalle").querySelectorAll("[data-accion]").forEach(boton => boton.addEventListener("click", async () => {
        switch (boton.dataset.accion) {
            case "candidatos": return buscarCandidatos(s);
            case "aceptar": return ejecutar("POST", `${base}/aceptar/${s.tecnicoId}`, undefined, "Oferta aceptada: se calculó el costo de visita");
            case "rechazar": return ejecutar("POST", `${base}/rechazar/${s.tecnicoId}`, undefined,
                s.modoAsignacion === "AUTOMATICA" ? "Oferta rechazada: el sistema buscó al siguiente técnico" : "Oferta rechazada");
            case "aprobar": return ejecutar("POST", `${base}/aprobar-presupuesto`, undefined, "Presupuesto aprobado: trabajo en curso");
            case "finalizar": return ejecutar("POST", `${base}/finalizar`, undefined, "Trabajo finalizado");
            case "reanudar": return ejecutar("POST", `${base}/reanudar`, undefined, "Trabajo reanudado");
            case "cancelar":
            case "rechazar-presupuesto":
            case "reprogramar":
            case "derivar":
                return abrirMotivo(boton.dataset.accion);
            case "cerrar-motivo": estado.accionAbierta = null; return dibujarDetalle();
            case "ofertar": return ejecutar("POST", `${base}/ofertar/${boton.dataset.tecnico}`, undefined, "Oferta enviada");
            case "cerrar-candidatos": estado.candidatos = null; return dibujarDetalle();
        }
    }));
    $("#form-motivo")?.addEventListener("submit", e => {
        e.preventDefault();
        const accion = ACCIONES_CON_MOTIVO[estado.accionAbierta.tipo];
        const texto = $("#m-texto").value.trim();
        const consulta = texto ? `?motivo=${encodeURIComponent(texto)}` : "";
        ejecutar("POST", `${base}/${accion.ruta}${consulta}`, undefined, accion.mensaje);
    });
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
        : `<input id="n-cliente" type="hidden" value="${estado.clienteActual}"><strong>nº ${estado.clienteActual}</strong>`;
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
            aviso(`Solicitud #${creada.id} creada`, creada.estado === "OFERTADA"
                ? `Se ofertó automáticamente a ${creada.tecnicoNombre}` : `Estado ${creada.estado}`);
            estado.seleccionada = creada;
            estado.filtroEstado = "";
            await mostrar("solicitudes");
        } catch { /* aviso ya mostrado */ }
    });
}

// ------------------------------------------------------------------ Mis datos

/** El cliente ve y corrige SOLO su propio registro (GET y PUT /api/v1/clientes/{su id}). */
async function vistaMisDatosCliente() {
    let c;
    try { c = await api("GET", `/api/v1/clientes/${estado.clienteActual}`); } catch { return; }
    const campo = (id, etiqueta, valor, tipo = "text", extra = "") =>
        `<div class="campo"><label for="${id}">${etiqueta}</label><input id="${id}" type="${tipo}" value="${esc(valor ?? "")}" ${extra}></div>`;
    $("#contenido").innerHTML = `
      <section class="panel">
        <h2>Mis datos</h2>
        <p class="ayuda">Tus datos de cliente nº ${c.id}. Si cambiás el domicilio, las solicitudes ya creadas conservan el anterior (snapshot).</p>
        <form id="form-datos" class="formulario">
          ${campo("d-nombre", "Nombre", c.nombre, "text", "required")}
          ${campo("d-apellido", "Apellido", c.apellido, "text", "required")}
          ${campo("d-dni", "DNI", c.dni, "text", 'required pattern="\\d{7,8}"')}
          ${campo("d-calle", "Calle", c.calle, "text", "required")}
          ${campo("d-barrio", "Barrio", c.barrio)}
          ${campo("d-lat", "Latitud", c.latitud, "number", 'step="any" required')}
          ${campo("d-lon", "Longitud", c.longitud, "number", 'step="any" required')}
          <div class="campo ancho"><button class="btn" type="submit">Guardar cambios</button></div>
        </form>
      </section>`;
    $("#form-datos").addEventListener("submit", async e => {
        e.preventDefault();
        try {
            await api("PUT", `/api/v1/clientes/${estado.clienteActual}`, {
                nombre: $("#d-nombre").value, apellido: $("#d-apellido").value, dni: $("#d-dni").value,
                calle: $("#d-calle").value, barrio: $("#d-barrio").value,
                latitud: Number($("#d-lat").value), longitud: Number($("#d-lon").value),
            });
            aviso("Datos actualizados");
        } catch { /* aviso ya mostrado */ }
    });
}

/** El técnico ve SOLO su perfil y puede cambiar su disponibilidad. */
async function vistaMisDatosTecnico() {
    let t;
    try { t = await api("GET", `/api/v1/tecnicos/${estado.tecnicoActual}`); } catch { return; }
    const dato = (titulo, valor) => `<div><dt>${titulo}</dt><dd>${valor}</dd></div>`;
    $("#contenido").innerHTML = `
      <section class="panel">
        <h2>Mis datos</h2>
        <dl class="datos">
          ${dato("Nombre", `${esc(t.nombre)} ${esc(t.apellido)}`)}
          ${dato("Legajo", esc(t.legajo))}
          ${dato("DNI", esc(t.dni))}
          ${dato("Base", `${t.latitud}, ${t.longitud}`)}
          ${dato("Certificaciones", esc(t.certificaciones.map(c => c.nombre).join(", ")))}
          ${dato("Zonas", esc(t.zonas.map(z => z.nombre).join(", ") || "—"))}
        </dl>
        <div class="acciones">
          <p class="nota">Disponible: <strong>${t.disponible ? "Sí" : "No"}</strong>. Si no estás disponible, no recibís ofertas.</p>
          <button type="button" class="btn ${t.disponible ? "secundario" : "ok"}" id="btn-disponible">
            ${t.disponible ? "Marcarme como no disponible" : "Marcarme como disponible"}</button>
        </div>
      </section>`;
    $("#btn-disponible").addEventListener("click", async () => {
        try {
            await api("PATCH", `/api/v1/tecnicos/${t.id}/disponibilidad`, { disponible: !t.disponible });
            aviso("Disponibilidad actualizada");
            vistaMisDatosTecnico();
        } catch { /* aviso ya mostrado */ }
    });
}

// ------------------------------------------------------------------ Informe (solo administrador)

const NOMBRES = {
    PENDIENTE: "Pendiente", OFERTADA: "Ofertada", ASIGNADA: "Asignada", EN_CURSO: "En curso",
    REPROGRAMADA: "Reprogramada", DERIVADA: "Derivada", CANCELADA: "Cancelada", FINALIZADA: "Finalizada",
    AUTOMATICA: "Automática", MANUAL: "Manual", NORMAL: "Normal", URGENTE: "Urgente",
    REPARACION: "Reparación", INSTALACION: "Instalación", MANTENIMIENTO: "Mantenimiento",
    ESTANDAR: "Estándar", PRIORIDAD: "Prioridad", FUERA_HORARIO: "Fuera de horario", COMBINADA: "Combinada",
};
const nombre = clave => NOMBRES[clave] ?? clave;

/**
 * Gráfico de barras horizontales de UNA sola serie (un solo color): cada fila es etiqueta, barra y valor.
 * El valor va escrito al lado (no depende del color) y el title muestra el detalle al pasar el mouse.
 */
function barras(titulo, filas, formato = v => v) {
    const max = Math.max(1, ...filas.map(f => f.valor));
    const total = filas.reduce((a, f) => a + f.valor, 0);
    return `<figure class="grafico">
      <figcaption>${esc(titulo)}</figcaption>
      ${filas.map(f => {
        const porcentaje = total ? Math.round(f.valor * 100 / total) : 0;
        const detalle = `${f.etiqueta}: ${formato(f.valor)}${f.extra ? " · " + f.extra : ""} (${porcentaje} %)`;
        return `<div class="barra-fila" title="${esc(detalle)}">
          <span class="barra-etiqueta">${esc(f.etiqueta)}</span>
          <span class="barra-pista"><span class="grafico-barra" style="width:${f.valor ? Math.max(2, f.valor * 100 / max) : 0}%"></span></span>
          <span class="barra-valor">${esc(formato(f.valor))}${f.extra ? `<small> · ${esc(f.extra)}</small>` : ""}</span>
        </div>`;
      }).join("")}
    </figure>`;
}

async function vistaInforme(desde = "", hasta = "") {
    $("#contenido").innerHTML = `
      <section class="panel">
        <div class="cabecera">
          <h2>Informe de gestión</h2>
          <form id="form-informe" class="filtros no-imprimir">
            <label>Desde <input id="i-desde" type="date" value="${esc(desde)}"></label>
            <label>Hasta <input id="i-hasta" type="date" value="${esc(hasta)}"></label>
            <button class="btn" type="submit">Generar</button>
            <button class="btn secundario" type="button" id="i-imprimir">Imprimir / PDF</button>
          </form>
        </div>
        <div id="informe"><p class="ayuda">Generando…</p></div>
      </section>`;
    $("#form-informe").addEventListener("submit", e => { e.preventDefault(); vistaInforme($("#i-desde").value, $("#i-hasta").value); });
    $("#i-imprimir").addEventListener("click", () => window.print());

    const parametros = new URLSearchParams();
    if (desde) parametros.set("desde", desde);
    if (hasta) parametros.set("hasta", hasta);
    let r;
    try { r = await api("GET", `/api/v1/solicitudes/informe?${parametros}`); } catch { $("#informe").innerHTML = ""; return; }

    const periodo = r.desde || r.hasta ? `del ${r.desde ?? "inicio"} al ${r.hasta ?? "hoy"}` : "de todo el período";
    const aFilas = mapa => Object.entries(mapa).map(([clave, valor]) => ({ etiqueta: nombre(clave), valor }));
    const o = r.ofertas, f = r.facturacion;
    const tile = (titulo, valor, pie = "") =>
        `<div class="kpi"><span>${esc(titulo)}</span><strong>${valor}</strong>${pie ? `<small>${esc(pie)}</small>` : ""}</div>`;

    $("#informe").innerHTML = `
      <p class="ayuda">Solicitudes creadas ${esc(periodo)} · generado ${fecha(r.generado)}. Pasá el mouse por una barra para ver el detalle.</p>
      <div class="kpis">
        ${tile("Solicitudes", r.totalSolicitudes)}
        ${tile("Finalizadas", f.finalizadas, r.totalSolicitudes ? `${Math.round(f.finalizadas * 100 / r.totalSolicitudes)} % del total` : "")}
        ${tile("Facturado", pesos(f.total), `visitas ${pesos(f.visitas)} + presupuestos ${pesos(f.presupuestos)}`)}
        ${tile("Ticket promedio", pesos(f.ticketPromedio), "por trabajo finalizado")}
        ${tile("Ofertas aceptadas", o.tasaAceptacion == null ? "—" : `${o.tasaAceptacion} %`, `${o.aceptadas} de ${o.enviadas} enviadas`)}
        ${tile("Distancia promedio", r.distanciaPromedioKm == null ? "—" : `${r.distanciaPromedioKm} km`, "de las visitas asignadas")}
      </div>
      <div class="graficos">
        ${barras("Solicitudes por estado", aFilas(r.porEstado))}
        ${barras("Qué pasó con las ofertas (RN-3)", [
            { etiqueta: "Enviadas", valor: o.enviadas },
            { etiqueta: "Aceptadas", valor: o.aceptadas },
            { etiqueta: "Rechazadas", valor: o.rechazadas },
            { etiqueta: "Vencidas (15 min)", valor: o.vencidas },
            { etiqueta: "Canceladas", valor: o.canceladas }])}
        ${barras("Estrategias de costo usadas (RN-11)", r.porEstrategia.length
            ? r.porEstrategia.map(e => ({ etiqueta: nombre(e.estrategia), valor: e.cantidad, extra: pesos(e.totalVisitas) }))
            : [{ etiqueta: "Sin visitas con costo", valor: 0 }])}
        ${barras("Tipos de servicio pedidos", aFilas(r.porTipoServicio))}
        ${barras("Modo de asignación", aFilas(r.porModo))}
        ${barras("Prioridad", aFilas(r.porPrioridad))}
      </div>
      <h3>Desempeño por técnico</h3>
      ${r.porTecnico.length ? `<div class="tabla-scroll"><table class="tabla-informe">
        <thead><tr><th>Técnico</th><th class="num">En espera</th><th class="num">En curso</th><th class="num">Finalizadas</th>
          <th class="num" title="Ofertas que rechazó, dejó vencer o derivó">Descartadas</th><th class="num">Facturado</th></tr></thead>
        <tbody>${r.porTecnico.map(t => `
          <tr class="sin-clic"><td>${esc(t.tecnicoNombre)}</td><td class="num">${t.enEspera}</td><td class="num">${t.enCurso}</td>
          <td class="num">${t.finalizadas}</td><td class="num">${t.descartadas}</td><td class="num">${pesos(t.facturado)}</td></tr>`).join("")}
        </tbody></table></div>` : `<p class="vacio">Ningún técnico tuvo solicitudes en el período.</p>`}`;
}

// ------------------------------------------------------------------ Técnicos y clientes (solo administrador)

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
              <td>${esAdmin()
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
