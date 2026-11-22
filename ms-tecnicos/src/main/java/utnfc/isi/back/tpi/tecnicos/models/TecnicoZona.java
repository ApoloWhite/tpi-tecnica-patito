package utnfc.isi.back.tpi.tecnicos.models;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Entidad intermedia TECNICO_ZONA. Se usa en lugar de @ManyToMany directo (Ap.16)
 * y se modela solo el lado N a 1, que es el que se necesita navegar.
 */
@Entity
@Table(name = "tecnico_zona",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tecnico_id", "zona_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class TecnicoZona {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tecnico_id")
    @ToString.Exclude
    private Tecnico tecnico;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zona_id")
    @ToString.Exclude
    private ZonaCobertura zona;
}
