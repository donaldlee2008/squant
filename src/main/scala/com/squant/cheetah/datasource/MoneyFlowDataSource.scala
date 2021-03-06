package com.squant.cheetah.datasource

import java.io.{File, FileWriter}
import java.time.LocalDateTime
import java.util.Random

import com.google.gson.Gson
import com.squant.cheetah.Feeds
import com.squant.cheetah.domain.{MoneyFlow, StockMoneyFlow}
import com.squant.cheetah.engine.{DataBase, Row}
import com.squant.cheetah.utils.Constants._
import com.squant.cheetah.utils._
import com.typesafe.scalalogging.LazyLogging
import org.jsoup.Jsoup

import scala.io.Source
import scala.collection.JavaConverters._

object MoneyFlowDataSource extends DataSource with LazyLogging {

  private val baseDir = config.getString(CONFIG_PATH_DB_BASE)
  private val moneyflowDir = config.getString(CONFIG_PATH_MONEYFLOW)

  private val STOCK_HIS_COLUMNS = List(
    "date", //0   日期
    "close", //1   收盘价
    "change", //2   涨跌幅
    "turnover", //3   换手率
    "inflowAmount", //4   资金流入（万元）
    "outflowAmount", //5   资金流出（万元）
    "netInflowAmount", //6   净流入（万元）
    "mainInflowAmount", //7   主力流入（万元）
    "mainOutflowAmount", //8   主力流出（万元）
    "mainNetInflowAmount" //9   主力净流入（万元）
  )

  //http://quotes.money.163.com/trade/lszjlx_600199,0.html
  //code,pageNum
  private val moneyFlowStockHisURL: String = "http://quotes.money.163.com/trade/lszjlx_%s,%s.html"

  //http://data.eastmoney.com/bkzj/hy.html
  private val moneyFlowIndustryHisURL: String = "http://nufm.dfcfw.com/EM_Finance2014NumericApplication/JS.aspx?cmd=C._BKHY&type=ct&st=(BalFlowMain)&&token=894050c76af8597a853f5b408b759f5d&sty=DCFFITABK&rt=%s"
  private val moneyFlowIndustry5DayHisURL: String = "http://nufm.dfcfw.com/EM_Finance2014NumericApplication/JS.aspx?cmd=C._BKHY&type=ct&st=(BalFlowMainNet5)&token=894050c76af8597a853f5b408b759f5d&sty=DCFFITABK5&rt=%s"
  private val moneyFlowIndustry10DayHisURL: String = "http://nufm.dfcfw.com/EM_Finance2014NumericApplication/JS.aspx?cmd=C._BKHY&type=ct&st=(BalFlowMainNet10)&token=894050c76af8597a853f5b408b759f5d&sty=DCFFITABK10&rt=%s"
  //http://data.eastmoney.com/bkzj/gn.html
  private val moneyFlowConceptHisURL: String = "http://nufm.dfcfw.com/EM_Finance2014NumericApplication/JS.aspx?cmd=C._BKGN&type=ct&st=(BalFlowMain)&sr=-1&p=1&ps=500&token=894050c76af8597a853f5b408b759f5d&sty=DCFFITABK&rt=%s"
  private val moneyFlowConcept5DayHisURL: String = "http://nufm.dfcfw.com/EM_Finance2014NumericApplication/JS.aspx?cmd=C._BKGN&type=ct&st=(BalFlowMainNet5)&sr=-1&p=1&ps=500&token=894050c76af8597a853f5b408b759f5d&sty=DCFFITABK5&rt=%s"
  private val moneyFlowConcept10DayHisURL: String = "http://nufm.dfcfw.com/EM_Finance2014NumericApplication/JS.aspx?cmd=C._BKGN&type=ct&st=(BalFlowMainNet10)&sr=-1&p=1&ps=500&token=894050c76af8597a853f5b408b759f5d&sty=DCFFITABK10&rt=%s"
  //http://data.eastmoney.com/bkzj/dy.html
  private val moneyFlowRegionHisURL: String = "http://nufm.dfcfw.com/EM_Finance2014NumericApplication/JS.aspx?cmd=C._BKDY&type=ct&st=(BalFlowMain)&sr=-1&p=1&ps=50&token=894050c76af8597a853f5b408b759f5d&sty=DCFFITABK&rt=%s"
  private val moneyFlowRegion5DayHisURL: String = "http://nufm.dfcfw.com/EM_Finance2014NumericApplication/JS.aspx?cmd=C._BKDY&type=ct&st=(BalFlowMainNet5)&sr=-1&p=1&ps=50&&token=894050c76af8597a853f5b408b759f5d&sty=DCFFITABK5&rt=%s"
  private val moneyFlowRegion10DayHisURL: String = "http://nufm.dfcfw.com/EM_Finance2014NumericApplication/JS.aspx?cmd=C._BKDY&type=ct&st=(BalFlowMainNet10)&sr=-1&p=1&ps=50&&token=894050c76af8597a853f5b408b759f5d&sty=DCFFITABK10&rt=%s"

