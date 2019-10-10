package cetc.huacloud.jd

import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

object KafkaTest {
  Logger.getLogger("org").setLevel(Level.WARN)
  def main(args: Array[String]): Unit ={
    val spark = SparkSession.builder
      .config(new SparkConf())
      .master("local[4]")
      .appName("KafkaTest")
      .getOrCreate()
    val df =spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "192.168.10.14:9092")
      .option("subscribe", "ALL_ALARM_004")
      .option("startingOffsets","""{"ALL_ALARM_004":{"0":0,"1":0,"2":41402}}""")
      //.option("endingOffsets", "latest")
      .option("failOnDataLoss","false")
      .load()
    val castDF = df.selectExpr("""replace(CAST(value AS STRING),"\\","") as value""")
      .selectExpr("""replace(value,"\"{","") as value""")
    castDF.show(false)

    //输出样例数据
    /*castDF.write
      .format("com.databricks.spark.csv")
      .option("header", "true")
      .option("delimiter",",")
      .save("D:\\tmp\\kafka\\alarm")*/
  }

}
