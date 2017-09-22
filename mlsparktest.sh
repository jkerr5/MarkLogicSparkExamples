#!/bin/bash

CLASS=$1
shift

#MASTER="local[4]"
MASTER="yarn"

SPARK_MAJOR_VERSION=2; export SPARK_MAJOR_VERSION;

DIR=`pwd`

spark-submit \
    --jars $LIBJARS \
    --master $MASTER \
    --deploy-mode client \
    --executor-cores 1 \
    --num-executors 4 \
    --executor-memory 600M \
    --class com.marklogic.spark.examples.$CLASS \
    $DIR/target/SparkExamples-1.0-SNAPSHOT.jar \
    $DIR/marklogic-spark-count.xml \
    $@
