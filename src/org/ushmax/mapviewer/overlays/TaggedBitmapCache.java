package org.ushmax.mapviewer.overlays;

import org.ushmax.common.LRUCache;

public class TaggedBitmapCache<K> extends LRUCache<K, TaggedBitmap> {
  public TaggedBitmapCache(int capacity) {
    super(capacity);
  }

  @Override
  protected void freeItem(TaggedBitmap tb) {
    tb.bitmap.recycle();
  }
}
