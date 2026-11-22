package utnfc.isi.back.tpi.clientes.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utnfc.isi.back.tpi.clientes.dtos.ClienteRequest;
import utnfc.isi.back.tpi.clientes.dtos.ClienteResponse;
import utnfc.isi.back.tpi.clientes.exceptions.RecursoNoEncontradoException;
import utnfc.isi.back.tpi.clientes.exceptions.ReglaNegocioException;
import utnfc.isi.back.tpi.clientes.models.Cliente;
import utnfc.isi.back.tpi.clientes.repositories.ClienteRepository;

/** Lógica de negocio del padrón de clientes (F2). */
@Service
public class ClienteService {

    private static final Logger log = LoggerFactory.getLogger(ClienteService.class);

    private final ClienteRepository clienteRepository;

    public ClienteService(ClienteRepository clienteRepository) {
        this.clienteRepository = clienteRepository;
    }

    @Transactional
    public ClienteResponse crear(ClienteRequest request) {
        if (clienteRepository.existsByDni(request.dni())) {
            throw new ReglaNegocioException("Ya existe un cliente con DNI " + request.dni());
        }
        Cliente cliente = new Cliente();
        copiarDatos(request, cliente);
        Cliente guardado = clienteRepository.save(cliente);
        log.info("Cliente creado id={}", guardado.getId());
        return ClienteResponse.desde(guardado);
    }

    @Transactional(readOnly = true)
    public Page<ClienteResponse> listar(String apellido, Pageable pageable) {
        Page<Cliente> pagina = (apellido == null || apellido.isBlank())
                ? clienteRepository.findAll(pageable)
                : clienteRepository.findByApellidoContainingIgnoreCase(apellido, pageable);
        return pagina.map(ClienteResponse::desde);
    }

    @Transactional(readOnly = true)
    public ClienteResponse obtener(Long id) {
        return ClienteResponse.desde(buscar(id));
    }

    @Transactional
    public ClienteResponse actualizar(Long id, ClienteRequest request) {
        Cliente cliente = buscar(id);
        if (clienteRepository.existsByDniAndIdNot(request.dni(), id)) {
            throw new ReglaNegocioException("Ya existe otro cliente con DNI " + request.dni());
        }
        copiarDatos(request, cliente);
        log.info("Cliente actualizado id={}", id);
        return ClienteResponse.desde(cliente);
    }

    @Transactional
    public void eliminar(Long id) {
        Cliente cliente = buscar(id);
        clienteRepository.delete(cliente);
        log.info("Cliente eliminado id={}", id);
    }

    private Cliente buscar(Long id) {
        return clienteRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el cliente con id " + id));
    }

    private void copiarDatos(ClienteRequest request, Cliente cliente) {
        cliente.setNombre(request.nombre());
        cliente.setApellido(request.apellido());
        cliente.setDni(request.dni());
        cliente.setCalle(request.calle());
        cliente.setBarrio(request.barrio());
        cliente.setLatitud(request.latitud());
        cliente.setLongitud(request.longitud());
    }
}
