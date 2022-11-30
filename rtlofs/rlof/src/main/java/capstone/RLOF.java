package capstone;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Properties;

import com.google.common.collect.MinMaxPriorityQueue;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Printed;
import org.javatuples.Pair;
import org.javatuples.Triplet;
import io.github.cdimascio.dotenv.Dotenv;

public class RLOF {

    public static HashSet<Point> window;
    public static HashMap<HashSet<Point>, Double> symDistances;
    public static HashMap<Point, PriorityQueue<Pair<Point, Double>>> kNNs;
    public static HashMap<Point, Double> kDistances;
    public static HashMap<Pair<Point, Point>, Double> reachDistances;
    public static HashMap<Point, Double> LRDs;
    public static HashMap<Point, Double> LOFs;

    public static HashSet<Triplet<Point, Double, Integer>> blackHoles; // Center, Radius, Number
    // profiles of vps, where the keys are the blackhole centers
    public static HashMap<Point, Double> vpKdists;
    public static HashMap<Point, Double> vpLrds;

    public static long ts = 0L;
    public static HashMap<Point, Long> pointTimestamps;
    // again, key is BH center
    public static HashMap<Point, Long> vpTimestamps;

    public static int k;
    public static long W;
    public static long MAX_AGE;
    public static String DISTANCE_MEASURE;
    public static int INLIER_PERCENTAGE;
    public static int TOP_N;
    public static long totalPoints;
    public static MinMaxPriorityQueue<Pair<Point, Double>> topOutliers;
    public static HashSet<KeyValue<String, Integer>> mapped;
    public static String SINK;

    public static long startTime;

    public static HashSet<Triplet<Point, Double, Integer>> findBlackholeIfAny(Point point) {
        HashSet<Triplet<Point, Double, Integer>> found = new HashSet<>();
        try {
            for (Triplet<Point, Double, Integer> triplet : blackHoles) {
                Double distance = point.getDistanceTo(triplet.getValue0(), DISTANCE_MEASURE);
                symDistances.put(new HashSet<Point>(Arrays.asList(point, triplet.getValue0())), distance);
                if (distance <= triplet.getValue1()) {
                    found.add(triplet);
                }
            }
        } catch (Exception e) {
            System.out.println("findBlackholeIfAny " + e + e.getStackTrace()[0].getLineNumber());
        }
        return found;
    }

    public static void summarize() {
        try {
            final int numberTopInliers = (int)(window.size() * INLIER_PERCENTAGE / 100);
            // sort lofs asc
            MinMaxPriorityQueue<Pair<Point, Double>> sorted = MinMaxPriorityQueue
                                                            .orderedBy(PointComparator.comparator())
                                                            .maximumSize(numberTopInliers)
                                                            .create();
            for (Point point : window) {
                sorted.add(new Pair<Point, Double>(point, LOFs.get(point)));
            }
            assert(Tests.isMinHeap(sorted));
            HashSet<Point> toDelete = new HashSet<>();
            for (Pair<Point, Double> inlier : sorted) {
                Point center = inlier.getValue0();
                assert(Tests.centerIsNotVirtual(center));
                toDelete.add(center);
                double radius = kDistances.get(center);
                HashSet<Point> neighbors = new HashSet<>();
                for (Pair<Point, Double> n : kNNs.get(center)) {
                    neighbors.add(n.getValue0());
                }
                toDelete.addAll(neighbors);
                int number = neighbors.size() + 1;
                assert(Tests.isAtLeast(number, k));
                assert(Tests.blackholeDoesNotAlreadyExist(blackHoles, center));
                blackHoles.add(new Triplet<Point, Double, Integer>(center, radius, number));
                double avgKdist = 0, avgLrd = 0;
                for (Point neighbor : neighbors) {
                    Double kdist, lrd;
                    if (neighbor.getClass().equals(VPoint.class)) {
                        kdist = vpKdists.get(((VPoint)neighbor).center);
                        lrd = vpLrds.get(((VPoint)neighbor).center);
                    } else {
                        kdist = kDistances.get(neighbor);
                        lrd = LRDs.get(neighbor);
                    }
                    avgKdist += kdist;
                    avgLrd += lrd;
                }
                assert(Tests.isPositive(neighbors.size()));
                avgKdist /= neighbors.size();
                avgLrd /= neighbors.size();
                assert(Tests.isPositive(avgKdist));
                assert(Tests.isPositive(avgLrd));

                vpKdists.put(center, avgKdist);
                vpLrds.put(center, avgLrd);
                vpTimestamps.put(center, ts);
            }
            int before = window.size();
            fullyDeleteRealPoints(toDelete);
            assert(Tests.isLessThan(window.size(), before));
        } catch (Exception e) {
            System.out.println("summarize " + e + e.getStackTrace()[0].getLineNumber());
        }
    }

