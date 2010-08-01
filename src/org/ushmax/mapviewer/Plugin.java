package org.ushmax.mapviewer;

import org.ushmax.mapviewer.overlays.YandexTrafficOverlay;

import android.content.SharedPreferences;
import android.content.res.Resources;

public class Plugin implements AbstractPlugin {

  public void onLoad(ObjectManager manager) {
    OverlayRegistry registry = manager.overlayRegistry;
    registry.registerOverlayFactory(new OverlayFactory() {
      public String name() {
        return "yandex_traffic";
      }

      public Overlay createOverlay(ObjectManager manager, SharedPreferences prefs, Resources resources) {
        return new YandexTrafficOverlay(manager.taskDispatcher);
      }
    });
  }

  public void onUnLoad(ObjectManager manager) {
    OverlayRegistry registry = manager.overlayRegistry;
    registry.unregisterOverlayFactory("yandex_traffic");
  }
}
