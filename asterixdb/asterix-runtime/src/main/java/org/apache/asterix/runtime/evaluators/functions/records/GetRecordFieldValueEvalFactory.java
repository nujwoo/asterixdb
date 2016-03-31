/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.runtime.evaluators.functions.records;

import java.io.DataOutput;
import java.io.IOException;

import org.apache.asterix.common.exceptions.AsterixException;
import org.apache.asterix.dataflow.data.nontagged.serde.ARecordSerializerDeserializer;
import org.apache.asterix.formats.nontagged.AqlSerializerDeserializerProvider;
import org.apache.asterix.om.base.ANull;
import org.apache.asterix.om.types.ARecordType;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.asterix.om.types.EnumDeserializer;
import org.apache.asterix.om.types.runtime.RuntimeRecordTypeInfo;
import org.apache.asterix.om.util.NonTaggedFormatUtil;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluator;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.ISerializerDeserializer;
import org.apache.hyracks.data.std.api.IPointable;
import org.apache.hyracks.data.std.primitive.VoidPointable;
import org.apache.hyracks.data.std.util.ArrayBackedValueStorage;
import org.apache.hyracks.dataflow.common.data.accessors.IFrameTupleReference;

public class GetRecordFieldValueEvalFactory implements IScalarEvaluatorFactory {

    private static final long serialVersionUID = 1L;

    private IScalarEvaluatorFactory recordEvalFactory;
    private IScalarEvaluatorFactory fldNameEvalFactory;
    private final ARecordType recordType;

    public GetRecordFieldValueEvalFactory(IScalarEvaluatorFactory recordEvalFactory,
            IScalarEvaluatorFactory fldNameEvalFactory, ARecordType recordType) {
        this.recordEvalFactory = recordEvalFactory;
        this.fldNameEvalFactory = fldNameEvalFactory;
        this.recordType = recordType;
    }

    @Override
    public IScalarEvaluator createScalarEvaluator(final IHyracksTaskContext ctx) throws AlgebricksException {
        return new IScalarEvaluator() {

            private final ArrayBackedValueStorage resultStorage = new ArrayBackedValueStorage();
            private final DataOutput out = resultStorage.getDataOutput();

            private final IPointable inputArg0 = new VoidPointable();
            private final IPointable inputArg1 = new VoidPointable();
            private final IScalarEvaluator recordEval = recordEvalFactory.createScalarEvaluator(ctx);
            private final IScalarEvaluator fieldNameEval = fldNameEvalFactory.createScalarEvaluator(ctx);

            @SuppressWarnings("unchecked")
            private final ISerializerDeserializer<ANull> nullSerde = AqlSerializerDeserializerProvider.INSTANCE
                    .getSerializerDeserializer(BuiltinType.ANULL);
            private final RuntimeRecordTypeInfo recTypeInfo = new RuntimeRecordTypeInfo();

            {
                recTypeInfo.reset(recordType);
            }

            @Override
            public void evaluate(IFrameTupleReference tuple, IPointable result) throws AlgebricksException {
                try {
                    resultStorage.reset();
                    fieldNameEval.evaluate(tuple, inputArg1);
                    byte[] serFldName = inputArg1.getByteArray();
                    int serFldNameOffset = inputArg1.getStartOffset();
                    int serFldNameLen = inputArg1.getLength();
                    if (serFldName[serFldNameOffset] != ATypeTag.SERIALIZED_STRING_TYPE_TAG) {
                        nullSerde.serialize(ANull.NULL, out);
                        result.set(resultStorage);
                        return;
                    }

                    recordEval.evaluate(tuple, inputArg0);
                    byte[] serRecord = inputArg0.getByteArray();
                    int serRecordOffset = inputArg0.getStartOffset();
                    int serRecordLen = inputArg0.getLength();
                    if (serRecord[serRecordOffset] == ATypeTag.SERIALIZED_NULL_TYPE_TAG) {
                        nullSerde.serialize(ANull.NULL, out);
                        result.set(resultStorage);
                        return;
                    }
                    if (serRecord[serRecordOffset] != ATypeTag.SERIALIZED_RECORD_TYPE_TAG) {
                        throw new AlgebricksException("Field accessor is not defined for values of type "
                                + EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(serRecord[serRecordOffset]));
                    }

                    int subFieldOffset = -1;
                    int subFieldLength = -1;

                    // Look at closed fields first.
                    int subFieldIndex = recTypeInfo.getFieldIndex(serFldName, serFldNameOffset + 1, serFldNameLen - 1);
                    if (subFieldIndex >= 0) {
                        int nullBitmapSize = ARecordType.computeNullBitmapSize(recordType);
                        subFieldOffset = ARecordSerializerDeserializer.getFieldOffsetById(serRecord, serRecordOffset,
                                subFieldIndex, nullBitmapSize, recordType.isOpen());
                        if (subFieldOffset == 0) {
                            // the field is null, we checked the null bit map
                            out.writeByte(ATypeTag.SERIALIZED_NULL_TYPE_TAG);
                            result.set(resultStorage);
                            return;
                        }
                        ATypeTag fieldTypeTag = recordType.getFieldTypes()[subFieldIndex].getTypeTag();
                        subFieldLength = NonTaggedFormatUtil.getFieldValueLength(serRecord, subFieldOffset,
                                fieldTypeTag, false);
                        // write result.
                        out.writeByte(fieldTypeTag.serialize());
                        out.write(serRecord, subFieldOffset, subFieldLength);
                        result.set(resultStorage);
                        return;
                    }

                    // Look at open fields.
                    subFieldOffset = ARecordSerializerDeserializer.getFieldOffsetByName(serRecord, serRecordOffset,
                            serRecordLen, serFldName, serFldNameOffset);
                    if (subFieldOffset < 0) {
                        out.writeByte(ATypeTag.SERIALIZED_NULL_TYPE_TAG);
                        result.set(resultStorage);
                        return;
                    }
                    // Get the field length.
                    ATypeTag fieldValueTypeTag = EnumDeserializer.ATYPETAGDESERIALIZER
                            .deserialize(serRecord[subFieldOffset]);
                    subFieldLength = NonTaggedFormatUtil.getFieldValueLength(serRecord, subFieldOffset,
                            fieldValueTypeTag, true) + 1;
                    // write result.
                    result.set(serRecord, subFieldOffset, subFieldLength);
                } catch (IOException e) {
                    throw new AlgebricksException(e);
                } catch (AsterixException e) {
                    throw new AlgebricksException(e);
                }
            }
        };
    }
}