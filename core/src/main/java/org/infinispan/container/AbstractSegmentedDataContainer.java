package org.infinispan.container;

import static org.infinispan.commons.util.Util.toStr;

import java.lang.invoke.MethodHandles;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.infinispan.Cache;
import org.infinispan.commons.logging.Log;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.commons.util.AbstractIterator;
import org.infinispan.commons.util.ByRef;
import org.infinispan.commons.util.CloseableSpliterator;
import org.infinispan.commons.util.EvictionListener;
import org.infinispan.commons.util.IntSet;
import org.infinispan.commons.util.PeekableMap;
import org.infinispan.configuration.cache.HashConfiguration;
import org.infinispan.container.entries.ImmortalCacheEntry;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.distribution.ch.KeyPartitioner;
import org.infinispan.eviction.ActivationManager;
import org.infinispan.eviction.EvictionManager;
import org.infinispan.eviction.PassivationManager;
import org.infinispan.expiration.ExpirationManager;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.metadata.Metadata;
import org.infinispan.metadata.impl.L1Metadata;
import org.infinispan.util.CoreImmutables;
import org.infinispan.util.TimeService;
import org.infinispan.util.concurrent.WithinThreadExecutor;

import com.github.benmanes.caffeine.cache.CacheWriter;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

/**
 * Abstract class implemenation for a segmented data container. All methods delegate to
 * {@link #getSegmentForKey(Object)} for methods that don't provide a segment and implementors can provide what
 * map we should look into for a given segment via {@link #getMapForSegment(int)}.
 * @author wburns
 * @since 9.3
 */
public abstract class AbstractSegmentedDataContainer<K, V> implements SegmentedDataContainer<K, V> {
   private static final Log log = LogFactory.getLog(MethodHandles.lookup().lookupClass());
   private static final boolean trace = log.isTraceEnabled();

   @Inject protected TimeService timeService;
   @Inject protected EvictionManager evictionManager;
   @Inject protected ExpirationManager<K, V> expirationManager;
   @Inject protected InternalEntryFactory entryFactory;
   @Inject protected ActivationManager activator;
   @Inject protected PassivationManager passivator;
   @Inject protected Cache<K, V> cache;
   protected KeyPartitioner keyPartitioner;

   protected final List<Consumer<Iterable<InternalCacheEntry<K, V>>>> listeners = new CopyOnWriteArrayList<>();

   protected abstract ConcurrentMap<K, InternalCacheEntry<K, V>> getMapForSegment(int segment);
   protected abstract int getSegmentForKey(Object key);

   @Start
   public void start() {
      HashConfiguration hashConfiguration = cache.getCacheConfiguration().clustering().hash();
      keyPartitioner = hashConfiguration.keyPartitioner();
   }

   @Override
   public InternalCacheEntry<K, V> get(int segment, Object k) {
      ConcurrentMap<K, InternalCacheEntry<K, V>> map = getMapForSegment(segment);
      InternalCacheEntry<K, V> e = map != null ? map.get(k) : null;
      if (e != null && e.canExpire()) {
         long currentTimeMillis = timeService.wallClockTime();
         if (e.isExpired(currentTimeMillis) &&
               expirationManager.entryExpiredInMemory(e, currentTimeMillis).join() == Boolean.TRUE) {
            e = null;
         } else {
            e.touch(currentTimeMillis);
         }
      }
      return e;
   }

   @Override
   public InternalCacheEntry<K, V> get(Object k) {
      return get(getSegmentForKey(k), k);
   }

   @Override
   public InternalCacheEntry<K, V> peek(int segment, Object k) {
      ConcurrentMap<K, InternalCacheEntry<K, V>> entries = getMapForSegment(segment);
      if (entries != null) {
         if (entries instanceof PeekableMap) {
            return ((PeekableMap<K, InternalCacheEntry<K, V>>) entries).peek(k);
         }
         return entries.get(k);
      }
      return null;
   }

   @Override
   public InternalCacheEntry<K, V> peek(Object k) {
      return peek(getSegmentForKey(k), k);
   }

