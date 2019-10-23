package scuff.concurrent

import scuff._
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeoutException
import scala.util.control.NonFatal
import scala.util.Failure

/**
 * Stream consumer extension that delegates
 * `onNext(T): Unit` to `apply(T): Future[_]` and
 * returns the final result on `onDone(): Future[R]`
 * through calling `whenDone()` when all `apply(T)`
 * futures have completed.
 */
trait AsyncStreamConsumer[-T, +R]
  extends StreamConsumer[T, Future[R]] {
  self: (T => Future[_]) =>

  /**
   * Max. allowed processing time to completion of
   * all `Future`s returned from `this.apply(T)`
   * (which is invoked from `onNext(T)`).
   */
  protected def completionTimeout: FiniteDuration
  /**
   * Called when processing is fully completed, i.e.
   * all `Future`s returned from `this.apply(T)` has
   * completed.
   */
  protected def whenDone(): Future[R]

  private[this] val semaphore = new java.util.concurrent.Semaphore(Int.MaxValue)
  private[this] val error = new AtomicReference[Throwable]

  def onNext(t: T): Unit = {

    val future: Future[_] = try apply(t) catch {
      case NonFatal(th) => Future failed th
    }

      implicit def ec = Threads.PiggyBack
    if (future.isCompleted) {
      future.failed.foreach(onError)
    } else {
      semaphore.tryAcquire()
      future
        .andThen {
          case Failure(th) => onError(th)
        }
        .onComplete {
          case _ => semaphore.release
        }
    }

  }

  def onError(th: Throwable): Unit = error.compareAndSet(null, th)

  def onDone(): Future[R] = {

      def whenDone: Future[R] = {
        error.get match {
          case null => this.whenDone()
          case cause => Future failed cause
        }
      }

    if (semaphore tryAcquire Int.MaxValue) whenDone
    else error.get match {
      case th: Throwable => Future failed th // Fail fast
      case _ =>
        val instanceName = this.toString()
        val aquired = Threads.onBlockingThread(s"Awaiting completion of $instanceName") {
          val timeout = completionTimeout
          if (!semaphore.tryAcquire(Int.MaxValue, timeout.length, timeout.unit)) {
            throw new TimeoutException(
              s"Stream consumption in `$className` is still not finished, $timeout after stream completion, possibly due to either incomplete stream or incomplete state. Instance: $instanceName")
          }
        }
        aquired.flatMap(_ => whenDone)(Threads.PiggyBack)
    }

  }

  private[this] def className = AsyncStreamConsumer.className(this.getClass)

  override def toString() = s"$className@$hashCode"

}

private object AsyncStreamConsumer {

  def className(cls: Class[_]): String = ClassName get cls

  private[this] val ClassName = new ClassValue[String] {
    protected def computeValue(cls: Class[_]): String = toClassName(cls)

    @annotation.tailrec
    private[this] def toClassName(cls: Class[_]): String =
    if (cls.getName.contains("$anon$") && cls.getEnclosingClass != null) {
      toClassName(cls.getEnclosingClass)
    } else cls.getName
  }

}