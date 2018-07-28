/*
 * Copyright (C) 2018 Indeed Inc.
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

package com.indeed.flamdex.utils;

import com.google.common.collect.ImmutableList;
import com.indeed.flamdex.api.DocIdStream;
import com.indeed.flamdex.api.TermIterator;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author zheli
 */
public class DocIdStreamIteratorTest {

    private class MockDocIdStream implements DocIdStream {
        private final List<Integer> docs;
        private int index;

        MockDocIdStream(final List<Integer> docs) {
            this.docs = docs;
        }

        @Override
        public void reset(final TermIterator term) {
            index = 0;
        }

        @Override
        public int fillDocIdBuffer(final int[] docIdBuffer) {
            final int n = Math.min(docs.size() - index, docIdBuffer.length);
            for (int i = 0; i < n; ++i) {
                docIdBuffer[i] = docs.get(index);
                index++;
            }
            return n;
        }

        @Override
        public void close() {
        }
    }

    private void checkIterator(final DocIdStreamIterator docIdStreamIterator, final int n) {
        for (int i = 0; i < n; i++) {
            assertTrue(docIdStreamIterator.hasElement());
            assertEquals(i, docIdStreamIterator.docId());
            docIdStreamIterator.advance();
        }
    }

    public void testDocIdStreamIterator() {
        final MockDocIdStream mockDocIdStream = new MockDocIdStream(ImmutableList.of(0, 1, 2));
        final DocIdStreamIterator docIdStreamIterator = new DocIdStreamIterator(mockDocIdStream, 2);

        assertFalse(docIdStreamIterator.hasElement());
        assertEquals(-1, docIdStreamIterator.docId());

        docIdStreamIterator.reset(null);
        checkIterator(docIdStreamIterator, 2);
        assertTrue(docIdStreamIterator.hasElement());


        docIdStreamIterator.reset(null);
        checkIterator(docIdStreamIterator, 3);
        assertFalse(docIdStreamIterator.hasElement());
    }
}