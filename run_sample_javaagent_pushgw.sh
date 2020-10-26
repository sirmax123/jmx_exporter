#!/usr/bin/env bash

set -x

# Script to run a java application for testing jmx4prometheus.

version=$(sed -n -e 's#.*<version>\(.*-SNAPSHOT\)</version>#\1#p' pom.xml)
interval=3
# Note: You can use localhost:5556 instead of 5556 for configuring socket hostname.
java \
    -Dcom.sun.management.jmxremote.ssl=false \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.port=5555 \
    -DA=B \
    -Dc=D \
    -Dkey=value \
    -javaagent:./jmx_prometheus_javaagent_pushgw/target/jmx_prometheus_javaagent_pushgw-${version}.jar=127.0.0.1:9092:example_configs/javaagent_pushgw_sample_config.yml:${interval}jvmLabels=true:allowedJVMLabelPrefixes=storm,java:extraTrueLabels=storm_topology,storm_testing_cluster \
    -jar jmx_prometheus_httpserver/target/jmx_prometheus_httpserver-${version}-jar-with-dependencies.jar 5556 example_configs/httpserver_sample_config.yml


