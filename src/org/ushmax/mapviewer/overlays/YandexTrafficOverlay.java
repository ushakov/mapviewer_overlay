/**
 * 
 */
package org.ushmax.mapviewer.overlays;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.ushmax.mapviewer.MercatorReference;
import org.ushmax.mapviewer.MyMath;
import org.ushmax.mapviewer.Overlay;
import org.ushmax.mapviewer.Task;
import org.ushmax.mapviewer.TaskDispatcher;
import org.ushmax.mapviewer.TaskType;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Log;

class YandexTrafficOverlay extends Overlay {
  private static final int tileSize = 256;
  private static final String BASE = "http://trf.maps.yandex.net/tiles?l=trf";
  private YandexReference myref = new YandexReference();
  private PointF centerGeo = new PointF();
  private Point originY = new Point();
  private Paint paint = new Paint();
  private TaggedBitmapCache<String> cache = new TaggedBitmapCache<String>(20);
  private StringBuilder lookupKey = new StringBuilder();
  private StringBuilder url = new StringBuilder();
  private TaskDispatcher taskDispatcher;
  private long cacheRenewalTime = 0;
  private String cacheCookieValue = null;
  
  private class YandexTileInfo {
    int google_x;
    int google_y;
    int yandex_x;
    int yandex_y;
    int zoom;
  }

  public YandexTrafficOverlay(TaskDispatcher taskDispatcher) {
    this.taskDispatcher = taskDispatcher;
  }
  
  @Override
  public void draw(Canvas canvas, int zoom, Point origin,
      Point size) {
    // This is mostly copied from TileView.drawRect()
    MercatorReference.toGeo(origin.x + size.x / 2, origin.y + size.y / 2, zoom,
        centerGeo);
    myref.fromGeo(centerGeo.x, centerGeo.y, zoom, originY);
    originY.x -= size.x / 2;
    originY.y -= size.y / 2;

    int leftTile = MyMath.div(originY.x, tileSize);
    int topTile = MyMath.div(originY.y, tileSize);
    int rightTile = MyMath.div(originY.x + size.x + tileSize - 1, tileSize);
    int bottomTile = MyMath.div(originY.y + size.y + tileSize - 1, tileSize);

    for (int py = topTile; py < bottomTile; py++) {
      for (int px = leftTile; px < rightTile; px++) {
        YandexTileInfo info = new YandexTileInfo();
        info.google_x = px * tileSize - originY.x + origin.x;
        info.google_y = py * tileSize - originY.y + origin.y;
        info.yandex_x = px;
        info.yandex_y = py;
        info.zoom = zoom;
        prefetchTile(info);
      }
    }

    for (int y = topTile; y < bottomTile; y++) {
      for (int x = leftTile; x < rightTile; x++) {
        Bitmap currentMapTile = getTile(x, y, zoom);
        // getTile returns null on tiles that are not loaded yet.
        if (currentMapTile != null) {
          int tileLeft = x * tileSize - originY.x;
          int tileTop = y * tileSize - originY.y;
          canvas.drawBitmap(currentMapTile, tileLeft, tileTop, paint);
        }
      }
    }
  }

  private Bitmap getTile(int x, int y, int zoom) {
    synchronized (cache) {
      lookupKey.setLength(0);
      lookupKey.append(x).append('|').append(y).append('|').append(zoom);
      final TaggedBitmap entry = cache.get(lookupKey.toString());
      if (entry == null) { 
        return null; 
      }
      return entry.bitmap;
    }
  }

  private boolean checkCookie() {
    synchronized (this) {
      long now = System.currentTimeMillis();
      if (now - cacheRenewalTime > 60000) {
        cacheRenewalTime = now;
        cacheCookieValue = null;
        return true;
      }
      return false;
    }
  }

  private void prefetchTile(final YandexTileInfo info) {
    boolean haveThisKey = false;
    final String key;
    checkCookie();
    synchronized (cache) {
      lookupKey.setLength(0);
      lookupKey.append(info.yandex_x).append('|').append(info.yandex_y).append('|').append(info.zoom);
      // If entry is already in cache, the tile is already there or is being
      // loaded.
      key = lookupKey.toString();
      haveThisKey = cache.hasKey(key);
      if (haveThisKey) {
        final TaggedBitmap entry = cache.get(key);
        if (entry == null ||
            entry.tag.equals(cacheCookieValue)) {
          // We only return here if the cache entry is not expired.
          return;
        }
      }
    }
    synchronized (cache) {
      if (!haveThisKey) {
        // Add an entry into cache to mark that tile is being loaded.
        // If we already have this key it means that we are refetching a new
        // tile after cache expiry, so don't remove the previous version until
        // the new is ready.
        cache.put(key, null);
      }
    }
    taskDispatcher.addTask(TaskType.NETWORK, new Task() {
      public void execute() {
        fetchTile(info);
      }

      public String toString() {
        return "YandexTrafficOverlay.getTile(" +
            info.yandex_x + "," + info.yandex_y + "@" + info.zoom + ")";
      }
    });
  }

  protected void fetchTile(YandexTileInfo info) {
    String cookie;
    synchronized (this) {
      if (cacheCookieValue == null) {
        getNewCookie();
      }
      cookie = cacheCookieValue;
    }
    Bitmap ret = doGetTile(info.yandex_x, info.yandex_y, info.zoom, cookie);
    if (ret == null) {
      return;
    }
    synchronized (cache) {
      lookupKey.setLength(0);
      lookupKey.append(info.yandex_x).append('|').append(info.yandex_y).append('|').append(info.zoom);
      String k = lookupKey.toString();
      TaggedBitmap entry = new TaggedBitmap();
      entry.bitmap = ret;
      entry.tag = cookie;
      cache.put(k, entry);
    }
    onLayerUpdate(new Rect(info.google_x, info.google_y,
                           info.google_x + 256, info.google_y + 256),
                  info.zoom);
  }

  private Bitmap doGetTile(int x, int y, int zoom, String cookie) {
    url.setLength(0);
    url.append(BASE);
    url.append("&x=").append(x);
    url.append("&y=").append(y);
    url.append("&z=").append(zoom);
    url.append("&tm=").append(cookie);
    Log.d("yandex", "loading: " + url.toString());
    final ByteArrayOutputStream urlContent = getURLContent(url.toString());
    if (urlContent == null) {
      return null;
    }
    byte[] content = urlContent.toByteArray();
    return BitmapFactory.decodeByteArray(content, 0, content.length);
  }

  private void getNewCookie() {
      final String str = getURLContent("http://trf.maps.yandex.net/trf/stat.js").toString();
      int start = str.indexOf("timestamp:");
      start = str.indexOf("\"", start) + 1;
      int end = str.indexOf("\"", start);
      cacheCookieValue= str.substring(start, end);
      Log.d("yandex", "got cookie: " + cacheCookieValue);
  }

  private ByteArrayOutputStream getURLContent(final String url) {
    try {
      InputStream inStream = new URL(url).openStream();
      ByteArrayOutputStream outStream = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      while (true) {
        int numBytes = inStream.read(buf);
        if (numBytes < 0) break;
        outStream.write(buf, 0, numBytes);
      }
      return outStream;
    } catch (IOException e) {
      return null;
    } 
  }

  @Override
  public boolean onTap(Point where, Point origin, int zoom) {
    return false;
  }

}