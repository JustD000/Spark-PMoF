package org.apache.spark.storage.pmof

import java.nio.ByteBuffer

import org.apache.spark.internal.Logging
import org.apache.spark.network.pmof.PmofTransferService
import org.apache.spark.{SparkConf, SparkEnv}

import scala.collection.JavaConverters._
import java.nio.file.{Files, Paths}
import java.util.UUID
import java.lang.management.ManagementFactory

import org.apache.spark.network.buffer.ManagedBuffer
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

private[spark] class PersistentMemoryHandler(
    val root_dir: String,
    val path_list: List[String],
    val shuffleId: String,
    val maxStages: Int = 1000,
    val numMaps: Int = 1000,
    var poolSize: Long = -1) extends Logging {
  // need to use a locked file to get which pmem device should be used.
  val pmMetaHandler: PersistentMemoryMetaHandler = new PersistentMemoryMetaHandler(root_dir)
  var device: String = pmMetaHandler.getShuffleDevice(shuffleId)
  if(device == "") {
    //this shuffleId haven't been written before, choose a new device
    val path_array_list = new java.util.ArrayList[String](path_list.asJava)
    device = pmMetaHandler.getUnusedDevice(path_array_list)
    logInfo("This a new shuffleBlock, find an unused device:" + device + ", numMaps of this stage is " + numMaps)

    val dev = Paths.get(device)
    if (Files.isDirectory(dev)) {
      // this is fsdax, add a subfile
      device += "/shuffle_block_" + UUID.randomUUID().toString()
      logInfo("This is a fsdax, filename:" + device)
    } else {
      logInfo("This is a devdax, name:" + device)
      poolSize = 0
    }
  } else {
    logInfo("This a recently opened shuffleBlock, use the original device:" + device + ", numMaps of this stage is " + numMaps)
  }
  
  val pmpool = new PersistentMemoryPool(device, maxStages, numMaps, poolSize)
  var rkey: Long = 0


  def getDevice(): String = {
    device
  }

  def updateShuffleMeta(shuffleId: String): Unit = synchronized {
    pmMetaHandler.insertRecord(shuffleId, device);
  }

  def getBlockDetail(blockId: String): (String, Int, Int, Int) = {
    val shuffleBlockIdPattern = raw"shuffle_(\d+)_(\d+)_(\d+)".r
    val spilledBlockIdPattern = raw"reduce_spill_(\d+)_(\d+)".r
    blockId match {
      case shuffleBlockIdPattern(stageId, shuffleId, partitionId) => ("shuffle", stageId.toInt, shuffleId.toInt, partitionId.toInt)
      case spilledBlockIdPattern(stageId, partitionId) => ("reduce_spill", stageId.toInt, 0, partitionId.toInt)
    }
  }

  def getPartitionBlockInfo(blockId: String): Array[(Long, Int)] = {
    val (blockType, stageId, shuffleId, partitionId) = getBlockDetail(blockId)
    var res_array: Array[Long] = if (blockType == "shuffle") pmpool.getMapPartitionBlockInfo(stageId, shuffleId, partitionId) else pmpool.getReducePartitionBlockInfo(stageId, shuffleId, partitionId)
    var i = -2
    var blockInfo = Array.ofDim[(Long, Int)]((res_array.length)/2)
    blockInfo.map{ x => i += 2; (res_array(i), res_array(i+1).toInt)}
  }

  def getPartitionSize(blockId: String): Long = {
    val (blockType, stageId, shuffleId, partitionId) = getBlockDetail(blockId)
    if (blockType == "shuffle") {
      return pmpool.getMapPartitionSize(stageId, shuffleId, partitionId)
    }
    pmpool.getReducePartitionSize(stageId, shuffleId, partitionId)
  }
  
  def setPartition(numPartitions: Int, blockId: String, unsafeByteBuffer: ByteBuffer, dataSize: Int, clean: Boolean, numMaps: Int = 1): Long = {
    val (blockType, stageId, shuffleId, partitionId) = getBlockDetail(blockId)
    var ret_addr: Long = 0
    if (blockType == "shuffle") {
      ret_addr = pmpool.setMapPartition(numPartitions, stageId, shuffleId, partitionId, unsafeByteBuffer, dataSize, clean, numMaps)
    } else if (blockType == "reduce_spill") {
      ret_addr = pmpool.setReducePartition(10000, stageId, partitionId, unsafeByteBuffer, dataSize, clean, numMaps)
    }
    ret_addr
  }

  def getPartition(stageId: Int, shuffleId: Int, partitionId: Int): Array[Byte] = {
    pmpool.getMapPartition(stageId, shuffleId, partitionId)
  }

  def getPartition(blockId: String): Array[Byte] = {
    val (blockType, stageId, shuffleId, partitionId) = getBlockDetail(blockId)
    if (blockType == "shuffle") {
      pmpool.getMapPartition(stageId, shuffleId, partitionId)
    } else if (blockType == "reduce_spill") {
      pmpool.getReducePartition(stageId, shuffleId, partitionId)
    } else {
      new Array[Byte](0)
    }
  }

  def deletePartition(blockId: String): Unit = {
    val (blockType, stageId, shuffleId, partitionId) = getBlockDetail(blockId)
    if (blockType == "shuffle") {
      pmpool.deleteMapPartition(stageId, shuffleId, partitionId)
    } else if (blockType == "reduce_spill") {
      pmpool.deleteReducePartition(stageId, shuffleId, partitionId)
    }
  }

  def getPartitionManagedBuffer(blockId: String): ManagedBuffer = {
    new PmemManagedBuffer(this, blockId)
  }

  def close(): Unit = synchronized {
    pmpool.close()
    pmMetaHandler.remove()
  }

  def getRootAddr(): Long = {
    pmpool.getRootAddr();
  }

  def log(printout: String) {
    logInfo(printout)
  }
}

