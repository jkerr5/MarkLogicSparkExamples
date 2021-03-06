package com.marklogic.spark.examples;

/**
 * Created by Hemant Puranik, MarkLogic Corporation in 2015.
 * This software is distributed without any warranty.
 */
import com.marklogic.mapreduce.DocumentInputFormat;
import com.marklogic.mapreduce.DocumentURI;
import com.marklogic.mapreduce.MarkLogicNode;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Writable;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.*;
import org.apache.spark.serializer.KryoRegistrator;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import scala.Tuple2;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;

public class MarkLogicWordCount {

    private static final PairFlatMapFunction<Tuple2<DocumentURI, MarkLogicNode>, String, String> ELEMENT_NAME_VALUE_PAIR_EXTRACTOR =
        new PairFlatMapFunction<Tuple2<DocumentURI,MarkLogicNode>, String, String>() {
            @Override
            public Iterator<Tuple2<String, String>> call(Tuple2<DocumentURI, MarkLogicNode> arg) throws Exception {
                DocumentURI key = arg._1();
                MarkLogicNode value = arg._2();
                ArrayList<Tuple2<String, String>> elementValuePairs = new ArrayList<Tuple2<String, String>>();
                if (key != null && value != null && value.get() != null &&
                        value.get().getNodeType() == Node.DOCUMENT_NODE) {
                    Document doc = (Document)value.get();
                    Element docElement = doc.getDocumentElement();
                    NodeList childNodes = docElement.getChildNodes();
                    for(int index=0; index < childNodes.getLength(); index++){
                        Node nodeItem = childNodes.item(index);
                        if (nodeItem.getNodeType() == Node.ELEMENT_NODE) {
                            elementValuePairs.add(new Tuple2<String, String>(nodeItem.getNodeName(), nodeItem.getTextContent()));
                        }
                    }
                } else {
                    System.out.println("Error in FlatMapFunction key: " + key + ", value: " + value);
                }
                return elementValuePairs.iterator();
            }
        };

    private static final PairFunction<Tuple2<String, String>, String, Integer> ELEMENT_VALUE_OCCURRENCE_COUNT_MAPPER =
        new PairFunction<Tuple2<String, String>, String, Integer>() {
            @Override
            public Tuple2<String, Integer> call(Tuple2<String, String> pair) throws Exception {
                String nameValueOccurrence = pair._1().concat(":").concat(pair._2());
                if(nameValueOccurrence.length() > 0) {
                    return new Tuple2<String, Integer>(nameValueOccurrence, 1);
                }else{
                    return new Tuple2<String, Integer>(nameValueOccurrence, 0);
                }
            }
        };

    private static final Function2<Integer, Integer, Integer> ELEMENT_VALUE_COUNT_REDUCER =
        new Function2<Integer, Integer, Integer>() {
            @Override
            public Integer call(Integer a, Integer b) throws Exception {
                return a + b;
            }
        };

    private static final Function<Tuple2<String, Integer>, Boolean> ELEMENT_VALUE_COUNT_FILTER =
        new Function<Tuple2<String, Integer>, Boolean>() {
            @Override
            public Boolean call(Tuple2<String, Integer> arg) throws Exception {
                return arg._2() > 1;
            }
        };

    private static final Function<Iterable<String>, Integer> DISTINCT_VALUE_COUNTER =
        new Function<Iterable<String>, Integer>() {
            @Override
            public Integer call(Iterable<String> values) throws Exception {
                HashSet<String> distinctValues = new HashSet<String>();
                Iterator<String> it = values.iterator();
                while(it.hasNext()){
                    distinctValues.add(it.next());
                }
                return distinctValues.size();
            }
        };

    public static class WritableSerializer extends Serializer<Writable> {
        @Override
        public void write(Kryo kryo, Output output, Writable obj) {
            try {
                obj.write(new DataOutputStream(output));
            } catch (IOException e) {
                throw new KryoException(e);
            }
        }