  private val today = List(moneyFlowIndustryHisURL, moneyFlowConceptHisURL, moneyFlowRegionHisURL)
  private val fiveDay = List(moneyFlowIndustry5DayHisURL, moneyFlowConcept5DayHisURL, moneyFlowRegion5DayHisURL)
  private val tenDay = List(moneyFlowIndustry10DayHisURL, moneyFlowConcept10DayHisURL, moneyFlowRegion10DayHisURL)

  private val CATEGORY_MONEYFLOW_COLUMNS = List(
    "num", //0     序号
    "block_code", //1     板块代码
    "name", //2     板块名称
    "change", //3     涨跌幅
    "主力净流入-净额", //4     主力净流入-净额(万)
    "主力净流入-净占比", //5     主力净流入-净占比
    "超大单净流入-净额", //6     超大单净流入-净额
    "超大单净流入-净占比", //7     超大单净流入-净占比
    "大单净流入-净额", //8     大单净流入-净额
    "大单净流入-净占比", //9     大单净流入-净占比
    "中单净流入-净额", //10     中单净流入-净额
    "中单净流入-净占比", //11    中单净流入-净占比
    "小单净流入-净额", //12    小单净流入-净额
    "小单净流入-净占比", //13     小单净流入-净占比
    "name", //14     股票名称
    "code" //15     股票代码
  )

  //初始化数据源
  override def init(taskConfig: TaskConfig =
                    TaskConfig("StockCategoryDataSource",
                      "", false, true, false,
                      LocalDateTime.now, LocalDateTime.now)): Unit = {
    clear()
    update(taskConfig)
  }

  //每个周期更新数据
  override def update(taskConfig: TaskConfig): Unit = {
    val types = List("today", "5_day", "10_day")

    val dir = new File(s"$baseDir/$moneyflowDir/${format(taskConfig.stop, "yyyyMMdd")}")
    if (!dir.exists()) {
      dir.mkdirs()
    }

    /**
      * @param `type` 0: history, 1:5day , 2:10day
      * @return
      */
    def collect(`type`: Int): List[String] = {
      var url: String = null
      val random: Random = new Random
      val i: Int = random.nextInt(99999999)
      if (`type` == 1) {
        ;
        url = fiveDay(`type`).format(i)
      }
      else if (`type` == 2) {
        url = tenDay(`type`).format(i)
      }
      else {
        url = today(`type`).format(i)
      }
      val data: String = Source.fromURL(url, "utf8").mkString
      val s: String = data.substring(1, data.length - 1)
      val gson: Gson = new Gson
      val list: java.util.List[String] = gson.fromJson(s, classOf[java.util.List[String]])
      return list.asScala.toList
    }

    if (taskConfig.clear) clear()

    logger.info(s"Start to download moneyflow data")
    for (path <- types) {
      if (taskConfig.toCSV) toCSV("Industry_" + path, taskConfig.stop, collect(0))
      if (taskConfig.toCSV) toCSV("Concept_" + path, taskConfig.stop, collect(1))
      if (taskConfig.toCSV) toCSV("Region_" + path, taskConfig.stop, collect(2))

      if (taskConfig.toDB) toDB("Industry_" + path, taskConfig.stop)
      if (taskConfig.toDB) toDB("Concept_" + path, taskConfig.stop)
      if (taskConfig.toDB) toDB("Region_" + path, taskConfig.stop)
    }

    val symbols = Feeds.symbols()
    symbols.par.foreach(symbol => {
      if (taskConfig.toCSV) toCSV(symbol.code)
      if (taskConfig.toDB) toDB(symbol.code)
    })

    logger.info(s"Download completed")
  }

