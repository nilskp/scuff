package scuff

import org.junit._
import org.junit.Assert._

class TestGeoPoint {
  @Test
  def `dot decimal` {
    assertEquals(new GeoPoint(-23.34534f, 12.54642f), GeoPoint.parse("-23.34534, 12.54642").get)
    assertEquals(new GeoPoint(23.34534f, -12.54642f), GeoPoint.parse("23.34534 -12.54642").get)
    assertEquals(new GeoPoint(-23.34534f, -12.54642f), GeoPoint.parse("-23.34534 -12.54642").get)
    assertEquals(new GeoPoint(124.3453454654631798f, 85.5464245621354678f), GeoPoint.parse("124.3453454654631798 85.5464245621354678").get)
  }

  @Test
  def `comma decimal` {
    assertEquals(new GeoPoint(-23.34534f, 12.54642f), GeoPoint.parse("-23,34534 : 12,54642").get)
    assertEquals(new GeoPoint(23.34534f, -12.54642f), GeoPoint.parse("23,34534 -12,54642").get)
    assertEquals(new GeoPoint(-23.34534f, -12.54642f), GeoPoint.parse("-23,34534 -12,54642").get)
    assertEquals(new GeoPoint(124.3453454654631798f, 85.5464245621354678f), GeoPoint.parse("124,3453454654631798, 85.5464245621354678").get)
  }

  @Test()
  def `out of positive bounds` {
    assertEquals(None, GeoPoint.parse("-23,34534 : 180,54642"))
  }

  @Test()
  def `out of negative bounds` {
    assertEquals(None, GeoPoint.parse("-230.34534 : 179,54642"))
  }
}