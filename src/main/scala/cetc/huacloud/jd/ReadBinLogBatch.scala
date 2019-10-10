package cetc.huacloud.jd

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

object ReadBinLogBatch {

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder
      .config(new SparkConf()
        .set("spark.eventLog.enabled","true")
        .set("spark.eventLog.dir",
          "hdfs://master:8020/user/spark/spark2ApplicationHistory")
        .set("spark.yarn.historyServer.address","http://master:18080"))
      .master("local[4]")
      .appName("ReadBinLogBatch")
      .getOrCreate()

    val df =spark.read.
      format("org.apache.spark.sql.delta.sources.MLSQLDeltaDataSource")
      .option("path","hdfs://master.hadoop.com:8020/tmp/delta/binlog/monitor_mysql")
      .load()

    df.show(false)
  }

}
