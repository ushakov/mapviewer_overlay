/**
 * 
 */
package org.ushmax.mapviewer.overlays;

import org.nativeutils.NativeUtils;
import org.ushmax.common.BufferAllocator;
import org.ushmax.common.ByteArraySlice;
import org.ushmax.common.Callback;
import org.ushmax.common.ImageUtils;
import org.ushmax.common.Logger;
import org.ushmax.common.LoggerFactory;
import org.ushmax.common.Pair;
import org.ushmax.fetcher.AsyncHttpFetcher;
import org.ushmax.fetcher.HttpFetcher.NetworkException;
import org.ushmax.geometry.GeoPoint;
import org.ushmax.geometry.MercatorReference;
import org.ushmax.geometry.MyMath;
import org.ushmax.geometry.Point;
import org.ushmax.geometry.Rectangle;
import org.ushmax.mapviewer.Overlay;
import org.ushmax.mapviewer.UiController;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;

public class YandexTrafficOverlay implements Overlay {
  private static final Logger logger = LoggerFactory.getLogger(YandexTrafficOverlay.class);
  private static final int tileSize = 256;
  private static final String BASE = "http://jgo.maps.yandex.net/tiles?l=trf";
  private static final String COOKIE_LOADING = "__LOADING__";
  private static final int HTTP_DEADLINE = 10000;
  private YandexReference myref = new YandexReference();
  private GeoPoint centerGeo = new GeoPoint();
  private Point originY = new Point();
  private Paint paint = new Paint();
  private TaggedBitmapCache<String> cache;
  private AsyncHttpFetcher httpFetcher;
  private int cookieUpdateInterval;
  // protected by this
  private String cacheCookieValue = null;
  private long cacheRenewalTime = 0;
  private UiController uiController;

  private class YandexTileInfo {
    int google_x;
    int google_y;
    int yandex_x;
    int yandex_y;
    int zoom;
  }

  public YandexTrafficOverlay(AsyncHttpFetcher httpFetcher, UiController uiController) {
    this.httpFetcher = httpFetcher;
    this.uiController = uiController;
    cache = new TaggedBitmapCache<String>(25);
    cookieUpdateInterval = 120000; // in ms
  }

  public void draw(Canvas canvas, int zoom, Point origin, Point size) {
    String cookie = checkCookie();
    // Don't load tiles if we don't have good cookie.
    if (cookie == null || cookie == COOKIE_LOADING) {
      return;
    }
    
    // This is mostly copied from TileView.drawRect()
    MercatorReference.toGeo(origin.x + size.x / 2, origin.y + size.y / 2, zoom,
        centerGeo);
    myref.fromGeo(centerGeo.lat, centerGeo.lng, zoom, originY);
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
        prefetchTile(info, cookie);
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
    StringBuilder lookupKey = new StringBuilder();
    lookupKey.append(x).append('|').append(y).append('|').append(zoom);
    String key = lookupKey.toString(); 
    synchronized (cache) {
      final TaggedBitmap entry = cache.get(key);
      if (entry == null) { 
        return null; 
      }
      return entry.bitmap;
    }
  }

  private String checkCookie() {
    long now = System.currentTimeMillis();
    boolean needLoadCookie = false;
    String cookie;
    synchronized (this) {
      if (now - cacheRenewalTime > cookieUpdateInterval  && cacheCookieValue != COOKIE_LOADING) {
        cacheCookieValue = COOKIE_LOADING;
        needLoadCookie = true;
      }
      cookie = cacheCookieValue;
    }
    if (needLoadCookie) {
      logger.debug("Fetching new cookie");
      httpFetcher.fetch("http://jgo.maps.yandex.net/trf/stat.js", 
          new Callback<Pair<ByteArraySlice, NetworkException>>() {
            @Override
            public void run(Pair<ByteArraySlice, NetworkException> result) {
              onReceiveNewCookie(result);
            }}, HTTP_DEADLINE);
    }
    return cookie;
  }