object PersistentMemoryHandler {
  private var persistentMemoryHandler: PersistentMemoryHandler = _
  private var stopped: Boolean = false
  def getPersistentMemoryHandler(conf: SparkConf, root_dir: String, path_arg: List[String], shuffleBlockId: String, pmPoolSize: Long, maxStages: Int, maxMaps: Int): PersistentMemoryHandler = synchronized {
    if (persistentMemoryHandler == null) {
      persistentMemoryHandler = new PersistentMemoryHandler(root_dir, path_arg, shuffleBlockId, maxStages, maxMaps, pmPoolSize)
      persistentMemoryHandler.log("Use persistentMemoryHandler Object: " + this)
      val enable_rdma: Boolean = conf.getBoolean("spark.shuffle.pmof.enable_rdma", defaultValue = true)
      if (enable_rdma) {
        val pmem_capacity: Long = conf.getLong("spark.shuffle.pmof.pmem_capacity", defaultValue = 264239054848L)
        val blockManager = SparkEnv.get.blockManager
        val eqService = PmofTransferService.getTransferServiceInstance(blockManager).server.getEqService
        val offset: Long = persistentMemoryHandler.getRootAddr
        val rdmaBuffer = eqService.regRmaBufferByAddress(null, offset, pmem_capacity)
        persistentMemoryHandler.rkey = rdmaBuffer.getRKey()
      }
      val dev_core_map = conf.get("spark.shuffle.pmof.dev_core_set").split(";").map(_.trim).map(_.split(":")).map(arr => arr(0) -> arr(1)).toMap
      val core_set = dev_core_map.get(persistentMemoryHandler.getDevice())
      core_set match {
        case Some(s) => Future {nativeTaskset(s)}
        case None => {}
      }
    }
    persistentMemoryHandler
  }

  def getPersistentMemoryHandler: PersistentMemoryHandler = synchronized {
    if (persistentMemoryHandler == null) {
      throw new NullPointerException("persistentMemoryHandler")
    }
    persistentMemoryHandler
  }

  def stop(): Unit = synchronized {
    if (!stopped && persistentMemoryHandler != null) {
      persistentMemoryHandler.close()
      persistentMemoryHandler = null
      stopped = true
    }
  }

  def nativeTaskset(core_set: String): Unit = {
    Runtime.getRuntime.exec("taskset -cpa " + core_set + " " + getProcessId())
  }

  def getProcessId(): Int = {
    val runtimeMXBean = ManagementFactory.getRuntimeMXBean()
    runtimeMXBean.getName().split("@")(0).toInt
  }
}
