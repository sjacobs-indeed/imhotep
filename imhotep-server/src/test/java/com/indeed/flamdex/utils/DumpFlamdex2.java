/*
 * Copyright (C) 2014 Indeed Inc.
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
 package com.indeed.flamdex.utils;

import com.indeed.flamdex.api.DocIdStream;
import com.indeed.flamdex.api.IntTermIterator;
import com.indeed.flamdex.api.StringTermIterator;
import com.indeed.flamdex.simple.SimpleFlamdexReader;

import java.io.*;
import java.nio.file.Paths;

/**
 * @author darren
 */
public class DumpFlamdex2 {
    public static void main(String[] args) throws Exception {
        final SimpleFlamdexReader reader = SimpleFlamdexReader.open(Paths.get(args[0])); //"/tmp/native-ftgs-test7045001985093560042test");
        final BufferedWriter w  = new BufferedWriter(new FileWriter(args[1])); //"/tmp/shard3.dat"));

        w.write("num docs: " + reader.getNumDocs());
        w.newLine();

        final DocIdStream stream = reader.getDocIdStream();
        final int[] docIdBuffer = new int[1024];
        int n;

        for (String strField : reader.getStringFields()) {
            System.out.println("strField: " + strField);
            w.write("String Field : " + strField);
            w.newLine();

            final StringTermIterator iter = reader.getStringTermIterator(strField);
            while (iter.next()) {
                final String term = iter.term();
                w.write("String Term : " + term);
                w.newLine();

                stream.reset(iter);
                int j = 0;
                do {
                    n = stream.fillDocIdBuffer(docIdBuffer);
                    for (int i = 0; i < n; i++) {
                        w.write(Integer.toString(docIdBuffer[i]) + ", ");
                        j ++;
                    }
                } while (n == docIdBuffer.length);
                w.newLine();
                w.write("count: " + j);
                w.newLine();
            }
            w.newLine();
        }
        w.newLine();

        for (String intField : reader.getIntFields()) {
            System.out.println("intField: " + intField);
            w.write("Int Field : " + intField);
            w.newLine();

            final IntTermIterator iter = reader.getIntTermIterator(intField);
            while (iter.next()) {
                final long term = iter.term();
                w.write("Int Term : " + term);
                w.newLine();

                stream.reset(iter);
                int j = 0;
                do {
                    n = stream.fillDocIdBuffer(docIdBuffer);
                    for (int i = 0; i < n; i++) {
                        w.write(Integer.toString(docIdBuffer[i]) + ", ");
                        j ++;
                    }
                } while (n == docIdBuffer.length);
                w.newLine();
                w.write("count: " + j);
                w.newLine();
            }
            w.newLine();
        }
        w.newLine();

        w.close();
    }
}