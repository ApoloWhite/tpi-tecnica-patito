// Configuración del frontend. Keycloak se accede directo desde el navegador (puerto 47390).
window.PATITO_CONFIG = {
    keycloakUrl: "http://localhost:47390",
    realm: "patito",
    clientId: "bda-client",
    // Vacío = mismo origen: nginx reenvía /api al gateway. Fuera de Docker se puede poner "http://localhost:47380".
    apiUrl: ""
};
