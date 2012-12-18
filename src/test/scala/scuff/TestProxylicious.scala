package scuff

import org.junit._
import org.junit.Assert._

import java.lang.reflect.Method

class TestProxylicious {

  @Test def `general test` {
    val multiply = new Arithmetic {
      def apply(a: Int, b: Int) = a * b
    }
    val proxyfier = new Proxylicious[Arithmetic]
    val doubler = new proxyfier.Sandwich {
      def include(method: Method) = {
        method.getParameterTypes match {
          case Array(Integer.TYPE, Integer.TYPE) ⇒ method.getReturnType == Integer.TYPE && method.getName == "apply"
          case _ ⇒ false
        }
      }
      def before(proxy: Arithmetic, method: Method, args: Array[Any]) {}
      def after(proxy: Arithmetic, method: Method, args: Array[Any], result: Either[Throwable, Any]): Any = {
        result match {
          case Left(t) ⇒ throw t
          case Right(r: Int) ⇒ r * 2
        }
      }
    }
    val withDoubling = proxyfier.proxy(multiply, doubler)
    assertEquals(9, multiply(3, 3))
    assertEquals(9 * 2, withDoubling(3, 3))
    assertEquals(121, multiply(11, 11))
    assertEquals(121 * 2, withDoubling(11, 11))
  }
  @Test def `retrying` {
    val multiply = new Multiply with ThirtiethTimesACharm
    val proxyfier = new util.RetryOnExceptionProxylicious[Arithmetic, IllegalStateException]
    val retryingMultiply = proxyfier.proxy(multiply)
    try {
      multiply(5, 6)
      fail("Should fail on illegal state")
    } catch {
      case e ⇒ assertEquals(classOf[IllegalStateException], e.getClass)
    }
    assertEquals(42, retryingMultiply(6, 7))
  }
}

trait Arithmetic {
  def apply(a: Int, b: Int): Int
}
class Multiply extends Arithmetic {
  def apply(a: Int, b: Int) = a * b
}

trait ThirtiethTimesACharm extends Arithmetic {
  private var invoCount = 0
  abstract override def apply(a: Int, b: Int) = {
    invoCount += 1
    if (invoCount < 30)
      throw new IllegalStateException
    else
      super.apply(a, b)
  }
}