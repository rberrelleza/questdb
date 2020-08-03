/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo;

import io.questdb.cairo.sql.DataFrame;
import io.questdb.std.LongList;
import io.questdb.std.Transient;

public class IntervalBwdDataFrameCursor extends AbstractIntervalDataFrameCursor {

    /**
     * Cursor for data frames that chronologically intersect collection of intervals.
     * Data frame low and high row will be within intervals inclusive of edges. Intervals
     * themselves are pairs of microsecond time.
     *
     * @param intervals      pairs of microsecond interval values, as in "low" and "high" inclusive of
     *                       edges.
     * @param timestampIndex index of timestamp column in the readr that is used by this cursor
     */
    public IntervalBwdDataFrameCursor(@Transient LongList intervals, int timestampIndex) {
        super(intervals, timestampIndex);
    }

    @Override
    public DataFrame next() {
        // order of logical operations is important
        // we are not calculating partition rages when intervals are empty
        while (intervalsLo < intervalsHi && partitionLo < partitionHi) {
            // We don't need to worry about column tops and null column because we
            // are working with timestamp. Timestamp column cannot be added to existing table.
            final int currentInterval = intervalsHi - 1;
            final int currentPartition = partitionHi - 1;
            long rowCount = reader.openPartition(currentPartition);
            if (rowCount > 0) {

                final ReadOnlyColumn column = reader.getColumn(TableReader.getPrimaryColumnIndex(reader.getColumnBase(currentPartition), timestampIndex));
                final long intervalLo = intervals.getQuick(currentInterval * 2);
                final long intervalHi = intervals.getQuick(currentInterval * 2 + 1);


                // interval is wholly above partition, skip interval
                if (column.getLong(0) > intervalHi) {
                    partitionHi = currentPartition;
                    partitionLimit = -1;
                    continue;
                }

                // interval is wholly below partition, skip partition
                if (column.getLong((rowCount - 1) * 8) < intervalLo) {
                    partitionLimit = -1;
                    intervalsHi = currentInterval;
                    continue;
                }

                // calculate intersection

                long lo = BinarySearch.search(column, intervalLo, 0, partitionLimit == -1 ? rowCount : partitionLimit, BinarySearch.SCAN_UP);
                if (lo < 0) {
                    lo = -lo - 1;
                }

                long hi = BinarySearch.search(column, intervalHi, lo, rowCount, BinarySearch.SCAN_DOWN);

                if (hi < 0) {
                    hi = -hi - 1;
                } else {
                    // We have direct hit. Interval is inclusive of edges and we have to
                    // bump to high bound because it is non-inclusive
                    hi++;
                }

                if (lo < hi) {
                    dataFrame.partitionIndex = currentPartition;
                    dataFrame.rowLo = lo;
                    dataFrame.rowHi = hi;

                    // we do have whole partition of fragment?
                    if (lo == 0) {
                        // whole partition, will need to skip to next one
                        partitionLimit = -1; // use row count next time
                        partitionHi = currentPartition;
                    } else {
                        // only fragment, need to skip to next interval
                        partitionLimit = lo; // use "lo" for max
                        intervalsHi = currentInterval;
                    }

                    return dataFrame;
                }
                // interval yielded empty data frame
                partitionLimit = lo;
                intervalsHi = currentInterval;
            } else {
                // partition was empty, just skip to next
                partitionHi = currentPartition;
            }
        }
        return null;
    }

    @Override
    public long size() {
        return -1;
    }

    @Override
    public void toTop() {
        super.toTop();
        partitionLimit = -1;
    }
}
