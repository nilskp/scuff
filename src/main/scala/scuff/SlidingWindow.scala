package scuff

import concurrent.{ Threads, StreamCallback, StreamResult }
import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.Future
import scala.concurrent.duration._
import SlidingWindow._
import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal
import scala.util._
import collection.JavaConverters._
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ScheduledFuture

object SlidingWindow {

  type EpochMillis = Long

  trait Reducer[@specialized(AnyRef, Int, Long, Float, Double) T, @specialized(AnyRef, Int, Long, Float, Double) R, @specialized(AnyRef, Int, Long, Float, Double) F]
      extends ((R, R) => R) {
    def init(t: T): R
    def finalize(r: R): Option[F]
    def default: Option[F] = None
  }

  /** System clock with various timestamp granularity. */
  object SystemClock {
    /** Current sys time, millisecond granularity. */
    def asMilliseconds: EpochMillis = System.currentTimeMillis
    /** Current sys time, second granularity. */
    def asSeconds: EpochMillis = (System.currentTimeMillis / 1000) * 1000
    /** Current sys time, minute granularity. */
    def asMinutes: EpochMillis = (System.currentTimeMillis / 60000) * 60000
    /** Current sys time, hour granularity. */
    def asHours: EpochMillis = (System.currentTimeMillis / 360000) * 360000
    /** Current sys time, day granularity. */
    def asDays: EpochMillis = (System.currentTimeMillis / 8640000) * 8640000
  }
  //  trait Window {
  //    def toInterval(now: EpochMillis): Interval[EpochMillis]
  //  }
  case class Window(length: Duration, offset: FiniteDuration = Duration.Zero) {
    private[this] val offsetMillis = offset.toMillis
    private[this] val spanMs: Option[Long] = if (length.isFinite) Some(length.toMillis + offsetMillis) else None
    //    val finiteSpanMs = spanMs.getOrElse(-1L)
    def toInterval(now: EpochMillis): Interval[EpochMillis] = spanMs match {
      case Some(spanMs) => new Interval(false, now - spanMs, true, now - offsetMillis)
      case None => new Interval(true, Long.MinValue, true, now - offsetMillis)
    }
    override lazy val toString: String = {
      val sb = new java.lang.StringBuilder
      if (length.isFinite) {
        sb append '[' append length append ']'
      } else {
        sb append "<--unbounded]"
      }
      if (offset.length > 0) {
        sb append "--" append offset append "--"
      }
      sb append ">|"
      sb.toString
    }
  }

  case class Sum[N: Numeric]() extends Reducer[N, N, N] {
    private def num = implicitly[Numeric[N]]
    def init(n: N) = n
    def apply(x: N, y: N) = num.plus(x, y)
    def finalize(sum: N) = Some(sum)
  }

  case class Average[N: Numeric]() extends Reducer[N, (N, Int), N] {
    private def num = implicitly[Numeric[N]]
    private[this] val div = num match {
      case f: Fractional[N] => f.div _
      case i: Integral[N] => i.quot _
      case num => (x: N, y: N) => num.fromInt(math.round(num.toFloat(x) / num.toFloat(y)))
    }
    def init(value: N) = value -> 1
    def apply(x: (N, Int), y: (N, Int)) = num.plus(x._1, y._1) -> (x._2 + y._2)
    def finalize(sumAndCount: (N, Int)) = {
      val (sum, count) = sumAndCount
      if (count == 0) None
      else Some(div(sum, num.fromInt(count)))
    }
  }

