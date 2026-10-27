FROM nginx:stable-alpine@sha256:47928bf67eb62a1863cb08d9ecb52d3e704304ba614eaee17884204677702951
COPY frontend/dist/ /usr/share/nginx/html/
COPY deploy/nginx/default.conf.template /etc/nginx/templates/default.conf.template
