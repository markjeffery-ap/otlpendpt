# oltpendpt - Open Telemetry Tomcat 10 prototype listener

Prototype listener for tomcat 10 listening for open telemetry metrics. Looking at how DBmarlin can use this to capture host metrics, but then also how easy it would be to use this framework to capture metrics for storage and infrastructure related to supported metrics.

Immediate interest is with Informix, but large platforms like PostgreSQL, MS SQL-Server and Oracle are important too.

## Building

mvn clean package
sudo cp target/dbmarlin-otlp-0.1.0.war /opt/tomcat/webapps/dbmarlin-otlp.war

## Testing

curl http://localhost:8080/dbmarlin-otlp/v1/metrics

## Trying with an open telemetry collector

Installed opentelemetry contrib collector. Installation steps for ubuntu:

cd /tmp
wget https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v0.152.0/otelcol-contrib_0.152.0_linux_amd64.deb
sudo dpkg -i otelcol-contrib_0.152.0_linux_amd64.deb

sudo cp  /etc/oitelcol-contrib/config.yaml

receivers:
  hostmetrics:
    collection_interval: 30s
    scrapers:
      cpu:
      memory:
      disk:
      filesystem:
      network:
      load:

processors:
  batch:

exporters:
  otlphttp/dbmarlin:
    endpoint: http://localhost:8080/dbmarlin-otlp
    compression: gzip

service:
  pipelines:
    metrics:
      receivers: [hostmetrics]
      processors: [batch]
      exporters: [otlphttp/dbmarlin]

