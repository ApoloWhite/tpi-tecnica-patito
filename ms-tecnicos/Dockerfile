FROM eclipse-temurin:21.0.8_9-jre
# ms-tecnicos - Padrón de técnicos y búsqueda de candidatos
# Antes de construir la imagen: mvn clean package -DskipTests (desde la raíz del proyecto)

WORKDIR /app

# Usuario sin privilegios para no correr como root dentro del contenedor
RUN groupadd --system patito && useradd --system --gid patito patito
# Carpeta de la base H2 en archivo (se monta como volumen nombrado en el compose)
RUN mkdir -p /app/data && chown -R patito:patito /app/data

COPY target/ms-tecnicos-1.0.0.jar app.jar

USER patito

EXPOSE 47382

ENTRYPOINT ["java", "-jar", "app.jar"]
