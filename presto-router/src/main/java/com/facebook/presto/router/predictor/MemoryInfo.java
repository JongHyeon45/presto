/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.router.predictor;

import static java.util.Objects.requireNonNull;

public class MemoryInfo
        implements ResourceInfo
{
    private final int memoryBytesLabel;
    private final String memoryBytesRange;

    public MemoryInfo(int memoryBytesLabel, String memoryBytesRange)
    {
        this.memoryBytesLabel = memoryBytesLabel;
        this.memoryBytesRange = requireNonNull(memoryBytesRange, "Memory bytes range is null");
    }

    public int getMemoryBytesLabel()
    {
        return memoryBytesLabel;
    }

    public String getMemoryBytesRange()
    {
        return memoryBytesRange;
    }
}
