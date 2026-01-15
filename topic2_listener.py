from kafka import KafkaConsumer
import time
import statistics
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("--count", type=int, default=100000, help="How many messages to consume")
parser.add_argument("--bootstrap", type=str, default="localhost:9094")
parser.add_argument("--topic", type=str, default="topic2")

args = parser.parse_args()

print(f"""
================ TOPIC2 LISTENER =================
Topic: {args.topic}
Bootstrap: {args.bootstrap}
Messages to read: {args.count}
==================================================
""")

consumer = KafkaConsumer(
    args.topic,
    bootstrap_servers=args.bootstrap,
    auto_offset_reset="latest",
    enable_auto_commit=False,
    value_deserializer=lambda x: x.decode("utf-8")
)

start_time = None
end_time = None
count = 0

latencies = []

for message in consumer:
    if start_time is None:
        start_time = time.time()

    now = time.time()

    value = message.value
    count += 1

    # oczekiwany format: f1,f2,f3;label;prediction;t1
    try:
        parts = value.strip().split(";")
        if len(parts) >= 4:
            t1 = int(parts[-1])
            t1_sec = t1 / 1000
            latency = now - t1_sec
            latencies.append(latency)
    except:
        pass

    if count % 10000 == 0:
        print(f"[{count}] messages consumed")

    if count >= args.count:
        end_time = time.time()
        break

duration = end_time - start_time
throughput = count / duration

print("\n=============== RESULTS ==================")
print(f"Total messages: {count}")
print(f"Duration: {duration:.4f} sec")
print(f"Throughput: {throughput:.2f} msg/sec")

if latencies:
    print(f"Avg latency:    {statistics.mean(latencies):.6f} sec")
    print(f"Median latency: {statistics.median(latencies):.6f} sec")
    print(f"Max latency:    {max(latencies):.6f} sec")
    print(f"Min latency:    {min(latencies):.6f} sec")

print("==========================================")
consumer.close()