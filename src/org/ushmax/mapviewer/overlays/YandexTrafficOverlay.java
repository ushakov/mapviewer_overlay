/**
 * 
 */
package org.ushmax.mapviewer.overlays;

import org.nativeutils.NativeUtils;
import org.ushmax.android.BitmapCache;
import org.ushmax.common.BufferAllocator;
import org.ushmax.common.ByteArraySlice;
import org.ushmax.common.Callback;
import org.ushmax.common.ImageUtils;
import org.ushmax.common.Logger;
import org.ushmax.common.LoggerFactory;
import org.ushmax.common.Pair;
import org.ushmax.fetcher.AsyncHttpFetcher;
import org.ushmax.fetcher.HttpFetcher;
import org.ushmax.fetcher.HttpFetcher.MHttpRequest;
import org.ushmax.fetcher.HttpFetcher.NetworkException;
import org.ushmax.geometry.EllipsoidCoordMapping;
import org.ushmax.geometry.FastMercator;
import org.ushmax.geometry.GeoPoint;
import org.ushmax.geometry.MercatorReference;
import org.ushmax.geometry.MyMath;
import org.ushmax.geometry.Point;
import org.ushmax.geometry.Point4;
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
  private static final int COOKIE_LOADING = -1;
  private static final int HTTP_DEADLINE = 60000;
  private EllipsoidCoordMapping yaRef = new EllipsoidCoordMapping();
  private GeoPoint centerGeo = new GeoPoint();
  private Point originY = new Point();
  private Paint paint = new Paint();
  private BitmapCache<Point4> cache;
  private AsyncHttpFetcher httpFetcher;
  private int cookieUpdateInterval;
  // protected by this
  private int cacheCookieValue = 0;
  private long cacheRenewalTime = 0;
  private UiController uiController;

  private static class YandexTileInfo {
    int google_x;
    int google_y;
    int yandex_x;
    int yandex_y;
    int zoom;
    int cookie;
  }
 
  public YandexTrafficOverlay(AsyncHttpFetcher httpFetcher, UiController uiController) {
    this.httpFetcher = httpFetcher;
    this.uiController = uiController;
    cache = new BitmapCache<Point4>(25);
    cookieUpdateInterval = 120000; // in ms
  }

  public void draw(Canvas canvas, int zoom, Point origin, Point size) {
    int cookie = checkCookie();
    // Don't load tiles if we don't have good cookie.
    if (cookie <= 0) {
      return;
    }
    
    // This is mostly copied from TileView.drawRect()
    MercatorReference.toGeo(origin.x + size.x / 2, origin.y + size.y / 2, zoom,
        centerGeo);
    yaRef.fromGeo(centerGeo.lat, centerGeo.lng, zoom, originY);
    originY.x -= size.x / 2;
    originY.y -= size.y / 2;

    int leftTile = MyMath.div(originY.x, tileSize);
    int topTile = MyMath.div(originY.y, tileSize);
    int rightTile = MyMath.div(originY.x + size.x + tileSize - 1, tileSize);
    int bottomTile = MyMath.div(originY.y + size.y + tileSize - 1, tileSize);
    
    Point4 point = new Point4();
    point.cookie = cookie;
    point.zoom = zoom;
    
    for (int y = topTile; y < bottomTile; y++) {
      for (int x = leftTile; x < rightTile; x++) {
        point.x = x;
        point.y = y;
        Bitmap tile = getTile(point);
        // getTile returns null on tiles that are not loaded yet.
        if (tile != null) {
          int tileLeft = x * tileSize - originY.x;
          int tileTop = y * tileSize - originY.y;
          canvas.drawBitmap(tile, tileLeft, tileTop, paint);
        }
      }
    }
  }

  private Bitmap getTile(Point4 point) {
    synchronized (cache) {
      // If entry is already in cache, the tile is already there or is being
      // loaded.
      if (cache.hasKey(point)) {
        Bitmap entry = cache.get(point);
        // Null value indicates that tile is being loaded.
        if (entry == null) {
          return null;
        }
        return entry;
      }
      // Add a null entry into cache to mark that tile is being loaded.
      Point4 pointCopy = new Point4(point);
      cache.put(pointCopy, null);
    }
    fetchTile(point);
    return null;
  }

  private int checkCookie() {
    long now = System.currentTimeMillis();
    boolean needLoadCookie = false;
    int cookie;
    synchronized (this) {
      if (now - cacheRenewalTime > cookieUpdateInterval  && cacheCookieValue != COOKIE_LOADING) {
        cacheCookieValue = COOKIE_LOADING;
        needLoadCookie = true;
      }
      cookie = cacheCookieValue;
    }
    if (needLoadCookie) {
      logger.debug("Fetching new cookie");
      MHttpRequest req = new MHttpRequest();
      req.method = HttpFetcher.Method.GET;
      req.url = "http://jgo.maps.yandex.net/trf/stat.js";
      httpFetcher.fetch(req,
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
        cacheCookieValue = 0;
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
    String newCookieStr = statString.substring(start, end);
    logger.debug("got cookie: " + newCookieStr);
    int newCookie = Integer.parseInt(newCookieStr);
    synchronized (this) {
      cacheCookieValue = newCookie;
      cacheRenewalTime = now;
    }
    uiController.invalidate();
  }

  private void fetchTile(Point4 point) {
    StringBuilder urlBuilder = new StringBuilder();
    urlBuilder.append(BASE);
    urlBuilder.append("&x=").append(point.x);
    urlBuilder.append("&y=").append(point.y);
    urlBuilder.append("&z=").append(point.zoom);
    urlBuilder.append("&tm=").append(point.cookie);
    final String url = urlBuilder.toString();
    final YandexTileInfo info = new YandexTileInfo();
    
    GeoPoint geo = new GeoPoint();
    yaRef.toGeo(point.x * tileSize, point.y * tileSize, point.zoom, geo);
    int zoomShift = 20 - point.zoom;
    info.google_x = FastMercator.projectLng((int)(geo.lng * 1e+7)) >> zoomShift;
    info.google_y = FastMercator.projectLat((int)(geo.lat * 1e+7)) >> zoomShift;
    info.yandex_x = point.x;
    info.yandex_y = point.y;
    info.zoom = point.zoom;
    info.cookie = point.cookie;
    
    MHttpRequest req = new MHttpRequest();
    req.method = HttpFetcher.Method.GET;
    req.url = url;
    httpFetcher.fetch(req, new Callback<Pair<ByteArraySlice, NetworkException>>(){
      @Override
      public void run(Pair<ByteArraySlice, NetworkException> result) {
        onReceiveTile(result, info, url);
      }}, HTTP_DEADLINE);
  }
    
  protected void onReceiveTile(Pair<ByteArraySlice, NetworkException> result, YandexTileInfo info, String url) {
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
    Point4 key = new Point4(info.yandex_x, info.yandex_y, info.zoom, info.cookie);
    synchronized (cache) {
      cache.put(key, bitmap);
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