   @Override
   public void put(int segment, K k, V v, Metadata metadata) {
      ConcurrentMap<K, InternalCacheEntry<K, V>> entries = getMapForSegment(segment);
      if (entries != null) {
         boolean l1Entry = false;
         if (metadata instanceof L1Metadata) {
            metadata = ((L1Metadata) metadata).metadata();
            l1Entry = true;
         }
         InternalCacheEntry<K, V> e = entries.get(k);

         if (trace) {
            log.tracef("Creating new ICE for writing. Existing=%s, metadata=%s, new value=%s", e, metadata, toStr(v));
         }
         final InternalCacheEntry<K, V> copy;
         if (l1Entry) {
            copy = entryFactory.createL1(k, v, metadata);
         } else if (e != null) {
            copy = entryFactory.update(e, v, metadata);
         } else {
            // this is a brand-new entry
            copy = entryFactory.create(k, v, metadata);
         }

         if (trace)
            log.tracef("Store %s in container", copy);

         entries.compute(copy.getKey(), (key, entry) -> {
            computeEntryWritten(key, copy);
            activator.onUpdate(key, entry == null);
            return copy;
         });
      } else {
         log.fatalf("Insertion attempted for key: %s but there was no map created for it at segment: %d", k, segment);
      }
   }

   @Override
   public void put(K k, V v, Metadata metadata) {
      put(getSegmentForKey(k), k, v, metadata);
   }

   @Override
   public boolean containsKey(int segment, Object k) {
      InternalCacheEntry<K, V> ice = peek(segment, k);
      if (ice != null && ice.canExpire()) {
         long currentTimeMillis = timeService.wallClockTime();
         if (ice.isExpired(currentTimeMillis)) {
            if (expirationManager.entryExpiredInMemory(ice, currentTimeMillis).join() == Boolean.TRUE) {
               ice = null;
            }
         }
      }
      return ice != null;
   }

   @Override
   public boolean containsKey(Object k) {
      return containsKey(getSegmentForKey(k), k);
   }

   @Override
   public InternalCacheEntry<K, V> remove(int segment, Object k) {
      ConcurrentMap<K, InternalCacheEntry<K, V>> entries = getMapForSegment(segment);
      if (entries != null) {
         final ByRef<InternalCacheEntry<K, V>> reference = new ByRef<>(null);
         entries.compute((K) k, (key, entry) -> {
            activator.onRemove(key, entry == null);
            if (entry != null) {
               computeEntryRemoved(key, entry);
            }
            reference.set(entry);
            return null;
         });
         InternalCacheEntry<K, V> e = reference.get();
         if (trace) {
            log.tracef("Removed %s from container", e);
         }

         return e == null || (e.canExpire() && e.isExpired(timeService.wallClockTime())) ? null : e;
      }
      return null;
   }

   @Override
   public InternalCacheEntry<K, V> remove(Object k) {
      return remove(getSegmentForKey(k), k);
   }

   @Override
   public int sizeIncludingExpired(IntSet segments) {
      int size = 0;
      // The foreach loop would use boxed Iterator, so we have to use old style for loop with iterator
      for (PrimitiveIterator.OfInt iter = segments.iterator(); iter.hasNext(); ) {
         int segment = iter.next();
         ConcurrentMap<K, InternalCacheEntry<K, V>> map = getMapForSegment(segment);
         size += map.size();
         if (size < 0) return Integer.MAX_VALUE;
      }
      return size;
   }

   @Override
   public void evict(int segment, K key) {
      ConcurrentMap<K, InternalCacheEntry<K, V>> entries = getMapForSegment(segment);
      if (entries != null) {
         entries.computeIfPresent(key, (o, entry) -> {
            passivator.passivate(entry);
            computeEntryRemoved(o, entry);
            return null;
         });
      }
   }

   @Override
   public void evict(K key) {
      evict(getSegmentForKey(key), key);
   }

