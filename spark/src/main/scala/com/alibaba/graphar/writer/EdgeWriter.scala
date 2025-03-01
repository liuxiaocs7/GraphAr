/**
 * Copyright 2022 Alibaba Group Holding Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.alibaba.graphar.writer

import com.alibaba.graphar.util.{
  FileSystem,
  ChunkPartitioner,
  EdgeChunkPartitioner
}
import com.alibaba.graphar.{
  GeneralParams,
  EdgeInfo,
  FileType,
  AdjListType,
  PropertyGroup
}

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.Row
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types.{
  IntegerType,
  LongType,
  StructType,
  StructField
}
import org.apache.spark.util.Utils
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions._

import scala.collection.SortedMap
import scala.collection.mutable.ArrayBuffer

/** Helper object for EdgeWriter class. */
object EdgeWriter {
  private def repartitionAndSort(
      spark: SparkSession,
      edgeDf: DataFrame,
      edgeInfo: EdgeInfo,
      adjListType: AdjListType.Value,
      vertexNumOfPrimaryVertexLabel: Long
  ): (DataFrame, Seq[DataFrame], Array[Long], Map[Long, Int]) = {
    import spark.implicits._
    val edgeSchema = edgeDf.schema
    val colName = if (
      adjListType == AdjListType.ordered_by_source || adjListType == AdjListType.unordered_by_source
    ) GeneralParams.srcIndexCol
    else GeneralParams.dstIndexCol
    val colIndex = edgeSchema.fieldIndex(colName)
    val vertexChunkSize: Long = if (
      adjListType == AdjListType.ordered_by_source || adjListType == AdjListType.unordered_by_source
    ) edgeInfo.getSrc_chunk_size()
    else edgeInfo.getDst_chunk_size()
    val edgeChunkSize: Long = edgeInfo.getChunk_size()
    val vertexChunkNum: Int =
      ((vertexNumOfPrimaryVertexLabel + vertexChunkSize - 1) / vertexChunkSize).toInt // ceil

    // sort by primary key and generate continue edge id for edge records
    val sortedDfRDD = edgeDf.sort(colName).rdd
    // generate continue edge id for every edge
    val partitionCounts = sortedDfRDD
      .mapPartitionsWithIndex(
        (i, ps) => Array((i, ps.size)).iterator,
        preservesPartitioning = true
      )
      .collectAsMap()
    val aggregatedPartitionCounts = SortedMap(partitionCounts.toSeq: _*)
      .foldLeft((0L, Map.empty[Int, Long])) { case ((total, map), (i, c)) =>
        (total + c, map + (i -> total))
      }
      ._2
    val broadcastedPartitionCounts =
      spark.sparkContext.broadcast(aggregatedPartitionCounts)
    val rddWithEid = sortedDfRDD.mapPartitionsWithIndex((i, ps) => {
      val start = broadcastedPartitionCounts.value(i)
      for { (row, j) <- ps.zipWithIndex } yield (start + j, row)
    })

    // Construct partitioner for edge chunk
    // get edge num of every vertex chunk
    val edgeNumOfVertexChunks = sortedDfRDD
      .mapPartitions(iterator => {
        iterator.map(row =>
          (row(colIndex).asInstanceOf[Long] / vertexChunkSize, 1)
        )
      })
      .reduceByKey(_ + _)
      .collectAsMap()
    // Mapping: vertex_chunk_index -> edge num of the vertex chunk
    var edgeNumMutableMap =
      collection.mutable.Map(edgeNumOfVertexChunks.toSeq: _*)
    for (i <- 0L until vertexChunkNum.toLong) {
      if (!edgeNumMutableMap.contains(i)) {
        edgeNumMutableMap(i) = 0
      }
    }
    var eidBeginOfVertexChunks =
      new Array[Long](vertexChunkNum + 1) // eid begin of vertex chunks
    var aggEdgeChunkNumOfVertexChunks =
      new Array[Long](vertexChunkNum + 1) // edge chunk begin of vertex chunks
    var eid: Long = 0
    var edgeChunkIndex: Long = 0
    for (i <- 0 until vertexChunkNum) {
      eidBeginOfVertexChunks(i) = eid
      aggEdgeChunkNumOfVertexChunks(i) = edgeChunkIndex
      eid = eid + edgeNumMutableMap(i)
      edgeChunkIndex = edgeChunkIndex + (edgeNumMutableMap(
        i
      ) + edgeChunkSize - 1) / edgeChunkSize
    }
    eidBeginOfVertexChunks(vertexChunkNum) = eid
    aggEdgeChunkNumOfVertexChunks(vertexChunkNum) = edgeChunkIndex

    val partitionNum = edgeChunkIndex.toInt
    val partitioner = new EdgeChunkPartitioner(
      partitionNum,
      eidBeginOfVertexChunks,
      aggEdgeChunkNumOfVertexChunks,
      edgeChunkSize.toInt
    )

    // repartition edge dataframe and sort within partitions
    val partitionRDD =
      rddWithEid.repartitionAndSortWithinPartitions(partitioner).values
    val partitionEdgeDf = spark.createDataFrame(partitionRDD, edgeSchema)
    partitionEdgeDf.cache()

    // generate offset dataframes
    if (
      adjListType == AdjListType.ordered_by_source || adjListType == AdjListType.ordered_by_dest
    ) {
      val edgeCountsByPrimaryKey = partitionRDD
        .mapPartitions(iterator => {
          iterator.map(row => (row(colIndex).asInstanceOf[Long], 1))
        })
        .reduceByKey(_ + _)
      val offsetDfSchema = StructType(
        Seq(StructField(GeneralParams.offsetCol, IntegerType))
      )
      val offsetDfArray: Seq[DataFrame] = (0 until vertexChunkNum).map { i =>
        {
          val filterRDD = edgeCountsByPrimaryKey
            .filter(v => v._1 / vertexChunkSize == i)
            .map { case (k, v) => (k - i * vertexChunkSize + 1, v) }
          val initRDD = spark.sparkContext.parallelize(
            (0L to vertexChunkSize).map(key => (key, 0))
          )
          val unionRDD = spark.sparkContext
            .union(filterRDD, initRDD)
            .reduceByKey(_ + _)
            .sortByKey(numPartitions = 1)
          val offsetRDD = unionRDD
            .mapPartitionsWithIndex((i, ps) => {
              var sum = 0
              var preSum = 0
              for ((k, count) <- ps) yield {
                preSum = sum
                sum = sum + count
                (k, count + preSum)
              }
            })
            .map { case (k, v) => Row(v) }
          val offsetChunk = spark.createDataFrame(offsetRDD, offsetDfSchema)
          offsetChunk.cache()
          offsetChunk
        }
      }
      return (
        partitionEdgeDf,
        offsetDfArray,
        aggEdgeChunkNumOfVertexChunks,
        edgeNumMutableMap.toMap
      )
    }
    val offsetDfArray = Seq.empty[DataFrame]
    return (
      partitionEdgeDf,
      offsetDfArray,
      aggEdgeChunkNumOfVertexChunks,
      edgeNumMutableMap.toMap
    )
  }
}

