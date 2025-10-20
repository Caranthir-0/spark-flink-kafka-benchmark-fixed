
package app;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.Arrays;
import java.util.Properties;

// Minimal POJO
// !! Placeholder model calculates average
public class LinearRegressionStreamProcessor {

  public static class Karp {
    public double[] features;
    public double label;
    public long t1;

    public Karp() {}
    public Karp(double[] f, double l, long t1) { this.features = f; this.label = l; this.t1 = t1; }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < features.length; i++) {
        if (i>0) sb.append(',');
        sb.append(features[i]);
      }
      sb.append(';').append(label).append(';').append(t1);
      return sb.toString();
    }
  }

  public static class PredictionResults {
    public double[] features;
    public double label;
    public double prediction;
    public long t1;

    public PredictionResults() {}
    public PredictionResults(double[] f, double label, double prediction, long t1) {
      this.features = f; this.label = label; this.prediction = prediction; this.t1 = t1;
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < features.length; i++) {
        if (i>0) sb.append(',');
        sb.append(features[i]);
      }
      sb.append(';').append(label).append(';').append(prediction).append(';').append(t1);
      return sb.toString();
    }
  }

  public static void main(String[] args) throws Exception {
    int parallelism = 8;

    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.setParallelism(parallelism);

    Properties config = new Properties();
    config.setProperty("bootstrap.servers", "kafka:9092");
    config.setProperty("group.id", "flink-ml");

    KafkaSource<String> kafkaSource = KafkaSource.<String>builder()
        .setBootstrapServers("kafka:9092")
        .setTopics("topic1")
        .setGroupId("flink-ml")
        .setStartingOffsets(OffsetsInitializer.latest())
        .setValueOnlyDeserializer(new SimpleStringSchema())
        .build();

    KafkaSink<String> kafkaSink = KafkaSink.<String>builder()
        .setKafkaProducerConfig(config)
        .setRecordSerializer(
          KafkaRecordSerializationSchema.builder()
            .setTopic("topic2")
            .setValueSerializationSchema(new SimpleStringSchema())
            .build()
        )
        .build();

    // Inbound strings -> Karp POJO
    DataStream<String> stringStream = env.fromSource(
      kafkaSource, WatermarkStrategy.noWatermarks(), "topic1"
    );

    DataStream<Karp> karpStream = stringStream.map(new MapFunction<String, Karp>() {
      @Override public Karp map(String in) {
        String[] s = in.split(";");
        String[] f = s[0].split(",");
        double[] features = Arrays.stream(f).mapToDouble(Double::parseDouble).toArray();
        double label = Double.parseDouble(s[1]);
        long t1 = System.nanoTime();
        return new Karp(features, label, t1);
      }
    });

    // Simple fixed-weight prediction to mirror topology
    DataStream<PredictionResults> pred = karpStream.map(new MapFunction<Karp, PredictionResults>() {
      @Override public PredictionResults map(Karp k) {
        double w = 1.0 / Math.max(1, k.features.length);
        double yhat = 0.0;
        for (double v : k.features) yhat += v * w;
        return new PredictionResults(k.features, k.label, yhat, k.t1);
      }
    });

    DataStream<String> out = pred.map(new MapFunction<PredictionResults, String>() {
      @Override public String map(PredictionResults pr) {
        return pr.toString();
      }
    });

    out.sinkTo(kafkaSink);
    env.execute(LinearRegressionStreamProcessor.class.getName());
  }
}