   @Override
   public InternalCacheEntry<K, V> compute(int segment, K key, ComputeAction<K, V> action) {
      ConcurrentMap<K, InternalCacheEntry<K, V>> entries = getMapForSegment(segment);
      return entries != null ? entries.compute(key, (k, oldEntry) -> {
         InternalCacheEntry<K, V> newEntry = action.compute(k, oldEntry, entryFactory);
         if (newEntry == oldEntry) {
            return oldEntry;
         } else if (newEntry == null) {
            computeEntryRemoved(k, oldEntry);
            activator.onRemove(k, false);
            return null;
         }
         computeEntryWritten(k, newEntry);
         activator.onUpdate(k, oldEntry == null);
         if (trace)
            log.tracef("Store %s in container", newEntry);
         return newEntry;
      }) : null;
   }

   @Override
   public InternalCacheEntry<K, V> compute(K key, ComputeAction<K, V> action) {
      return compute(getSegmentForKey(key), key, action);
   }

   @Override
   public Spliterator<InternalCacheEntry<K, V>> spliterator(IntSet segments) {
      return Spliterators.spliteratorUnknownSize(iterator(segments), Spliterator.DISTINCT | Spliterator.CONCURRENT | Spliterator.NONNULL);
   }

   @Override
   public Spliterator<InternalCacheEntry<K, V>> spliterator() {
      return Spliterators.spliteratorUnknownSize(iterator(), Spliterator.DISTINCT | Spliterator.CONCURRENT | Spliterator.NONNULL);
   }

   @Override
   public Spliterator<InternalCacheEntry<K, V>> spliteratorIncludingExpired(IntSet segments) {
      return Spliterators.spliteratorUnknownSize(iteratorIncludingExpired(segments), Spliterator.DISTINCT | Spliterator.CONCURRENT | Spliterator.NONNULL);
   }

   @Override
   public Spliterator<InternalCacheEntry<K, V>> spliteratorIncludingExpired() {
      return Spliterators.spliteratorUnknownSize(iteratorIncludingExpired(), Spliterator.DISTINCT | Spliterator.CONCURRENT | Spliterator.NONNULL);
   }

   @Override
   public void clear(IntSet segments) {
      segments.forEach((int segment) -> {
         Map<K, InternalCacheEntry<K, V>> map = getMapForSegment(segment);
         if (map != null) {
            map.clear();
         }
      });
   }

   /**
    * This method is invoked every time an entry is written inside a compute block
    * @param key key passed to compute method
    * @param value the new value
    */
   protected void computeEntryWritten(K key, InternalCacheEntry<K, V> value) {
      // Do nothing by default
   }

   /**
    * This method is invoked every time an entry is removed inside a compute block
    * @param key key passed to compute method
    * @param value the old value
    */
   protected void computeEntryRemoved(K key, InternalCacheEntry<K, V> value) {
      // Do nothing by default
   }

   @Override
   public void addRemovalListener(Consumer<Iterable<InternalCacheEntry<K, V>>> listener) {
      listeners.add(listener);
   }

   @Override
   public void removeRemovalListener(Object listener) {
      listeners.remove(listener);
   }

   protected class EntryIterator extends AbstractIterator<InternalCacheEntry<K, V>> {

      private final Iterator<InternalCacheEntry<K, V>> it;

      public EntryIterator(Iterator<InternalCacheEntry<K, V>> it) {
         this.it = it;
      }

      protected InternalCacheEntry<K, V> getNext() {
         boolean initializedTime = false;
         long now = 0;
         while (it.hasNext()) {
            InternalCacheEntry<K, V> entry = it.next();
            if (!entry.canExpire()) {
               if (trace) {
                  log.tracef("Return next entry %s", entry);
               }
               return entry;
            } else {
               if (!initializedTime) {
                  now = timeService.wallClockTime();
                  initializedTime = true;
               }
               if (!entry.isExpired(now) || expirationManager.entryExpiredInMemoryFromIteration(entry, now).join()
                     == Boolean.FALSE) {
                  if (trace) {
                     log.tracef("Return next entry %s", entry);
                  }
                  return entry;
               } else if (trace) {
                  log.tracef("%s is expired", entry);
               }
            }
         }
         if (trace) {
            log.tracef("Return next null");
         }
         return null;
      }
   }

