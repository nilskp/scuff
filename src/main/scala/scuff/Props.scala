package scuff

import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.jar.JarFile

/**
  * Look up properties from generic source, with generic fallback.
  */
class Props(metaName: String, getProperty: String => String, fallback: Props = null) {
  def optional(name: String, validValues: Set[String] = Set.empty): Option[String] = {
    var value = getProperty(name)
    if (value == null && fallback != null) {
      value = fallback.optional(name, validValues).orNull
    }
    if (value == null) {
      None
    } else if (!validValues.isEmpty && !validValues.contains(value)) {
      throw new IllegalStateException(s"The $metaName '$name' has invalid value '$value'; valid values: [${validValues.mkString(", ")}]")
    } else {
      Some(value)
    }
  }

  @throws(classOf[IllegalStateException])
  def required(name: String, validValues: Set[String] = Set.empty): String = optional(name, validValues) match {
    case None if validValues.isEmpty => throw new IllegalStateException(s"Required $metaName '$name' missing")
    case None => throw new IllegalStateException(s"Required $metaName '$name' missing; valid values: [${validValues.mkString(", ")}]")
    case Some(value) => value
  }

}

class SysProps(fallback: Props) extends Props("system property", System.getProperty, fallback)
object SysProps extends SysProps(null)
class EnvVars(fallback: Props) extends Props("environment variable", System.getenv, fallback)
object EnvVars extends EnvVars(null)
class ManifestAttributes(attrs: Attributes, fallback: Props) extends Props("manifest attribute", attrs.getValue _, fallback) {
  def this(manifest: Manifest, fallback: Props) = this(manifest.getMainAttributes, fallback)
}

object ManifestAttributes {
  import collection.JavaConverters._
  def apply(cl: ClassLoader = getClass.getClassLoader, fallback: Props = null): Option[Props] = {
    cl.getResources(JarFile.MANIFEST_NAME).asScala.foldLeft(None: Option[ManifestAttributes]) {
      case (chain, url) => Some {
        val stream = url.openStream()
        try {
          val manifest = new Manifest(stream)
          new ManifestAttributes(manifest, chain orElse Option(fallback) orNull)
        } finally stream.close()
      }
    }
  }
}

object Props {
  import java.io._
  def apply(file: File, fallback: Props = null) = {
    require(file.exists, "Must exist: " + file)
    require(file.isFile, "Must be a file: " + file)
    require(file.canRead, "Must be readable: " + file)
    val props = new java.util.Properties
    val fileReader = new FileReader(file)
    try {
      props.load(fileReader)
      new Props(s"${file.getName} property", props.getProperty, fallback)
    } finally {
      fileReader.close()
    }
  }
}
