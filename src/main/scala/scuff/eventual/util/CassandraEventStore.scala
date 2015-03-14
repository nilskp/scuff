package scuff.eventual.util

import com.datastax.driver.core._
import scala.reflect.ClassTag
import java.util.{ UUID, List => JList, ArrayList }
import scuff._
import scala.concurrent._
import scala.util.{ Try, Success, Failure }
import collection.JavaConverters._

private object CassandraEventStore {

  private def getUUID(row: Row, col: String): Any = row.getUUID(col)
  private def getString(row: Row, col: String): Any = row.getString(col)
  private def getLong(row: Row, col: String): Any = row.getLong(col)
  private def getInt(row: Row, col: String): Any = row.getInt(col)
  private def getBigInt(row: Row, col: String): Any = row.getVarint(col)

  private val CassandraIdGetters: Map[Class[_], (Row, String) => Any] = Map(
    classOf[UUID] -> getUUID,
    classOf[String] -> getString,
    classOf[Long] -> getLong,
    classOf[java.lang.Long] -> getLong,
    classOf[Int] -> getInt,
    classOf[java.lang.Integer] -> getInt,
    classOf[BigInt] -> getBigInt,
    classOf[java.math.BigInteger] -> getBigInt)

  private val CassandraIdTypes: Map[Class[_], String] = Map(
    classOf[UUID] -> "UUID",
    classOf[String] -> "TEXT",
    classOf[Long] -> "BIGINT",
    classOf[java.lang.Long] -> "BIGINT",
    classOf[Int] -> "INT",
    classOf[java.lang.Integer] -> "INT",
    classOf[BigInt] -> "VARINT",
    classOf[java.math.BigInteger] -> "VARINT")

  private def ensureTable[ID, CAT](session: Session, keyspace: String, table: String, replication: Map[String, Any])(implicit idType: ClassTag[ID], catType: ClassTag[CAT]) {
    val replicationStr = replication.map {
      case (key, str: CharSequence) => s"'$key':'$str'"
      case (key, any) => s"'$key':$any"
    }.mkString("{", ",", "}")
    val idTypeStr = CassandraIdTypes.getOrElse(idType.runtimeClass, sys.error(s"Unsupported ID type: $idType"))
    val catTypeStr = CassandraIdTypes.getOrElse(catType.runtimeClass, sys.error(s"Unsupported category type: $catType"))
    session.execute(s"CREATE KEYSPACE IF NOT EXISTS $keyspace WITH REPLICATION = $replicationStr")
    session.execute(s"""CREATE TABLE IF NOT EXISTS $keyspace.$table (
    		stream $idTypeStr,
    		revision INT,
    		timestamp BIGINT,
    		category $catTypeStr,
    		events LIST<TEXT>,
    		metadata MAP<TEXT,TEXT>,
    		PRIMARY KEY (stream, revision));""")
    session.execute(s"CREATE INDEX IF NOT EXISTS ON $keyspace.$table(timestamp)")
    session.execute(s"CREATE INDEX IF NOT EXISTS ON $keyspace.$table(category)")
  }

}

/**
 * Cassandra event store implementation.
 * WARNING: Not tested WHAT SO EVER.
 * If you're interested in contributing,
 * please try it out and report any problems.
 */
