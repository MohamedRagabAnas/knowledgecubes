package edu.purdue.knowledgecubes

import java.io.{File, FileWriter, IOException, PrintWriter}
import java.text.SimpleDateFormat
import java.util.Date

import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.Sorting

import com.typesafe.scalalogging.Logger
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.sql.SparkSession
import org.datasyslab.geospark.serde.GeoSparkKryoRegistrator
import org.datasyslab.geosparksql.utils.GeoSparkSQLRegistrator
import org.slf4j.LoggerFactory

import edu.purdue.knowledgecubes.GEFI.GEFIType
import edu.purdue.knowledgecubes.queryprocessor.QueryProcessor
import edu.purdue.knowledgecubes.utils.CliParser

object QueryCLI {

  val LOG = Logger(LoggerFactory.getLogger(getClass))

  def main(args: Array[String]): Unit = {
    val params = CliParser.parseExecutor(args)
    val spark = SparkSession.builder
      .appName(s"Knowledge Cubes Query")
      .config("spark.sql.inMemoryColumnarStorage.batchSize", "20000")
      .config("spark.serializer", classOf[KryoSerializer].getName)
      .config("spark.kryo.registrator", classOf[GeoSparkKryoRegistrator].getName)
      .getOrCreate()

    GeoSparkSQLRegistrator.registerAll(spark)

    val dbPath = params("db")
    val localPath = params("local")
    var queriesPath = params("queries")
    val ftype = params("fType")
    val fp = params("fp").toDouble
    val spatial = params("spatial")

    val directory = new File(localPath)
    if (!directory.exists) {
      LOG.error("Local Path Does Not Exist, Exiting")
      System.exit(1)
    }

    val falsePositiveRate = fp
    var filterType = GEFIType.NONE
    if (ftype == "bloom") {
      filterType = GEFIType.BLOOM
    } else if (ftype == "roaring") {
      filterType = GEFIType.ROARING
    } else if (ftype == "bitset") {
      filterType = GEFIType.BITSET
    }

    var spatialSupport = false
    if(spatial.equals("y")) {
      spatialSupport = true
    }

    LOG.info(s"GEFI: $filterType")

    LOG.info(s"Using Database ${params("local")}")
    LOG.info(s"Reading Queries at ${params("queries")}")

    var numQueries: Int = 0
    try {
      val timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
      val printer: PrintWriter = new PrintWriter(new FileWriter(localPath + s"/results-$timestamp.txt"))
      val folder: File = new File(queriesPath)
      val listOfFiles: Array[File] = folder.listFiles
      val queries = ListBuffer[String]()
      Sorting.quickSort(listOfFiles)

      for( file <- listOfFiles) {
        if (file.isFile) {
          queries += file.getName
        }
      }

      printer.println(s"Name\tNumResults\tExecTime\tOrig\tRed\tMaxJoins\tnumTriples\tisWarm")

      val queryProcessor = QueryProcessor(spark, dbPath, localPath, filterType, falsePositiveRate, spatialSupport)
      for (qryFile <- queries) {
        numQueries += 1
        val qryName: String = qryFile.split("\\.")(0)
        val qry: String = Source.fromFile(queriesPath + "/" + qryFile).getLines.mkString("\n")
        val r = queryProcessor.benchmark(qry)
        printer.println(qryName + "\t" +
          r.numResults + "\t" +
          r.execTime + "\t" +
          r.tableSizes + "\t" +
          r.reductionSizes + "\t" +
          r.maxJoins + "\t" +
          r.numTriples + "\t"
          + r.isWarm)
        println(s"$qryName: ${r.execTime}ms (${r.numResults}) ${r.reductionSizes}/${r.tableSizes}")
      }
      queryProcessor.close()
      printer.close()
    } catch {
      case exp: IOException =>
        exp.printStackTrace()
    }
  }

  def rep[A](n: Int)(f: => A) { if (n > 0) { f; rep(n-1)(f) } }
}
