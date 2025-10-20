#!/usr/bin/env bash
set -euo pipefail
/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --topic topic1 --partitions 10 --replication-factor 1 || true
/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --topic topic2 --partitions 10 --replication-factor 1 || true
/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
echo "Topics ready."