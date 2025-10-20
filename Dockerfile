
FROM ubuntu:bionic

ENV DEBIAN_FRONTEND=noninteractive
RUN set -ex \
 && apt-get update -yq \
 && apt-get upgrade -yq \
 && apt-get install -yq openjdk-11-jdk build-essential vim git wget curl netcat maven sudo python3 python3-pip gpg ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /tmp/downloads

# Versions
ENV ZK_VERSION=3.5.6 \
    KAFKA_VERSION=2.4.0 \
    SCALA_VERSION=2.12 \
    FLINK_VERSION=1.17.2 \
    SPARK_VERSION=3.4.2 \
    HADOOP_VERSION=3

# Download tarballs
RUN set -ex \
 && wget -q https://archive.apache.org/dist/zookeeper/zookeeper-${ZK_VERSION}/apache-zookeeper-${ZK_VERSION}-bin.tar.gz \
 && wget -q https://archive.apache.org/dist/kafka/${KAFKA_VERSION}/kafka_${SCALA_VERSION}-${KAFKA_VERSION}.tgz \
 && wget -q https://archive.apache.org/dist/flink/flink-${FLINK_VERSION}/flink-${FLINK_VERSION}-bin-scala_${SCALA_VERSION}.tgz \
 && wget -q https://archive.apache.org/dist/spark/spark-${SPARK_VERSION}/spark-${SPARK_VERSION}-bin-hadoop${HADOOP_VERSION}.tgz

# Decompress
RUN set -ex \
 && tar -xf apache-zookeeper-${ZK_VERSION}-bin.tar.gz \
 && tar -xf kafka_${SCALA_VERSION}-${KAFKA_VERSION}.tgz \
 && tar -xf flink-${FLINK_VERSION}-bin-scala_${SCALA_VERSION}.tgz \
 && tar -xf spark-${SPARK_VERSION}-bin-hadoop${HADOOP_VERSION}.tgz \
 && mv apache-zookeeper-${ZK_VERSION}-bin /opt/zookeeper \
 && mv kafka_${SCALA_VERSION}-${KAFKA_VERSION} /opt/kafka \
 && mv flink-${FLINK_VERSION} /opt/flink \
 && mv spark-${SPARK_VERSION}-bin-hadoop${HADOOP_VERSION} /opt/spark

# Configs
WORKDIR /opt
COPY zoo.cfg zookeeper/conf
COPY flink-conf.yaml flink/conf/
COPY server.properties kafka/config/server.properties

# Helper scripts and sources
COPY create.topics.sh /opt/kafka/create.topics.sh
COPY flink/jobs/ /opt/flink/jobs/
COPY sparkjob/ /opt/sparkjob/
COPY dsta.py /opt/dsta.py

EXPOSE 2181 9092 9094 8081
WORKDIR /opt
