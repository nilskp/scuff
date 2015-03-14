package scuff

import java.util.{Locale, ResourceBundle, PropertyResourceBundle, MissingResourceException, MissingFormatArgumentException, Currency, TimeZone}
import java.text.MessageFormat
import java.nio.charset.Charset
import scala.util.Try

/**
 * Class that combines a `.properties` file with a formatter.
 * <p>Example:
 * {{{
 *   class Text extends L10nPropFormatter
 * }}}
 * Create file `Text.properties` in the same package as class `Text`:
 * {{{
 *   msg.hello = Hello World! Today is {0}
 * }}}
 * And, voila, the property values are accessible through `apply`:
 * {{{
 *   val messages = new Text
 *   val msg = messages("label.hello", new java.util.Date)
 *   println(msg)
 * }}}
 * This class has per-key fallback behavior.
 * This means that more specific locale files (country and variant)
 * need not have all the keys, but merely the keys that differ.
 *
 * Synchronization: This class is thread-safe and can be used concurrently.
 *
 * NOTICE: Unlike [[java.text.MessageFormat]] this class expects tick,
 * not apostrophe, for escaping text inside the curly brackets formatting.
 *
 * @see java.text.MessageFormat
 * For text formatting syntax
 * @see java.util.PropertyResourceBundle
 * For locale specific `.properties` file name syntax
 * @author Nils Kilden-Pedersen
 */
class L10nPropFormatter private (_baseName: Option[String], desiredLocales: Seq[Locale], charset: Charset)
    extends ((String, Any*) => String) {

  /**
   * Subclass constructor. Used when sub-classing is used for location and naming.
   * @param desiredLocale The desired locale. May use a fallback locale if specific locale is not found
   * @param charset Override default properties charset of UTF-8
   */
  protected def this(desiredLocales: Seq[Locale] = Seq(Locale.getDefault), charset: Charset = L10nPropFormatter.ISO_8859_1) = this(None, desiredLocales, charset)

  private[this] val control = new CharsetControl(charset, getClass.getClassLoader)

  private[this] val baseName = _baseName match {
    case Some(bn) => bn
    case None => {
      val name = getClass.getName
      if (name endsWith "$") name.substring(0, name.length - 1) else name
    }
  }

  private class Message(strFmt: String, val locale: Locale) {
    def unformatted = strFmt
    val parmCount = L10nPropFormatter.countParms(strFmt)
    private val stringFormat = if (parmCount == 0) strFmt else strFmt.replace("'", "''").replace('`', '\'')
    val formatter = new ThreadLocal[MessageFormat]() {
      override def initialValue = new MessageFormat(stringFormat, locale)
    }
    override def toString = s"${locale.toLanguageTag}: $strFmt"
  }

  private val map: Map[String, Message] = {
      def findText(key: String, bundles: Seq[ResourceBundle]): Option[(String, Locale)] = {
        bundles.iterator.map { bundle =>
          Try(bundle.getString(key) -> bundle.getLocale).toOption
        }.collectFirst {
          case Some(t) => t
        }
      }
    val bundles = L10nPropFormatter.toResourceBundles(baseName, desiredLocales, control)
    val allKeys = bundles.flatMap(e => collection.JavaConversions.enumerationAsScalaIterator(e.getKeys).toSeq).toSet
    allKeys.foldLeft(Map.empty[String, Message]) {
      case (map, key) => findText(key, bundles).map {
        case (fmt, lang) => map.updated(key, new Message(fmt, lang))
      }.getOrElse(map)
    }
  }

  /**
   * Lookup string based on the supplied key, and optionally
   * perform formatting if parameters are supplied.
   * @param key The lookup key
   * @param parms The optional formatting parameters
   * @return The string, formatted if applicable
   * @throws MissingResourceException if key is unknown
   * @throws MissingFormatArgumentException
   * if the number of supplied parameters do not match the expected number of parameters
   * @throws IllegalArgumentException if the parameters otherwise fail to format the message
   */
  def apply(key: String, parms: Any*): String = this.get(key, parms: _*).getOrElse {
    throw new MissingResourceException("Cannot find \"" + key + "\"", baseName, key)
  }

  @annotation.tailrec
  private def checkParms(lang: Locale, a: Array[Any], i: Int = 0): Array[Any] = {
    if (i < a.length) {
      a(i) match {
        case l: Locale =>
          a(i) = l.getDisplayName(lang)
        case tz: TimeZone =>
          a(i) = tz.getDisplayName(lang)
        case f: Function0[_] =>
          a(i) = f()
        case f: Function1[Locale, _] =>
          a(i) = f(lang)
        case _ => // Ignore
      }
      checkParms(lang, a, i + 1)
    } else {
      a
    }
  }

  def get(key: String, parms: Any*): Option[String] = map.get(key).map { msg =>
    if (msg.parmCount != parms.length) {
      throw new MissingFormatArgumentException(s"Message '$key' expects ${msg.parmCount} parameters, but received ${parms.length}")
    }
    if (msg.parmCount == 0) {
      msg.unformatted
    } else {
      val array = checkParms(msg.locale, parms.toArray)
      msg.formatter.get.format(array)
    }
  }

  def keySet() = map.keySet

  lazy val unformatted: Map[String, String] = map.map {
    case (key, msg) => key -> msg.unformatted
  }

  def unformatted(key: String): Option[String] = map.get(key).map(_.unformatted)

}