  /**
    * stock money flow data to csv
    *
    * @param code
    */
  def toCSV(code: String) = {
    val list = scala.collection.mutable.ListBuffer[List[String]]()

    for (i <- 0 to 50) {
      val source = Source.fromURL(moneyFlowStockHisURL.format(code, i), "utf-8").mkString
      val doc = Jsoup.parse(source)
      val tbody = doc.select("table[class='table_bg001 border_box'] tbody")
      if (!tbody.isEmpty) {
        val elements = tbody.get(0).select("tr").iterator()
        while (elements.hasNext) {
          val tds = elements.next().select("td")
          val columns = List(
            tds.get(0).text(),
            tds.get(1).text(),
            tds.get(2).text(),
            tds.get(3).text(),
            tds.get(4).text().replaceAll(",", ""),
            tds.get(5).text().replaceAll(",", ""),
            tds.get(6).text().replaceAll(",", ""),
            tds.get(7).text().replaceAll(",", ""),
            tds.get(8).text().replaceAll(",", ""),
            tds.get(9).text().replaceAll(",", "")
          )
          list.append(columns)
        }
      }
    }

    if (list.size > 0) {
      createDir(s"$baseDir/$moneyflowDir/stock")
      val writer = new FileWriter(s"$baseDir/$moneyflowDir/stock/$code.csv", false)
      writer.write(STOCK_HIS_COLUMNS.foldLeft("")((x: String, y: String) => x + "," + y).drop(1) + "\n")
      for (line <- list.reverse) {
        writer.write(line.foldLeft("")((x: String, y: String) => x + "," + y).drop(1) + "\n")
      }
      writer.close
    }
  }

  def fromCSV(code: String): List[StockMoneyFlow] = {
    val file = Source.fromFile(new File(s"$baseDir/$moneyflowDir/stock/$code.csv")).getLines()
    file.drop(1).map(StockMoneyFlow.csvToStockMoneyFlow(_) match {
      case Some(flow) => flow
      case None => throw new UnknownError()
    }).toList
  }

  def toCSV(path: String, dateTime: LocalDateTime, data: List[String]) = {
    val file = new File(s"$baseDir/$moneyflowDir/${format(dateTime, "yyyyMMdd")}/$path.csv")
    val writer = new FileWriter(file, false)

    writer.write(CATEGORY_MONEYFLOW_COLUMNS.foldLeft[String]("")((x: String, y: String) => x + "," + y).drop(1) + "\n")

    for (line <- data) {
      writer.write(new String(line.getBytes("utf-8")) + "\n");
    }
    writer.close()
  }

  def fromCSV(path: String, dateTime: LocalDateTime = LocalDateTime.now()): List[MoneyFlow] = {
    val data = Source.fromFile(new File(s"$baseDir/$moneyflowDir/${format(dateTime, "yyyyMMdd")}/$path.csv")).getLines()
    val list = data.drop(1).map(item => MoneyFlow.csvToMoneyFlow(item) match {
      case Some(flow) => flow
      case None => throw new UnknownError("fail to parse moneyflow data")
    }
    )
    list.toList
  }

  def toCategoryTableName(path: String, date: LocalDateTime) = {
    s"${path}_${format(date, "yyyyMMdd")}"
  }

  def toDB(path: String, dateTime: LocalDateTime = LocalDateTime.now()): Unit = {
    val data = fromCSV(path, dateTime)
    if (data != null && data.size > 0) {
      val rows: List[Row] = data.map(MoneyFlow.moneyflowToRow)
      DataBase.getEngine.toDB(toCategoryTableName(path, dateTime), rows)
    }
  }

  def fromDB(path: String, dateTime: LocalDateTime): List[MoneyFlow] = {
    val rowList = DataBase.getEngine.fromDB(toCategoryTableName(path, dateTime),
      dateTime.plusDays(-1), dateTime.plusDays(1))
    rowList.map(MoneyFlow.rowToMoneyFlow)
  }

  def toDB(code: String) = {
    DataBase.getEngine.toDB(s"moneyflow_$code", fromCSV(code).map(StockMoneyFlow.stockMoneyFlowToRow))
  }

  def fromDB(code: String): List[StockMoneyFlow] = {
    DataBase.getEngine.fromDB(s"moneyflow_$code", FIRST_DAY, TODAY).map(StockMoneyFlow.rowToStockMoneyFlow)
  }

  //清空数据源
  override def clear(): Unit = {
    rm(s"/$baseDir/$moneyflowDir").foreach(r => logger.info(s"delete ${r._1} ${r._2}"))
  }
}
