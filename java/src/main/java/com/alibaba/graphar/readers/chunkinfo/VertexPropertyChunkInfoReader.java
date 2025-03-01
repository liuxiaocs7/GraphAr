/*
 * Copyright 2022 Alibaba Group Holding Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.graphar.readers.chunkinfo;

import static com.alibaba.graphar.util.CppClassName.GAR_ID_TYPE;
import static com.alibaba.graphar.util.CppClassName.GAR_VERTEX_PROPERTY_CHUNK_INFO_READER;
import static com.alibaba.graphar.util.CppHeaderName.GAR_CHUNK_INFO_READER_H;

import com.alibaba.fastffi.CXXHead;
import com.alibaba.fastffi.CXXPointer;
import com.alibaba.fastffi.CXXReference;
import com.alibaba.fastffi.CXXValue;
import com.alibaba.fastffi.FFIFactory;
import com.alibaba.fastffi.FFIGen;
import com.alibaba.fastffi.FFINameAlias;
import com.alibaba.fastffi.FFITypeAlias;
import com.alibaba.fastffi.FFITypeFactory;
import com.alibaba.graphar.graphinfo.GraphInfo;
import com.alibaba.graphar.graphinfo.PropertyGroup;
import com.alibaba.graphar.graphinfo.VertexInfo;
import com.alibaba.graphar.stdcxx.StdString;
import com.alibaba.graphar.util.GrapharStaticFunctions;
import com.alibaba.graphar.util.Result;
import com.alibaba.graphar.util.Status;

/** The chunk info reader for vertex property group. */
@FFIGen
@FFITypeAlias(GAR_VERTEX_PROPERTY_CHUNK_INFO_READER)
@CXXHead(GAR_CHUNK_INFO_READER_H)
public interface VertexPropertyChunkInfoReader extends CXXPointer {

    Factory factory = FFITypeFactory.getFactory(VertexPropertyChunkInfoReader.class);

    /**
     * Sets chunk position indicator for reader by internal vertex id. If internal vertex id is not
     * found, will return Status::IndexError error. After seeking to an invalid vertex id, the next
     * call to GetChunk function may undefined, e.g. return a non exist path.
     *
     * @param id the internal vertex id.
     */
    @CXXValue
    Status seek(@FFITypeAlias(GAR_ID_TYPE) long id);

    /** Return the current chunk file path of chunk position indicator. */
    @FFINameAlias("GetChunk")
    @CXXValue
    Result<StdString> getChunk();

    /**
     * Sets chunk position indicator to next chunk. if current chunk is the last chunk, will return
     * Status::IndexError error.
     */
    @FFINameAlias("next_chunk")
    @CXXValue
    Status nextChunk();

    /** Get the chunk number of the current vertex property group. */
    @FFINameAlias("GetChunkNum")
    long getChunkNum();

    /**
     * Helper function to Construct VertexPropertyChunkInfoReader.
     *
     * @param graphInfo The graph info to describe the graph.
     * @param label label name of the vertex.
     * @param propertyGroup The property group of the vertex.
     */
    static Result<VertexPropertyChunkInfoReader> constructVertexPropertyChunkInfoReader(
            @CXXReference GraphInfo graphInfo,
            @CXXReference StdString label,
            @CXXReference PropertyGroup propertyGroup) {

        return GrapharStaticFunctions.INSTANCE.constructVertexPropertyChunkInfoReader(
                graphInfo, label, propertyGroup);
    }

    @FFIFactory
    interface Factory {
        /**
         * Initialize the VertexPropertyChunkInfoReader.
         *
         * @param vertexInfo The vertex info that describes the vertex type.
         * @param propertyGroup The property group that describes the property group.
         * @param prefix The absolute prefix of the graph.
         */
        VertexPropertyChunkInfoReader create(
                @CXXReference VertexInfo vertexInfo,
                @CXXReference PropertyGroup propertyGroup,
                @CXXReference StdString prefix);
    }
}
