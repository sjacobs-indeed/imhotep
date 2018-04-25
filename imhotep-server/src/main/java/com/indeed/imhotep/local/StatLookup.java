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
package com.indeed.imhotep.local;

import com.indeed.flamdex.api.IntValueLookup;

import java.util.ArrayList;
import java.util.List;

/**
 * Intended exclusively for use by ImhotepLocalSession, hence package private
 * access for all operations. This is just a wrapper around an array of
 * IntValueLookups that fires a property change event whenever a value it is
 * changed. The event fired is always the same, as more granular knowledge of
 * changes is not useful in the context of ImhotepLocalSession.
 *
 * To do: move numStats from ImhotepLocalSession to this class along with
 * push()/pop() operations
 *
 * @author johnf
 */
class StatLookup
{
    interface Observer {
        void onChange(final StatLookup statLookup, final int index);
    }

    private final List<Observer> observers = new ArrayList<>();

    private final String[]         names;
    private final IntValueLookup[] lookups;

    StatLookup(final int numLookups) {
        this.names   = new String[numLookups];
        this.lookups = new IntValueLookup[numLookups];
    }

    int length() { return lookups.length; }

    String getName(final int index) { return names[index]; }
    IntValueLookup get(final int index) { return lookups[index]; }

    void set(final int index, final String name, final IntValueLookup lookup) {
        names[index]   = name;
        lookups[index] = lookup;
        for (final Observer observer: observers) {
            observer.onChange(this, index);
        }
    }

    void    addObserver(final Observer observer) { observers.add(observer);    }
    void removeObserver(final Observer observer) { observers.remove(observer); }
}