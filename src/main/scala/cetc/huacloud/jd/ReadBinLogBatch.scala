package cetc.huacloud.jd

import java.io.{File, FileWriter, PrintWriter}

import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, Dataset, SaveMode, SparkSession}

object ReadBinLogBatch {
  var offsetFile= "C:\\lwp\\work_space\\monitor-mysql\\conf\\batchBinLog.txt"
  //var offsetFile= "/opt/monitor-mysql/batchBinLog.txt"
  def main(args: Array[String]): Unit = {
    if(args.length==0){
      println(
        """
          |binlog offset默认存储路径：/opt/monitor-mysql/batchBinLog.txt
          |如需更换，请添加入参arg(0)
        """.stripMargin)
    }else{
       offsetFile=args(0)
    }
    val spark = SparkSession.builder
      .config(new SparkConf()
        .set("spark.eventLog.enabled","true")
        .set("spark.eventLog.dir",
          "hdfs://master:8020/user/spark/spark2ApplicationHistory")
        .set("spark.yarn.historyServer.address","http://master:18080")
        .set("spark.sql.session.timeZone", "UTC"))
      .master("local[4]")
      .appName("ReadBinLogBatch")
      .getOrCreate()
    import spark.implicits._

    //加载delta中的binlog原始数据
    val df =spark.read.
      format("org.apache.spark.sql.delta.sources.MLSQLDeltaDataSource")
      .option("path","hdfs://master.hadoop.com:8020/tmp/delta/binlog/monitor-mysql")
      .load()

    //解析json
    val schemaDF =df.as[String].rdd.map(line=> {
      val arr =line.split(",")
      (arr(0),arr(1),arr(2),arr(3))
    }).map(word=>{
      (word._1.split(":")(1).replaceAll("\"",""),
        word._2.split(":")(1).replaceAll("\"",""),
        word._3.split(":")(1).replaceAll("\"",""),
        word._4.split(":")(1).replaceAll("\"",""))
    }).toDF("type","timestamp","databaseName","tableName")
      .selectExpr("type AS opType","CAST(timestamp AS long) AS offset",
       // "from_unixtime(CAST(timestamp AS long), 'yyyy-MM-dd HH:mm:ss') AS offsetDate",
        "databaseName AS dbName","tableName as tableName")

    //读取上次的offset数据,过滤binlog数据，统计操作数
    val lastOffsetDF = spark.read.text("file:\\"+offsetFile)
    println("--上次的offset信息---")
    lastOffsetDF.show(false)
    val lastOffset = lastOffsetDF.selectExpr("CAST(value as long) as lastOffset").groupBy().max("lastOffset")
      .first().getAs[Long](0)
    val filterdBinLog = schemaDF.where(s"offset > ${lastOffset}")
    val binLogCount = filterdBinLog.groupBy("dbName","tableName","opType").count()

    //过滤后的binlog数据注册为临时表
    binLogCount.createOrReplaceGlobalTempView("binlog")

    //行转列
    val pivot_sql =
      """
        |select
        | dbName,
        | tableName,
        | ifnull(insert,0) as insert,
        | ifnull(update,0) as update,
        | ifnull(delete,0) as delete
        |from global_temp.binlog
        |pivot
        |(
        |sum(`count`) for
        | `opType` in ('insert','update','delete')
        |)
      """.stripMargin
    val pivotBinLog = spark.sql(pivot_sql)
    println("---行转列后的统计数据---")
    pivotBinLog.show(false)

    //将转换后的数据注册为临时表
    pivotBinLog.createOrReplaceGlobalTempView("pivot_binlog")

    //读取cache_area_table表
    val jdbcProp = Map("url"->"jdbc:mysql://192.168.3.154:3306/data_management",
      "user"->"root",
      "password"->"Lxx123456",
      "driver"->"com.mysql.jdbc.Driver")
      spark.read
        .format("jdbc")
        .options(jdbcProp)
        .option("dbtable","(select id,code,db_name,lastInsertsCount,lastUpdatesCount,lastDeletesCount from cache_area_table) AS tempTable")
        .load().createOrReplaceGlobalTempView("cache_area_table")
    val joinSql =
      """
        |SELECT t1.id,
        |       t1.db_name,
        |       t1.code,
        |       t1.lastInsertsCount+t2.insert AS lastInsertsCount,
        |       t1.lastUpdatesCount+t2.update AS lastUpdatesCount,
        |       t1.lastDeletesCount+t2.delete AS lastDeletesCount
        | FROM (
        | select id,
        |         code,
        |         db_name,
        |         ifnull(lastInsertsCount,0) lastInsertsCount,
        |         ifnull(lastUpdatesCount,0) lastUpdatesCount,
        |         ifnull(lastDeletesCount,0) lastDeletesCount
        | from global_temp.cache_area_table
        | ) as t1
        | INNER JOIN global_temp.pivot_binlog as t2
        | ON t1.code=t2.tableName
        | AND t1.db_name=t2.dbName
      """.stripMargin
    val dataVaryResult =spark.sql(joinSql)
    println("---最终插入到cache_area_table的数据---")
    dataVaryResult.show()
    //更新cache_area_table表,spark2.4.3暂时未修改源码以支持merge到mysql的功能
    /*dataVaryResult.write
      .format("jdbc")
      .option("saveMode","merge" )
      .option("dbType","mysql")
      .options(jdbcProp)
      .option("dbtable","cache_area_table")
      .save()*/

    //存入最新的offset
    val maxOffset = schemaDF.groupBy().max("offset").first().getAs[Long](0)
    if(maxOffset>lastOffset){
      val writer = new PrintWriter(new FileWriter(new File(offsetFile),true))
      writer.println(maxOffset)
      writer.flush()
      writer.close()
    }
  }

}