        @Override
        public Writable read(Kryo kryo, Input input, Class<Writable> aClass) {
            try {
                Writable obj = aClass.newInstance();
                obj.readFields(new DataInputStream(input));
                return obj;
            } catch (IllegalAccessException e) {
                throw new KryoException(e);
            } catch (InstantiationException e) {
                throw new KryoException(e);
            } catch (IOException e) {
                throw new KryoException(e);
            }
        }
    }

    public static class MarkLogicKryoRegistrator implements KryoRegistrator {
        public void registerClasses(Kryo kryo) {
            // we could cover all Writable objects using this but we will just limit it to the MarkLogic classes
            // we know need a custom serializer for now

            Serializer serializer = new WritableSerializer();
            kryo.register(MarkLogicNode.class, serializer);
            kryo.register(DocumentURI.class, serializer);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Please provide the configuration file full path and target hdfs location as arguments");
            System.exit(0);
        }

        // first you create the spark context within java
        SparkConf conf = new SparkConf()
            .setAppName("com.marklogic.spark.examples")
            // Spark uses Java serialization as the default serializer but the MarkLogic
            // hadoop classes implement org.apache.hadoop.io.Writable but not java.io.Serializable
            // so we need to use the Kryo serializer
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            .set("spark.kryo.registrator", MarkLogicKryoRegistrator.class.getName())
        ;

        JavaSparkContext context = new JavaSparkContext(conf);

        //Create configuration object and load the MarkLogic specific properties from the configuration file.
        Configuration hdConf = new Configuration();
        String configFilePath = args[0].trim();
        FileInputStream ipStream =  new FileInputStream(configFilePath);
        hdConf.addResource(ipStream);
        //Create RDD based on documents within MarkLogic database. Load documents as DocumentURI, MarkLogicNode pairs.
        JavaPairRDD<DocumentURI, MarkLogicNode> mlRDD = context.newAPIHadoopRDD(
            hdConf,                     //Configuration
            DocumentInputFormat.class,  //InputFormat
            DocumentURI.class,          //Key Class
            MarkLogicNode.class         //Value Class
        );

        // Use a repartitioning stage to test out the serialization of MarkLogicNode objects
        // this makes the job run a lot slower as it has to serialize/deserialize all the XML
        //mlRDD = mlRDD.repartition(8);

        //extract XML elements as name value pairs where element content is value
        JavaPairRDD<String, String> elementNameValuePairs = mlRDD.flatMapToPair(ELEMENT_NAME_VALUE_PAIR_EXTRACTOR);
        //Group element values for the same element name
        JavaPairRDD<String, Iterable<String> > elementNameValueGroup = elementNameValuePairs.groupByKey();
        //Count distinct values for each element name
        JavaPairRDD<String, Integer> elementNameDistinctValueCountMap = elementNameValueGroup.mapValues(DISTINCT_VALUE_COUNTER);

        //map the element name value pairs to occurrence count of each name:value pair
        JavaPairRDD<String, Integer> elementNameValueOccurrenceCountMap = elementNameValuePairs.mapToPair(ELEMENT_VALUE_OCCURRENCE_COUNT_MAPPER);
        //aggregate the occurrence count of each distinct name:value pair.
        JavaPairRDD<String, Integer> elementNameValueOccurrenceAggregateCountMap = elementNameValueOccurrenceCountMap.reduceByKey(ELEMENT_VALUE_COUNT_REDUCER);
        //filter out the name:value occurrences that are statistically insignificant
        JavaPairRDD<String, Integer> relevantNameValueOccurrenceCounters = elementNameValueOccurrenceAggregateCountMap.filter(ELEMENT_VALUE_COUNT_FILTER);
        //Combine the distinct value count for each element name and occurrence count for each distinct name:value pair in a single RDD
        JavaPairRDD<String, Integer> valueDistribution = elementNameDistinctValueCountMap.union(relevantNameValueOccurrenceCounters);
        //sort the RDD based on key value so that element name and name:value keys appear in order
        JavaPairRDD<String, Integer> sortedValueDistribution = valueDistribution.sortByKey(true, 1);

        //Save the output to HDFS location that is specified as a part of input argument.
        sortedValueDistribution.saveAsTextFile(args[1]);
    }
}
