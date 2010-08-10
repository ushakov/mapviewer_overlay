package org.ushmax.mapviewer;

import org.ushmax.common.Factory;
import org.ushmax.common.Registry;
import org.ushmax.mapviewer.overlays.YandexTrafficOverlay;

public class Plugin implements AbstractPlugin {

  public void onLoad(ObjectManager manager) {
    Registry<Overlay, ObjectManager> registry = manager.overlayRegistry;
    registry.register(new Factory<Overlay, ObjectManager>() {
      public String name() {
        return "yandex_traffic";
      }

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