  trait StoreProvider[@specialized(AnyRef, Int, Long, Float, Double) V] {
    /** Store accessor. */
    def apply[T](thunk: TimeStore => T): T
    trait TimeStore {
      def upsert(ts: EpochMillis, insert: V)(update: (V, V) => V)
      def querySince(ts: EpochMillis)(callback: StreamCallback[(EpochMillis, V)])
      /**
        * Optional method. Can return Future(None) if unsupported, but
        * will then not be able to supply value for Infinite windows.
        * NOTE: The reduce function passed is guaranteed to be identical
        * to the update function used in `upsert`, thus this method
        * can be cheaply implemented by maintaining a single running
        * reduction on `upsert` (thus ignoring the passed reduce function).
        */
      def queryAll(reduce: (V, V) => V): Future[Option[V]]
    }
  }
  private abstract class SynchedMapProvider[V] extends StoreProvider[V] {
    abstract class UnsynchedTimeStore extends TimeStore {
      protected type M <: java.util.Map[EpochMillis, V]
      protected def map: M
      private[this] var foreverValue: Option[V] = None
      def queryAll(reduce: (V, V) => V): Future[Option[V]] = Future successful foreverValue
      def upsert(ts: EpochMillis, value: V)(update: (V, V) => V) {
        map.put(ts, value) match {
          case null => // No update necessary
          case oldValue => map.put(ts, update(oldValue, value))
        }
        foreverValue = foreverValue.map(update(_, value)) orElse Some(value)
      }
    }
    protected val timeStore: UnsynchedTimeStore
    def apply[T](thunk: TimeStore => T): T = timeStore.synchronized(thunk(timeStore))
  }
  def TreeMapProvider[V]: StoreProvider[V] = new TreeMapProvider
  private class TreeMapProvider[V] extends SynchedMapProvider[V] {
    protected val timeStore = new UnsynchedTimeStore {
      protected type M = java.util.TreeMap[EpochMillis, V]
      protected val map = new M
      def querySince(ts: EpochMillis)(callback: StreamCallback[(EpochMillis, V)]) {
        import collection.JavaConversions._
        map.headMap(ts).clear()
        map.entrySet().foreach(e => callback.onNext(e.getKey -> e.getValue))
        callback.onCompleted()
      }
    }
  }
  def HashMapProvider[V]: StoreProvider[V] = new HashMapProvider
  private class HashMapProvider[V] extends SynchedMapProvider[V] {
    protected val timeStore = new UnsynchedTimeStore {
      protected type M = java.util.HashMap[EpochMillis, V]
      protected val map = new M(256)
      def querySince(ts: EpochMillis)(callback: StreamCallback[(EpochMillis, V)]) {
        val iter = map.entrySet.iterator
        while (iter.hasNext) {
          val entry = iter.next
          if (ts > entry.getKey) {
            iter.remove()
          } else {
            callback.onNext(entry.getKey, entry.getValue)
          }
        }
        callback.onCompleted()
      }
    }
  }

  def apply[T, R, F](reducer: Reducer[T, R, F], window: Window, otherWindows: Window*): SlidingWindow[T, R, F] =
    new SlidingWindow[T, R, F](reducer, (window +: otherWindows).toSet)
  def apply[T, R, F](reducer: Reducer[T, R, F], storeProvider: StoreProvider[R], window: Window, otherWindows: Window*): SlidingWindow[T, R, F] =
    new SlidingWindow[T, R, F](reducer, (window +: otherWindows).toSet, storeProvider)
}