    public static void updateVps(Triplet<Point, Double, Integer> blackHole,
                                Point point,
                                PriorityQueue<Pair<Point, Double>> kNN,
                                Double kdistance,
                                HashMap<Pair<Point, Point>, Double> reachDistances,
                                Double lrd) {
        try {
            Point center = blackHole.getValue0();
            int number = blackHole.getValue2();
            double newAvgKdist = (vpKdists.get(center) * number + kdistance) / (number + 1);
            double newAvgLrd = (vpLrds.get(center) * number + lrd) / (number + 1);
            //assert(Tests.isAtMost(newAvgKdist, vpKdists.get(center)));
            //assert(Tests.isAtLeast(newAvgLrd, vpLrds.get(center))); // perhaps these 2 assertions are wishful thinking?
            vpKdists.put(center, newAvgKdist);
            vpLrds.put(center, newAvgLrd);
            // new_kdist is new radius
            Double new_kdist = symDistances.containsKey(new HashSet<Point>(Arrays.asList(point, blackHole.getValue0()))) ?
                                symDistances.get(new HashSet<Point>(Arrays.asList(point, blackHole.getValue0()))) :
                                point.getDistanceTo(blackHole.getValue0(), DISTANCE_MEASURE);
            assert(Tests.isAtMost(new_kdist, blackHole.getValue1()));
            blackHoles.remove(blackHole);
            blackHoles.add(new Triplet<Point,Double,Integer>(center, new_kdist, number+1));
            vpTimestamps.put(center, 0L);
        } catch (Exception e) {
            System.out.println("updateVps " + e + e.getStackTrace()[0].getLineNumber());
        }
    }

    public static void ageBasedDeletion() {
        try {
            HashSet<Point> toDelete = new HashSet<>();
            pointTimestamps.entrySet().forEach(entry -> {
                if (entry.getValue() > MAX_AGE) {
                    // in case they're an old outlier:
                    topOutliers.add(new Pair<>(entry.getKey(), LOFs.get(entry.getKey())));
                    toDelete.add(entry.getKey());
                }
            });
            int before = window.size();
            fullyDeleteRealPoints(toDelete);
            // now for the vps:
            toDelete.clear();
            vpTimestamps.entrySet().forEach(entry -> {
                if (entry.getValue() > MAX_AGE) {
                    toDelete.add(entry.getKey());
                }
            });
            fullyDeleteVirtualPoints(toDelete);
            assert(Tests.isAtMost(window.size(), before));
        } catch (Exception e) {
            System.out.println("ageBasedDeletion " + e + e.getStackTrace()[0].getLineNumber());
        }
    }

    public static void fullyDeleteRealPoints(HashSet<Point> toDelete) {
        try {
            window.removeAll(toDelete);
            for (Point x : toDelete) {
                // you also want to add this point to labeled data
                if (!(x.getClass().equals(VPoint.class))) {
                    //System.out.println(x.key + " " + labelPoint(x));
                    mapped.add(new KeyValue<String, Integer>(x.key, labelPoint(x)));
                }

                kNNs.remove(x);
                kDistances.remove(x);
                LRDs.remove(x);
                LOFs.remove(x);
                pointTimestamps.remove(x);
                HashSet<Pair<Point, Point>> keys = new HashSet<>();
                for (Entry<Pair<Point, Point>, Double> entry : reachDistances.entrySet()) {
                    if (entry.getKey().getValue0().equals(x) || entry.getKey().getValue1().equals(x)) {
                        keys.add(entry.getKey());
                    }
                }
                reachDistances.keySet().removeAll(keys);
                for (Entry<Point, PriorityQueue<Pair<Point, Double>>> entry : kNNs.entrySet()) {
                    entry.getValue().removeIf(pair -> pair.getValue0().equals(x));
                }
                HashSet<HashSet<Point>> dkeys = new HashSet<>();
                for (Entry<HashSet<Point>, Double> entry : symDistances.entrySet()) {
                    if (entry.getKey().contains(x)) {
                        dkeys.add(entry.getKey());
                    }
                }
                symDistances.keySet().removeAll(dkeys);
            }
        } catch (Exception e) {
            System.out.println("fullyDeleteRealPoints " + e + e.getStackTrace()[0].getLineNumber());
        }
    }

    public static void fullyDeleteVirtualPoints(HashSet<Point> toDelete) {
        // toDelete is the set of blackhole centers
        try {
            blackHoles.removeIf(bh -> toDelete.contains(bh.getValue0()));
            for (Point x : toDelete) {
                vpKdists.remove(x);
                vpLrds.remove(x);
                vpTimestamps.remove(x);

                HashSet<Pair<Point,Point>> keys = new HashSet<>();
                for (Entry<Pair<Point, Point>, Double> entry : reachDistances.entrySet()) {
                    if (entry.getKey().getValue1().equals(x)) {
                        keys.add(entry.getKey());
                    }
                }
                reachDistances.keySet().removeAll(keys);
                for (Entry<Point, PriorityQueue<Pair<Point, Double>>> entry : kNNs.entrySet()) {
                    entry.getValue().removeIf(pair -> pair.getValue0().equals(x));
                }
                HashSet<HashSet<Point>> dkeys = new HashSet<>();
                for (Entry<HashSet<Point>, Double> entry : symDistances.entrySet()) {
                    if (entry.getKey().contains(x)) {
                        dkeys.add(entry.getKey());
                    }
                }
                symDistances.keySet().removeAll(dkeys);
            }
        } catch (Exception e) {
            System.out.println("fullyDeleteVirtualPoints " + e + e.getStackTrace()[0].getLineNumber());
        }
    }