object L10nPropFormatter {

  final val ISO_8859_1 = Charset.forName("ISO-8859-1")
  final val UTF_8 = Charset.forName("UTF-8")

  private val parmFinder = java.util.regex.Pattern compile """\{(\d+)[^\}]*\}"""
  private def countParms(format: CharSequence): Int = {
    var set = collection.SortedSet()(scala.math.Ordering.Int.reverse)
    val matcher = parmFinder.matcher(format)
    while (matcher.find) {
      set += matcher.group(1).toInt
    }
    set.headOption.foreach { h =>
      if (h + 1 != set.size) throw new IndexOutOfBoundsException("Parameter mismatch: \"" + format + "\"")
    }
    set.size
  }
  private def toResourceBundles(baseName: String, locales: Seq[Locale], control: CharsetControl): Seq[ResourceBundle] = {
    val expandedLocales =
        locales.flatMap { locale =>
          val buffer = new collection.mutable.ArrayBuffer[Locale](3)
          buffer += locale
          if (locale.getVariant.length > 0) {
            buffer += new Locale(locale.getLanguage, locale.getCountry)
          }
          if (locale.getCountry.length > 0) {
            buffer += new Locale(locale.getLanguage)
          }
          buffer
        } :+ Locale.ROOT
    expandedLocales.distinct.foldLeft(collection.mutable.Buffer[ResourceBundle]()) {
      case (list, locale) =>
        try {
          val bundle = ResourceBundle.getBundle(baseName, locale, control)
          if (bundle.getLocale == locale) {
            list :+ bundle
          } else {
            list
          }
        } catch {
          case e: MissingResourceException => list
        }
    }
  }

  /**
   * Construct instance from specific package, using non-class based name.
   * @param location The package location
   * @param baseName The `.properties` base name.
   * @param desiredLocale The preferred locale
   * @param charset The properties encoding
   * @return new instance
   */
  def apply(location: Option[Package], baseName: String, desiredLocales: Seq[Locale], charset: Charset) = {
    val packagePrefix = location.map(_.getName concat ".").getOrElse("")
    new L10nPropFormatter(Some(packagePrefix concat baseName), desiredLocales, charset)
  }

  def root(name: String, desiredLocales: Seq[Locale] = Seq(Locale.getDefault), charset: Charset = ISO_8859_1) = {
    new L10nPropFormatter(Some(name), desiredLocales, charset)
  }

  /**
   * Construct instance to match the provided class, which is not a
   * subclass of [[PropertiesFormatter]].
   * @param baseName The class which name will match `.properties` file in same package
   * @param desiredLocale The preferred locale
   * @return new instance
   */
  def apply(baseName: Class[_], desiredLocales: Seq[Locale] = Seq(Locale.getDefault), charset: Charset = ISO_8859_1) =
    new L10nPropFormatter(Some(baseName.getName), desiredLocales, charset)

}

private class CharsetControl(charset: Charset, altLoader: ClassLoader) extends ResourceBundle.Control {
  import java.io._
  import java.security._
  override def newBundle(baseName: String, locale: Locale, format: String, loader: ClassLoader, reload: Boolean): ResourceBundle = {
    val bundleName = toBundleName(baseName, locale)
    var bundle: ResourceBundle = null
    format match {
      case "java.class" =>
        try {
          val bundleClass = loader.loadClass(bundleName).asInstanceOf[Class[_ <: ResourceBundle]]
          // If the class isn't a ResourceBundle subclass, throw a
          // ClassCastException.
          if (classOf[ResourceBundle].isAssignableFrom(bundleClass)) {
            bundle = bundleClass.newInstance()
          } else {
            throw new ClassCastException(bundleClass.getName()
              + " cannot be cast to ResourceBundle")
          }
        } catch {
          case _: ClassNotFoundException => // Ignore
        }
      case "java.properties" =>
        val resourceName = toResourceName(bundleName, "properties")
        val classLoader = loader
        val reloadFlag = reload
        var stream: InputStream = null
        try {
          stream = AccessController.doPrivileged(
            new PrivilegedExceptionAction[InputStream] {
              def run: InputStream = {
                var is: InputStream = null
                if (reloadFlag) {
                  val url = classLoader.getResource(resourceName)
                  if (url != null) {
                    val connection = url.openConnection()
                    if (connection != null) {
                      // Disable caches to get fresh data for
                      // reloading.
                      connection.setUseCaches(false)
                      is = connection.getInputStream()
                    }
                  }
                } else {
                  is = classLoader.getResourceAsStream(resourceName)
                }
                return is
              }
            })
        } catch {
          case e: PrivilegedActionException => throw e.getException()
        }
        if (stream != null) {
          try {
            bundle = new PropertyResourceBundle(new InputStreamReader(stream, charset))
          } finally {
            stream.close()
          }
        }
      case _ =>
        throw new IllegalArgumentException("unknown format: " + format)
    }
    if (bundle == null && loader != altLoader) {
      newBundle(baseName, locale, format, altLoader, reload)
    } else {
      bundle
    }
  }
}
