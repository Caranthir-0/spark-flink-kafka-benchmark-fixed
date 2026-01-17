package app;

import org.apache.spark.sql.*;
import org.apache.spark.api.java.function.MapFunction;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;

import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.google.gson.Gson;

public class LinearRegression {
  public static class Karp {
    public double[] features;
    public double label;
    public long t1;

    public String toString() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < features.length; i++) {
        if (i > 0) sb.append(',');
        sb.append(features[i]);
      }
      sb.append(';').append(label).append(';').append(t1);
      return sb.toString();
    }
  }

  public static class OutgoingKarp {
    public double[] features;
    public double label;
    public double prediction;
    public long t1;

    public String toString() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < features.length; i++) {
        if (i > 0) sb.append(',');
        sb.append(features[i]);
      }
      sb.append(';').append(label).append(';').append(prediction).append(';').append(t1);
      return sb.toString();
    }
  }

  public static class LinearModel implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    public double bias;
    public double[] weights;
    public int features;
    public long seed;
    public double noise;
    public String note;
  }

  static LinearModel loadModel(String path) throws Exception {
    String json = new String(Files.readAllBytes(Paths.get(path)));
    return new Gson().fromJson(json, LinearModel.class);
  }

  static double predict(double[] x, LinearModel m) {
    int n = Math.min(x.length, m.weights.length);
    double y = m.bias;
    for (int i = 0; i < n; i++) {
      y += x[i] * m.weights[i];
    }
    return y;
  }

  public static void main(String[] args) throws Exception {
    int parallelism = 8;

    String modelPath = System.getenv().getOrDefault("MODEL_PATH", "/opt/model/linear_model.json");
    LinearModel model = loadModel(modelPath);
    if (model.weights == null || model.weights.length == 0) {
      throw new IllegalStateException("Model weights are empty. Check MODEL_PATH=" + modelPath);
    }

    SparkSession spark = SparkSession.builder()
            .appName("Simple-Application")
            .master("local[*]")
            .config("spark.executor.instances", String.valueOf(parallelism))
            .config("spark.executor.cores", String.valueOf(parallelism))
            .getOrCreate();

    spark.sparkContext().setLogLevel("WARN");

    Dataset<Row> kafkaInStream = spark
            .readStream()
            .format("kafka")
            .option("kafka.bootstrap.servers", "kafka:9092")
            .option("subscribe", "topic1")
            .option("startingOffsets", "latest")
            .load()
            .selectExpr("CAST(value AS STRING) as value");

    Encoder<Karp> encKarp = Encoders.javaSerialization(Karp.class);

    Dataset<Karp> karpStream = kafkaInStream
            .as(Encoders.STRING())
            .map((MapFunction<String, Karp>) (String s) -> {
              String[] parts = s.split(";");
              double[] f = Arrays.stream(parts[0].split(","))
                      .mapToDouble(Double::parseDouble)
                      .toArray();
              double label = Double.parseDouble(parts[1]);
              long t1 = System.currentTimeMillis();   // <<< UJEDNOLICONY TIMESTAMP
              Karp k = new Karp();
              k.features = f;
              k.label = label;
              k.t1 = t1;
              return k;
            }, encKarp);

    Encoder<OutgoingKarp> encOut = Encoders.javaSerialization(OutgoingKarp.class);

    Dataset<OutgoingKarp> outgoing = karpStream
            .map((MapFunction<Karp, OutgoingKarp>) (Karp k) -> {
              double yhat = predict(k.features, model);
              OutgoingKarp o = new OutgoingKarp();
              o.features = k.features;
              o.label = k.label;
              o.t1 = k.t1;
              o.prediction = yhat;
              return o;
            }, encOut);

    Dataset<String> outgoingStr = outgoing
            .map((MapFunction<OutgoingKarp, String>) OutgoingKarp::toString,
                    Encoders.STRING());

    StreamingQuery query = outgoingStr
            .writeStream()
            .format("kafka")
            .option("kafka.bootstrap.servers", "kafka:9092")
            .option("topic", "topic2")
            .option("checkpointLocation", "checkpoint_dir")
            .start();

    try {
      query.awaitTermination();
    } catch (StreamingQueryException e) {
      throw e;
    }
  }
}