    public static void setup(Dotenv config) {
        window = new HashSet<>();
        symDistances = new HashMap<>();
        kNNs = new HashMap<>();
        kDistances = new HashMap<>();
        reachDistances = new HashMap<>();
        LRDs = new HashMap<>();
        LOFs = new HashMap<>();
        blackHoles = new HashSet<>();
        vpKdists = new HashMap<>();
        vpLrds = new HashMap<>();
        pointTimestamps = new HashMap<>();
        vpTimestamps = new HashMap<>();

        k = Integer.parseInt(config.get("k"));
        W = Integer.parseInt(config.get("WINDOW"));
        MAX_AGE = Integer.parseInt(config.get("MAX_AGE"));
        DISTANCE_MEASURE = config.get("DISTANCE_MEASURE");
        INLIER_PERCENTAGE = Integer.parseInt(config.get("INLIER_PERCENTAGE"));
        TOP_N = Optional.ofNullable(Integer.parseInt(config.get("TOP_N_OUTLIERS"))).orElse(10);
        topOutliers = MinMaxPriorityQueue.orderedBy(PointComparator.comparator().reversed()).maximumSize(TOP_N).create();
        mapped = new HashSet<>();
        SINK = Utils.buildSinkFilename(config, true);
    }

    // TODO: copy paste, make util?
    public static int labelPoint(Point point) {
        return topOutliers.contains(new Pair<Point, Double>(point, LOFs.get(point))) ? 1 : 0;
      }
    
    public static void process(KStream<String, Point> data, Dotenv config) {
        setup(config);

        data
        .map((key, point) -> {
            mapped.clear();
            point.setKey(key);
            totalPoints++;
            if (totalPoints == 1) {
                startTime = System.nanoTime();
            }
            HashSet<Triplet<Point, Double, Integer>> triplets = findBlackholeIfAny(point);
            return new KeyValue<Point, HashSet<Triplet<Point, Double, Integer>>>(point, triplets);
        })
        .flatMap((point, triplets) -> {
            window.add(point);
            ILOF.ilofSubroutineForRlof(point,
                                    window,
                                    symDistances,
                                    kNNs,
                                    kDistances,
                                    reachDistances,
                                    LRDs,
                                    LOFs,
                                    blackHoles,
                                    vpKdists,
                                    vpLrds,
                                    config);

            if (triplets.size() == 0) {
                pointTimestamps.put(point, ts++);
            } else {
                for (Triplet<Point,Double,Integer> triplet : triplets) {
                    updateVps(triplet, point, kNNs.get(point), kDistances.get(point), reachDistances, LRDs.get(point));
                }
                fullyDeleteRealPoints(new HashSet<Point>(Arrays.asList(point)));

                assert(Tests.pointHasNotAffectedRlof(point, window, kNNs, kDistances, reachDistances, LRDs, LOFs, symDistances));

                //System.out.println(point.key + " " + labelPoint(point));
                mapped.add(new KeyValue<String, Integer>(point.key, labelPoint(point)));
            }
            if (window.size() >= W) {
                summarize();
            }
            if (window.size() >= W) {
                ageBasedDeletion();
            }

            //System.out.println(totalPoints);
            if (totalPoints == Integer.parseInt(config.get("TOTAL_POINTS"))) {
                long estimatedEndTime = System.nanoTime();

                // TODO: quite a bit of copy paste here from ILOF
                for (Point x : window) {
                  topOutliers.add(new Pair<Point, Double>(x, LOFs.get(x)));
                };
                assert(Tests.isMaxHeap(topOutliers));
                for (Point x : window) {
                  //System.out.println(x + " " + LOFs.get(x));
                  //System.out.println(x.key + " " + labelPoint(x));
                  mapped.add(new KeyValue<String, Integer>(x.key, labelPoint(x)));
                }
                System.out.println("estimated time elapsed ms " + (estimatedEndTime - startTime) / 1000000);
            }
            return mapped;
        })
        .print(Printed.toFile(SINK))
        ;
    }

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.load();

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, dotenv.get("KAFKA_APP_ID"));
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, dotenv.get("KAFKA_BROKER"));
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        StreamsBuilder builder = new StreamsBuilder();
        KStream<String, String> rawData = builder.stream(dotenv.get("SOURCE_TOPIC"));

        KStream<String, Point> data = rawData.flatMapValues(value -> Arrays.asList(Utils.parse(value,
                                                                                " ",
                                                                                Integer.parseInt(dotenv.get("DIMENSIONS")))));

        process(data, dotenv);

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streams.start();
    }
}
