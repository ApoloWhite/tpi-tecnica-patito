package utnfc.isi.back.tpi.solicitudes.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import utnfc.isi.back.tpi.solicitudes.config.CorrelationIdFilter;

import java.util.UUID;

/**
 * Revisa cada cierto tiempo (app.oferta.scheduler-ms, por defecto 30 s) si hay ofertas
 * cuyo plazo venció y las devuelve a PENDIENTE (RN-3, EN-07).
 */
@Component
public class OfertaScheduler {

    private static final Logger log = LoggerFactory.getLogger(OfertaScheduler.class);

    private final SolicitudService solicitudService;

    public OfertaScheduler(SolicitudService solicitudService) {
        this.solicitudService = solicitudService;
    }

    @Scheduled(fixedDelayString = "${app.oferta.scheduler-ms:30000}", initialDelayString = "${app.oferta.scheduler-ms:30000}")
    public void expirarOfertas() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "scheduler-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            int vencidas = solicitudService.expirarOfertasVencidas();
            if (vencidas > 0) {
                log.info("Scheduler: {} ofertas vencidas volvieron a PENDIENTE", vencidas);
            }
        } catch (ObjectOptimisticLockingFailureException e) {
            // Un técnico aceptó justo al mismo tiempo: se respeta la aceptación y se reintenta en la próxima pasada
            log.warn("Scheduler: conflicto de concurrencia al expirar ofertas, se reintenta en la próxima pasada");
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
