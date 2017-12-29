/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.liquigraph.trinity.internal.collection;

import static java.util.Arrays.asList;

import java.util.ArrayList;
import java.util.List;

public final class Lists {

    public static final List<String> prepend(String head, String... tail) {
        List<String> result = new ArrayList<>(1 + tail.length);
        result.add(head);
        result.addAll(asList(tail));
        return result;
    }
}
