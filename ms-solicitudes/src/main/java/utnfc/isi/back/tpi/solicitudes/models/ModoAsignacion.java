package utnfc.isi.back.tpi.solicitudes.models;

/**
 * Cómo se elige al técnico de una solicitud.
 * <ul>
 *   <li>AUTOMATICA: el sistema oferta solo al técnico más cercano y, si rechaza o vence la oferta,
 *       pasa al siguiente candidato (F5, RN-3).</li>
 *   <li>MANUAL: el cliente o el administrador eligen a quién ofertar con /elegir y /ofertar.</li>
 * </ul>
 */
public enum ModoAsignacion {
    AUTOMATICA, MANUAL
}
