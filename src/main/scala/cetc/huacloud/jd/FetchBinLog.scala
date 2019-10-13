package cetc.huacloud.jd

import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.streaming.OutputMode

object FetchBinLog {

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder
      .config(new SparkConf()
        .set("spark.eventLog.enabled","true")
        .set("spark.eventLog.dir",
          "hdfs://master:8020/user/spark/spark2ApplicationHistory")
        .set("spark.yarn.historyServer.address","http://master:18080")
      .set("spark.sql.shuffle.partitions","100"))
      .master("local[4]")
      .appName("FetchBinLog")
      .getOrCreate()


    val df= spark.readStream.
      format("org.apache.spark.sql.mlsql.sources.MLSQLBinLogDataSource")
      .option("host","192.168.3.154")
      .option("port","3306")
      .option("userName","root")
      .option("password","Lxx123456")
      .option("databaseNamePattern","data_management")
      .option("tableNamePattern",".*")
//      .option("binaryLogClient.keepAlive","120000")
//      .option("binaryLogClient.heartbeatInterval","5000")
      .option("binlogIndex","1")
      .option("binlogFileOffset","4")
      .option("bingLogNamePrefix","mysql-bin")
      .load()

    df.writeStream
      .format("org.apache.spark.sql.delta.sources.MLSQLDeltaDataSource")
//      .option("idCols","id")
      .option("checkpointLocation","hdfs://master.hadoop.com:8020/tmp/cpl-binlog")
      .outputMode(OutputMode.Append)
      .start("hdfs://master.hadoop.com:8020/tmp/delta/binlog/monitor-mysql")
     .awaitTermination()

     /*df.writeStream.format("console")
      .outputMode(OutputMode.Append())
      .start().awaitTermination()
*/
  }

}