abstract class CassandraEventStore[ID, EVT, CAT](session: Session, keyspace: String, table: String, replication: Map[String, Any])(implicit idType: ClassTag[ID], catType: ClassTag[CAT])
    extends eventual.EventStore[ID, EVT, CAT] { expectedTrait: EventStorePublisher[ID, EVT, CAT] =>

  CassandraEventStore.ensureTable[ID, CAT](session, keyspace, table, replication)

  private val idGetter = CassandraEventStore.CassandraIdGetters(idType.runtimeClass)
  private val catGetter = CassandraEventStore.CassandraIdGetters(catType.runtimeClass)
  private def getID(row: Row): ID = idGetter(row, "stream").asInstanceOf[ID]
  private def getCategory(row: Row): CAT = catGetter(row, "category").asInstanceOf[CAT]

  protected def execCtx = Threads.Blocking
  protected def eventToString(evt: EVT): String
  protected def stringToEvent(evt: String): EVT

  private def toTransaction(row: Row): Transaction = {
    val id = getID(row)
    val timestamp = row.getLong("timestamp")
    val category = getCategory(row)
    val revision = row.getInt("revision")
    val metadata = {
      val map = row.getMap("metadata", classOf[String], classOf[String])
      if (map.isEmpty) {
        Map.empty[String, String]
      } else {
        map.asScala.toMap
      }
    }
    val events = row.getList("events", classOf[String]).asScala.iterator.map(stringToEvent).toList
    new Transaction(timestamp, category, id, revision, metadata, events)
  }

  private def execute[T](stm: BoundStatement)(handler: ResultSet => T): Future[T] = {
    val result = session.executeAsync(stm)
    val promise = Promise[T]
    val listener = new Runnable {
      def run = try {
        val rs = result.get
        promise success handler(rs)
      } catch {
        case e: Exception => promise failure e
      }
    }
    result.addListener(listener, execCtx)
    promise.future
  }

  private def query[T](stm: PreparedStatement, handler: Iterator[Transaction] => T, parms: Any*): Future[T] = {
    val refParms = parms.asInstanceOf[Seq[AnyRef]]
    val bound = stm.bind(refParms: _*)
    execute(bound) { rs =>
      val i = rs.iterator().asScala.map(toTransaction)
      handler(i)
    }
  }
  private def execute[T](stm: PreparedStatement, parms: Any*)(handler: ResultSet => T): Future[T] = {
    val refParms = parms.asInstanceOf[Seq[AnyRef]]
    val bound = stm.bind(refParms: _*)
    execute(bound)(handler)
  }

  @annotation.tailrec
  private def toStringList(events: List[_ <: EVT], list: JList[String] = new ArrayList[String]): JList[String] = events match {
    case Nil => list
    case head :: tail =>
      list add eventToString(head)
      toStringList(tail, list)
  }

  private val StreamExistsCheck = session.prepare(s"SELECT revision FROM $keyspace.$table WHERE stream = ? LIMIT 1")
  def exists(streamId: ID): Future[Boolean] = {
    execute(StreamExistsCheck, streamId)(_.one != null)
  }

  private val ReplayStream = session.prepare(s"SELECT * FROM $keyspace.$table WHERE stream = ? ORDER BY revision")
  def replayStream[T](streamId: ID)(callback: Iterator[Transaction] => T): Future[T] = {
    query(ReplayStream, callback, streamId)
  }

  private val ReplayStreamSince = session.prepare(s"SELECT * FROM $keyspace.$table WHERE stream = ? AND revision > ? ORDER BY revision")
  def replayStreamAfter[T](streamId: ID, afterRevision: Int)(callback: Iterator[Transaction] => T): Future[T] = {
    query(ReplayStreamSince, callback, streamId, afterRevision)
  }

  private val ReplayStreamTo = session.prepare(s"SELECT * FROM $keyspace.$table WHERE stream = ? AND revision <= ? ORDER BY revision")
  def replayStreamTo[T](streamId: ID, toRevision: Int)(callback: Iterator[Transaction] => T): Future[T] = {
    query(ReplayStreamTo, callback, streamId, toRevision)
  }

  private val ReplayStreamRange = session.prepare(s"SELECT * FROM $keyspace.$table WHERE stream = ? AND revision >= ? AND revision <= ? ORDER BY revision")
  def replayStreamRange[T](streamId: ID, revisionRange: collection.immutable.Range)(callback: Iterator[Transaction] => T): Future[T] = {
    query(ReplayStreamRange, callback, streamId, revisionRange.head, revisionRange.last)
  }

  private def newReplayStatement(categoryFilterCount: Int) = {
    val cql = categoryFilterCount match {
      case 0 =>
        s"SELECT * FROM $keyspace.$table ORDER BY timestamp"
      case 1 =>
        s"SELECT * FROM $keyspace.$table WHERE category = ? ORDER BY timestamp"
      case n =>
        val qs = Seq.fill(n)("?").mkString(",")
        s"SELECT * FROM $keyspace.$table WHERE category IN ($qs) ORDER BY timestamp"
    }
    session.prepare(cql)
  }
  private val Replay = new Multiton[Int, PreparedStatement](newReplayStatement)
  def replay[T](categories: CAT*)(callback: Iterator[Transaction] => T): Future[T] = {
    val stm = Replay(categories.size)
    query(stm, callback, categories: _*)
  }

  private def newReplayFromStatement(categoryFilterCount: Int) = {
    val cql = categoryFilterCount match {
      case 0 =>
        s"SELECT * FROM $keyspace.$table WHERE timestamp >= ? ORDER BY timestamp"
      case 1 =>
        s"SELECT * FROM $keyspace.$table WHERE timestamp >= ? AND category = ? ORDER BY timestamp"
      case n =>
        val qs = Seq.fill(n)("?").mkString(",")
        s"SELECT * FROM $keyspace.$table WHERE timestamp >= ? AND category IN ($qs) ORDER BY timestamp"
    }
    session.prepare(cql)
  }
  private val ReplayFrom = new Multiton[Int, PreparedStatement](newReplayFromStatement)
  def replayFrom[T](fromTimestamp: Long, categories: CAT*)(callback: Iterator[Transaction] => T): Future[T] = {
    val stm = ReplayFrom(categories.size)
    val parms = fromTimestamp +: categories
    query(stm, callback, parms)
  }

  private val RecordTransaction =
    session.prepare(s"INSERT INTO $keyspace.$table (timestamp, stream, revision, category, events, metadata) VALUES(?,?,?,?,?,?) IF NOT EXISTS")
  def record(timestamp: Long, category: CAT, stream: ID, revision: Int, events: List[_ <: EVT], metadata: Map[String, String]): Future[Transaction] = {
    val jEvents = events.map(eventToString).asJava
    val jMetadata = metadata.asJava
    val future = execute(RecordTransaction, stream, revision, category, jEvents, jMetadata) { rs =>
      if (rs.wasApplied) {
        new Transaction(timestamp, category, stream, revision, metadata, events)
      } else {
        val conflicting = toTransaction(rs.one)
        throw new DuplicateRevisionException(stream, conflicting)
      }
    }
    future.andThen {
      case Success(txn) =>
        try publish(txn) catch { case e: Exception => execCtx.reportFailure(e) }
    }(execCtx)
    future
  }
//  private def tryAppend(category: CAT, stream: ID, revision: Int, events: List[_ <: EVT], metadata: Map[String, String]): Future[Transaction] =
//    record(category, stream, revision, events, metadata).recoverWith {
//      case _: DuplicateRevisionException => tryAppend(category, stream, revision + 1, events, metadata)
//    }(execCtx)

  private val FetchLastRevision = session.prepare(s"SELECT revision FROM $keyspace.$table WHERE stream = ? ORDER BY revision DESC LIMIT 1")
//  def append(category: CAT, stream: ID, events: List[_ <: EVT], metadata: Map[String, String]): Future[Transaction] = {
//    val revision = execute(FetchLastRevision, stream) { rs =>
//      rs.one match {
//        case null => 0
//        case row => row.getInt(0) + 1
//      }
//    }
//    revision.flatMap { revision =>
//      tryAppend(category, stream, revision, events, metadata)
//    }(execCtx)
//  }

}

