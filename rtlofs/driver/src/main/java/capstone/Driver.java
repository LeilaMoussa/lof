package capstone;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;

import java.util.Arrays;
import java.util.Properties;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;

public class Driver {

    public static void main(String args[]) {
        // NOTE: Working directory must be rtlofs and .env must be in ./.env
        Dotenv dotenv = Dotenv.load();

        Properties props = new Properties();
        // IMPROVE: use defaults here and anywhere else dotenv is read
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, dotenv.get("KAFKA_APP_ID"));
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, dotenv.get("KAFKA_BROKER"));
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        StreamsBuilder builder = new StreamsBuilder();
        KStream<String, String> rawData = builder.stream(dotenv.get("SOURCE_TOPIC"));

        // Map the raw data values, deserialized as Strings containing the coordinates, to instances of Point
        KStream<String, Point> data = rawData.flatMapValues(value -> Arrays.asList(Utils.parse(value,
                                                                                    " ",
                                                                                    Integer.parseInt(dotenv.get("DIMENSIONS")))));

        final String algorithm = dotenv.get("ALGORITHM");
        switch (algorithm) {
            case "ILOF":
                ILOF.process(data, dotenv);
                break;
            case "RLOF":
                RLOF.process(data, dotenv);
                break;
            // TODO: implement as much of these as possible
            case "MILOF":
                //
                break;
            case "DILOF":
                //
                break;
            case "C_LOF":
                //
                break;
            default:
                throw new DotenvException("No Algorithm called " + algorithm + ".\n");
        }

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        // The following statement is what actually starts the stream
        streams.start();

        // This is supposed to gracefully handle shutdown with ctrl-c
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }
}
