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

import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Properties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.File;

// Minimal POJO
// Loads a linear model (bias + weights) from JSON in model/ folder and applies y = bias + w^T x
public class LinearRegressionStreamProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(LinearRegressionStreamProcessor.class);

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class LinearModel {
    public double bias;
    public double[] weights;
  }

  public static class ModelPredictMap extends RichMapFunction<Karp, PredictionResults> {
    private transient LinearModel model;
    private final String modelPath;

    public ModelPredictMap(String modelPath) {
      this.modelPath = modelPath;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
      ObjectMapper om = new ObjectMapper();
      File f = new File(modelPath);
      if (!f.exists()) {
        throw new IllegalStateException("Model file not found: " + modelPath);
      }
      this.model = om.readValue(f, LinearModel.class);
      if (this.model.weights == null) {
        throw new IllegalStateException("Model weights are null in: " + modelPath);
      }
      LOG.info("Loaded model from {} (bias={}, weights={})", modelPath, this.model.bias, this.model.weights.length);
    }

    @Override
    public PredictionResults map(Karp k) {
      double yhat = model.bias;
      int n = Math.min(k.features.length, model.weights.length);
      for (int i = 0; i < n; i++) {
        yhat += k.features[i] * model.weights[i];
      }
      return new PredictionResults(k.features, k.label, yhat, k.t1);
    }
  }

  public static class ThroughputLoggingMap extends RichMapFunction<PredictionResults, PredictionResults> {
    private long count = 0L;

    @Override
    public PredictionResults map(PredictionResults value) {
      count++;
      long now = System.currentTimeMillis();
      if (count <= 20) {
        System.out.println("[FLINK-THROUGHPUT] first=" + count + " ts=" + now);
      }
      return value;
    }
  }

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
    int parallelism = 1;
    String modelPath = System.getenv().getOrDefault("MODEL_PATH", "/opt/flink/jobs/linearregression/model/model.json");

    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    System.out.println("[FLINK-THROUGHPUT] job started");
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
        long t1 = System.currentTimeMillis();
        return new Karp(features, label, t1);
      }
    });

    // Prediction using JSON-loaded model (analogous to Spark job)
    DataStream<PredictionResults> pred = karpStream.map(new ModelPredictMap(modelPath));

    DataStream<PredictionResults> predWithLogging = pred.map(new ThroughputLoggingMap());

    DataStream<String> out = predWithLogging.map(new MapFunction<PredictionResults, String>() {
      @Override public String map(PredictionResults pr) {
        return pr.toString();
      }
    });

    out.sinkTo(kafkaSink);
    env.execute(LinearRegressionStreamProcessor.class.getName());
  }
}
