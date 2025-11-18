# Spark vs Flink – Kafka Streaming Linear Regression 

*A comparison on performance of Apache Spark and Apache Flink performing machine learning in an Apache Kafka event stream*
- Apache Kafka + ZooKeeper
- Apache Flink (JobManager + TaskManager)
- Apache Spark Structured Streaming job
- Flink DataStream job
- Topic bootstrap script
- Minimal configs

The jobs read records from `topic1`, perform linear regression prediction, then write results to `topic2`. A simple
data generator (`dsta.py`) is included to push sample records to Kafka and measure basic timestamps (t0, t3).


## Quick start

1) Build the all-in-one docker image of aplication (Kafka, ZooKeeper, Flink, Maven, JDK):
```bash
docker build -t kafka-flink-streaming-fd .
```

2) Start the stack :
```bash
docker compose up -d zookeeper kafka jobmanager taskmanager

```

3) Create topics inside the Kafka container:
```bash
docker compose exec kafka bash -lc '/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --topic topic1 --partitions 10 --replication-factor 1'
docker compose exec kafka bash -lc '/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --topic topic2 --partitions 10 --replication-factor 1'
docker compose exec kafka bash -lc '/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list'

#(alternatively) Run saved script:
docker compose run --rm create_topics

#(optional) Remove topics:
docker compose exec kafka bash -lc '
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
    --delete --topic topic1 --topic topic2
'

```

4) Start the **Flink** job (important!: parameter '-p' stands for maximum allowed parallel tasks):
```bash
docker compose exec jobmanager bash -lc '
  cd /opt/flink/jobs/linearregression &&
  mvn -q -DskipTests package &&
  /opt/flink/bin/flink run -d -m jobmanager:8081 -p 2 \
    -c app.LinearRegressionStreamProcessor \
    target/linearregression-1.0.1.jar
'
```

5) (Optional) Send a quick smoke-test record to `topic1` from inside the Kafka container (producer on Kafka 2.4 uses `--broker-list`):
```bash
docker compose exec -T kafka bash -lc \
  'printf "1.0,2.0,3.0;2.0\n" | /opt/kafka/bin/kafka-console-producer.sh --broker-list kafka:9092 --topic topic1'
```

6) (Optional)  Inspect messages arriving on `topic2` (keep this window open):
```bash
docker compose exec kafka bash -lc \
  '/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic topic2 --from-beginning'
```

7) Generate a larger batch from the host (use the host-mapped port; default is 9094):
```bash
python3 dsta.py --count 1000000
```

## Run Spark job ( benchmark)

You can run the alternative Spark Structured Streaming job to benchmark against Flink.  
This job reads from `topic1`, performs the same simple prediction logic, and writes results to `topic2`.

### 1) Submit Spark job from the running container
```bash
docker compose exec -d jobmanager bash -lc '
  cd /opt/sparkjob && mvn -q -DskipTests clean package && \
  /opt/spark/bin/spark-submit \
    --class app.LinearRegression \
    --master local[*] \
    --packages org.apache.spark:spark-sql-kafka-0-10_2.12:3.4.2 \
    --conf spark.ui.enabled=true \
    --conf spark.ui.port=4040 \
    --conf spark.driver.bindAddress=0.0.0.0 \
    --conf spark.driver.host=jobmanager \
    target/spark-linearregression-1.0.1.jar
'

#(optional) check if job is running

```

Notes:
- The `--packages` flag ensures Spark includes the Kafka source/sink connector.
- The job writes to `topic2`, so avoid running Flink and Spark simultaneously to prevent consumer offset interference.
- Checkpoint directory is automatically created under `/opt/sparkjob/checkpoint_dir`.

### 2) Smoke test
With the Spark job running, send data to `topic1` and monitor `topic2`:

```bash
python3 dsta.py --count 100

docker compose exec kafka bash -lc \
  '/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic topic2 --from-beginning'
```

### 3) (Optional) Enable Spark UI
To view Spark's Web UI, expose port 4040 in `docker-compose.yml`:

```yaml
  jobmanager:
    ports:
      - "8081:8081"   # Flink UI
      - "4040:4040"   # Spark UI (optional)
```

Then recreate the service:
```bash
docker compose up -d jobmanager
```

Open the Spark UI at:
```
http://localhost:4040
```

### Data format

Input on `topic1` is a single line per record:
```
f0,f1,...,fn;label
```
- `f*` are floats (features)
- `label` is a float (target)

The streaming jobs parse, run a simple linear model (demo), and emit a result with timestamps to `topic2`.


## Notes / Differences

- Uses official Maven layout and minimal POMs so the jobs build in-container.
- Dockerfile closely mirrors the appendix (Ubuntu base, JDK, Maven, Flink 1.17-ish, Kafka 2.4-ish), but resource URLs are parameterized.
- For simplicity, the Spark binary is installed under `/opt/spark` using Apache mirrors.
- The linear regression training data is randomly generated at startup to match the paper's approach of pre-generated vectors.
- Topic names, partitioning, and consumer group names match the paper (topic1 -> topic2, group `flink-ml`).

## Troubleshooting / Known pitfalls

- **Flink JM/TM require explicit memory on 1.17+** → in `flink/conf/flink-conf.yaml` set at minimum:
  ```
  jobmanager.memory.process.size: 1024m
  taskmanager.memory.process.size: 2048m
  taskmanager.numberOfTaskSlots: 4
  parallelism.default: 8
  jobmanager.rpc.address: jobmanager
  rest.port: 8081
  ```
  Rebuild image after editing configs: `docker build -t kafka-flink-streaming-fd .`.

- **Kafka topics not created after rebuild** → (no volumes). Re-run the create commands from Quick start step 3.

- **Submit endpoint** → use `-m jobmanager:8081` (no `http://`).

- **Console producer flag** (Kafka 2.4): use `--broker-list` for producer, `--bootstrap-server` for consumer.

- **Port conflicts** on macOS:
  - Remove port mapping for ZooKeeper (2181) from `docker-compose.yml` (internal use only).
  - Do not publish Kafka's internal `9092` to host; publish only a host port for `PLAINTEXT_HOST` (e.g. `9094:9094`).
  - Ensure `advertised.listeners` in `server.properties` matches the host port, e.g. `PLAINTEXT_HOST://localhost:9094`.


## Testing and monitoring metrics

To validate and monitor the streaming pipeline performance, consider the following approaches:

- Use the included `dsta.py` script to generate test data and measure end-to-end latency by comparing timestamps at ingestion and output.

- Monitor Kafka topic lag and throughput using Kafka tools or external monitoring systems to ensure consumer groups keep up with producers.

- Enable Flink and Spark metrics reporting (e.g., via JMX or Prometheus) to track job health, processing latency, and throughput.

- Inspect Flink Web UI and Spark UI for job status, parallelism, and resource utilization.

- Use Kafka's console consumer with `--from-beginning` to verify data flow correctness and completeness.

These steps help ensure the replication behaves consistently with the original system and provide insights for tuning and troubleshooting.