/**
 * Writer for edge dataframe.
 *
 * @constructor
 *   create a new writer for edge dataframe with edge info.
 * @param prefix
 *   the absolute prefix.
 * @param edgeInfo
 *   the edge info that describes the ede type.
 * @param adjListType
 *   the adj list type for the edge.
 * @param vertexNum
 *   vertex number of the primary vertex label
 * @param edgeDf
 *   the input edge DataFrame.
 */
class EdgeWriter(
    prefix: String,
    edgeInfo: EdgeInfo,
    adjListType: AdjListType.Value,
    vertexNum: Long,
    edgeDf: DataFrame
) {
  private val spark: SparkSession = edgeDf.sparkSession
  validate()
  writeVertexNum()

  // validate data and info
  private def validate(): Unit = {
    // chunk if edge info contains the adj list type
    if (edgeInfo.containAdjList(adjListType) == false) {
      throw new IllegalArgumentException
    }
    // check the src index and dst index column exist
    val src_filed = StructField(GeneralParams.srcIndexCol, LongType)
    val dst_filed = StructField(GeneralParams.dstIndexCol, LongType)
    val schema = edgeDf.schema
    if (
      schema.contains(src_filed) == false || schema.contains(dst_filed) == false
    ) {
      throw new IllegalArgumentException
    }
  }

  private def writeVertexNum(): Unit = {
    val outputPath = prefix + edgeInfo.getVerticesNumFilePath(adjListType)
    FileSystem.writeValue(
      vertexNum,
      outputPath,
      spark.sparkContext.hadoopConfiguration
    )
  }

  private val edgeDfAndOffsetDf
      : (DataFrame, Seq[DataFrame], Array[Long], Map[Long, Int]) =
    EdgeWriter.repartitionAndSort(
      spark,
      edgeDf,
      edgeInfo,
      adjListType,
      vertexNum
    )

  // write out the edge number
  private def writeEdgeNum(): Unit = {
    val vertexChunkSize: Long = if (
      adjListType == AdjListType.ordered_by_source || adjListType == AdjListType.unordered_by_source
    ) edgeInfo.getSrc_chunk_size()
    else edgeInfo.getDst_chunk_size()
    val vertexChunkNum: Int =
      ((vertexNum + vertexChunkSize - 1) / vertexChunkSize).toInt
    for (i <- 0 until vertexChunkNum) {
      val edgeNumber = edgeDfAndOffsetDf._4(i)
      val outputPath = prefix + edgeInfo.getEdgesNumFilePath(i, adjListType)
      FileSystem.writeValue(
        edgeNumber,
        outputPath,
        spark.sparkContext.hadoopConfiguration
      )
    }
  }

  // write out the generated offset chunks
  private def writeOffset(): Unit = {
    var chunkIndex: Int = 0
    val fileType = edgeInfo.getAdjListFileType(adjListType)
    val outputPrefix = prefix + edgeInfo.getOffsetPathPrefix(adjListType)
    for (offsetChunk <- edgeDfAndOffsetDf._2) {
      FileSystem.writeDataFrame(
        offsetChunk,
        FileType.FileTypeToString(fileType),
        outputPrefix,
        Some(chunkIndex),
        None
      )
      chunkIndex = chunkIndex + 1
    }
  }

  /** Generate the chunks of AdjList from edge dataframe for this edge type. */
  def writeAdjList(): Unit = {
    val fileType = edgeInfo.getAdjListFileType(adjListType)
    val outputPrefix = prefix + edgeInfo.getAdjListPathPrefix(adjListType)
    val adjListDf = edgeDfAndOffsetDf._1.select(
      GeneralParams.srcIndexCol,
      GeneralParams.dstIndexCol
    )
    FileSystem.writeDataFrame(
      adjListDf,
      FileType.FileTypeToString(fileType),
      outputPrefix,
      None,
      Some(edgeDfAndOffsetDf._3)
    )
    if (
      adjListType == AdjListType.ordered_by_source || adjListType == AdjListType.ordered_by_dest
    ) {
      writeOffset()
    }
    writeEdgeNum()
  }

  /**
   * Generate the chunks of the property group from edge dataframe.
   *
   * @param propertyGroup
   *   property group
   */
  def writeEdgeProperties(propertyGroup: PropertyGroup): Unit = {
    if (edgeInfo.containPropertyGroup(propertyGroup, adjListType) == false) {
      throw new IllegalArgumentException
    }

    val propertyList = ArrayBuffer[String]()
    val pIter = propertyGroup.getProperties().iterator
    while (pIter.hasNext()) {
      val property = pIter.next()
      propertyList += "`" + property.getName() + "`"
    }
    val propetyGroupDf = edgeDfAndOffsetDf._1.select(propertyList.map(col): _*)
    val outputPrefix =
      prefix + edgeInfo.getPropertyGroupPathPrefix(propertyGroup, adjListType)
    FileSystem.writeDataFrame(
      propetyGroupDf,
      propertyGroup.getFile_type(),
      outputPrefix,
      None,
      Some(edgeDfAndOffsetDf._3)
    )
  }

  /** Generate the chunks of all property groups from edge dataframe. */
  def writeEdgeProperties(): Unit = {
    val property_groups = edgeInfo.getPropertyGroups(adjListType)
    val it = property_groups.iterator
    while (it.hasNext()) {
      val property_group = it.next()
      writeEdgeProperties(property_group)
    }
  }

  /**
   * Generate the chunks for the AdjList and all property groups from edge
   * dataframe.
   */
  def writeEdges(): Unit = {
    writeAdjList()
    writeEdgeProperties()
  }
}
