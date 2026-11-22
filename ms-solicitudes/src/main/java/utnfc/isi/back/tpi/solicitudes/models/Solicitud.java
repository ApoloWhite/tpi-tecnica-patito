package utnfc.isi.back.tpi.solicitudes.models;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Solicitud de servicio técnico (DER: SOLICITUD).
 * Guarda una copia (snapshot) de los datos del cliente y del técnico: si después cambian en
 * sus padrones, la solicitud conserva los datos originales.
 */
@Entity
@Table(name = "solicitud")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Solicitud {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Control de concurrencia optimista: evita que dos operaciones pisen la misma solicitud (RN-4). */
    @Version
    private Long version;

    // --- Snapshot del cliente ---
    @Column(nullable = false)
    private Long clienteId;
    @Column(nullable = false, length = 170)
    private String clienteNombre;
    @Column(nullable = false, length = 210)
    private String domicilio;
    @Column(nullable = false)
    private Double latitud;
    @Column(nullable = false)
    private Double longitud;

    // --- Datos del pedido ---
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "solicitud_tipo", joinColumns = @JoinColumn(name = "solicitud_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_servicio", nullable = false, length = 20)
    @ToString.Exclude
    @Builder.Default
    private Set<TipoServicio> tiposServicio = new HashSet<>();

    @Column(nullable = false, length = 60)
    private String marca;
    @Column(nullable = false, length = 60)
    private String modelo;
    @Column(nullable = false, length = 500)
    private String descripcion;
    @Column(nullable = false)
    private Long especialidadId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoSolicitud estado;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Prioridad prioridad;
    @Column(nullable = false)
    private LocalDateTime fechaHoraProgramada;
    @Column(nullable = false)
    private Boolean esFeriado;

    // --- Snapshot del técnico y oferta ---
    private Long tecnicoId;
    @Column(length = 170)
    private String tecnicoNombre;
    private LocalDateTime ofertaExpira;

    // --- Costo de visita (RN-8, RN-11) ---
    private Double distanciaKm;
    private Integer tiempoEstimadoMin;
    @Column(length = 10)
    private String fuenteDistancia;
    @Column(length = 20)
    private String estrategiaCosto;
    @Column(precision = 12, scale = 2)
    private BigDecimal costoBase;
    @Column(precision = 12, scale = 2)
    private BigDecimal recargo;
    @Column(precision = 12, scale = 2)
    private BigDecimal total;

    // --- Diagnóstico y presupuesto (F7) ---
    @Column(length = 500)
    private String diagnostico;
    @Column(precision = 12, scale = 2)
    private BigDecimal presupuesto;

    @Column(nullable = false)
    private LocalDateTime fechaCreacion;
}
