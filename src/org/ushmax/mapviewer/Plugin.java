package org.ushmax.mapviewer;

import org.ushmax.common.Factory;
import org.ushmax.common.Registry;
import org.ushmax.mapviewer.overlays.YandexTrafficOverlay;

public class Plugin implements AbstractPlugin {

  public void onLoad(ObjectManager manager) {
    Registry<Overlay, ActivityData> registry = manager.overlayRegistry;
    registry.register("yandex_traffic", new Factory<Overlay, ActivityData>() {
      public Overlay create(ActivityData activityData) {
        return new YandexTrafficOverlay(activityData.objectManager.taskDispatcher, activityData.uiController);
      }
    });
  }

  public void onUnLoad(ObjectManager manager) {
    Registry<Overlay, ActivityData> registry = manager.overlayRegistry;
    registry.unregister("yandex_traffic");
  }
}
