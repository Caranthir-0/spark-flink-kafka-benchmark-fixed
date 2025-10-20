import argparse, random, sys, subprocess

def fmt_record(feats, label):
    return ",".join(f"{x:.6f}" for x in feats) + f";{label:.6f}"

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--features", type=int, default=12)
    p.add_argument("--count", type=int, default=1000)
    p.add_argument("--topic", type=str, default="topic1")
    args = p.parse_args()

    # Uruchom jeden producer wewnątrz kontenera Kafki
    proc = subprocess.Popen(
        ["docker","compose","exec","-T","kafka","bash","-lc",
         f'/opt/kafka/bin/kafka-console-producer.sh --broker-list kafka:9092 --topic {args.topic}'],
        stdin=subprocess.PIPE, text=True
    )

    try:
        for i in range(args.count):
            feats = [random.uniform(0, 10) for _ in range(args.features)]
            label = sum(feats)/max(1,args.features) + random.uniform(-0.01, 0.01)
            rec = fmt_record(feats, label)
            # Zapis jednej linii do stdin producenta
            proc.stdin.write(rec + "\n")
            if (i+1) % 100 == 0:
                print(f"sent {i+1}/{args.count}")
        proc.stdin.flush()
    finally:
        proc.stdin.close()
        proc.wait()
        print("done.")

if __name__ == "__main__":
    main()