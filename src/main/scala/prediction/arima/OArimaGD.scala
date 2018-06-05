package prediction.arima

import onlinearima.OARIMA_ogd
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types._
import org.apache.spark.sql.{Row, SaveMode}
import org.apache.spark.streaming._
import org.apache.spark.streaming.kafka010._
import simulation.CustomReceiver
import types.{OArimastateGD, STPoint}
import utils.{Copy, Interpolation, MobilityChecker, SparkSessionSingleton}

import scala.collection.mutable.ListBuffer

object OArimaGD {

  def main(args: Array[String]): Unit = {

    val batchInterval = 1
    val sparkConf = new SparkConf().setAppName("OArimaGD")

    /* Spark Init */
    val ssc = new StreamingContext(sparkConf, Seconds(batchInterval.toInt))
    ssc.checkpoint(".checkpoint")

    val prop = ssc.sparkContext.getConf

    /* Model Configuration */

    val window = prop.get("spark.window").toInt
    val train_set = prop.get("spark.train_set").toInt
    val horizon = prop.get("spark.horizon").toInt
    val lrate = prop.get("spark.lrate").toDouble
    val sampling = prop.get("spark.sampling").toInt
    val gap_threshold = prop.get("spark.gap_threshold").toInt
    val speed_threshold = prop.get("spark.speed_threshold").toDouble
    val path = prop.get("spark.path")
    val mode = prop.get("spark.mode")
    val topics = prop.get("spark.topics")
    val brokers = prop.get("spark.brokers")
    val groupId = prop.get("spark.groupId")

    if (train_set > window) {
      println("Window parameter must be greater than train_set")
      System.exit(1)
    }

    val broadcastTrain = ssc.sparkContext.broadcast(train_set)
    val broadcastGAP = ssc.sparkContext.broadcast(gap_threshold)
    val broadcastHistory = ssc.sparkContext.broadcast(window)
    val broadcastSampling = ssc.sparkContext.broadcast(sampling)
    val broadcastHorizon = ssc.sparkContext.broadcast(horizon)
    val broadcastLRATE = ssc.sparkContext.broadcast(lrate)
    val broadcastSpeedThres = ssc.sparkContext.broadcast(speed_threshold)


    /* Create (K=id,V=spatiotemporal point) for stateful streaming processing */

    val pointDstream = mode match {
      case "simulation" => ssc.receiverStream(new CustomReceiver(System.getProperty("user.dir") + "/data/" + path))
        .map(record => {
          val point: Array[String] = record.split(",")
          (point(0).toInt, STPoint(
            point(0).toInt, point(1).toLong, point(2).toDouble, point(3).toDouble,
            0.0, 0.0, error = false)
          )
        })
      case "kafka" =>
        val topicsSet = topics.split(",").toSet
        val kafkaParams = Map[String, Object](
          ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> brokers,
          ConsumerConfig.GROUP_ID_CONFIG -> groupId,
          ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG -> classOf[StringDeserializer],
          ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG -> classOf[StringDeserializer])

        val messages = KafkaUtils.createDirectStream[String, String](
          ssc,
          LocationStrategies.PreferConsistent,
          ConsumerStrategies.Subscribe[String, String](topicsSet, kafkaParams))

        messages.map(record => {
          val point: Array[String] = record.value().split(",")
          (point(0).toInt, STPoint(
            point(0).toInt, point(1).toLong, point(2).toDouble, point(3).toDouble,
            0.0, 0.0, error = false)
          )
        })
    }

    /* Stateful Streaming Processing */
    val mappingFunc = (key: Int, input: Option[STPoint], state: State[OArimastateGD]) => {

      val new_point: STPoint = input.get
      val h = broadcastHistory.value
      val Horizon = broadcastHorizon.value
      val wLen = broadcastTrain.value

      /* Update Moving Object State */

      val state_new: OArimastateGD = if (state.exists()) {

        if (state.isTimingOut() || new_point.timestamp - state.get().history.last.timestamp > broadcastGAP.value) {

          val linear = Array[Double](2.0, -1.0)
          val w_lon = linear.padTo(wLen, 0.0)
          val w_lat = linear.padTo(wLen, 0.0)

          val temp_state = OArimastateGD(Array(new_point))

          temp_state.setterWLON(w_lon)
          temp_state.setterWLAT(w_lat)

          temp_state.setterI(1)

          temp_state
        } else {

          val temp_state: OArimastateGD = state.get()
          val arr = temp_state.history
          temp_state.history = arr.padTo(arr.length + 1, new_point)
          temp_state.setterI(temp_state.getterI() + 1)

          temp_state
        }

      } else {

        val linear = Array[Double](2.0, -1.0)
        val w_lon = linear.padTo(wLen, 0.0)
        val w_lat = linear.padTo(wLen, 0.0)

        val temp_state = OArimastateGD(Array(new_point))

        temp_state.setterWLON(w_lon)
        temp_state.setterWLAT(w_lat)
        temp_state.setterI(1)

        temp_state
      }

      val prediction_result: Array[STPoint] = new Array[STPoint](Horizon + 1)

      /* Fix Sampling */
      val sampling = broadcastSampling.value
      var mode = false

      var j = 0
      while (j < state_new.history.length - 1 && !mode) {
        val elapsedTime = state_new.history.apply(j + 1).timestamp - state_new.history.apply(j).timestamp
        if (elapsedTime != sampling) mode = true
        j += 1
      }

      val spline: Array[STPoint] = if (mode) {
        if (Math.floor((state_new.history.last.timestamp - state_new.history.head.timestamp) / sampling.toDouble).toInt + 1 > h) {
          val interp = Interpolation.splinepolation2D(state_new.history.sortWith(_.timestamp < _.timestamp), sampling)
          state_new.setterI(interp.length)
          interp
        } else {
          Array.empty
        }
      } else {
        Copy.deepCopy(state_new.history.sortWith(_.timestamp < _.timestamp))
      }

      if (!spline.isEmpty && spline.length >= h) {

        var splitAt = broadcastTrain.value
        var start = 0

        val v_spline:Array[STPoint] = if (spline.length==h) {

          Array(STPoint(key, spline.head.timestamp,
            0,
            0,
            spline.head.speed, spline.head.heading, spline.head.error
          )) ++ spline.sliding(2).map(f=>{
            STPoint(f.head.id, f.last.timestamp,
              f.last.longitude-f.head.longitude,
              f.last.latitude-f.head.latitude,
              f.last.speed, f.last.heading, f.last.error
            )
          })

        } else {
          spline.slice(spline.length - h-1, spline.length).sliding(2).map(f=>{

            STPoint(f.head.id, f.last.timestamp,
              f.last.longitude-f.head.longitude,
              f.last.latitude-f.head.latitude,
              f.last.speed, f.last.heading, f.last.error
            )
          }).toArray
        }


        /* Train Arima Model */

        while (splitAt < h) {
          val train = v_spline.slice(start, splitAt)
          val test = v_spline(splitAt)

          val data_lon = train.map(x => x.longitude)
          val data_lat = train.map(x => x.latitude)

          val prediction_lon = OARIMA_ogd.prediction(data_lon, state_new.getterWLON())
          val prediction_lat = OARIMA_ogd.prediction(data_lat, state_new.getterWLAT())

          val new_wLon = OARIMA_ogd.adapt_w(
            prediction_lon,
            test.longitude,
            state_new.getterWLON(), broadcastLRATE.value,
            data_lon, state_new.getterI()
          )
          val new_wLat = OARIMA_ogd.adapt_w(
            prediction_lat,
            test.latitude,
            state_new.getterWLAT(), broadcastLRATE.value,
            data_lat, state_new.getterI()
          )

          state_new.setterWLON(new_wLon)
          state_new.setterWLAT(new_wLat)

          start = start + 1
          splitAt = splitAt + 1
        }

        prediction_result(0) = state_new.history.last

        var predictions = 1

        val data = spline.slice(spline.length - wLen, spline.length)

        val data_lon = data.map(x => x.longitude)
        val data_lat = data.map(x => x.latitude)
        val lastT = v_spline.last.timestamp

        /* Prediction */
        while (predictions <= Horizon) {

          val point = STPoint(
            key,
            lastT + (predictions * sampling),
            prediction_result(predictions - 1).longitude+OARIMA_ogd.prediction(data_lon, state_new.getterWLON()),
            prediction_result(predictions - 1).latitude+OARIMA_ogd.prediction(data_lat, state_new.getterWLAT()),
            0, 0, error = false
          )


          val speed = MobilityChecker.getSpeedKnots(prediction_result(predictions - 1), point)
          val heading = MobilityChecker.getBearing(prediction_result(predictions - 1), point)

          point.speed = speed
          point.heading = heading

          /*Error Checker*/

          if (
            point.speed > broadcastSpeedThres.value || point.speed.isNaN || point.speed.isInfinite /*|| p.getAltitude > 40000*/
          ) {
            point.error = true
          } else {
            point.error = false
          }

          prediction_result(predictions) = point

          predictions = predictions + 1
        }

      }

      /* Update State */
      if (state_new.history.length > h) {
        val new_arr = state_new.history.slice(state_new.history.length - h, state_new.history.length)
        state_new.history = new_arr
        state.update(state_new)
      } else {
        state.update(state_new)
      }


      prediction_result
    }

    val stateDstream = pointDstream.mapWithState(
      StateSpec.function(mappingFunc).timeout(Milliseconds(gap_threshold)))

    /* Store for evaluation*/
    stateDstream.foreachRDD { rdd: RDD[Array[STPoint]] =>

      if (!rdd.isEmpty()) {

        val spark = SparkSessionSingleton.getInstance(rdd.sparkContext.getConf)

        val schema = StructType(
          Array(
            StructField("id", IntegerType),
            StructField("timestamp", LongType),
            StructField("longitude", DoubleType),
            StructField("latitude", DoubleType),
            //StructField("altitude", DoubleType),
            StructField("speed", DoubleType),
            StructField("heading", DoubleType),
            StructField("error", BooleanType),
            StructField("horizon", IntegerType)
          )
        )

        spark.createDataFrame(rdd.flatMap(x => {

          val foo = new ListBuffer[Row]()
          var j = 0
          if (x.head != null) {
            while (j < x.length) {
              val p = x(j)
              foo.append(Row(p.id, p.timestamp, p.longitude, p.latitude, p.speed, p.heading, p.error, j))
              j = j + 1
            }
          }
          foo.iterator
        }), schema).write.mode(SaveMode.Append).parquet("predictions_parquet_OArimaGDoutput_historical_positions" + broadcastHistory + "_predicted_locations" + broadcastHorizon + "_sampling_" + broadcastSampling + "_lrate_" + broadcastLRATE.toString.replace(".", "") + "_train_" + broadcastTrain)
      }
    }

    //stateDstream.print()
    ssc.start()
    ssc.awaitTermination()
  }
}