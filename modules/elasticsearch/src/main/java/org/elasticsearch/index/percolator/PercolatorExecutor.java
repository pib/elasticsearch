/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.percolator;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.memory.MemoryIndex;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.FastByteArrayOutputStream;
import org.elasticsearch.common.io.FastStringReader;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.AbstractIndexComponent;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.field.data.FieldData;
import org.elasticsearch.index.field.data.FieldDataType;
import org.elasticsearch.index.mapper.*;
import org.elasticsearch.index.query.IndexQueryParser;
import org.elasticsearch.index.query.IndexQueryParserService;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.service.IndexService;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.index.shard.service.IndexShard;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.index.mapper.SourceToParse.*;

/**
 * @author kimchy (shay.banon)
 */
public class PercolatorExecutor extends AbstractIndexComponent {

    public static class Request {
        private final byte[] source;
        private final int offset;
        private final int length;

        public Request(byte[] source) {
            this(source, 0, source.length);
        }

        public Request(byte[] source, int offset, int length) {
            this.source = source;
            this.offset = offset;
            this.length = length;
        }

        public byte[] source() {
            return source;
        }
    }

    public static final class Response {
        private final List<String> matches;
        private final boolean mappersAdded;

        public Response(List<String> matches, boolean mappersAdded) {
            this.matches = matches;
            this.mappersAdded = mappersAdded;
        }

        public boolean mappersAdded() {
            return this.mappersAdded;
        }

        public List<String> matches() {
            return matches;
        }
    }

    private final MapperService mapperService;

    private final IndexQueryParserService queryParserService;

    private volatile ImmutableMap<String, Query> queries = ImmutableMap.of();

    @Inject public PercolatorExecutor(Index index, @IndexSettings Settings indexSettings,
                                      MapperService mapperService, IndexQueryParserService queryParserService) {
        super(index, indexSettings);
        this.mapperService = mapperService;
        this.queryParserService = queryParserService;
    }

    public synchronized void close() {
        ImmutableMap<String, Query> old = queries;
        queries = ImmutableMap.of();
        old.clear();
    }

    public void addQuery(String name, QueryBuilder queryBuilder) throws ElasticSearchException {
        try {
            XContentBuilder builder = XContentFactory.smileBuilder()
                    .startObject().field("query", queryBuilder).endObject();
            FastByteArrayOutputStream unsafeBytes = builder.unsafeStream();
            addQuery(name, unsafeBytes.unsafeByteArray(), 0, unsafeBytes.size());
        } catch (IOException e) {
            throw new ElasticSearchException("Failed to add query [" + name + "]", e);
        }
    }

    public void addQuery(String name, byte[] source) throws ElasticSearchException {
        addQuery(name, source, 0, source.length);
    }

