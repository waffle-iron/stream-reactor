/**
  * Copyright 2016 Datamountaineer.
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  **/

package com.datamountaineer.streamreactor.connect.redis.sink.writer

import com.datamountaineer.streamreactor.connect.errors.ErrorHandler
import com.datamountaineer.streamreactor.connect.redis.sink.config.RedisSinkSettings
import com.datamountaineer.streamreactor.connect.sink._
import com.google.gson.Gson
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.apache.kafka.connect.data.Struct
import org.apache.kafka.connect.sink.SinkRecord
import redis.clients.jedis.Jedis

import scala.collection.JavaConverters._
import scala.util.Try

/**
  * Responsible for taking a sequence of SinkRecord and write them to Redis
  */
case class RedisDbWriter(sinkSettings: RedisSinkSettings) extends DbWriter with StrictLogging with ErrorHandler {
  val gson = new Gson()
  private val connection = sinkSettings.connection
  private val jedis = new Jedis(connection.host, connection.port)
  connection.password.foreach(jedis.auth)

  //initialize error tracker
  initialize(sinkSettings.taskRetries, sinkSettings.errorPolicy)
  private val rowKeyMap = sinkSettings.rowKeyModeMap

  /**
    * Write a sequence of SinkRecords to Redis.
    * Groups the records by topic
    *
    * @param records The sinkRecords to write
    * */
  override def write(records: Seq[SinkRecord]): Unit = {
    if (records.isEmpty) {
      logger.info("No records received.")
    } else {
      val grouped = records.groupBy(_.topic())
      insert(grouped)
    }
  }

  /**
    * Insert a batch of sink records
    *
    * @param records A map of topic and sinkrecords to  insert
    * */
  def insert(records: Map[String, Seq[SinkRecord]]) = {
    records.foreach({
      case (topic, sinkRecords: Seq[SinkRecord]) => {

      //pass try to error handler and try
      val t = Try(
        {
          sinkRecords.foreach { record =>
            logger.info(s"Received recrod from topic:${record.topic()} partition:${record.kafkaPartition()} " +
              s"and offset:${record.kafkaOffset()}")
            require(record.value() != null && record.value().getClass == classOf[Struct],
              "The SinkRecord payload should be of type Struct")

            val keyBuilder = rowKeyMap.get(topic).get
            val extractor = sinkSettings.extractorFields.get(topic).get
            val fieldsAndValues = extractor.get(record.value.asInstanceOf[Struct])

            if (fieldsAndValues.nonEmpty) {
              val map = fieldsAndValues.toMap.asJava
              val key = keyBuilder.build(record)
              val payload: String = gson.toJson(map)
              jedis.set(key, payload)
            }
            else {
              None
            }
          }
       })
       handleTry(t)
      }
      logger.info(s"Wrote ${sinkRecords.size} rows for topic $topic")
    })
  }

  /**
    * Close the connection
    *
    * */
  override def close(): Unit = {
    if (jedis != null) {
      jedis.close()
    }
  }
}

object RedisDbWriterFactory {
  def apply(settings: RedisSinkSettings): RedisDbWriter = {
    new RedisDbWriter(settings)
  }
}
