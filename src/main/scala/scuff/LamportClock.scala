package scuff

import java.util.concurrent.atomic.AtomicLong
import scala.annotation.tailrec
import LamportClock._

final class LamportClock(private[this] val counter: CASLong) {
  def this(init: Long = 0) = this(new AtomicCASLong(init))

  def next(): Long = counter.incrAndGet()

  @tailrec
  def next(sync: Long): Long = {
    val last = counter.value
    val nextTs = (last max sync) + 1
    if (counter.compAndSwap(last, nextTs)) {
      nextTs
    } else {
      next(sync)
    }
  }

  @tailrec
  def sync(update: Long): Unit = {
    val curr = counter.value
    if (update > curr && !counter.compAndSwap(curr, update)) {
      sync(update)
    }
  }

}

object LamportClock {
  trait CASLong {
    def value: Long
    def compAndSwap(expected: Long, update: Long): Boolean
    def incrAndGet(): Long
  }
  private final class AtomicCASLong(private[this] val al: AtomicLong) extends CASLong {
    def this(init: Long = 0) = this(new AtomicLong(init))
    def value: Long = al.get
    def compAndSwap(expected: Long, update: Long): Boolean = al.compareAndSet(expected, update)
    def incrAndGet(): Long = al.incrementAndGet()
  }
}