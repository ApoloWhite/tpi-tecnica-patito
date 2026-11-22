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
 * cuyo plazo venció y las devuelve a PENDIENTE (RN-3, EN-07). En modo AUTOMATICA,
 * además oferta la solicitud al siguiente técnico candidato.
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
            for (Long id : solicitudService.ofertasVencidas()) {
                try {
                    solicitudService.expirarOferta(id);
                } catch (ObjectOptimisticLockingFailureException e) {
                    // El técnico aceptó justo al mismo tiempo: gana la aceptación
                    log.warn("Scheduler: la solicitud {} cambió mientras vencía su oferta, se respeta el otro cambio", id);
                } catch (RuntimeException e) {
                    log.error("Scheduler: error al vencer la oferta de la solicitud {}", id, e);
                }
            }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