    public void addQuery(String name, byte[] source, int sourceOffset, int sourceLength) throws ElasticSearchException {
        XContentParser parser = null;
        try {
            parser = XContentFactory.xContent(source, sourceOffset, sourceLength).createParser(source, sourceOffset, sourceLength);
            Query query = null;
            String currentFieldName = null;
            XContentParser.Token token;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token == XContentParser.Token.START_OBJECT) {
                    if ("query".equals(currentFieldName)) {
                        IndexQueryParser queryParser = queryParserService.defaultIndexQueryParser();
                        query = queryParser.parse(parser).query();
                    }
                }
            }
            addQuery(name, query);
        } catch (IOException e) {
            throw new ElasticSearchException("Failed to add query [" + name + "]", e);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    public synchronized void addQuery(String name, Query query) {
        this.queries = MapBuilder.newMapBuilder(queries).put(name, query).immutableMap();
    }

    public synchronized void removeQuery(String name) {
        this.queries = MapBuilder.newMapBuilder(queries).remove(name).immutableMap();
    }

    public Response percolate(final Request request) throws ElasticSearchException {
        return percolate(request, null, null);
    }

    public Response percolate(final Request request, @Nullable final IndexService percolatorIndex, @Nullable final IndexShard percolatorShard) throws ElasticSearchException {
        Query query = null;
        ParsedDocument doc = null;
        XContentParser parser = null;
        try {

            parser = XContentFactory.xContent(request.source()).createParser(request.source());
            String currentFieldName = null;
            XContentParser.Token token;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token == XContentParser.Token.START_OBJECT) {
                    if ("query".equals(currentFieldName)) {
                        IndexQueryParser queryParser = queryParserService.defaultIndexQueryParser();
                        query = queryParser.parse(parser).query();
                    } else if ("doc".equals(currentFieldName)) {
                        // the first level should be the type
                        token = parser.nextToken();
                        assert token == XContentParser.Token.FIELD_NAME;
                        String type = parser.currentName();
                        DocumentMapper docMapper = mapperService.documentMapperWithAutoCreate(type);
                        doc = docMapper.parse(source(parser).type(type).flyweight(true));
                    }
                } else if (token == null) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new PercolatorException(index, "failed to parse request", e);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }

        if (doc == null) {
            throw new PercolatorException(index, "No doc to percolate in the request");
        }


        // first, parse the source doc into a MemoryIndex
        final MemoryIndex memoryIndex = new MemoryIndex();

        for (Fieldable field : doc.doc().getFields()) {
            if (!field.isIndexed()) {
                continue;
            }
            TokenStream tokenStream = field.tokenStreamValue();
            if (tokenStream != null) {
                memoryIndex.addField(field.name(), tokenStream, field.getBoost());
            } else {
                Reader reader = field.readerValue();
                if (reader != null) {
                    try {
                        memoryIndex.addField(field.name(), doc.analyzer().reusableTokenStream(field.name(), reader), field.getBoost() * doc.doc().getBoost());
                    } catch (IOException e) {
                        throw new MapperParsingException("Failed to analyze field [" + field.name() + "]", e);
                    }
                } else {
                    String value = field.stringValue();
                    if (value != null) {
                        try {
                            memoryIndex.addField(field.name(), doc.analyzer().reusableTokenStream(field.name(), new FastStringReader(value)), field.getBoost() * doc.doc().getBoost());
                        } catch (IOException e) {
                            throw new MapperParsingException("Failed to analyze field [" + field.name() + "]", e);
                        }
                    }
                }
            }
        }

        final IndexSearcher searcher = memoryIndex.createSearcher();

        List<String> matches = new ArrayList<String>();
        if (query == null) {
            Lucene.ExistsCollector collector = new Lucene.ExistsCollector();
            for (Map.Entry<String, Query> entry : queries.entrySet()) {
                try {
                    searcher.search(entry.getValue(), collector);
                } catch (IOException e) {
                    logger.warn("[" + entry.getKey() + "] failed to execute query", e);
                }

                if (collector.exists()) {
                    matches.add(entry.getKey());
                }
            }
        } else {
            Engine.Searcher percolatorSearcher = percolatorShard.searcher();
            try {
                percolatorSearcher.searcher().search(query, new QueryCollector(logger, queries, searcher, percolatorIndex, matches));
            } catch (IOException e) {
                logger.warn("failed to execute", e);
            } finally {
                percolatorSearcher.release();
            }
        }

        return new Response(matches, doc.mappersAdded());
    }

    static class QueryCollector extends Collector {
        private final IndexSearcher searcher;
        private final IndexService percolatorIndex;
        private final List<String> matches;
        private final ImmutableMap<String, Query> queries;
        private final ESLogger logger;

        private final Lucene.ExistsCollector collector = new Lucene.ExistsCollector();

        QueryCollector(ESLogger logger, ImmutableMap<String, Query> queries, IndexSearcher searcher, IndexService percolatorIndex, List<String> matches) {
            this.logger = logger;
            this.queries = queries;
            this.searcher = searcher;
            this.percolatorIndex = percolatorIndex;
            this.matches = matches;
        }

        private FieldData fieldData;

        @Override public void setScorer(Scorer scorer) throws IOException {
        }

        @Override public void collect(int doc) throws IOException {
            String id = fieldData.stringValue(doc);
            Query query = queries.get(id);
            if (query == null) {
                // log???
                return;
            }
            // run the query
            try {
                searcher.search(query, collector);
                if (collector.exists()) {
                    matches.add(id);
                }
            } catch (IOException e) {
                logger.warn("[" + id + "] failed to execute query", e);
            }
        }

        @Override public void setNextReader(IndexReader reader, int docBase) throws IOException {
            fieldData = percolatorIndex.cache().fieldData().cache(FieldDataType.DefaultTypes.STRING, reader, IdFieldMapper.NAME);
        }

        @Override public boolean acceptsDocsOutOfOrder() {
            return true;
        }
    }
}
