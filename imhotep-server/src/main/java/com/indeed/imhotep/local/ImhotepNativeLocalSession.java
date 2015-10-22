/*
 * Copyright (C) 2014 Indeed Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.indeed.imhotep.local;

import com.indeed.flamdex.api.*;
import com.indeed.imhotep.*;
import com.indeed.imhotep.api.ImhotepOutOfMemoryException;

import org.apache.log4j.Logger;

import java.beans.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;

public class ImhotepNativeLocalSession extends ImhotepLocalSession {

    static final Logger log = Logger.getLogger(ImhotepNativeLocalSession.class);

    private MultiCache multiCache;
    private boolean    rebuildMultiCache = true;

    public ImhotepNativeLocalSession(final FlamdexReader flamdexReader)
        throws ImhotepOutOfMemoryException {
        this(flamdexReader,
             new MemoryReservationContext(new ImhotepMemoryPool(Long.MAX_VALUE)), null);
    }

    public ImhotepNativeLocalSession(final FlamdexReader flamdexReader,
                                     final MemoryReservationContext memory,
                                     AtomicLong tempFileSizeBytesLeft)
        throws ImhotepOutOfMemoryException {

        super(flamdexReader, memory, tempFileSizeBytesLeft);

        this.statLookup.addObserver(new StatLookup.Observer() {
                public void onChange(final StatLookup statLookup, final int index) {
                    ImhotepNativeLocalSession.this.rebuildMultiCache = true;
                }
            });
    }

    public MultiCache buildMultiCache(final MultiCacheConfig config) {
        if (rebuildMultiCache) {
            if (multiCache != null) {
                multiCache.close();
            }
            multiCache = new MultiCache(this, getNumDocs(), config, statLookup, docIdToGroup);
            docIdToGroup = multiCache.getGroupLookup();
            rebuildMultiCache = false;
        }
        return multiCache;
    }

    @Override
    public synchronized void rebuildAndFilterIndexes(@Nonnull final List<String> intFields,
                                                     @Nonnull final List<String> stringFields) {
        throw new UnsupportedOperationException();
    }

    @Override
    public synchronized long[] getGroupStats(int stat) {

        if (multiCache == null) {
            final MultiCacheConfig config = new MultiCacheConfig();
            final StatLookup[] statLookups = new StatLookup[1];
            statLookups[0] = statLookup;
            config.calcOrdering(statLookups, numStats);
            buildMultiCache(config);
        }

        long[] result = groupStats.get(stat);
        if (groupStats.isDirty(stat)) {
            multiCache.nativeGetGroupStats(stat, result);
            groupStats.validate(stat);
        }
        return result;
    }

    @Override
    protected GroupLookup resizeGroupLookup(GroupLookup lookup, final int size,
                                            final MemoryReservationContext memory)
        throws ImhotepOutOfMemoryException {
        return multiCache != null ? multiCache.getGroupLookup() : lookup;
    }

    @Override
    protected void freeDocIdToGroup() { }

    @Override
    protected void tryClose() {
        if (multiCache != null) {
            multiCache.close();
            // TODO: free memory?
        }
        super.tryClose();
    }

    @Override
    public synchronized int regroup(final GroupMultiRemapRule[] rules, boolean errorOnCollisions)
        throws ImhotepOutOfMemoryException {
        int result = 0;
        final long rulesPtr = nativeGetRules(rules);
        try {
            result = super.regroup(rules, errorOnCollisions);
        }
        finally {
            nativeReleaseRules(rulesPtr);
        }
        return result;
    }

    private native static long nativeGetRules(final GroupMultiRemapRule[] rules);
    private native static void nativeReleaseRules(final long nativeRulesPtr);
}