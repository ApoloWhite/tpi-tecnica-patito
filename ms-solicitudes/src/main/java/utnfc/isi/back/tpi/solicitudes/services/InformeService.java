package utnfc.isi.back.tpi.solicitudes.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utnfc.isi.back.tpi.solicitudes.dtos.InformeResponse;
import utnfc.isi.back.tpi.solicitudes.exceptions.ReglaNegocioException;
import utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud;
import utnfc.isi.back.tpi.solicitudes.models.ModoAsignacion;
import utnfc.isi.back.tpi.solicitudes.models.Prioridad;
import utnfc.isi.back.tpi.solicitudes.models.TipoServicio;
import utnfc.isi.back.tpi.solicitudes.repositories.HistorialEstadoRepository;
import utnfc.isi.back.tpi.solicitudes.repositories.SolicitudRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.ASIGNADA;
import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.CANCELADA;
import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.EN_CURSO;
import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.EN_ESPERA;
import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.FINALIZADA;
import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.OFERTADA;
import static utnfc.isi.back.tpi.solicitudes.models.EstadoSolicitud.PENDIENTE;

/**
 * Arma el informe de gestión del administrador. Todo se calcula con consultas agregadas
 * (GROUP BY, SUM, AVG) en la base: no se traen las solicitudes una por una.
 */
@Service
public class InformeService {

    private static final Logger log = LoggerFactory.getLogger(InformeService.class);

    private final SolicitudRepository solicitudRepository;
    private final HistorialEstadoRepository historialRepository;

    public InformeService(SolicitudRepository solicitudRepository, HistorialEstadoRepository historialRepository) {
        this.solicitudRepository = solicitudRepository;
        this.historialRepository = historialRepository;
    }

    /**
     * @param desde primer día incluido (null = sin límite)
     * @param hasta último día incluido (null = sin límite)
     */
    @Transactional(readOnly = true)
    public InformeResponse generar(LocalDate desde, LocalDate hasta) {
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw new ReglaNegocioException("La fecha 'desde' (" + desde + ") no puede ser posterior a 'hasta' (" + hasta + ")");
        }
        LocalDateTime inicio = desde == null ? LocalDateTime.of(2000, 1, 1, 0, 0) : desde.atStartOfDay();
        LocalDateTime fin = hasta == null ? LocalDateTime.of(2999, 12, 31, 23, 59) : hasta.atTime(LocalTime.MAX);

        Map<String, Long> porEstado = conteos(solicitudRepository.contarPorEstado(inicio, fin), EstadoSolicitud.values());
        long total = porEstado.values().stream().mapToLong(Long::longValue).sum();

