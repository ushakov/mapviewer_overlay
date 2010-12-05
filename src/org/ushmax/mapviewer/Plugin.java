package org.ushmax.mapviewer;

import org.ushmax.common.Factory;
import org.ushmax.common.Registry;
import org.ushmax.mapviewer.overlays.YandexTrafficOverlay;

public class Plugin implements AbstractPlugin {

  public void onLoad(final MapViewerApp app) {
    Registry<Overlay, ActivityData> registry = app.overlayRegistry;
    registry.register("yandex_traffic", new Factory<Overlay, ActivityData>() {
      public Overlay create(ActivityData activityData) {
        return new YandexTrafficOverlay(app.asyncHttpFetcher, activityData.uiController);
      }
    });
  }

  public void onUnLoad(MapViewerApp app) {
    Registry<Overlay, ActivityData> registry = app.overlayRegistry;
    registry.unregister("yandex_traffic");
  }
}
