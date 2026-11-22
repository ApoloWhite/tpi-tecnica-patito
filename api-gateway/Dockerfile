FROM eclipse-temurin:21.0.8_9-jre
# api-gateway - Punto de entrada único
# Antes de construir la imagen: mvn clean package -DskipTests (desde la raíz del proyecto)

WORKDIR /app

# Usuario sin privilegios para no correr como root dentro del contenedor
RUN groupadd --system patito && useradd --system --gid patito patito

COPY target/api-gateway-1.0.0.jar app.jar

USER patito

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
