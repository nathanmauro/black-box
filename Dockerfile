# Dev/local use: build the jar first with `mvn -DskipTests package`, then
# `docker build -t black-box . && docker run --rm -p 127.0.0.1:8766:8766 -v black-box-data:/data black-box`.
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY target/sba-agentic-*.jar app.jar

VOLUME /data

ENV SBA_DATASOURCE_URL=jdbc:sqlite:/data/black-box.db
# Inside the container bind all interfaces; publish with `-p 127.0.0.1:8766:8766` to keep it loopback-only on the host.
ENV SBA_BIND_ADDRESS=0.0.0.0

EXPOSE 8766

ENTRYPOINT ["java","-jar","app.jar"]
