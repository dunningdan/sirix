package io.sirix.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import io.sirix.page.IndirectPage;
import io.sirix.page.PageReference;
import io.sirix.page.interfaces.Page;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class PageCache implements Cache<PageReference, Page> {

  private final com.github.benmanes.caffeine.cache.Cache<PageReference, Page> pageCache;

  public PageCache(final int maxSize) {
    RemovalListener<PageReference, Page> removalListener = (PageReference key, Page value, RemovalCause cause) -> {
      key.setPage(null);
    };

    pageCache = Caffeine.newBuilder()
                        .maximumSize(maxSize)
                        .expireAfterAccess(5, TimeUnit.MINUTES)
                        .scheduler(scheduler)
                        .removalListener(removalListener)
                        .build();
  }

  @Override
  public void clear() {
    pageCache.invalidateAll();
  }

  @Override
  public Page get(PageReference key) {
    var page = pageCache.getIfPresent(key);

//    if (page instanceof IndirectPage indirectPage) {
//      page = new IndirectPage(indirectPage);
//    }

    return page;
  }

  @Override
  public void put(PageReference key, Page value) {
    pageCache.put(key, value);
  }

  @Override
  public void putAll(Map<? extends PageReference, ? extends Page> map) {
    pageCache.putAll(map);
  }

  @Override
  public void toSecondCache() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Map<PageReference, Page> getAll(Iterable<? extends PageReference> keys) {
    return pageCache.getAllPresent(keys);
  }

  @Override
  public void remove(PageReference key) {
    pageCache.invalidate(key);
  }

  @Override
  public void close() {
  }
}
