package scuff.eventual.ddd

import scuff.ddd._

/**
 * Trait that enables snapshotting if load time exceed a
 * certain threshold.
 */
trait LoadTimeSnapshots[ID, AR <: AggregateRoot, CAT] extends EventStoreRepository[ID, AR, CAT] {

  /**
   * Load threshold (in ms) to trigger snapshot.
   */
  protected def loadThreshold: Long

  private[this] val doSnapshot = new scuff.LockFreeConcurrentMap[AR#ID, Long]

  protected abstract override def onLoadNotification(id: AR#ID, revision: Long, timeMs: Long) {
    if (timeMs >= loadThreshold) {
      doSnapshot.putIfAbsent(id, revision)
    }
    super.onLoadNotification(id, revision, timeMs)
  }

  protected abstract override def saveSnapshot(id: AR#ID, revision: Long, state: S) {
    if (doSnapshot.remove(id, revision)) {
      super.saveSnapshot(id, revision, state)
    }
  }

}