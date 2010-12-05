package org.ushmax.mapviewer.overlays;

public final class Point4 {
  public int x;
  public int y;
  public int zoom;
  public int cookie;

  // Empty constructor.
  public Point4() {
  }

  public Point4(int x, int y, int zoom, int cookie) {
    this.x = x;
    this.y = y;
    this.zoom = zoom;
    this.cookie = cookie;
  }

  // Copy constructor.
  public Point4(Point4 src) {
    x = src.x;
    y = src.y;
    zoom = src.zoom; 
    cookie = src.cookie;
  }

  @Override
  public String toString() {    
    return "" + x + "," + y + "@" + zoom + ":" + cookie;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if ((obj == null) || (obj.getClass() != getClass())) {
      return false;
    }
    Point4 addr = (Point4) obj;
    return (x == addr.x) && (y == addr.y) && (zoom == addr.zoom) && (cookie == addr.cookie);
  }

  @Override
  public int hashCode() {
    return x + 109 * y + 5 * zoom + 1341 * cookie;
  }
}
