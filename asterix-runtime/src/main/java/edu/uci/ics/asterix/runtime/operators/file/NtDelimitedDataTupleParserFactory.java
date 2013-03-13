/*
 * Copyright 2009-2012 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.asterix.runtime.operators.file;

import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.hyracks.api.context.IHyracksTaskContext;
import edu.uci.ics.hyracks.dataflow.common.data.parsers.IValueParserFactory;
import edu.uci.ics.hyracks.dataflow.std.file.ITupleParser;
import edu.uci.ics.hyracks.dataflow.std.file.ITupleParserFactory;

/**
 * A tuple parser factory for creating a tuple parser capable of parsing
 * delimited data.
 */
public class NtDelimitedDataTupleParserFactory implements ITupleParserFactory {
    private static final long serialVersionUID = 1L;
    protected ARecordType recordType;
    protected IValueParserFactory[] valueParserFactories;
    protected char fieldDelimiter;

    public NtDelimitedDataTupleParserFactory(ARecordType recordType, IValueParserFactory[] valueParserFactories,
            char fieldDelimiter) {
        this.recordType = recordType;
        this.valueParserFactories = valueParserFactories;
        this.fieldDelimiter = fieldDelimiter;
    }

    @Override
    public ITupleParser createTupleParser(final IHyracksTaskContext ctx) {
        return new DelimitedDataTupleParser(ctx, recordType, valueParserFactories, fieldDelimiter);
    }

}