   /**
    * Spliterator that wraps another to make sure to now return expired entries. This class also implements
    * CloseableSpliterator to prevent additional allocations if user needs it to be closeable.
    */
   protected class EntrySpliterator implements CloseableSpliterator<InternalCacheEntry<K, V>> {
      private final Spliterator<InternalCacheEntry<K, V>> spliterator;
      // We assume that spliterator is not used concurrently - normally it is split so we can use these variables safely
      private final Consumer<? super InternalCacheEntry<K, V>> consumer = ice -> current = ice;

      private InternalCacheEntry<K, V> current;

      public EntrySpliterator(Spliterator<InternalCacheEntry<K, V>> spliterator) {
         this.spliterator = spliterator;
      }

      @Override
      public void close() {
         // Do nothing
      }

      @Override
      public boolean tryAdvance(Consumer<? super InternalCacheEntry<K, V>> action) {
         InternalCacheEntry<K, V> entryToUse = null;
         boolean initializedTime = false;
         long now = 0;
         while (entryToUse == null && spliterator.tryAdvance(consumer)) {
            entryToUse = current;
            if (entryToUse.canExpire()) {
               if (!initializedTime) {
                  now = timeService.wallClockTime();
                  initializedTime = true;
               }
               if (entryToUse.isExpired(now) &&
                     expirationManager.entryExpiredInMemoryFromIteration(entryToUse, now).join() == Boolean.TRUE) {
                  entryToUse = null;
               }
            }
         }
         if (entryToUse != null) {
            action.accept(entryToUse);
            return true;
         }

         return false;
      }

      @Override
      public void forEachRemaining(Consumer<? super InternalCacheEntry<K, V>> action) {
         // We don't call the forEachRemaining on the actual spliterator, since we want to keep the time between
         // invocations
         long now = timeService.wallClockTime();

         while (spliterator.tryAdvance(consumer)) {
            InternalCacheEntry<K, V> currentEntry = current;
            if (currentEntry.canExpire() && currentEntry.isExpired(now) &&
                  expirationManager.entryExpiredInMemoryFromIteration(currentEntry, now).join() == Boolean.TRUE) {
               continue;
            }
            action.accept(currentEntry);
         }
      }

      @Override
      public Spliterator<InternalCacheEntry<K, V>> trySplit() {
         Spliterator<InternalCacheEntry<K, V>> split = spliterator.trySplit();
         if (split != null) {
            return new EntrySpliterator(split);
         }
         return null;
      }

      @Override
      public long estimateSize() {
         return spliterator.estimateSize();
      }

      @Override
      public int characteristics() {
         return spliterator.characteristics() | Spliterator.DISTINCT;
      }
   }

   @Override
   public Collection<V> values() {
      return new Values();
   }

   /**
    * Minimal implementation needed for unmodifiable Collection
    * @deprecated This is to removed when {@link #entrySet()} is removed
    */
   @Deprecated
   protected class Values extends AbstractCollection<V> {
      @Override
      public Iterator<V> iterator() {
         return new ValueIterator<>(AbstractSegmentedDataContainer.this.iteratorIncludingExpired());
      }

      @Override
      public int size() {
         return AbstractSegmentedDataContainer.this.sizeIncludingExpired();
      }

      @Override
      public Spliterator<V> spliterator() {
         return Spliterators.spliterator(this, Spliterator.CONCURRENT);
      }
   }

   /**
    * @deprecated This is to removed when {@link #entrySet()} is removed
    */
   @Deprecated
   private static class ValueIterator<K, V> implements Iterator<V> {
      Iterator<InternalCacheEntry<K, V>> currentIterator;

      private ValueIterator(Iterator<InternalCacheEntry<K, V>> it) {
         currentIterator = it;
      }

      @Override
      public boolean hasNext() {
         return currentIterator.hasNext();
      }

      @Override
      public void remove() {
         throw new UnsupportedOperationException();
      }

