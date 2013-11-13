/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.bucket.multi.histogram;

import com.carrotsearch.hppc.LongArrayList;
import org.apache.lucene.util.CollectionUtil;
import org.apache.lucene.util.OpenBitSet;
import org.elasticsearch.common.inject.internal.Nullable;
import org.elasticsearch.common.rounding.Rounding;
import org.elasticsearch.index.fielddata.LongValues;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.bucket.BucketsAggregator;
import org.elasticsearch.search.aggregations.bucket.multi.LongHash;
import org.elasticsearch.search.aggregations.context.AggregationContext;
import org.elasticsearch.search.aggregations.context.ValuesSourceConfig;
import org.elasticsearch.search.aggregations.context.numeric.NumericValuesSource;
import org.elasticsearch.search.aggregations.context.numeric.ValueFormatter;
import org.elasticsearch.search.aggregations.factory.AggregatorFactories;
import org.elasticsearch.search.aggregations.factory.ValueSourceAggregatorFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class HistogramAggregator extends BucketsAggregator {

    private final static int INITIAL_CAPACITY = 50; // TODO sizing

    private final NumericValuesSource valuesSource;
    private final Rounding rounding;
    private final InternalOrder order;
    private final boolean keyed;
    private final boolean computeEmptyBuckets;
    private final AbstractHistogramBase.Factory histogramFactory;

    private final LongHash bucketOrds;
    private OpenBitSet matched = new OpenBitSet();
    private final LongArrayList matchedList;

    public HistogramAggregator(String name,
                               AggregatorFactories factories,
                               Rounding rounding,
                               InternalOrder order,
                               boolean keyed,
                               boolean computeEmptyBuckets,
                               @Nullable NumericValuesSource valuesSource,
                               AbstractHistogramBase.Factory<?> histogramFactory,
                               AggregationContext aggregationContext,
                               Aggregator parent) {

        super(name, BucketAggregationMode.PER_BUCKET, factories, 50, aggregationContext, parent);
        this.valuesSource = valuesSource;
        this.rounding = rounding;
        this.order = order;
        this.keyed = keyed;
        this.computeEmptyBuckets = computeEmptyBuckets;
        this.histogramFactory = histogramFactory;

        bucketOrds = new LongHash(INITIAL_CAPACITY);
        matched = new OpenBitSet(INITIAL_CAPACITY);
        matchedList = new LongArrayList();
    }

    @Override
    public boolean shouldCollect() {
        return valuesSource != null;
    }

    @Override
    public void collect(int doc, long owningBucketOrdinal) throws IOException {
        assert owningBucketOrdinal == 0;
        final LongValues values = valuesSource.longValues();
        final int valuesCount = values.setDocument(doc);

        // nocommit the matched logic could be more efficient if we knew the values are in order
        assert matched.cardinality() == 0;
        for (int i = 0; i < valuesCount; ++i) {
            long value = values.nextValue();
            long key = rounding.round(value);
            long bucketOrd = bucketOrds.add(key);
            if (bucketOrd < 0) { // already seen
                bucketOrd = -1 - bucketOrd;
            }
            if (!matched.get(bucketOrd)) {
                matched.set(bucketOrd);
                matchedList.add(bucketOrd);
                collectBucket(doc, bucketOrd);
            }
        }
        resetMatches();
    }

    private void resetMatches() {
        for (int i = 0; i < matchedList.size(); ++i) {
            matched.clear(matchedList.get(i));
        }
        matchedList.clear();
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrdinal) {
        assert owningBucketOrdinal == 0;
        List<HistogramBase.Bucket> buckets = new ArrayList<HistogramBase.Bucket>((int) bucketOrds.size());
        for (long i = 0; i < bucketOrds.capacity(); ++i) {
            final long ord = bucketOrds.id(i);
            if (ord < 0) {
                continue; // slot is not allocated
            }
            buckets.add(histogramFactory.createBucket(bucketOrds.key(i), bucketDocCount(ord), bucketAggregations(ord)));
        }
        CollectionUtil.introSort(buckets, order.comparator());

        // value source will be null for unmapped fields
        ValueFormatter formatter = valuesSource != null ? valuesSource.formatter() : null;
        return histogramFactory.create(name, buckets, order, computeEmptyBuckets ? rounding : null, formatter, keyed);
    }


    public static class Factory extends ValueSourceAggregatorFactory<NumericValuesSource> {

        private final Rounding rounding;
        private final InternalOrder order;
        private final boolean keyed;
        private final boolean computeEmptyBuckets;
        private final AbstractHistogramBase.Factory<?> histogramFactory;

        public Factory(String name, ValuesSourceConfig<NumericValuesSource> valueSourceConfig,
                       Rounding rounding, InternalOrder order, boolean keyed, boolean computeEmptyBuckets, AbstractHistogramBase.Factory<?> histogramFactory) {
            super(name, histogramFactory.type(), valueSourceConfig);
            this.rounding = rounding;
            this.order = order;
            this.keyed = keyed;
            this.computeEmptyBuckets = computeEmptyBuckets;
            this.histogramFactory = histogramFactory;
        }

        @Override
        protected Aggregator createUnmapped(AggregationContext aggregationContext, Aggregator parent) {
            return new HistogramAggregator(name, factories, rounding, order, keyed, computeEmptyBuckets, null, histogramFactory, aggregationContext, parent);
        }

        @Override
        protected Aggregator create(NumericValuesSource valuesSource, long expectedBucketsCount, AggregationContext aggregationContext, Aggregator parent) {
            return new HistogramAggregator(name, factories, rounding, order, keyed, computeEmptyBuckets, valuesSource, histogramFactory, aggregationContext, parent);
        }

    }
}