        InformeResponse informe = new InformeResponse(
                LocalDateTime.now(), desde, hasta, total, porEstado,
                conteos(solicitudRepository.contarPorModo(inicio, fin), ModoAsignacion.values()),
                conteos(solicitudRepository.contarPorPrioridad(inicio, fin), Prioridad.values()),
                conteos(solicitudRepository.contarPorTipoServicio(inicio, fin), TipoServicio.values()),
                estrategias(inicio, fin),
                facturacion(inicio, fin),
                redondear(solicitudRepository.distanciaPromedio(inicio, fin)),
                ofertas(inicio, fin),
                tecnicos(inicio, fin));
        log.info("Informe generado: {} solicitudes entre {} y {}", total, desde, hasta);
        return informe;
    }

    /** Convierte filas [clave, cantidad] en un mapa ordenado que incluye todos los valores posibles (con 0). */
    private static Map<String, Long> conteos(List<Object[]> filas, Enum<?>[] todos) {
        Map<String, Long> resultado = new LinkedHashMap<>();
        for (Enum<?> valor : todos) {
            resultado.put(valor.name(), 0L);
        }
        for (Object[] fila : filas) {
            // modoAsignacion puede ser null en solicitudes viejas: se cuentan como MANUAL
            String clave = fila[0] == null ? ModoAsignacion.MANUAL.name() : ((Enum<?>) fila[0]).name();
            resultado.merge(clave, (Long) fila[1], Long::sum);
        }
        return resultado;
    }

    private List<InformeResponse.Estrategia> estrategias(LocalDateTime inicio, LocalDateTime fin) {
        return solicitudRepository.resumirPorEstrategia(inicio, fin).stream()
                .map(f -> new InformeResponse.Estrategia((String) f[0], (Long) f[1], dinero(f[2])))
                .sorted(Comparator.comparing(InformeResponse.Estrategia::cantidad).reversed())
                .toList();
    }

    private InformeResponse.Facturacion facturacion(LocalDateTime inicio, LocalDateTime fin) {
        Object[] fila = solicitudRepository.resumirFinalizadas(inicio, fin).getFirst();
        long finalizadas = (Long) fila[0];
        BigDecimal visitas = dinero(fila[1]);
        BigDecimal presupuestos = dinero(fila[2]);
        BigDecimal totalFacturado = visitas.add(presupuestos);
        BigDecimal ticket = finalizadas == 0 ? BigDecimal.ZERO.setScale(2)
                : totalFacturado.divide(BigDecimal.valueOf(finalizadas), 2, RoundingMode.HALF_UP);
        return new InformeResponse.Facturacion(finalizadas, visitas, presupuestos, totalFacturado, ticket);
    }

    /**
     * Embudo de ofertas a partir del historial:
     * enviada = algo -> OFERTADA; aceptada = OFERTADA -> ASIGNADA; vencida = OFERTADA -> PENDIENTE por "sistema";
     * rechazada = OFERTADA -> PENDIENTE por un técnico; cancelada = OFERTADA -> CANCELADA.
     */
    private InformeResponse.Ofertas ofertas(LocalDateTime inicio, LocalDateTime fin) {
        long enviadas = 0, aceptadas = 0, rechazadas = 0, vencidas = 0, canceladas = 0;
        for (Object[] f : historialRepository.contarTransiciones(inicio, fin)) {
            EstadoSolicitud anterior = (EstadoSolicitud) f[0];
            EstadoSolicitud nuevo = (EstadoSolicitud) f[1];
            boolean sistema = SolicitudService.USUARIO_SISTEMA.equals(f[2]);
            long cantidad = (Long) f[3];
            if (nuevo == OFERTADA) {
                enviadas += cantidad;
            } else if (anterior == OFERTADA && nuevo == ASIGNADA) {
                aceptadas += cantidad;
            } else if (anterior == OFERTADA && nuevo == PENDIENTE) {
                if (sistema) {
                    vencidas += cantidad;
                } else {
                    rechazadas += cantidad;
                }
            } else if (anterior == OFERTADA && nuevo == CANCELADA) {
                canceladas += cantidad;
            }
        }
        Double tasa = enviadas == 0 ? null : redondear(aceptadas * 100.0 / enviadas);
        return new InformeResponse.Ofertas(enviadas, aceptadas, rechazadas, vencidas, canceladas, tasa);
    }

    /** Une dos consultas (por técnico y estado, y descartes) en una fila por técnico, ordenada por facturación. */
    private List<InformeResponse.Tecnico> tecnicos(LocalDateTime inicio, LocalDateTime fin) {
        Map<Long, AcumuladoTecnico> acumulados = new TreeMap<>();
        for (Object[] f : solicitudRepository.resumirPorTecnico(inicio, fin)) {
            AcumuladoTecnico a = acumulados.computeIfAbsent((Long) f[0], id -> new AcumuladoTecnico());
            a.nombre = (String) f[1];
            EstadoSolicitud estado = (EstadoSolicitud) f[2];
            long cantidad = (Long) f[3];
            if (EN_ESPERA.contains(estado)) {
                a.enEspera += cantidad;
            } else if (estado == EN_CURSO) {
                a.enCurso += cantidad;
            } else if (estado == FINALIZADA) {
                a.finalizadas += cantidad;
                a.facturado = a.facturado.add(dinero(f[4])).add(dinero(f[5]));
            }
        }
        for (Object[] f : solicitudRepository.contarDescartesPorTecnico(inicio, fin)) {
            acumulados.computeIfAbsent((Long) f[0], id -> new AcumuladoTecnico()).descartadas += (Long) f[1];
        }
        return acumulados.entrySet().stream()
                .map(e -> new InformeResponse.Tecnico(e.getKey(),
                        e.getValue().nombre == null ? "Técnico #" + e.getKey() : e.getValue().nombre,
                        e.getValue().enEspera, e.getValue().enCurso, e.getValue().finalizadas,
                        e.getValue().descartadas, e.getValue().facturado))
                .sorted(Comparator.comparing(InformeResponse.Tecnico::facturado).reversed())
                .toList();
    }

    /** Acumulador mutable, solo para armar la fila de cada técnico dentro de este método. */
    private static final class AcumuladoTecnico {
        private String nombre;
        private long enEspera;
        private long enCurso;
        private long finalizadas;
        private long descartadas;
        private BigDecimal facturado = BigDecimal.ZERO.setScale(2);
    }

    private static BigDecimal dinero(Object valor) {
        if (valor == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal numero = valor instanceof BigDecimal bd ? bd : new BigDecimal(valor.toString());
        return numero.setScale(2, RoundingMode.HALF_UP);
    }

    private static Double redondear(Double valor) {
        return valor == null ? null : Math.round(valor * 10.0) / 10.0;
    }
}
