package scuff.web

import javax.servlet._
import scuff._

/**
 * Typed cookie definition.
 */
trait CookieMonster {
  import java.util.concurrent.TimeUnit

  type T

  protected def clock: Clock = SystemClock

  /**
   * Use with `maxAge` for session cookies.
   */
  final val SessionOnly = -1

  /** Max age in seconds. */
  protected def maxAge: Int
  /** Convert Expires timestamp to MaxAge seconds, using current time. */
  protected final def toMaxAge(expires: Long, unit: TimeUnit) = (unit toSeconds clock.durationUntil(expires)(unit)).asInstanceOf[Int]
  protected def codec: Codec[T, String]
  protected def name: String
  /**
   * URL scope for cookie. Default is root.
   */
  protected def path: String = null

  /**
   * Domain scope for cookie.
   * Per the Cookie API: "By default, cookies are only returned to the server that sent them. "
   */
  protected def domain: String = null

  /**
   * Set value as cookie on response.
   */
  def set(res: http.HttpServletResponse, value: T) {
    val cookie = new http.Cookie(name, codec.encode(value))
    cookie.setMaxAge(maxAge)
    for (path ← Option(path)) cookie.setPath(path)
    for (domain ← Option(domain)) cookie.setDomain(domain)
    res.addCookie(cookie)
  }

  /**
   * Get value from cookie on request.
   */
  def get(request: http.HttpServletRequest): Option[T] = {
    Option(request.getCookies).flatMap { array ⇒
      array.find(_.getName == name).map(c ⇒ codec.decode(c.getValue))
    }
  }

  /**
   * Remove cookie.
   */
  def remove(res: http.HttpServletResponse) {
    val cookie = new http.Cookie(name, "")
    cookie.setMaxAge(0) // Remove cookie
    res.addCookie(cookie)
  }

}
