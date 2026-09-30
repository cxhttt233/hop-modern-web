FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends nginx curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/modern

COPY server/target/classes /opt/modern/server/classes
COPY server/target/preview-lib /opt/modern/server/lib
COPY app/dist /usr/share/nginx/html
COPY e2e/fixtures/real-graph-proof.hpl /opt/modern/fixtures/real-graph-proof.hpl
COPY deploy/preview/nginx.conf /etc/nginx/nginx.conf
COPY deploy/preview/entrypoint.sh /usr/local/bin/hop-modern-preview

RUN chmod +x /usr/local/bin/hop-modern-preview \
    && mkdir -p /workspace /var/log/nginx /run/nginx

VOLUME ["/workspace"]
EXPOSE 80

ENTRYPOINT ["/usr/local/bin/hop-modern-preview"]
