package utnfc.isi.back.tpi.solicitudes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // lo necesitamos para la ventana de 15 minutos (@Scheduled)
public class MsSolicitudesApplication {
    public static void main(String[] args) {
        SpringApplication.run(MsSolicitudesApplication.class, args);
    }
}