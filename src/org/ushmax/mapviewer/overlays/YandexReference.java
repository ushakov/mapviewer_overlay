package org.ushmax.mapviewer.overlays;

import org.ushmax.common.Logger;
import org.ushmax.common.LoggerFactory;
import org.ushmax.geometry.GeoPoint;
import org.ushmax.geometry.Point;

public class YandexReference {
  private static final Logger logger = LoggerFactory.getLogger(YandexReference.class);
  private static final long RADIUS_A = 6378137;
  private static final long RADIUS_B = 6356752;
  private static final double EPS = 1e-10;
  private static final double EXCENTR =
    Math.sqrt(RADIUS_A * RADIUS_A - RADIUS_B * RADIUS_B) / RADIUS_A;

  public YandexReference() {
  }
  
  public void toGeo(int x, int y, int zoom, GeoPoint resultf) {
    double px = x;
    double py = y;
    final double scale = Math.pow(2, zoom);

    px /= scale * 256;
    py /= scale * 256;

    double lng = 360 * (px - 0.5f);

    double dy = -2 * Math.PI * (py - 0.5);
    double iter = ((2 * Math.atan(Math.exp(dy)) - Math.PI / 2) * 180 / Math.PI) / (180 / Math.PI);
    double prev = -100;

    while (Math.abs(prev - iter) >= EPS) {
      logger.debug("" + (prev-iter));
      prev = iter;
      iter = Math.asin(
            1 - ((1 + Math.sin(prev)) * Math.pow(1 - EXCENTR * Math.sin(prev), EXCENTR))
                / (Math.exp(2 * dy) * Math.pow(1 + EXCENTR * Math.sin(prev), EXCENTR)));
    }

    resultf.lat = (float)(iter * 180 / Math.PI);
    resultf.lng = (float)lng;
  }

  public void fromGeo(float lat, float lng, int zoom, Point result) {
    final double lat_rad = (double) lat * Math.PI / 180;
    final double lat_merc = 
        (double)Math.log((1 + Math.sin(lat_rad)) / (1 - Math.sin(lat_rad))) / 2
      - EXCENTR * Math.log((1 + EXCENTR * Math.sin(lat_rad)) / (1 - EXCENTR * Math.sin(lat_rad))) / 2;
    final double py = 0.5 - lat_merc / (2*Math.PI);
    final double scale = Math.pow(2, zoom) * 256;
    result.y = (int)(scale * py);
    result.x = (int)((lng + 180) / 360 * scale);
  }
}