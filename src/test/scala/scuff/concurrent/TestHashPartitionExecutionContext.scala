package scuff.concurrent

import org.junit._
import org.junit.Assert._
import java.util.concurrent.CountDownLatch
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.Random
import java.util.concurrent.ConcurrentLinkedQueue

class TestHashPartitionExecutionContext {

  @annotation.tailrec
  private def updateMap(hash: Int, thread: Thread, map: collection.concurrent.Map[Int, Set[Thread]]) {
    map.get(hash) match {
      case Some(threadSet) =>
        if (!map.replace(hash, threadSet, threadSet + thread)) {
          updateMap(hash, thread, map)
        }
      case None =>
        if (map.putIfAbsent(hash, Set(thread)).isDefined) {
          updateMap(hash, thread, map)
        }
    }
  }

  @Test
  def verify {
    val numThreads = 16
    val ec = HashPartitionExecutionContext(numThreads)
    val jobsPerHash = 100
    val hashRange = -5000 to 5000
    val threadsByHash = new LockFreeConcurrentMap[Int, Set[Thread]]
    val starting = System.currentTimeMillis
    val futures =  for (_ <- 1 to jobsPerHash; hash <- hashRange) yield {
      val a = Random.nextInt
      val b = Random.nextInt
      (a*b) -> ec.submit(hash) {
        updateMap(hash, Thread.currentThread, threadsByHash)
        a * b
      }
    }
    futures.foreach {
      case (result, future) =>
        val futureResult = Await.result(future, 5.seconds)
        assertEquals(result, result)
    }
    var allThreads = Set.empty[Thread]
    threadsByHash.map(_._2).foreach { threadSet =>
      assertTrue(1 == threadSet.size)
      allThreads += threadSet.head
    }
    assertEquals(numThreads, allThreads.size)
  }
}