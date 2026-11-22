FROM nginx:1.27.3-alpine
# Frontend web de Técnica Patito: archivos estáticos + proxy de /api hacia el API Gateway
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY public/ /usr/share/nginx/html/
EXPOSE 80