      @Override
      public V next() {
         return currentIterator.next().getValue();
      }
   }

   @Override
   public Set<InternalCacheEntry<K, V>> entrySet() {
      return new EntrySet();
   }

   /**
    * @deprecated this class is to be removed when {@link #entrySet()} is removed
    */
   @Deprecated
   private class ImmutableEntryIterator extends EntryIterator {
      ImmutableEntryIterator(Iterator<InternalCacheEntry<K, V>> it){
         super(it);
      }

      @Override
      public InternalCacheEntry<K, V> next() {
         return CoreImmutables.immutableInternalCacheEntry(super.next());
      }
   }

   /**
    * Minimal implementation needed for unmodifiable Set
    *
    */
   private class EntrySet extends AbstractSet<InternalCacheEntry<K, V>> {

      @Override
      public boolean contains(Object o) {
         if (!(o instanceof Map.Entry)) {
            return false;
         }

         @SuppressWarnings("rawtypes")
         Map.Entry e = (Map.Entry) o;
         InternalCacheEntry ice = AbstractSegmentedDataContainer.this.get(e.getKey());
         if (ice == null) {
            return false;
         }
         return ice.getValue().equals(e.getValue());
      }

      @Override
      public Iterator<InternalCacheEntry<K, V>> iterator() {
         return new ImmutableEntryIterator(AbstractSegmentedDataContainer.this.iteratorIncludingExpired());
      }

      @Override
      public int size() {
         return AbstractSegmentedDataContainer.this.sizeIncludingExpired();
      }

      @Override
      public String toString() {
         return stream()
               .map(Object::toString)
               .collect(Collectors.joining(",", "[", "]"));
      }

      @Override
      public Spliterator<InternalCacheEntry<K, V>> spliterator() {
         return Spliterators.spliterator(this, Spliterator.DISTINCT | Spliterator.CONCURRENT);
      }
   }

   protected static <K, V> Caffeine<K, InternalCacheEntry<K, V>> applyListener(Caffeine<K, InternalCacheEntry<K, V>> caffeine,
         EvictionListener<K, InternalCacheEntry<K, V>> listener, CacheWriter<K, InternalCacheEntry<K, V>> additionalWriter) {
      return caffeine.executor(new WithinThreadExecutor()).removalListener((k, v, c) -> {
         switch (c) {
            case SIZE:
               listener.onEntryEviction(Collections.singletonMap(k, v));
               break;
            case EXPLICIT:
               listener.onEntryRemoved(new ImmortalCacheEntry(k, v));
               break;
            case REPLACED:
               listener.onEntryActivated(k);
               break;
         }
      }).writer(new CacheWriter<K, InternalCacheEntry<K, V>>() {
         @Override
         public void write(K key, InternalCacheEntry<K, V> value) {
            if (additionalWriter != null) {
               additionalWriter.write(key, value);
            }
         }

         @Override
         public void delete(K key, InternalCacheEntry<K, V> value, RemovalCause cause) {
            if (additionalWriter != null) {
               additionalWriter.delete(key, value, cause);
            }
            if (cause == RemovalCause.SIZE) {
               listener.onEntryChosenForEviction(new ImmortalCacheEntry(key, value));
            }
         }
      });
   }

   public static <K, V> Caffeine<K, V> caffeineBuilder() {
      return (Caffeine<K, V>) Caffeine.newBuilder();
   }

   final class DefaultEvictionListener implements EvictionListener<K, InternalCacheEntry<K, V>> {

      @Override
      public void onEntryEviction(Map<K, InternalCacheEntry<K, V>> evicted) {
         evictionManager.onEntryEviction(evicted);
      }

      @Override
      public void onEntryChosenForEviction(Map.Entry<K, InternalCacheEntry<K, V>> entry) {
         passivator.passivate(entry.getValue());
      }

      @Override
      public void onEntryActivated(Object key) {
         activator.onUpdate(key, true);
      }

      @Override
      public void onEntryRemoved(Map.Entry<K, InternalCacheEntry<K, V>> entry) {
      }
   }
}