/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.mapper.flattened;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.xcontent.XContentParserUtils;
import org.elasticsearch.index.mapper.ContentPath;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A helper class for {@link FlattenedFieldMapper} parses a JSON object
 * and produces a pair of indexable fields for each leaf value.
 */
class FlattenedFieldParser {
    static final String SEPARATOR = "\0";
    private static final byte SEPARATOR_BYTE = '\0';

    private final String rootFieldName;
    private final String keyedFieldName;

    private final MappedFieldType fieldType;
    private final int depthLimit;
    private final int ignoreAbove;
    private final String nullValue;

    FlattenedFieldParser(
        String rootFieldName,
        String keyedFieldName,
        MappedFieldType fieldType,
        int depthLimit,
        int ignoreAbove,
        String nullValue
    ) {
        this.rootFieldName = rootFieldName;
        this.keyedFieldName = keyedFieldName;
        this.fieldType = fieldType;
        this.depthLimit = depthLimit;
        this.ignoreAbove = ignoreAbove;
        this.nullValue = nullValue;
    }

    public List<IndexableField> parse(XContentParser parser) throws IOException {
        XContentParserUtils.ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.currentToken(), parser);

        ContentPath path = new ContentPath();
        List<IndexableField> fields = new ArrayList<>();

        parseObject(parser, path, fields);
        return fields;
    }

    private void parseObject(XContentParser parser, ContentPath path, List<IndexableField> fields) throws IOException {
        String currentName = null;
        while (true) {
            XContentParser.Token token = parser.nextToken();
            if (token == XContentParser.Token.END_OBJECT) {
                return;
            }

            if (token == XContentParser.Token.FIELD_NAME) {
                currentName = parser.currentName();
            } else {
                assert currentName != null;
                parseFieldValue(token, parser, path, currentName, fields);
            }
        }
    }

    private void parseArray(XContentParser parser, ContentPath path, String currentName, List<IndexableField> fields) throws IOException {
        while (true) {
            XContentParser.Token token = parser.nextToken();
            if (token == XContentParser.Token.END_ARRAY) {
                return;
            }
            parseFieldValue(token, parser, path, currentName, fields);
        }
    }

    private void parseFieldValue(
        XContentParser.Token token,
        XContentParser parser,
        ContentPath path,
        String currentName,
        List<IndexableField> fields
    ) throws IOException {
        if (token == XContentParser.Token.START_OBJECT) {
            path.add(currentName);
            validateDepthLimit(path);
            parseObject(parser, path, fields);
            path.remove();
        } else if (token == XContentParser.Token.START_ARRAY) {
            parseArray(parser, path, currentName, fields);
        } else if (token.isValue()) {
            String value = parser.text();
            addField(path, currentName, value, fields);
        } else if (token == XContentParser.Token.VALUE_NULL) {
            if (nullValue != null) {
                addField(path, currentName, nullValue, fields);
            }
        } else {
            // Note that we throw an exception here just to be safe. We don't actually expect to reach
            // this case, since XContentParser verifies that the input is well-formed as it parses.
            throw new IllegalArgumentException("Encountered unexpected token [" + token.toString() + "].");
        }
    }

    private void addField(ContentPath path, String currentName, String value, List<IndexableField> fields) {
        if (value.length() > ignoreAbove) {
            return;
        }

        String key = path.pathAsText(currentName);
        if (key.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                "Keys in [flattened] fields cannot contain the reserved character \\0." + " Offending key: [" + key + "]."
            );
        }
        String keyedValue = createKeyedValue(key, value);

        if (fieldType.isSearchable()) {
            fields.add(new StringField(rootFieldName, new BytesRef(value), Field.Store.NO));
            fields.add(new StringField(keyedFieldName, new BytesRef(keyedValue), Field.Store.NO));
        }

        if (fieldType.hasDocValues()) {
            fields.add(new SortedSetDocValuesField(rootFieldName, new BytesRef(value)));
            fields.add(new SortedSetDocValuesField(keyedFieldName, new BytesRef(keyedValue)));
        }
    }

    private void validateDepthLimit(ContentPath path) {
        if (path.length() + 1 > depthLimit) {
            throw new IllegalArgumentException(
                "The provided [flattened] field [" + rootFieldName + "]" + " exceeds the maximum depth limit of [" + depthLimit + "]."
            );
        }
    }

    static String createKeyedValue(String key, String value) {
        return key + SEPARATOR + value;
    }

    static BytesRef extractKey(BytesRef keyedValue) {
        int length;
        for (length = 0; length < keyedValue.length; length++) {
            if (keyedValue.bytes[keyedValue.offset + length] == SEPARATOR_BYTE) {
                break;
            }
        }
        return new BytesRef(keyedValue.bytes, keyedValue.offset, length);
    }

    static BytesRef extractValue(BytesRef keyedValue) {
        int length;
        for (length = 0; length < keyedValue.length; length++) {
            if (keyedValue.bytes[keyedValue.offset + length] == SEPARATOR_BYTE) {
                break;
            }
        }
        int valueStart = keyedValue.offset + length + 1;
        return new BytesRef(keyedValue.bytes, valueStart, keyedValue.length - valueStart);
    }
}
