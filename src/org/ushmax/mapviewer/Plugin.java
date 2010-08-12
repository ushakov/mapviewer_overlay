package org.ushmax.mapviewer;

import org.ushmax.common.Factory;
import org.ushmax.common.Registry;
import org.ushmax.mapviewer.overlays.YandexTrafficOverlay;

public class Plugin implements AbstractPlugin {

  public void onLoad(ObjectManager manager) {
    Registry<Overlay, ObjectManager> registry = manager.overlayRegistry;
    registry.register("yandex_traffic", new Factory<Overlay, ObjectManager>() {
      public Overlay create(ObjectManager manager) {
        return new YandexTrafficOverlay(manager.taskDispatcher);
      }
    });
  }

  public void onUnLoad(ObjectManager manager) {
    Registry<Overlay, ObjectManager> registry = manager.overlayRegistry;
    registry.unregister("yandex_traffic");
  }
}
