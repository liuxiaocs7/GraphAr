/**
 * Copyright 2022 Alibaba Group Holding Limited.
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.graphar;

/** General constant parameters for graphar. */
public class GeneralParams {
    // column name
    public static final String vertexIndexCol = "_graphArVertexIndex";
    public static final String srcIndexCol = "_graphArSrcIndex";
    public static final String dstIndexCol = "_graphArDstIndex";
    public static final String offsetCol = "_graphArOffset";
    public static final String primaryCol = "_graphArPrimary";
    public static final String vertexChunkIndexCol = "_graphArVertexChunkIndex";
    public static final String edgeIndexCol = "_graphArEdgeIndex";
    public static final String regularSeperator = "_";
    public static final String offsetStartChunkIndexKey = "_graphar_offset_start_chunk_index";
    public static final String aggNumListOfEdgeChunkKey = "_graphar_agg_num_list_of_edge_chunk";
    public static final Long defaultVertexChunkSize = 262144L; // 2^18
    public static final Long defaultEdgeChunkSize = 4194304L; // 2^22
    public static final String defaultFileType = "parquet";
    public static final String defaultVersion = "v1";
}
