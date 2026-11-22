package utnfc.isi.back.tpi.solicitudes.services.costos;

import java.math.BigDecimal;

/** Resultado del cálculo: se guarda completo en la solicitud para auditoría. */
public record DetalleCosto(String estrategia, BigDecimal costoBase, BigDecimal recargo, BigDecimal total) {
}