class SlidingWindow[T, R, F](
    reducer: Reducer[T, R, F],
    windows: Set[Window],
    storeProvider: StoreProvider[R] = TreeMapProvider[R],
    clock: => SlidingWindow.EpochMillis = SystemClock.asMilliseconds) {

  import SlidingWindow._
  require(windows.nonEmpty, "No windows provided")

  private[this] val (finiteWindows, foreverWindows) = windows.partition(_.length.isFinite)

  def add(value: T, time: EpochMillis = clock): Unit = storeProvider(_.upsert(time, reducer.init(value))(reducer))
  def addMany(values: Traversable[T], time: EpochMillis = clock) = if (values.nonEmpty) {
    val reduced = values.map(reducer.init).reduce(reducer)
    storeProvider(_.upsert(time, reduced)(reducer))
  }
  def addBatch(valuesWithTime: Traversable[(T, EpochMillis)]) = if (valuesWithTime.nonEmpty) {
    val reducedByTime = valuesWithTime.groupBy(_._2).mapValues(_.map(t => reducer.init(t._1)).reduce(reducer))
    storeProvider { map =>
      reducedByTime.foreach {
        case (time, value) => map.upsert(time, value)(reducer)
      }
    }
  }
  private[this] val NoFuture = Future successful None
  /**
    * Take snapshot of the provided window(s), using the `Reducer`,
    * and return result. If a window has no entries, or if `finalize`
    * returns `None`, it will not be present in the map, unless the
    * `Reducer` supplies a `default` value.
    * If a timestamp is provided, it is expected to always be
    * increasing (or equal to previous).
    */
  def snapshot(now: EpochMillis = clock): Future[Map[Window, F]] = {
    val (finitesFuture, foreverFuture) = storeProvider { tsMap =>
      val sinceForever = if (foreverWindows.isEmpty) NoFuture else tsMap.queryAll(reducer)
      val initMap = new java.util.HashMap[Window, R](windows.size * 2, 1f)
      val finiteMap =
        if (finiteWindows.isEmpty) Future successful initMap
        else {
          val cutoffs = finiteWindows.map(w => w -> w.toInterval(now))
          val querySinceCutoff = tsMap.querySince(cutoffs.map(_._2.from).min) _
          StreamResult.fold(querySinceCutoff)(initMap) {
            case (map, (ts, value)) =>
              cutoffs.foldLeft(map) {
                case (map, (finiteWindow, period)) =>
                  if (period.contains(ts)) {
                    val newValue = map.get(finiteWindow) match {
                      case null => value
                      case old => reducer(old, value)
                    }
                    map.put(finiteWindow, newValue)
                  }
                  map
              }
          }
        }
      finiteMap -> sinceForever
    }
      implicit def ec = Threads.PiggyBack
    for {
      map <- finitesFuture
      foreverOpt <- foreverFuture
    } yield {
      for {
        foreverValue <- foreverOpt
        foreverKey <- foreverWindows
      } map.put(foreverKey, foreverValue)
      val finalizedMap = map.asScala.flatMap {
        case (w, r) => reducer.finalize(r).map(v => w -> v)
      }.toMap
      reducer.default match {
        case Some(default) if windows.size > finalizedMap.size =>
          windows.diff(finalizedMap.keySet).foldLeft(finalizedMap) {
            case (map, missingWindow) => map.updated(missingWindow, default)
          }
        case _ => finalizedMap
      }
    }
  }

  def subscribe(
    callbackInterval: FiniteDuration,
    scheduler: ScheduledExecutorService = Threads.DefaultScheduler)(listener: StreamCallback[Map[Window, F]]): Subscription = {
    object SubscriptionState extends Subscription {
      @volatile var schedule: Option[ScheduledFuture[_]] = None
      private val error = new AtomicBoolean(false)
      def cancel() {
        schedule = schedule.flatMap { s =>
          s.cancel(false)
          if (!error.get) listener.onCompleted()
          None
        }
      }
      def onError(t: Throwable) {
        if (error.compareAndSet(false, true)) {
          listener.onError(t)
          cancel()
        }
      }
    }
    val notifier = new Runnable {
      implicit private[this] val ec = ExecutionContext.fromExecutorService(scheduler, SubscriptionState.onError)
      def run = try {
        snapshot().onComplete {
          case Success(result) => listener.onNext(result)
          case Failure(e) => SubscriptionState.onError(e)
        }
      } catch {
        case NonFatal(e) => SubscriptionState.onError(e)
      }
    }
    SubscriptionState.schedule = Some(scheduler.scheduleAtFixedRate(notifier, callbackInterval.toMillis, callbackInterval.toMillis, MILLISECONDS))
    SubscriptionState
  }
}