  private void onReceiveNewCookie(Pair<ByteArraySlice, NetworkException> result) {
    long now = System.currentTimeMillis();
    ByteArraySlice statData = result.first;
    if (statData == null) {
      logger.error("Error occured during fetching cookie: " + result.second);
      synchronized (this) {
        cacheCookieValue = null;
        cacheRenewalTime = now;
      }
      uiController.invalidate();
      return;
    }
    String statString = NativeUtils.decodeUtf8(statData.data, statData.start, statData.count);
    BufferAllocator.free(statData, "TrafficOverlay.onReceiveNewCookie");
    int start = statString.indexOf("timestamp:");
    start = statString.indexOf("\"", start) + 1;
    int end = statString.indexOf("\"", start);
    String newCookie = statString.substring(start, end);
    logger.debug("got cookie: " + newCookie);
    synchronized (this) {
      cacheCookieValue = newCookie;
      cacheRenewalTime = now;
    }
    uiController.invalidate();
  }

  private void prefetchTile(final YandexTileInfo info, String cookie) {
    StringBuilder lookupKey = new StringBuilder();
    lookupKey.append(info.yandex_x).append('|').append(info.yandex_y).append('|').append(info.zoom);
    final String key = lookupKey.toString();
    boolean haveThisKey = false;
    synchronized (cache) {
      // If entry is already in cache, the tile is already there or is being
      // loaded.
      haveThisKey = cache.hasKey(key);
      if (haveThisKey) {
        final TaggedBitmap entry = cache.get(key);
        if (entry == null ||
            entry.tag.equals(cookie)) {
          // We only return here if the cache entry is not expired.
          return;
        }
      } else {
        // Add an entry into cache to mark that tile is being loaded.
        // If we already have this key it means that we are refetching a new
        // tile after cache expiry, so don't remove the previous version until
        // the new is ready.
        cache.put(key, null);
      }
    }
    fetchTile(info);
  }

  private void fetchTile(final YandexTileInfo info) {
    String cookie;
    synchronized (this) {
      cookie = cacheCookieValue;
    }
    if (cookie == null || cookie == COOKIE_LOADING) {
      // Cookie is either obsolete or being loaded right now.
      return;
    }
    
    StringBuilder urlBuilder = new StringBuilder();
    urlBuilder.append(BASE);
    urlBuilder.append("&x=").append(info.yandex_x);
    urlBuilder.append("&y=").append(info.yandex_y);
    urlBuilder.append("&z=").append(info.zoom);
    urlBuilder.append("&tm=").append(cookie);
    final String url = urlBuilder.toString();
    
    // JAVACRAP: only final in closures
    final String cookieCopy = cookie;
    httpFetcher.fetch(url, new Callback<Pair<ByteArraySlice, NetworkException>>(){
      @Override
      public void run(Pair<ByteArraySlice, NetworkException> result) {
        onReceiveTile(result, info, url, cookieCopy);
      }}, HTTP_DEADLINE);
  }
    
  protected void onReceiveTile(Pair<ByteArraySlice, NetworkException> result, YandexTileInfo info, String url, String cookie) {
    ByteArraySlice tile = result.first;
    if (tile == null) {
      logger.info("Fetching tile failed: " + result.second);
      return;
    }
    if (!ImageUtils.mayBeImage(tile)) {
      BufferAllocator.free(tile, "TrafficOverlay.doGetTile");
      logger.debug("Broken image at " + url);
      return;
    }
    Bitmap bitmap = BitmapFactory.decodeByteArray(tile.data, tile.start, tile.count);
    BufferAllocator.free(tile, "TrafficOverlay.doGetTile");
    if (bitmap == null) {
      return;
    }
    StringBuilder lookupKey = new StringBuilder();
    lookupKey.append(info.yandex_x).append('|').append(info.yandex_y).append('|').append(info.zoom);
    final String k = lookupKey.toString();
    synchronized (cache) {
      TaggedBitmap entry = new TaggedBitmap();
      entry.bitmap = bitmap;
      entry.tag = cookie;
      cache.put(k, entry);
    }
    uiController.onUpdate(new Rectangle(info.google_x, info.google_y,
        info.google_x + 256, info.google_y + 256),
        info.zoom);
  }

  public boolean onTap(Point where, Point origin, int zoom) {
    return false;
  }

  public String name() {
    return "yandex_traffic";
  }

  public void free() {
    cache.clear();
  }
}