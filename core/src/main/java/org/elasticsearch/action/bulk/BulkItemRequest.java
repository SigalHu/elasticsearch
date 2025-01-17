/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.bulk;

import org.elasticsearch.Version;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;

import java.io.IOException;
import java.util.Objects;

/**
 *
 */
public class BulkItemRequest implements Streamable {

    private int id;
    private DocWriteRequest request;
    private volatile BulkItemResponse primaryResponse;

    BulkItemRequest() {

    }

    public BulkItemRequest(int id, DocWriteRequest request) {
        assert request instanceof IndicesRequest;
        this.id = id;
        this.request = request;
    }

    public int id() {
        return id;
    }

    public DocWriteRequest request() {
        return request;
    }

    public String index() {
        assert request.indices().length == 1;
        return request.indices()[0];
    }

    BulkItemResponse getPrimaryResponse() {
        return primaryResponse;
    }

    void setPrimaryResponse(BulkItemResponse primaryResponse) {
        this.primaryResponse = primaryResponse;
    }


    boolean isIgnoreOnReplica() {
        return primaryResponse != null &&
                (primaryResponse.isFailed() || primaryResponse.getResponse().getResult() == DocWriteResponse.Result.NOOP);
    }

    /**
     * Abort this request, and store a {@link org.elasticsearch.action.bulk.BulkItemResponse.Failure} response.
     *
     * @param index The concrete index that was resolved for this request
     * @param cause The cause of the rejection (may not be null)
     * @throws IllegalStateException If a response already exists for this request
     */
    public void abort(String index, Exception cause) {
        if (primaryResponse == null) {
            final BulkItemResponse.Failure failure = new BulkItemResponse.Failure(index, request.type(), request.id(),
                    Objects.requireNonNull(cause), true);
            setPrimaryResponse(new BulkItemResponse(id, request.opType(), failure));
        } else {
            assert primaryResponse.isFailed() && primaryResponse.getFailure().isAborted()
                    : "response [" + Strings.toString(primaryResponse) + "]; cause [" + cause + "]";
            if (primaryResponse.isFailed() && primaryResponse.getFailure().isAborted()) {
                primaryResponse.getFailure().getCause().addSuppressed(cause);
            } else {
                throw new IllegalStateException(
                        "aborting item that with response [" + primaryResponse + "] that was previously processed", cause);
            }
        }
    }

    public static BulkItemRequest readBulkItem(StreamInput in) throws IOException {
        BulkItemRequest item = new BulkItemRequest();
        item.readFrom(in);
        return item;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        id = in.readVInt();
        request = DocWriteRequest.readDocumentRequest(in);
        if (in.readBoolean()) {
            primaryResponse = BulkItemResponse.readBulkItem(in);
            // This is a bwc layer for 6.0 which no longer mutates the requests with these
            // Since 5.x still requires it we do it here. Note that these are harmless
            // as both operations are idempotent. This is something we rely on and assert on
            // in InternalEngine.planIndexingAsNonPrimary()
            request.version(primaryResponse.getVersion());
            request.versionType(request.versionType().versionTypeForReplicationAndRecovery());
        }
        if (in.getVersion().before(Version.V_5_6_0_UNRELEASED)) {
            boolean ignoreOnReplica = in.readBoolean();
            assert ignoreOnReplica == isIgnoreOnReplica() :
                "ignoreOnReplica mismatch. wire [" + ignoreOnReplica + "], ours [" + isIgnoreOnReplica() + "]";
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(id);
        DocWriteRequest.writeDocumentRequest(out, request);
        out.writeOptionalStreamable(primaryResponse);
        if (out.getVersion().before(Version.V_5_6_0_UNRELEASED)) {
            out.writeBoolean(isIgnoreOnReplica());
        }
    }
}
