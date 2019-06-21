package scuff.concurrent

import java.util.concurrent.{ Future => _, _ }

import scala.concurrent._
import scala.util.Try

/**
 * Thread helper class.
 */
object Threads {

  val SystemThreadGroup = rootThreadGroup(Thread.currentThread.getThreadGroup)
  private val MainThreadGroup = {
    val tgs = new Array[ThreadGroup](128)
    val count = SystemThreadGroup.enumerate(tgs, /* recurse = */ false)
    val main = tgs.take(count).find(_.getName == "main")
    main getOrElse SystemThreadGroup
  }

  val PiggyBack = new SameThreadExecutor {
    def reportFailure(t: Throwable) = throw t
  }

  def newBlockingThreadPool(name: String, reportFailure: Throwable => Unit) = {
    val tg = newThreadGroup(name, false, reportFailure = reportFailure)
    val tf = factory(tg)
    val exec = newCachedThreadPool(tf, reportFailure)
    ExecutionContext.fromExecutor(exec, reportFailure)
  }

  def newScheduledThreadPool(
      corePoolSize: Int, threadFactory: ThreadFactory,
      failureReporter: Throwable => Unit = null): ScheduledExecutorService with ExecutionContext = {

    val exec = new ScheduledThreadPoolExecutor(corePoolSize, threadFactory) with FailureReporting {
      override def reportFailure(th: Throwable) = {
        if (failureReporter != null) failureReporter(th)
        else super.reportFailure(th)
      }
    }
    Runtime.getRuntime addShutdownHook new Thread {
      override def run(): Unit = {
        exec.shutdownNow()
      }
    }
    exec
  }

  def onBlockingThread[T](
      name: String, done: Promise[T] = Promise[T], tg: ThreadGroup = MainThreadGroup)(
      blockingThunk: => T): Future[T] = {
    val t = new Thread(tg, name) {
      override def run() = {
        done tryComplete Try(blockingThunk)
      }
    }
    t.start()
    done.future
  }

    /** Executor encapsulating a single `Thread` for a single execution. Cannot be re-used. */
  def newSingleRunExecutor(threadName: String, failureReporter: Throwable => Unit): ExecutionContextExecutor = {
    newSingleRunExecutor(factory(threadName, failureReporter), failureReporter)
  }
  /** Executor encapsulating a single `Thread` for a single execution. Cannot be re-used. */
  def newSingleRunExecutor(tf: ThreadFactory, failureReporter: Throwable => Unit): ExecutionContextExecutor = new ExecutionContextExecutor {
    def execute(r: Runnable): Unit = {
      val thread = tf newThread new Runnable {
        def run = try {
          r.run()
        } catch {
          case t: Throwable => reportFailure(t)
        }
      }
      thread.start()
    }
    def reportFailure(t: Throwable) = failureReporter(t)
  }

  /**
   * `ExecutionContext`, which executes on the same thread.
   * Use this to prevent a cheap execution having to pass through
   * another to another thread, and only use this when you're certain
   * that it's OK to execute on the existing thread.
   */
  abstract class SameThreadExecutor extends ExecutionContextExecutor {
    def execute(runnable: Runnable) = runnable.run()
  }

  def newCachedThreadPool(
      threadFactory: ThreadFactory,
      failureReporter: Throwable => Unit = null): ExecutionContextExecutorService = {
    val exec = new ThreadPoolExecutor(1, Short.MaxValue,
      60L, TimeUnit.SECONDS,
      new SynchronousQueue[Runnable],
      threadFactory) with ExecutionContextExecutorService with FailureReporting {

      override def reportFailure(th: Throwable) =
        if (failureReporter != null) failureReporter(th)
        else super.reportFailure(th)
    }
    Runtime.getRuntime addShutdownHook new Thread {
      override def run(): Unit = {
        exec.shutdownNow()
      }
    }
    exec
  }

  def newSingleThreadExecutor(threadFactory: ThreadFactory, failureReporter: Throwable => Unit = null, queue: BlockingQueue[Runnable] = new LinkedBlockingQueue[Runnable]): ExecutionContextExecutorService = {
    val exec = new ThreadPoolExecutor(1, 1,
      0L, TimeUnit.MILLISECONDS,
      queue, threadFactory) with ExecutionContextExecutorService with FailureReporting {

      override def reportFailure(th: Throwable) =
        if (failureReporter != null) failureReporter(th)
        else super.reportFailure(th)
    }
    Runtime.getRuntime addShutdownHook new Thread {
      override def run(): Unit = {
        exec.shutdownNow()
      }
    }
    exec
  }

  private def rootThreadGroup(group: ThreadGroup): ThreadGroup = {
    if (group.getParent == null) group
    else rootThreadGroup(group.getParent)
  }
  def newThreadGroup(
      name: String,
      daemon: Boolean,
      reportFailure: Throwable => Unit,
      parent: ThreadGroup = MainThreadGroup) = {
    val tg = new ThreadGroup(parent, name) {
      override def uncaughtException(t: Thread, e: Throwable): Unit = {
        reportFailure(e)
      }
    }
    tg.setDaemon(daemon)
    tg
  }

  def factory(name: String, threadGroup: ThreadGroup): ThreadFactory = {
    new ScuffThreadFactory(name, threadGroup, threadGroup, false)
  }
  def factory(name: String, reportFailure: Throwable => Unit): ThreadFactory = {
    val tg = newThreadGroup(name, daemon = false, reportFailure)
    new ScuffThreadFactory(name, tg, tg, false)
  }
  def daemonFactory(name: String, threadGroup: ThreadGroup): ThreadFactory = {
    new ScuffThreadFactory(name, threadGroup, threadGroup, true)
  }
  def daemonFactory(name: String, reportFailure: Throwable => Unit): ThreadFactory = {
    val tg = newThreadGroup(name, daemon = true, reportFailure)
    new ScuffThreadFactory(name, tg, tg, true)
  }
  def factory(threadGroup: ThreadGroup): ThreadFactory = {
    new ScuffThreadFactory(threadGroup.getName, threadGroup, threadGroup, threadGroup.isDaemon)
  }

  final class ExecutorProxy[E <: Executor](real: E, reporter: Throwable => Unit) extends Executor {
    implicit def executor = real
    def execute(cmd: Runnable): Unit = {
      real execute new Runnable {
        def run = try {
          cmd.run()
        } catch {
          case t: Throwable => reporter(t); throw t
        }
      }
    }
  }

}

private[scuff] class ScuffThreadFactory(name: String, threadGroup: ThreadGroup, exceptionHandler: Thread.UncaughtExceptionHandler, daemon: Boolean) extends ThreadFactory {
  private val counter = new java.util.concurrent.atomic.AtomicInteger
  private def newName = s"$name.${counter.getAndIncrement}"
  def newThread(runnable: Runnable) = {
    val t = new Thread(threadGroup, runnable, newName)
    t.setUncaughtExceptionHandler(exceptionHandler)
    t.setDaemon(daemon)
    t
  }
}
